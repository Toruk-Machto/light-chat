package sg.lightchat

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

class ChatTest {
    @Test fun branchKeepsOriginalAndRestoresDraft() {
        val original = Chat(title = "Example", pinned = true, messages = listOf(
            Message("First", true), Message("Answer", false), Message("Second", true),
        ))
        val branch = original.branch(2, "Second")
        assertNotEquals(original.id, branch.id)
        assertEquals(3, original.messages.size)
        assertEquals(original.messages.take(2), branch.messages)
        assertEquals("Second", branch.draft)
        assertFalse(branch.pinned)
    }

    @Test fun contextModesAndRetention() {
        val chat = Chat(updated = 0, messages = List(15) { Message("Message $it", it % 2 == 0) })
        assertEquals(15, chat.copy(context = ContextMode.Full).contextMessages().size)
        assertEquals(7, chat.copy(context = ContextMode.Recent).contextMessages().size)
        assertTrue(chat.copy(context = ContextMode.Fresh).contextMessages().isEmpty())
        val summary = chat.copy(context = ContextMode.Summary).contextSummary().orEmpty()
        assertTrue(summary.contains("Message 0"))
        assertTrue(summary.contains("Message 14"))
        assertFalse(summary.contains("Message 3"))
        assertEquals(30, Preferences().retentionDays)
        assertTrue(chat.isExpired(30, 31 * 86_400_000L))
        assertFalse(chat.isExpired(0, 31 * 86_400_000L))
        assertTrue(chat.matches("message 10"))
        assertFalse(chat.matches("different"))
    }

