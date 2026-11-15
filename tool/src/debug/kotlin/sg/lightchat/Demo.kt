package sg.lightchat

import kotlinx.coroutines.delay

internal val demoGateway: ChatGateway? = object : ChatGateway {
    private val openai = requireNotNull(openAiModel("gpt-6-luna"))
    private val gemini = Model(ModelRef(Provider.Gemini, "gemini-3.8-flash"), "Gemini 3.8 Flash", 1_048_576,
        vision = true, pdf = true, search = true, url = true, efforts = listOf(Effort.Low, Effort.Medium, Effort.High))

    override suspend fun catalog(provider: Provider, key: String?) = when (provider) {
        Provider.Router -> listOf(autoModel,
            Model(ModelRef(provider, "example/demo:free"), "Demo Free", 32_768, vision = true,
                efforts = listOf(Effort.Low, Effort.Medium, Effort.High)))
        Provider.OpenAI -> listOf(openai)
        Provider.Gemini -> listOf(
            gemini,
            gemini.copy(ref = ModelRef(Provider.Gemini, "gemini-3.1-flash-lite"), name = "Gemini 3.1 Flash-Lite"),
            gemini.copy(ref = ModelRef(Provider.Gemini, "gemini-4-carbon"), name = "Gemini 4 Carbon"),
        )
    }

    override suspend fun check(provider: Provider, key: String) = KeyStatus("Simulated key status")

    override suspend fun answer(prompt: Prompt, key: String): Answer {
        delay(250)
        if (prompt.question == "simulate failure") throw ApiProblem("Simulated network error. Your draft was kept.")
        val actual = if (prompt.model.ref == ModelRef.auto) "example/demo:free" else prompt.model.ref.id
        val text = buildString {
            append("Demo reply to “${prompt.question}”.")
            append(" ${prompt.history.size} prior messages.")
            if (prompt.summary != null) append(" Summary included.")
            prompt.attachment?.let { append(" Attached ${it.name} (${it.mime}).") }
            append(" Effort: ${prompt.effort.label}. Length: ${prompt.length.name}.")
            if (prompt.question.startsWith("Format demo answer")) {
                append("\n\n## Example heading\n- First example\n1. Second example\n\n```kotlin\nprintln(42)\n```")
            }
        }
        val links = if (prompt.research in listOf(Research.Search, Research.Url)) {
            listOf(Source("Example source", "https://example.com/"))
        } else emptyList()
        return Answer(text, actual, Usage(19, 11, 2, if (prompt.model.ref.provider == Provider.Router) 0.0 else 0.000008), links)
    }
}
