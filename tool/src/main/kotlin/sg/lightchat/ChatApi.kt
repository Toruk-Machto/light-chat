package sg.lightchat

import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

private const val ROUTER = "https://openrouter.ai/api/v1"
private const val OPENAI = "https://api.openai.com/v1"
internal const val GEMINI = "https://generativelanguage.googleapis.com/v1beta"
private val jsonType = "application/json".toMediaType()

internal fun JsonObject.obj(name: String) = this[name] as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.objects(name: String) = (this[name] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
internal fun JsonObject.strings(name: String) = (this[name] as? JsonArray).orEmpty()
    .filterIsInstance<JsonPrimitive>().mapNotNull { it.contentOrNull }
internal fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.count(name: String) = (this[name] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.decimal(name: String) = (this[name] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.flag(name: String) = (this[name] as? JsonPrimitive)?.booleanOrNull ?: false
internal fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)
internal fun parseObject(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

internal class ApiProblem(message: String) : Exception(message)

data class KeyStatus(val detail: String)
data class Answer(val text: String, val model: String, val usage: Usage, val sources: List<Source> = emptyList())

internal interface ChatGateway {
    suspend fun catalog(provider: Provider, key: String?): List<Model>
    suspend fun check(provider: Provider, key: String): KeyStatus
    suspend fun answer(prompt: Prompt, key: String): Answer
}

internal class ChatApi(
    val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(120, TimeUnit.SECONDS).readTimeout(105, TimeUnit.SECONDS).build(),
) : ChatGateway {
    override suspend fun catalog(provider: Provider, key: String?): List<Model> = when (provider) {
        Provider.Router -> listOf(autoModel) + request("$ROUTER/models", provider, null)
            .objects("data").mapNotNull(::routerModel).sortedBy { it.name.lowercase() }
        Provider.OpenAI -> request("$OPENAI/models", provider, requireNotNull(key))
            .objects("data").mapNotNull { openAiModel(it.text("id")) }.sortedBy { it.name }
        Provider.Gemini -> {
            val models = mutableListOf<Model>()
            var cursor = ""
            do {
                val url = "$GEMINI/models".toHttpUrl().newBuilder().addQueryParameter("pageSize", "100").apply {
                    if (cursor.isNotEmpty()) addQueryParameter("pageToken", cursor)
                }.build().toString()
                val result = request(url, provider, requireNotNull(key))
                models += result.objects("models").mapNotNull(::geminiModel)
                cursor = result.text("nextPageToken")
            } while (cursor.isNotEmpty() && models.size < 400)
            models.sortedBy { it.name }
        }
    }

    override suspend fun check(provider: Provider, key: String): KeyStatus {
        val endpoint = when (provider) {
            Provider.Router -> "$ROUTER/key"
            Provider.OpenAI -> "$OPENAI/models"
            Provider.Gemini -> "$GEMINI/models?pageSize=1"
        }
        val result = request(endpoint, provider, key)
        if (provider != Provider.Router) return KeyStatus("Key accepted. Model access and quota are checked when you send.")
        val data = result.obj("data")
        val requests = data.count("free_requests_remaining")
        val remaining = data.decimal("limit_remaining")
        return KeyStatus(buildString {
            append("Key accepted. ")
            when {
                requests != null -> append("$requests free requests remaining.")
                data.flag("is_free_tier") -> append("Free tier. Remaining free requests are unavailable.")
                else -> append("Remaining free requests are unavailable.")
            }
            if (remaining != null) append(" Key spending allowance remaining: US${'$'}${"%.2f".format(java.util.Locale.US, remaining)}.")
        })
    }

    override suspend fun answer(prompt: Prompt, key: String): Answer {
        validatePrompt(prompt)
        return when (prompt.model.ref.provider) {
            Provider.Router -> parseRouter(request("$ROUTER/chat/completions", Provider.Router, key, routerBody(prompt)), prompt)
            Provider.OpenAI -> parseOpenAi(request("$OPENAI/responses", Provider.OpenAI, key, openAiBody(prompt)), prompt)
            Provider.Gemini -> parseGemini(request(
                geminiEndpoint(prompt.model.ref.id), Provider.Gemini, key, geminiBody(prompt),
            ), prompt)
        }
    }

    internal suspend fun request(url: String, provider: Provider, key: String?, body: JsonObject? = null): JsonObject =
        try {
            parseObject(bytes(url, provider, key, body).decodeToString())
        } catch (_: IllegalArgumentException) {
            throw ApiProblem("${provider.label} returned a response the app could not read.")
        }

    internal suspend fun bytes(url: String, provider: Provider, key: String?, body: JsonObject?): ByteArray {
        require(url.startsWith(when (provider) {
            Provider.Router -> "$ROUTER/"
            Provider.OpenAI -> "$OPENAI/"
            Provider.Gemini -> "$GEMINI/"
        }))
        val request = Request.Builder().url(url).apply {
            key?.let {
                if (provider == Provider.Gemini) header("x-goog-api-key", it)
                else header("Authorization", "Bearer $it")
            }
            if (body != null) post(body.toString().toRequestBody(jsonType))
        }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(ApiProblem("Connection failed. Check your network and retry."))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val data = response.use {
                            val content = it.body.source()
                            if (content.request(16L * 1024 * 1024 + 1)) {
                                throw ApiProblem("The provider response exceeded the app's size limit.")
                            }
                            val bytes = content.readByteArray()
                            if (!it.isSuccessful) {
                                val problem = runCatching { parseObject(bytes.decodeToString()).obj("error") }.getOrNull()
                                throw ApiProblem(errorText(provider, it.code, problem))
                            }
                            bytes
                        }
                        continuation.resume(data)
                    } catch (error: Exception) {
                        continuation.resumeWithException(
                            if (error is ApiProblem) error else ApiProblem("The connection ended before the response arrived."),
                        )
                    }
                }
            })
        }
    }
}

