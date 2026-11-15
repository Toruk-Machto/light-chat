package sg.lightchat

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class Page(val title: String) {
    Home("Light Chat"), Chat("Chat"), Settings("Settings"), Models("Models"), Key("API key"),
    ChatOptions("Chat options"), Branch("Message"), Switch("Switch model"), Attach("Attach"), Voice("Voice"), Sources("Sources"), ReadAloud("Read aloud"),
}

internal data class ChatState(
    val saved: SavedChats = SavedChats(),
    val loaded: Boolean = false,
    val active: Chat? = null,
    val privateChat: Boolean = false,
    val page: Page = Page.Home,
    val status: String? = null,
    val storageWarning: String? = null,
    val busy: Boolean = false,
    val keyBusy: Boolean = false,
    val connected: Set<Provider> = emptySet(),
    val keyProvider: Provider = Provider.Router,
    val keyChecks: Map<Provider, String> = emptyMap(),
    val models: List<Model> = listOf(autoModel),
    val loadingModels: Boolean = false,
    val candidate: Model? = null,
    val attachment: Attachment? = null,
    val attachmentUse: AttachmentUse = AttachmentUse.Once,
    val research: Research = Research.None,
    val failedFree: Boolean = false,
    val demo: Boolean = false,
) {
    val model: Model? get() = models.firstOrNull { it.ref == active?.model }
    val contextDescription: String
        get() = active?.let { it.sendDescription(it.model.provider, attachment) }.orEmpty()
}