    @Test fun freeCatalogRejectsPricedModels() {
        val fixture = """{"id":"example/model:free","name":"Example","context_length":4096,
          "pricing":{"prompt":"0","completion":"0","request":"0"},
          "architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},
          "supported_parameters":["reasoning"]}"""
        val model = assertNotNull(routerModel(parseObject(fixture)))
        assertTrue(model.vision)
        assertTrue(Effort.High in model.effortOptions)
        assertNull(routerModel(parseObject(fixture.replace(":free", ""))))
        assertNull(routerModel(parseObject(fixture.replace(""""request":"0"""", """"request":"0.01""""))))
        assertNull(routerModel(parseObject(fixture.replace(""""output_modalities":["text"]""", """"output_modalities":["audio"]"""))))
    }

    @Test fun autoFreeShowsActualModelAndNoPaidTools() = runBlocking {
        val api = apiWith { request ->
            assertEquals("openrouter.ai", request.url.host)
            assertEquals("Bearer fake-router-key", request.header("Authorization"))
            assertNull(request.header("x-goog-api-key"))
            val body = json(request)
            assertEquals("openrouter/free", body.text("model"))
            assertEquals(0, body.obj("provider").obj("max_price").count("prompt"))
            assertFalse("tools" in body)
            assertFalse("plugins" in body)
            """{"model":"example/model:free","choices":[{"message":{"content":"Hello"}}],
                 "usage":{"prompt_tokens":12,"completion_tokens":2}}"""
        }
        val answer = api.answer(prompt(autoModel), "fake-router-key")
        assertEquals("example/model:free", answer.model)
        assertEquals(12, answer.usage.input)
        assertEquals(0.0, answer.usage.estimatedDollars)
        assertFailsWith<ApiProblem> { api.answer(prompt(autoModel).copy(research = Research.Search), "fake-router-key") }
        assertFailsWith<ApiProblem> { api.answer(prompt(autoModel.copy(ref = ModelRef(Provider.Router, "paid/model"))), "fake-router-key") }
        Unit
    }

    @Test fun openAiResearchUsesDirectKeyAndReturnsOnlyCitations() = runBlocking {
        val api = apiWith { request ->
            assertEquals("api.openai.com", request.url.host)
            assertEquals("Bearer fake-openai-key", request.header("Authorization"))
            val body = json(request)
            assertEquals("high", body.obj("reasoning").text("effort"))
            assertFalse(body.flag("store"))
            assertEquals("web_search", body.objects("tools").single().text("type"))
            """{"model":"gpt-6-luna","status":"completed","output":[{"type":"message","content":[{"type":"output_text",
                 "text":"Research answer","annotations":[{"type":"url_citation","url":"https://example.com/","title":"Example"}]}]}],
                 "usage":{"input_tokens":30,"output_tokens":19,"output_tokens_details":{"reasoning_tokens":15}}}"""
        }
        val p = prompt(requireNotNull(openAiModel("gpt-6-luna"))).copy(research = Research.Search, effort = Effort.High)
        val answer = api.answer(p, "fake-openai-key")
        assertEquals("Research answer", answer.text)
        assertEquals(listOf(Source("Example", "https://example.com/")), answer.sources)
        assertEquals(15, answer.usage.reasoning)
        assertNull(answer.usage.estimatedDollars)
    }

    @Test fun geminiPdfAndUrlUseProviderVerifiedContent() = runBlocking {
        val api = apiWith { request ->
            assertEquals("generativelanguage.googleapis.com", request.url.host)
            assertEquals("fake-gemini-key", request.header("x-goog-api-key"))
            assertNull(request.header("Authorization"))
            assertFalse(request.url.toString().contains("fake-gemini-key"))
            val body = json(request)
            assertEquals("LOW", body.obj("generationConfig").obj("thinkingConfig").text("thinkingLevel"))
            assertEquals("application/pdf", body.objects("contents").last().objects("parts").last().obj("inlineData").text("mimeType"))
            """{"modelVersion":"gemini-3.8-flash","candidates":[{"content":{"parts":[
                 {"text":"internal","thought":true},{"text":"Page summary"}]},"urlContextMetadata":{"urlMetadata":[
                 {"retrievedUrl":"https://example.com/","urlRetrievalStatus":"URL_RETRIEVAL_STATUS_SUCCESS"}]}}],
                 "usageMetadata":{"promptTokenCount":49,"candidatesTokenCount":8}}"""
        }
        val model = assertNotNull(geminiModel(parseObject("""{"name":"models/gemini-3.8-flash",
            "supportedGenerationMethods":["generateContent"],"inputTokenLimit":1048576}""")))
        val p = prompt(model).copy(question = "Read https://example.com", research = Research.Url, effort = Effort.Low,
            attachment = Attachment("fixture.pdf", "application/pdf", byteArrayOf(2, 7)))
        val answer = api.answer(p, "fake-gemini-key")
        assertEquals("Page summary", answer.text)
        assertEquals("https://example.com/", answer.sources.single().url)
        assertFailsWith<ApiProblem> { api.answer(p.copy(question = "Read https://example.net"), "fake-gemini-key") }
        assertFailsWith<ApiProblem> { api.answer(p.copy(question = "Read example.com"), "fake-gemini-key") }
        Unit
    }

    @Test fun testsAccountErrorsWithoutEchoingTheirReturnedMessages() = runBlocking {
        val codes = listOf(401 to "rejected this key", 402 to "requires billing", 403 to "denied access", 429 to "rate-limited")
        for ((code, expected) in codes) {
            val api = apiWith(code) { """{"error":{"message":"fake-secret-should-not-appear"}}""" }
            val error = assertFailsWith<ApiProblem> { api.check(Provider.Router, "fake-secret-should-not-appear") }
            assertContains(error.message.orEmpty(), expected)
            assertFalse(error.message.orEmpty().contains("fake-secret"))
        }
        val api = ChatApi(OkHttpClient.Builder().addInterceptor { throw IOException() }.build())
        val error = assertFailsWith<ApiProblem> { api.check(Provider.OpenAI, "fake-key") }
        assertContains(error.message.orEmpty(), "Check your network")
    }

    @Test fun audioFormatAndLiveSessionSetup() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = ByteBuffer.wrap(wav(pcm, 16_000)).order(ByteOrder.LITTLE_ENDIAN)
        val bytes = wav.array()
        assertEquals("RIFF", bytes.copyOfRange(0, 4).decodeToString())
        assertEquals(16_000, wav.getInt(24))
        assertEquals(4, wav.getInt(40))
        assertContentEquals(pcm, bytes.copyOfRange(44, 48))
        val config = liveSetup().obj("setup")
        assertEquals("models/gemini-3.8-live", config.text("model"))
        assertEquals(listOf("AUDIO"), config.obj("generationConfig").strings("responseModalities"))
        assertEquals("START_OF_ACTIVITY_INTERRUPTS", config.obj("realtimeInputConfig").text("activityHandling"))
        assertEquals("audio/pcm;rate=16000", liveAudio(pcm).obj("realtimeInput").obj("audio").text("mimeType"))
    }

    @Test fun attachmentsAreBoundedAndTyped() {
        assertEquals("application/pdf", makeAttachment("paper.PDF", ByteArrayInputStream(byteArrayOf(1))).mime)
        assertEquals("image/png", makeAttachment("photo.png", ByteArrayInputStream(byteArrayOf(1))).mime)
        assertFailsWith<ApiProblem> { makeAttachment("program.apk", ByteArrayInputStream(byteArrayOf(1))) }
        assertFailsWith<ApiProblem> { makeAttachment("too-long.txt", ByteArrayInputStream(ByteArray(60_001))) }
    }


    @Test fun routerPhotoUsesAnInlineImageWithoutAFileParsingPlugin() {
        val body = routerBody(prompt(autoModel).copy(
            attachment = Attachment("photo.png", "image/png", byteArrayOf(1, 2, 3)),
            summary = "Earlier user message",
        ))
        val parts = body.objects("messages").last().objects("content")
        assertContains(parts[0].text("text"), "Earlier user message")
        assertEquals("data:image/png;base64,AQID", parts[1].obj("image_url").text("url"))
        assertFalse("plugins" in body)
    }

    @Test fun openAiPaperUsesAnInlinePdfAndStructuredSummaryInstruction() {
        val model = requireNotNull(openAiModel("gpt-6-luna"))
        val body = openAiBody(prompt(model).copy(
            research = Research.Paper,
            attachment = Attachment("paper.pdf", "application/pdf", byteArrayOf(1, 2, 3)),
        ))
        val file = body.objects("input").last().objects("content").last()
        assertEquals("input_file", file.text("type"))
        assertEquals("paper.pdf", file.text("filename"))
        assertEquals("data:application/pdf;base64,AQID", file.text("file_data"))
        assertContains(body.text("instructions"), "methods, results, limitations")
        assertFalse(body.flag("store"))
    }

    @Test fun geminiWebSearchRequiresCitationsAndPreservesUsage() = runBlocking {
        val model = Model(ModelRef(Provider.Gemini, "gemini-3.8-flash"), "Gemini", search = true)
        val p = prompt(model).copy(question = "Find a source", research = Research.Search)
        val fixture = """{"candidates":[{"content":{"parts":[{"text":"A sourced answer."}]},
            "groundingMetadata":{"groundingChunks":[{"web":{"title":"Example","uri":"https://example.com/"}}]}}],
            "usageMetadata":{"promptTokenCount":22,"candidatesTokenCount":5,"thoughtsTokenCount":8}}"""
        val api = apiWith { request ->
            assertTrue("googleSearch" in json(request).objects("tools").single())
            fixture
        }
        val answer = api.answer(p, "fake-gemini-key")
        assertEquals(22, answer.usage.input)
        assertEquals(8, answer.usage.reasoning)
        assertEquals("https://example.com/", answer.sources.single().url)
        val withoutCitation = apiWith {
            """{"candidates":[{"content":{"parts":[{"text":"A claim without evidence."}]}}]}"""
        }
        assertFailsWith<ApiProblem> { withoutCitation.answer(p, "fake-gemini-key") }
        Unit
    }

    @Test fun modelSearchAndFreeTierUseProviderIds() {
        val lite = Model(ModelRef(Provider.Gemini, "gemini-3.1-flash-lite"), "Gemini 3.1 Flash-Lite")
        assertTrue(lite.matches("Flash Lite"))
        assertTrue(lite.matches("gemini-3.1-flash-lite"))
        assertTrue(lite.ref.freeTierAvailable)
        assertFalse(lite.copy(ref = lite.ref.copy(id = "gemini-3.1-flash-lite-preview")).ref.freeTierAvailable)
        assertFalse(ModelRef(Provider.OpenAI, "gpt-6-luna").freeTierAvailable)
        assertTrue(ModelRef.auto.freeTierAvailable)
    }

    @Test fun draftAndContextSummaryReflectWhatTheUserSees() {
        val draft = Chat(draft = "   My draft\nMore text")
        assertEquals("My draft", draft.displayTitle)
        assertEquals("Custom title", draft.copy(title = "Custom title").displayTitle)
        val chat = draft.copy(messages = List(12) { Message("Message $it", it % 2 == 0) }, context = ContextMode.Summary)
        val pdf = Attachment("schedule.pdf", "application/pdf", byteArrayOf(3))
        assertEquals(listOf("Summary of 10 earlier messages", "1 PDF file"), chat.contextCounts(pdf))
        assertContains(chat.sendDescription(Provider.Gemini, pdf), "schedule.pdf (PDF)")
        assertEquals(listOf("12 earlier messages"), chat.copy(context = ContextMode.Full).contextCounts(null))
        assertEquals("Question only", chat.copy(context = ContextMode.Fresh).contextCounts(null).single())
    }

    @Test fun replyFormattingPreservesCodeAndShowsHeadingsAndLists() {
        val blocks = formatAnswer("## C#\n- First\n  2. Nested\n```kotlin\n  println(1)\n```\nAfter")
        assertEquals(
            listOf(
                ReplyBlock(ReplyBlockKind.Heading, "C#"),
                ReplyBlock(ReplyBlockKind.ListItem, "First", "•"),
                ReplyBlock(ReplyBlockKind.ListItem, "Nested", "2.", 1),
                ReplyBlock(ReplyBlockKind.Code, "  println(1)"),
                ReplyBlock(ReplyBlockKind.Text, "After"),
            ),
            blocks,
        )
        assertEquals(listOf(ReplyBlock(ReplyBlockKind.Code, "unfinished")), formatAnswer("```\nunfinished"))
    }

    private fun prompt(model: Model) = Prompt(model, "Hello", emptyList(), null, Length.Normal, Effort.Auto)

    private fun apiWith(code: Int = 200, fixture: (Request) -> String) = ChatApi(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .body(fixture(chain.request()).toResponseBody("application/json".toMediaType())).build()
        }.build(),
    )

    private fun json(request: Request) = parseObject(Buffer().also { request.body?.writeTo(it) }.readUtf8())
}