internal val autoModel = Model(ModelRef.auto, "Auto Free", 200_000, vision = true)

internal fun routerModel(item: JsonObject): Model? {
    val id = item.text("id")
    val price = item.obj("pricing")
    val media = item.obj("architecture")
    if (!id.endsWith(":free") || price.decimal("prompt") != 0.0 || price.decimal("completion") != 0.0 ||
        price.any { (_, value) -> (value as? JsonPrimitive)?.doubleOrNull?.let { it != 0.0 } == true } ||
        "text" !in media.strings("output_modalities")
    ) return null
    val flags = item.strings("supported_parameters")
    return Model(
        ModelRef(Provider.Router, id), item.text("name").ifBlank { id }, item.count("context_length") ?: 0,
        vision = "image" in media.strings("input_modalities"),
        // PDF parsing plugins can cost money on OpenRouter.
        efforts = if ("reasoning" in flags || "include_reasoning" in flags) listOf(Effort.Low, Effort.Medium, Effort.High)
        else emptyList(),
    )
}

internal fun openAiModel(id: String): Model? {
    val family = Regex("^(gpt-[456][a-z0-9.-]*|o[134](?:-[a-z0-9.-]+)?)$")
    if (!family.matches(id) || listOf("image", "audio", "realtime", "transcribe", "tts", "search", "codex", "chat-latest", "instruct")
            .any { it in id }) return null
    val regular = listOf(Effort.Low, Effort.Medium, Effort.High)
    val vision = !id.startsWith("gpt-4-") && id != "gpt-4" && !id.startsWith("o1-mini")
    val extended = regular + listOf(Effort.ExtraHigh, Effort.Max)
    val efforts = when {
        id.startsWith("gpt-6.1-sol") || id.startsWith("gpt-6-astra") -> extended
        id.startsWith("gpt-6-sol") || id.startsWith("gpt-6-luna") -> listOf(Effort.None) + extended
        id.startsWith("gpt-5.6") -> extended
        id.startsWith("gpt-5.5") || id.startsWith("gpt-5.4") || id.startsWith("gpt-5.2") ->
            listOf(Effort.None) + regular + Effort.ExtraHigh
        id.startsWith("gpt-5.1") -> listOf(Effort.None) + regular
        id.startsWith("gpt-5") && !id.startsWith("gpt-5-pro") -> listOf(Effort.Minimal) + regular
        id.startsWith("o3") || id.startsWith("o4") -> regular
        else -> emptyList()
    }
    val rates = when (id) {
        "gpt-6-luna" -> 0.10 to 0.50
        "gpt-4.1-mini" -> 0.40 to 1.60
        "gpt-5-mini" -> 0.25 to 2.00
        else -> null
    }
    return Model(ModelRef(Provider.OpenAI, id), id, vision = vision, pdf = vision,
        search = id.startsWith("gpt-5") || id.startsWith("gpt-6") || id.startsWith("gpt-4.1"),
        efforts = efforts, inputPerMillion = rates?.first, outputPerMillion = rates?.second)
}