internal class ChatViewModel(
    private val store: ChatStore,
    private val keys: Keys,
    val api: ChatApi = ChatApi(),
) : LightViewModel<Unit>() {
    private val mutable = MutableStateFlow(ChatState())
    val state = mutable.asStateFlow()
    private val pendingWrites = Channel<SavedChats>(Channel.CONFLATED)
    private var request: Job? = null
    private var refreshing: Job? = null
    private val current get() = mutable.value
    private val gateway get(): ChatGateway = if (current.demo) demoGateway ?: api else api

    init {
        viewModelScope.launch {
            val saved = storage { store.load() } ?: SavedChats()
            change { it.copy(saved = saved, loaded = true) }
            prune()
            val connected = withContext(Dispatchers.IO) {
                Provider.entries.filter { provider -> runCatching { keys.get(provider) }.getOrNull() != null }.toSet()
            }
            change { it.copy(connected = connected) }
            for (data in pendingWrites) storage { store.save(data) }
        }
        viewModelScope.launch {
            while (true) {
                delay(15 * 60_000L)
                prune()
            }
        }
    }

    private suspend fun <T> storage(operation: () -> T): T? = try {
        val result = withContext(Dispatchers.IO) { operation() }
        change { it.copy(storageWarning = null) }
        result
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        change { it.copy(storageWarning = "Local storage could not be accessed. Your current chat is still on screen.") }
        null
    }

    private fun change(update: (ChatState) -> ChatState) {
        mutable.value = update(current)
    }

    private fun save(data: SavedChats) {
        change { it.copy(saved = data) }
        pendingWrites.trySend(data)
    }

    private fun prune() {
        val saved = current.saved
        val now = System.currentTimeMillis()
        val activeId = current.active?.id
        val retained = saved.chats.filter { it.id == activeId || !it.isExpired(saved.preferences.retentionDays, now) }
        if (retained.size != saved.chats.size) save(saved.copy(chats = retained))
    }

    fun open(page: Page) {
        change { it.copy(page = page, status = null) }
        if (page == Page.Home) prune()
        if (page == Page.Models && current.models.size == 1) refreshModels()
    }

    fun setStatus(text: String?) = change { it.copy(status = text) }

    fun openKey(provider: Provider) = change { it.copy(keyProvider = provider, page = Page.Key, status = null) }

    fun newChat(oneOff: Boolean) {
        if (!current.loaded || current.busy) return
        activate(Chat(), oneOff)
    }

    fun reopen(chat: Chat) {
        activate(chat, false)
    }

    private fun activate(chat: Chat, oneOff: Boolean) {
        val touched = if (oneOff) chat else chat.copy(updated = System.currentTimeMillis())
        change {
            it.copy(active = touched, page = Page.Chat, privateChat = oneOff, status = null, attachment = null,
                research = Research.None, failedFree = false, candidate = null)
        }
        if (current.models.none { it.ref == touched.model }) refreshModels()
        if (!oneOff && current.saved.chats.any { it.id == touched.id }) updateChat { touched }
    }

    private fun updateChat(update: (Chat) -> Chat) {
        val chat = current.active ?: return
        val next = update(chat).copy(updated = System.currentTimeMillis())
        change { it.copy(active = next) }
        if (current.privateChat) return
        val saved = current.saved
        val existed = saved.chats.any { it.id == chat.id }
        if (!existed && next.messages.isEmpty() && next.draft.isBlank() && next.title == "New chat") return
        val chats = if (existed) saved.chats.map { if (it.id == next.id) next else it } else saved.chats + next
        save(saved.copy(chats = chats))
    }

    fun draft(text: String) {
        if (current.active?.draft != text) updateChat { it.copy(draft = text.take(60_000)) }
    }

    fun pin() = updateChat { it.copy(pinned = !it.pinned) }

    fun rename(title: String) {
        if (title.isNotBlank()) updateChat { it.copy(title = title.trim().take(100)) }
        open(Page.Chat)
    }

    fun delete() {
        val chat = current.active ?: return
        cancelSend()
        if (!current.privateChat) save(current.saved.copy(chats = current.saved.chats.filterNot { it.id == chat.id }))
        leaveChat()
    }

    fun deleteAll() {
        save(current.saved.copy(chats = emptyList()))
        change { it.copy(active = null, attachment = null, privateChat = false, research = Research.None) }
    }

    fun leaveChat() {
        cancelSend()
        change { it.copy(active = null, privateChat = false, attachment = null, research = Research.None, page = Page.Home, status = null) }
        prune()
    }

    fun retention(days: Int) {
        val saved = current.saved
        save(saved.copy(preferences = saved.preferences.copy(retentionDays = days.coerceIn(0, 3650))))
        prune()
    }

    fun length(value: Length) = updateChat { it.copy(length = value) }

    fun effort(value: Effort) = updateChat { it.copy(effort = value) }

    fun context(value: ContextMode) = updateChat { it.copy(context = value) }

    fun attach(file: Attachment?) {
        change { it.copy(attachment = file, attachmentUse = AttachmentUse.Once, status = null, page = Page.Chat) }
    }

    fun attachmentUse(value: AttachmentUse) {
        if (current.attachment != null && !current.busy) change { it.copy(attachmentUse = value) }
    }

    fun research(value: Research) {
        change { it.copy(research = value, status = null, page = Page.Chat) }
    }

    fun branch(at: Int) {
        val chat = current.active ?: return
        val index = if (chat.messages.getOrNull(at)?.fromUser == true) at
            else (at - 1 downTo 0).firstOrNull { chat.messages[it].fromUser } ?: return
        val branched = chat.branch(index, chat.messages[index].text)
        // The branch contains text only. Attachments must be selected again.
        val notice = if (chat.messages[index].attachment != null) "Reattach the original file to resend it." else null
        activate(branched, current.privateChat)
        updateChat { it }
        if (notice != null) setStatus(notice)
    }

    fun favorite(ref: ModelRef) {
        val saved = current.saved
        val favorites = saved.preferences.favorites
        val next = if (ref in favorites) favorites - ref else favorites + ref
        save(saved.copy(preferences = saved.preferences.copy(favorites = next)))
    }

    fun choose(model: Model) {
        val chat = current.active ?: return
        if (chat.model != model.ref && (chat.messages.isNotEmpty() || model.ref.provider.mayBill)) {
            change { it.copy(candidate = model, page = Page.Switch, status = null) }
        } else {
            switch(model, chat.context)
        }
    }

    fun switch(model: Model, mode: ContextMode) {
        updateChat { it.copy(model = model.ref, context = mode, effort = Effort.Auto) }
        change { it.copy(candidate = null, page = Page.Chat, status = null, failedFree = false) }
    }

    fun refreshModels() {
        refreshing?.cancel()
        change { it.copy(loadingModels = true) }
        refreshing = viewModelScope.launch {
            val connected = current.connected
            val transport = gateway
            val updated = current.models.associateBy { it.ref }.toMutableMap()
            val errors = mutableListOf<String>()
            for (provider in Provider.entries) {
                if (provider != Provider.Router && provider !in connected && !current.demo) continue
                attempt(onError = { errors += it }) {
                    val list = transport.catalog(provider, key(provider))
                    updated.keys.removeAll { it.provider == provider }
                    list.forEach { updated[it.ref] = it }
                }
            }
            change { it.copy(models = updated.values.toList(), loadingModels = false) }
            if (errors.isNotEmpty() && current.page == Page.Models) setStatus(errors.first())
        }
    }

    suspend fun key(provider: Provider): String? = withContext(Dispatchers.IO) { keys.get(provider) }

    fun saveKey(text: String) {
        val key = text.trim()
        if (key.length !in 8..500 || key.any { it.code !in 33..126 }) {
            setStatus("Enter the API key with no spaces.")
            return
        }
        val provider = current.keyProvider
        keyAction(provider) {
            val result = api.check(provider, key)
            withContext(Dispatchers.IO) { keys.put(provider, key) }
            change { it.copy(connected = it.connected + provider, page = Page.Settings) }
            result.detail
        }
    }

    fun checkKey(provider: Provider) = keyAction(provider) {
        val value = key(provider) ?: throw ApiProblem("Add a ${provider.label} key first.")
        api.check(provider, value).detail
    }

    fun removeKey(provider: Provider) = keyAction(provider) {
        withContext(Dispatchers.IO) { keys.put(provider, null) }
        refreshing?.cancel()
        change {
            it.copy(connected = it.connected - provider, loadingModels = false,
                models = if (provider == Provider.Router) it.models else it.models.filter { model -> model.ref.provider != provider })
        }
        "Key removed."
    }

    private fun keyAction(provider: Provider, action: suspend () -> String) {
        if (current.keyBusy) return
        change { it.copy(keyBusy = true, status = null) }
        viewModelScope.launch {
            try {
                attempt(onError = { setStatus(it) }) {
                    val note = action()
                    change { it.copy(keyChecks = it.keyChecks + (provider to note)) }
                }
            } finally {
                change { it.copy(keyBusy = false) }
            }
        }
    }

    fun toggleDemo() {
        if (demoGateway == null) return
        cancelSend()
        refreshing?.cancel()
        change {
            it.copy(demo = !it.demo, active = null, attachment = null, privateChat = false, loadingModels = false,
                models = listOf(autoModel), status = if (it.demo) "Live providers enabled." else "Emulator demo enabled. Replies are simulated.")
        }
    }

    fun send() {
        val chat = current.active ?: return
        val question = chat.draft.trim()
        val model = current.model
        if (current.busy || (question.isEmpty() && current.attachment == null)) return
        if (model == null) {
            setStatus("Refresh Models or select another model before sending.")
            return
        }
        val attachment = current.attachment
        val attachmentUse = current.attachmentUse
        val prompt = Prompt(model, question.ifBlank { "Explain this attachment." }, chat.contextMessages(), chat.contextSummary(),
            chat.length, chat.effort, current.research, attachment)
        val transport = gateway
        change { it.copy(busy = true, status = null, failedFree = false) }
        request = viewModelScope.launch {
            val provider = model.ref.provider
            try {
                attempt(onError = { message ->
                    change { it.copy(status = message, failedFree = provider == Provider.Router) }
                }) {
                    val key = if (current.demo) "emulator-demo" else key(provider)
                        ?: throw ApiProblem("Add a ${provider.label} API key in Settings.")
                    val answer = transport.answer(prompt, key)
                    updateChat {
                        val title = if (it.title == "New chat") prompt.question.replace('\n', ' ').take(55) else it.title
                        it.copy(
                            title = title,
                            draft = "",
                            messages = it.messages + Message(prompt.question, true, attachment = attachment?.name) +
                                Message(answer.text, false, answer.model, provider, answer.sources, answer.usage),
                        )
                    }
                    change {
                        val clearFile = attachmentUse == AttachmentUse.Once && it.attachment === attachment
                        it.copy(research = Research.None, attachment = if (clearFile) null else it.attachment)
                    }
                }
            } finally {
                change { it.copy(busy = false) }
            }
        }
    }

    fun cancelSend() {
        request?.cancel()
        request = null
        if (current.busy) change { it.copy(busy = false, status = "Request cancelled. Draft kept.") }
    }

    override fun onBackPressed(): Boolean = when (current.page) {
        Page.Home -> false
        Page.Chat -> true.also { leaveChat() }
        Page.Settings -> true.also { open(if (current.active == null) Page.Home else Page.Chat) }
        Page.Key -> true.also { open(Page.Settings) }
        Page.Switch -> true.also { open(Page.Models) }
        else -> true.also { open(Page.Chat) }
    }
}

internal suspend fun attempt(onError: (String) -> Unit, run: suspend () -> Unit) {
    try {
        run()
    } catch (error: CancellationException) {
        throw error
    } catch (error: ApiProblem) {
        onError(error.message.orEmpty())
    } catch (_: Exception) {
        onError("The action could not finish. Your draft and settings are still available.")
    }
}
