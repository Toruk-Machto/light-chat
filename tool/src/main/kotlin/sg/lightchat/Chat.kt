package sg.lightchat

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class Provider(val label: String) {
    Router("OpenRouter"), OpenAI("OpenAI"), Gemini("Gemini");

    val mayBill: Boolean get() = this != Router
}

@Serializable
enum class Effort(val label: String) {
    Auto("Auto"), None("Off"), Minimal("Minimal"), Low("Low"), Medium("Medium"),
    High("High"), ExtraHigh("Extra high"), Max("Maximum");

    val apiName: String get() = if (this == ExtraHigh) "xhigh" else name.lowercase()
}

@Serializable
enum class Length(val instruction: String) {
    Brief("Answer briefly. Lead with the answer."),
    Normal("Give a clear answer sized to the question."),
    Detailed("Explain the answer in detail with useful examples.");
}

@Serializable
enum class ContextMode(val label: String) {
    Full("Full chat"), Recent("Recent turns"), Summary("Summary"), Fresh("Latest question only");
}

enum class Research(val label: String) {
    None("Chat"), Search("Web search"), Url("Read URL"), Paper("Research paper summary");
}

@Serializable
data class ModelRef(val provider: Provider, val id: String) {
    val freeTierAvailable: Boolean get() = when (provider) {
        Provider.Router -> id == auto.id || id.endsWith(":free")
        Provider.Gemini -> id in geminiFreeTierModels
        Provider.OpenAI -> false
    }

    val accessLabel: String get() = when {
        provider == Provider.Router -> "Free only"
        freeTierAvailable -> "Free tier available"
        provider == Provider.OpenAI -> "Uses project billing"
        else -> "Check project pricing"
    }

    companion object {
        val auto = ModelRef(Provider.Router, "openrouter/free")

        // Google publishes pricing separately from the model catalog. Match named models
        // explicitly so new aliases aren't assumed to be available on the free tier.
        private val geminiFreeTierModels = setOf(
            "gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite", "gemini-2.5-flash", "gemini-2.5-flash-lite",
        )
    }
}

data class Model(
    val ref: ModelRef,
    val name: String,
    val context: Int = 0,
    val vision: Boolean = false,
    val pdf: Boolean = false,
    val search: Boolean = false,
    val url: Boolean = false,
    val efforts: List<Effort> = emptyList(),
    val inputPerMillion: Double? = null,
    val outputPerMillion: Double? = null,
) {
    val effortOptions get() = listOf(Effort.Auto) + efforts
    private val searchName = searchText(name)
    private val searchId = searchText(ref.id)

    fun matches(query: String): Boolean = searchText(query).let { it in searchName || it in searchId }

    private fun searchText(value: String) = value.filter(Char::isLetterOrDigit).lowercase()
}

@Serializable
data class Source(val title: String, val url: String)

@Serializable
data class Usage(
    val input: Int? = null,
    val output: Int? = null,
    val reasoning: Int? = null,
    val estimatedDollars: Double? = null,
)

@Serializable
data class Message(
    val text: String,
    val fromUser: Boolean,
    val model: String? = null,
    val provider: Provider? = null,
    val sources: List<Source> = emptyList(),
    val usage: Usage? = null,
    val attachment: String? = null,
)

@Serializable
data class Chat(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val pinned: Boolean = false,
    val updated: Long = System.currentTimeMillis(),
    val model: ModelRef = ModelRef.auto,
    val length: Length = Length.Normal,
    val effort: Effort = Effort.Auto,
    val context: ContextMode = ContextMode.Recent,
    val draft: String = "",
    val messages: List<Message> = emptyList(),
) {
    val displayTitle get() = if (title == "New chat" && messages.isEmpty() && draft.isNotBlank()) {
        draft.trim().lineSequence().first().take(55)
    } else title

    fun branch(end: Int, newDraft: String): Chat = copy(
        id = UUID.randomUUID().toString(),
        title = "Branch of ${title.take(65)}",
        pinned = false,
        updated = System.currentTimeMillis(),
        messages = messages.take(end),
        draft = newDraft,
    )
}

@Serializable
data class Preferences(
    val favorites: Set<ModelRef> = emptySet(),
    val retentionDays: Int = 30,
)

enum class AttachmentUse(val label: String) {
    Once("Send once"), FollowUp("Keep for follow-ups");
}

data class Attachment(val name: String, val mime: String, val bytes: ByteArray) {
    val typeLabel: String get() = when (mime) {
        "application/pdf" -> "PDF"
        "image/jpeg" -> "JPG"
        "image/png" -> "PNG"
        "image/webp" -> "WebP"
        else -> "Text"
    }
}

data class Prompt(
    val model: Model,
    val question: String,
    val history: List<Message>,
    val summary: String?,
    val length: Length,
    val effort: Effort,
    val research: Research = Research.None,
    val attachment: Attachment? = null,
)

fun Chat.contextMessages(): List<Message> = when (context) {
    ContextMode.Full -> messages
    ContextMode.Recent -> messages.takeLast(8).dropWhile { !it.fromUser }
    ContextMode.Summary, ContextMode.Fresh -> emptyList()
}

private fun Chat.summaryMessages(): List<Message> {
    if (context != ContextMode.Summary) return emptyList()
    return if (messages.size <= 10) messages else messages.take(2) + messages.takeLast(8)
}

fun Chat.contextSummary(): String? {
    // Keep it local and bounded. The exact excerpts are reviewed before switching if desired.
    val sampled = summaryMessages().ifEmpty { return null }
    return sampled.joinToString("\n") {
        val speaker = if (it.fromUser) "User" else "Assistant"
        "$speaker: ${it.text.trim().replace(Regex("\\s+"), " ").take(360)}"
    }
}

fun Chat.contextCounts(attachment: Attachment?): List<String> {
    val excerpts = summaryMessages().size
    val prior = contextMessages().size
    val scope = when {
        excerpts > 0 -> "Summary of $excerpts earlier messages"
        prior == 1 -> "1 earlier message"
        prior > 1 -> "$prior earlier messages"
        else -> "Question only"
    }
    return listOfNotNull(scope, attachment?.let { "1 ${it.typeLabel} file" })
}

fun Chat.sendDescription(provider: Provider, attachment: Attachment?): String = buildString {
    append("Send your next message to ${provider.label}.")
    append(" Context: ${contextCounts(null).single()}.")
    attachment?.let { append(" Attachment: ${it.name} (${it.typeLabel}).") }
}

fun Chat.isExpired(retentionDays: Int, now: Long): Boolean =
    retentionDays > 0 && updated < now - retentionDays * 86_400_000L

fun Chat.matches(query: String): Boolean =
    title.contains(query, ignoreCase = true) || draft.contains(query, ignoreCase = true) ||
        messages.any { it.text.contains(query, ignoreCase = true) }