internal fun geminiModel(item: JsonObject): Model? {
    if ("generateContent" !in item.strings("supportedGenerationMethods")) return null
    val id = item.text("name").removePrefix("models/")
    if (!id.startsWith("gemini-") || listOf("-image", "-tts", "-live", "-robotics", "-computer", "-embedding", "-aqa").any { it in id }) return null
    val regular = listOf(Effort.Low, Effort.Medium, Effort.High)
    val efforts = when {
        id.startsWith("gemini-4-hydrogen") -> regular + Effort.ExtraHigh
        id.startsWith("gemini-3-pro") -> listOf(Effort.Low, Effort.High)
        id.startsWith("gemini-3.1-flash-lite") || id.startsWith("gemini-3-flash") ||
            id.startsWith("gemini-3.5-flash-lite") || id.startsWith("gemini-3.6-flash") ->
            listOf(Effort.Minimal) + regular
        id.startsWith("gemini-3.") || id.startsWith("gemini-4-") || id.startsWith("gemini-2.5-pro") -> regular
        id.startsWith("gemini-2.5-flash") -> listOf(Effort.None) + regular
        else -> emptyList()
    }
    val rates = when (id) {
        "gemini-3.1-flash-lite" -> 0.25 to 1.50
        "gemini-3.8-flash" -> if (java.time.LocalDate.now() < java.time.LocalDate.of(2027, 1, 1)) 0.75 to 3.75 else 1.50 to 7.50
        else -> null
    }
    val tools = id.startsWith("gemini-3") || id.startsWith("gemini-4") || id.startsWith("gemini-2.5")
    return Model(
        ModelRef(Provider.Gemini, id), item.text("displayName").ifBlank { id },
        item.count("inputTokenLimit") ?: 0, vision = true, pdf = true, search = tools, url = tools, efforts = efforts,
        inputPerMillion = rates?.first, outputPerMillion = rates?.second,
    )
}

internal fun geminiEndpoint(id: String): String {
    require(Regex("gemini-[a-zA-Z0-9.-]+").matches(id))
    return "$GEMINI/models/$id:generateContent"
}

internal fun errorText(provider: Provider, status: Int, result: JsonObject?): String {
    val code = result?.text("code").orEmpty().lowercase()
    val reason = result?.objects("details")?.firstNotNullOfOrNull { it.text("reason").ifBlank { null } }.orEmpty()
    return when {
        status == 402 || "BILLING" in reason || code in setOf("billing_hard_limit_reached", "insufficient_quota") ->
            "${provider.label} requires billing or its project has no available credit."
        status == 401 || reason.startsWith("API_KEY") ||
            code in setOf("invalid_api_key", "unauthenticated") -> "${provider.label} rejected this key. Check the key in Settings."
        status == 403 -> "${provider.label} denied access. Check project permissions or model access."
        status == 429 -> "${provider.label} is rate-limited or has reached its quota. Try again later."
        status == 404 -> "This model is unavailable on ${provider.label}. Pick another model."
        status >= 500 -> "${provider.label} is temporarily unavailable (HTTP $status). Retry when ready."
        else -> "${provider.label} rejected the request (HTTP $status). Check the model, context and attachments."
    }
}

private fun validatePrompt(prompt: Prompt) {
    val model = prompt.model
    val id = model.ref.id
    if (model.ref.provider == Provider.Router &&
        (id != ModelRef.auto.id && !id.endsWith(":free") || prompt.research == Research.Search || prompt.research == Research.Url)
    ) throw ApiProblem("OpenRouter is set to free-only. Select a supported direct model for this tool.")
    if (prompt.effort !in model.effortOptions) throw ApiProblem("That reasoning level is unavailable for this model.")
    if (prompt.research == Research.Search && !model.search) throw ApiProblem("This model cannot run web search.")
    if (prompt.research == Research.Url && !model.url) throw ApiProblem("Choose a Gemini model with URL reading.")
    if (prompt.research == Research.Url && urlsIn(prompt.question).size != 1) {
        throw ApiProblem("Include one full public HTTPS link in your question.")
    }
    if (prompt.attachment?.mime?.startsWith("image/") == true && !model.vision) throw ApiProblem("Choose a model with photo support.")
    if (prompt.attachment?.mime == "application/pdf" && !model.pdf) throw ApiProblem("Choose a direct model with PDF support.")
    val chars = prompt.question.length + prompt.history.sumOf { it.text.length } + (prompt.summary?.length ?: 0)
    val bound = if (model.context > 0) minOf(240_000, model.context.toLong().times(3).toInt()) else 240_000
    if (chars > bound) throw ApiProblem("This context is too long. Choose Recent turns, Summary, or Latest question only.")
}

private fun instruction(p: Prompt) = buildString {
    append(p.length.instruction)
    if (p.research == Research.Search) append(" Use the web search tool and cite its sources.")
    if (p.research == Research.Url) append(" Retrieve the specified URL with URL context. Report a retrieval failure plainly.")
    if (p.research == Research.Paper) append(" Organize the research summary as question, methods, results, limitations, and practical meaning. Include page references where supported.")
}

private fun promptText(p: Prompt): String {
    return buildString {
        append(p.question)
        p.summary?.let { append("\n\nPrior chat excerpts for context:\n$it") }
        p.attachment?.takeIf { it.mime == "text/plain" }?.let { file ->
            append("\n\nAttached file ${file.name} follows as reference material:\n${file.bytes.decodeToString()}")
        }
    }
}

internal fun routerBody(p: Prompt) = buildJsonObject {
    put("model", p.model.ref.id)
    put("max_tokens", 4096)
    put("provider", buildJsonObject {
        put("require_parameters", true)
        put("max_price", buildJsonObject {
            put("prompt", 0)
            put("completion", 0)
            put("image", 0)
            put("request", 0)
        })
    })
    if (p.effort != Effort.Auto) put("reasoning", buildJsonObject {
        put("effort", p.effort.apiName)
        put("exclude", true)
    })
    put("messages", buildJsonArray {
        add(chatPart("system", instruction(p)))
        p.history.forEach { add(chatPart(if (it.fromUser) "user" else "assistant", it.text)) }
        val image = p.attachment?.takeIf { it.mime.startsWith("image/") }
        if (image == null) add(chatPart("user", promptText(p)))
        else add(buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", promptText(p)) })
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", "data:${image.mime};base64,${image.bytes.base64()}") })
                })
            })
        })
    })
}

private fun chatPart(role: String, content: String) = buildJsonObject {
    put("role", role)
    put("content", content)
}

internal fun openAiBody(p: Prompt) = buildJsonObject {
    put("model", p.model.ref.id)
    put("store", false)
    put("max_output_tokens", 4096)
    put("instructions", instruction(p))
    if (p.effort != Effort.Auto) put("reasoning", buildJsonObject { put("effort", p.effort.apiName) })
    if (p.research == Research.Search) {
        put("tool_choice", buildJsonObject { put("type", "web_search") })
        put("tools", buildJsonArray { add(buildJsonObject { put("type", "web_search") }) })
    }
    put("input", buildJsonArray {
        p.history.forEach { add(chatPart(if (it.fromUser) "user" else "assistant", it.text)) }
        add(buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "input_text"); put("text", promptText(p)) })
                p.attachment?.let { file ->
                    val data = "data:${file.mime};base64,${file.bytes.base64()}"
                    if (file.mime == "application/pdf") add(buildJsonObject {
                        put("type", "input_file"); put("filename", file.name); put("file_data", data)
                    })
                    if (file.mime.startsWith("image/")) add(buildJsonObject {
                        put("type", "input_image"); put("image_url", data); put("detail", "auto")
                    })
                }
            })
        })
    })
}

internal fun geminiBody(p: Prompt) = buildJsonObject {
    put("systemInstruction", buildJsonObject {
        put("parts", buildJsonArray { add(buildJsonObject { put("text", instruction(p)) }) })
    })
    put("generationConfig", buildJsonObject {
        put("maxOutputTokens", 8192)
        if (p.effort != Effort.Auto) put("thinkingConfig", buildJsonObject {
            if (p.model.ref.id.startsWith("gemini-2.5")) {
                put("thinkingBudget", when (p.effort) {
                    Effort.None -> 0
                    Effort.Low -> 512
                    Effort.Medium -> 2048
                    else -> 8192
                })
            } else {
                put("thinkingLevel", p.effort.apiName.uppercase())
            }
        })
    })
    put("contents", buildJsonArray {
        p.history.forEach {
            add(geminiPart(if (it.fromUser) "user" else "model", it.text, null))
        }
        add(geminiPart("user", promptText(p), p.attachment?.takeUnless { it.mime == "text/plain" }))
    })
    val tool = when (p.research) {
        Research.Search -> "googleSearch"
        Research.Url -> "urlContext"
        else -> null
    }
    if (tool != null) put("tools", buildJsonArray {
        add(buildJsonObject { put(tool, buildJsonObject {}) })
    })
}

internal fun geminiPart(role: String, text: String, file: Attachment?) = buildJsonObject {
    put("role", role)
    put("parts", buildJsonArray {
        add(buildJsonObject { put("text", text) })
        if (file != null) add(buildJsonObject {
            put("inlineData", buildJsonObject {
                put("mimeType", file.mime)
                put("data", file.bytes.base64())
            })
        })
    })
}

private fun parseRouter(result: JsonObject, p: Prompt): Answer {
    val choice = result.objects("choices").firstOrNull() ?: throw ApiProblem("OpenRouter returned no answer. Try another free model.")
    val usage = result.obj("usage")
    if (choice.text("finish_reason") == "error") throw ApiProblem("The free model could not finish. Try another free model.")
    return answer(choice.obj("message").text("content"), result.text("model"), p,
        usage.count("prompt_tokens"), usage.count("completion_tokens"),
        usage.obj("completion_tokens_details").count("reasoning_tokens"))
}

private fun parseOpenAi(result: JsonObject, p: Prompt): Answer {
    val parts = result.objects("output").filter { it.text("type") == "message" }.flatMap { it.objects("content") }
    val citations = parts.flatMap { it.objects("annotations") }.filter { it.text("type") == "url_citation" }
        .mapNotNull { source(it.text("title"), it.text("url")) }
    val usage = result.obj("usage")
    val text = parts.filter { it.text("type") == "output_text" }.joinToString("\n") { it.text("text") }
    if (text.isBlank() && parts.any { it.text("type") == "refusal" }) throw ApiProblem("The OpenAI model declined this request.")
    return answer(text, result.text("model"), p, usage.count("input_tokens"), usage.count("output_tokens"),
        usage.obj("output_tokens_details").count("reasoning_tokens"), citations,
        truncated = result.text("status") == "incomplete")
}

private fun parseGemini(result: JsonObject, p: Prompt): Answer {
    val candidate = result.objects("candidates").firstOrNull() ?: throw ApiProblem("Gemini returned no answer.")
    val text = candidate.obj("content").objects("parts").filterNot { it.flag("thought") }.joinToString("\n") { it.text("text") }
    val links = candidate.obj("groundingMetadata").objects("groundingChunks").mapNotNull {
        val web = it.obj("web")
        source(web.text("title"), web.text("uri"))
    }
    val requested = urlsIn(p.question)
    val researched = candidate.obj("urlContextMetadata").objects("urlMetadata")
        .filter { it.text("urlRetrievalStatus") == "URL_RETRIEVAL_STATUS_SUCCESS" }
        .mapNotNull {
            val url = it.text("retrievedUrl")
            if (url.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build() in requested) source(url, url) else null
        }
    if (p.research == Research.Url && researched.isEmpty()) throw ApiProblem("Gemini could not verify that it read the URL. Check that the page is public and try again.")
    val counts = result.obj("usageMetadata")
    return answer(text, result.text("modelVersion"), p, counts.count("promptTokenCount"),
        counts.count("candidatesTokenCount"), counts.count("thoughtsTokenCount"), links + researched,
        truncated = candidate.text("finishReason") == "MAX_TOKENS")
}

private fun urlsIn(text: String) = Regex("https://[^\\s<>]+", RegexOption.IGNORE_CASE).findAll(text)
    .mapNotNull { it.value.trimEnd('.', ',', ')', ']', '!', '?').toHttpUrlOrNull() }
    .map { it.newBuilder().fragment(null).build() }.toSet()

private fun source(title: String, url: String): Source? {
    if (!url.startsWith("https://") && !url.startsWith("http://")) return null
    return Source(title.ifBlank { url }, url)
}

private fun answer(
    text: String, model: String, p: Prompt, input: Int?, output: Int?, reasoning: Int?,
    sources: List<Source> = emptyList(), truncated: Boolean = false,
): Answer {
    if (text.isBlank()) throw ApiProblem(if (truncated) "The model used its response limit before producing an answer." else "The provider returned an empty answer.")
    if (p.research == Research.Search && sources.isEmpty()) throw ApiProblem("The provider returned no verified search sources.")
    val estimate = when {
        p.model.ref.provider == Provider.Router -> 0.0
        p.research in listOf(Research.Search, Research.Url) || p.attachment != null -> null
        input != null && output != null && p.model.inputPerMillion != null && p.model.outputPerMillion != null ->
            (input * p.model.inputPerMillion +
                (output + if (p.model.ref.provider == Provider.Gemini) reasoning ?: 0 else 0) *
                p.model.outputPerMillion) / 1_000_000
        else -> null
    }
    val status = if (truncated) "\n\n[Response reached its length limit.]" else ""
    return Answer(text.trim() + status, model.ifBlank { p.model.ref.id }, Usage(input, output, reasoning, estimate), sources.distinctBy { it.url })
}
