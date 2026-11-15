package sg.lightchat

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import android.content.ClipData
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.getOrNull
import com.thelightphone.sdk.ui.*
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@InitialScreen
internal class ChatScreen(activity: SealedLightActivity) : LightScreen<Unit, ChatViewModel>(activity) {
    override val viewModelClass = ChatViewModel::class.java
    override fun createViewModel() = ChatViewModel(ChatStore(lightContext.filesDir), KeyVault(lightContext.dataStore))
    private var stopAudio: (() -> Unit)? = null

    override fun onAppPause() {
        stopAudio?.invoke()
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val colors by LightThemeController.colors.collectAsState()
        val back = { goBack() }
        val scope = rememberCoroutineScope()
        var messageIndex by remember { mutableIntStateOf(-1) }
        val voice = remember { VoiceController(viewModel.api, scope) }
        val voiceState by voice.state.collectAsState()
        DisposableEffect(voice) {
            stopAudio = voice::stop
            onDispose { stopAudio = null; voice.close() }
        }
        val audioPermission = Manifest.permission.RECORD_AUDIO
        val permission = rememberPermissionRequestLauncher(audioPermission)
        val askMic: suspend () -> Boolean = {
            val result = withTimeoutOrNull(2_000) { checkPermission(audioPermission).getOrNull()?.permissionResult }
            when (result) {
                LightServiceMethod.GetPermission.Result.Denied -> {
                    permission?.launch()
                    viewModel.setStatus("Allow microphone access and try again.")
                    false
                }
                LightServiceMethod.GetPermission.Result.BlockedByServer -> {
                    viewModel.setStatus("LightOS blocked the microphone for this tool.")
                    false
                }
                else -> true
            }
        }
        DisposableEffect(state.page) {
            val audioPage = state.page in setOf(Page.Chat, Page.Voice, Page.ReadAloud)
            onDispose { if (audioPage) voice.close() }
        }
        val startDictation: suspend () -> Boolean = {
            val canRecord = state.demo || Provider.Gemini in state.connected
            if (!canRecord) viewModel.setStatus("Add a Gemini API key in Settings to dictate.")
            if (canRecord && (state.demo || askMic()) && viewModel.state.value.page == Page.Chat) {
                viewModel.setStatus(null)
                voice.startDictating()
            } else false
        }
        val finishDictation = {
            voice.finishDictating(state.demo, { viewModel.key(Provider.Gemini) }) { text ->
                val draft = viewModel.state.value.active?.draft.orEmpty()
                viewModel.draft(if (draft.isBlank()) text else "$draft $text")
            }
        }

        LightTheme(colors = colors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                val title = if (state.page == Page.Chat) {
                    if (state.privateChat) "One-off chat" else state.active?.title?.take(23) ?: "Chat"
                } else state.page.title
                val pageBack: () -> Unit = {
                    if (state.page in setOf(Page.Chat, Page.Voice, Page.ReadAloud)) voice.stop()
                    back()
                }
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = pageBack),
                    center = LightTopBarCenter.Text(title),
                    rightButton = when (state.page) {
                        Page.Home -> LightBarButton.Text("Settings", onClick = { viewModel.open(Page.Settings) })
                        Page.Chat -> if (state.busy || voiceState.mode != VoiceMode.Idle) null else LightBarButton.Text("Options", onClick = { viewModel.open(Page.ChatOptions) })
                        else -> null
                    },
                )
                if (state.demo) Note("Emulator demo · simulated answers", Modifier.padding(horizontal = 14.dp))
                state.storageWarning?.let { Note(it, Modifier.padding(horizontal = 14.dp)) }
                when (state.page) {
                    Page.Home -> Home(state, viewModel)
                    Page.Chat -> Conversation(state, viewModel, voiceState, startDictation, finishDictation, voice::stop) { index, page ->
                        messageIndex = index
                        viewModel.open(page)
                    }
                    Page.Settings -> Settings(state, viewModel)
                    Page.Models -> Models(state, viewModel)
                    Page.Key -> KeyEditor(state, viewModel)
                    Page.ChatOptions -> ChatOptions(state, viewModel)
                    Page.Switch -> SwitchModel(state, viewModel)
                    Page.Branch -> MessageOptions(state, messageIndex, viewModel, onRead = {
                        viewModel.open(Page.ReadAloud)
                        voice.prepareRead(it)
                    })
                    Page.Sources -> ListPage { sources(state.active?.messages?.getOrNull(messageIndex)?.sources.orEmpty()) }
                    Page.Attach -> Attachments(state, viewModel, shared = {
                        lightContext.fileShare.list(IMPORTS).filter { it.isFile }.map { it.name }
                    }, chooseShared = { name ->
                        scope.launch {
                            attempt(onError = viewModel::setStatus) {
                                val file = withContext(Dispatchers.IO) {
                                    lightContext.fileShare.readStream("$IMPORTS/$name") { makeAttachment(name, it) }
                                        ?: throw ApiProblem("The selected file is unavailable.")
                                }
                                viewModel.attach(file)
                            }
                        }
                    })
                    Page.Voice, Page.ReadAloud -> VoicePanel(state, voiceState, viewModel, voice, askMic)
                }
            }
        }
    }
}

@Composable
private fun Home(state: ChatState, model: ChatViewModel) {
    var query by remember { mutableStateOf("") }
    val chats = state.saved.chats.filter { it.matches(query.trim()) }
        .sortedWith(compareByDescending<Chat> { it.pinned }.thenByDescending { it.updated })
    Column(Modifier.padding(horizontal = 14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactButton("New chat", Modifier.fillMaxWidth(), enabled = state.loaded, primary = true) { model.newChat(false) }
            CompactButton("One-off chat", Modifier.fillMaxWidth(), enabled = state.loaded) { model.newChat(true) }
        }
        Input(query, { query = it }, "Search chats")
        if (chats.isEmpty()) Note(if (query.isBlank()) "No saved chats" else "No matching chats")
        LazyColumn {
            items(chats, key = { it.id }) { chat ->
                val detail = when {
                    chat.draft.isNotBlank() && chat.displayTitle != chat.title -> if (chat.pinned) "Pinned · Draft" else "Draft"
                    chat.draft.isNotBlank() -> "Draft · ${chat.draft.trim().lineSequence().first().take(70)}"
                    chat.pinned -> "Pinned"
                    else -> chat.model.provider.label
                }
                Menu(chat.displayTitle, detail) {
                    model.reopen(chat)
                }
            }
        }
    }
}

@Composable
private fun Conversation(
    state: ChatState,
    model: ChatViewModel,
    audio: VoiceState,
    startDictation: suspend () -> Boolean,
    finishDictation: () -> Unit,
    stopDictation: () -> Unit,
    inspect: (Int, Page) -> Unit,
) {
    val chat = state.active ?: return
    val list = rememberLazyListState()
    LaunchedEffect(chat.id, chat.messages.size) {
        if (chat.messages.isNotEmpty()) list.scrollToItem(chat.messages.lastIndex)
    }
    var consent by remember(chat.id) { mutableStateOf(false) }
    var dataSheet by remember(chat.id) { mutableStateOf(false) }
    var fileSheet by remember(chat.id) { mutableStateOf(false) }
    val ready = !state.busy && audio.mode == VoiceMode.Idle

    if (consent) Confirm("Send to ${chat.model.provider.label}?", state.contextDescription +
        if (chat.model.provider.mayBill) " ${chat.model.requestBillingLabel}" else "", "Send",
        accept = { consent = false; model.send() }, dismiss = { consent = false })

    if (dataSheet) DetailsDialog("What gets sent", { dataSheet = false }) {
        Note(state.contextDescription)
        if (state.privateChat) Note("Your transcript stays off this phone’s chat history. The provider still processes what you send.")
        if (chat.model.provider.mayBill) Note(chat.model.requestBillingLabel)
    }
    if (fileSheet && state.attachment != null) DetailsDialog(state.attachment.name, { fileSheet = false }) {
        Note("${state.attachment.typeLabel} attachment")
        AttachmentUse.entries.forEach { option ->
            Choice(option.label, state.attachmentUse == option) {
                model.attachmentUse(option)
                fileSheet = false
            }
        }
        TextAction("Remove attachment") { model.attach(null); fileSheet = false }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).imePadding()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextAction(state.model?.name ?: chat.model.id, Modifier.weight(1f), ready) { model.open(Page.Models) }
            TextAction("Voice", enabled = ready) { model.open(Page.Voice) }
        }
        Divider()
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(vertical = 5.dp)) {
            if (chat.messages.isEmpty()) item {
                Note(if (state.privateChat) "One-off chats stay off your history. The provider still receives what you send."
                    else "Ask a question or add a file.")
            }
            itemsIndexed(chat.messages) { index, message ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    LightText(if (message.fromUser) "You" else message.model.orEmpty(), LightTextVariant.Detail,
                        lighten = true, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (message.sources.isNotEmpty()) TextAction("Sources (${message.sources.size})", enabled = ready) {
                        inspect(index, Page.Sources)
                    }
                    TextAction(if (message.fromUser) "Edit in branch" else "Answer options", enabled = ready) {
                        inspect(index, Page.Branch)
                    }
                }
                SelectionContainer {
                    if (message.fromUser) LightText(message.text, LightTextVariant.Paragraph, modifier = Modifier.padding(vertical = 3.dp))
                    else FormattedAnswer(message.text)
                }
                message.attachment?.let { Note("File · $it") }
                Divider()
            }
        }
        state.status?.let { Note(it) }
        if (state.failedFree) TextAction("Choose another free model") { model.open(Page.Models) }
        if (audio.note.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Note(audio.note, Modifier.weight(1f))
                if (audio.mode == VoiceMode.Processing) TextAction("Cancel dictation", action = stopDictation)
            }
        }
        state.attachment?.let { file ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f).heightIn(min = 44.dp)
                        .lightClickable(enabled = ready, role = Role.Button, onClickLabel = "Attachment options") { fileSheet = true }
                        .padding(vertical = 5.dp),
                ) {
                    LightText(file.name, LightTextVariant.Detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LightText("${file.typeLabel} · ${state.attachmentUse.label}", LightTextVariant.Fine, lighten = true)
                }
                TextAction("Remove", enabled = ready) { model.attach(null) }
            }
        }
        if (state.research != Research.None) Note(state.research.label)

        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom) {
            CompactButton("+", label = "Attach", modifier = Modifier.width(40.dp), enabled = ready) { model.open(Page.Attach) }
            ComposerField(chat.draft, model::draft, ready, Modifier.weight(1f))
            DictateButton(audio.mode, enabled = !state.busy, startDictation, finishDictation, stopDictation)
            CompactButton(if (state.busy) "Cancel" else "Send", enabled = state.busy ||
                (ready && (chat.draft.isNotBlank() || state.attachment != null)), primary = true) {
                when {
                    state.busy -> model.cancelSend()
                    chat.model.provider.mayBill || state.attachment != null -> consent = true
                    else -> model.send()
                }
            }
        }
        if (chat.draft.isNotBlank() || chat.messages.isNotEmpty() || state.attachment != null || chat.model.provider.mayBill) {
            val sharing = (listOf(chat.model.provider.label) + chat.contextCounts(state.attachment)).joinToString(" · ")
            Box(
                Modifier.fillMaxWidth().heightIn(min = 32.dp)
                    .lightClickable(enabled = ready, role = Role.Button, onClickLabel = "What gets sent") { dataSheet = true },
                contentAlignment = Alignment.CenterStart,
            ) {
                LightText(sharing, LightTextVariant.Fine, lighten = true, underline = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else Spacer(Modifier.height(6.dp))
    }
}

private val ModelRef.requestBillingLabel: String
    get() = if (freeTierAvailable) "A free tier is available. Your project’s billing and quota still apply."
        else "Your project’s billing and quota apply."

@Composable
private fun ComposerField(text: String, change: (String) -> Unit, enabled: Boolean, modifier: Modifier) {
    val colors = LightThemeTokens.colors
    BasicTextField(
        value = text,
        onValueChange = change,
        modifier = modifier,
        enabled = enabled,
        minLines = 1,
        maxLines = 4,
        textStyle = LightThemeTokens.typography.paragraph.copy(color = colors.content, fontSize = 16.sp, lineHeight = 21.sp),
        cursorBrush = SolidColor(colors.content),
        decorationBox = { field ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 46.dp)
                    .border(1.dp, colors.content.copy(alpha = .55f)).padding(horizontal = 8.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) LightText("Message", LightTextVariant.Detail, lighten = true)
                field()
            }
        },
    )
}

@Composable
private fun DictateButton(
    mode: VoiceMode, enabled: Boolean, start: suspend () -> Boolean, finish: () -> Unit, cancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentMode by rememberUpdatedState(mode)
    val currentStart by rememberUpdatedState(start)
    val currentFinish by rememberUpdatedState(finish)
    val currentCancel by rememberUpdatedState(cancel)
    val touchEnabled = enabled && mode != VoiceMode.Processing
    val label = if (mode == VoiceMode.Recording) "Finish dictation" else "Dictate"
    val toggle: () -> Unit = {
        when (currentMode) {
            VoiceMode.Recording -> currentFinish()
            VoiceMode.Idle -> scope.launch { currentStart() }
            else -> Unit
        }
    }
    Box(
        Modifier.size(46.dp).border(1.dp, LightThemeTokens.colors.content.copy(alpha = if (touchEnabled) .65f else .25f))
            .semantics {
                contentDescription = label
                role = Role.Button
                if (touchEnabled) onClick(label) { toggle(); true }
            }
            .pointerInput(touchEnabled) {
                if (!touchEnabled) return@pointerInput
                var held: Job? = null
                detectTapGestures(
                    onTap = { toggle() },
                    onLongPress = {
                        if (currentMode == VoiceMode.Idle) held = scope.launch { currentStart() }
                    },
                    onPress = {
                        val released = tryAwaitRelease()
                        held?.let { job ->
                            job.cancel()
                            if (currentMode == VoiceMode.Recording) {
                                if (released) currentFinish() else currentCancel()
                            }
                        }
                        held = null
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (mode == VoiceMode.Recording) LightText("■", LightTextVariant.Detail)
        else LightIcon(LightIcons.MICROPHONE, size = 1.6f, contentDescription = null)
    }
}

@Composable
private fun Settings(state: ChatState, model: ChatViewModel) {
    var days by remember(state.saved.preferences.retentionDays) {
        mutableStateOf(state.saved.preferences.retentionDays.takeIf { it > 0 }?.toString().orEmpty())
    }
    var pendingDays by remember { mutableStateOf<Int?>(null) }
    var remove by remember { mutableStateOf<Provider?>(null) }
    var clear by remember { mutableStateOf(false) }
    pendingDays?.let {
        Confirm("Change history retention?", if (it == 0) "Saved chats will stay until you delete them."
            else "Chats inactive for more than $it days will be deleted.", "Apply", {
            model.retention(it)
            pendingDays = null
        }, { pendingDays = null })
    }
    remove?.let {
        Confirm("Remove ${it.label} key?", "You can add a new key in Settings.", "Remove",
            { model.removeKey(it); remove = null }, { remove = null })
    }
    if (clear) Confirm("Delete all saved chats?", "This removes the transcripts and drafts stored on this phone.",
        "Delete", { model.deleteAll(); clear = false }, { clear = false })
    ListPage {
        item {
            state.status?.let { Note(it) }
            if (state.keyBusy) Note("Checking key…")
            Note("OpenRouter chat uses free models exclusively. Direct OpenAI and Gemini calls use the selected project's quota.")
        }
        items(Provider.entries) { provider ->
            val saved = provider in state.connected
            Menu(provider.label, if (saved) "Key saved · ••••••••" else "Add API key", !state.keyBusy) { model.openKey(provider) }
            if (saved) {
                Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                    TextAction("Test key", enabled = !state.keyBusy) { model.checkKey(provider) }
                    TextAction("Remove key", enabled = !state.keyBusy) { remove = provider }
                }
            }
            state.keyChecks[provider]?.let { Note(it) }
        }
        item {
            val retention = state.saved.preferences.retentionDays
            Title("History retention")
            Note(if (retention == 0) "Keep until I delete" else "$retention days since last activity")
            Input(days, { days = it.filter(Char::isDigit).take(4) }, "Days (1–3650)", numeric = true)
            TextAction("Set days") {
                val value = days.toIntOrNull()
                if (value in 1..3650) pendingDays = value else model.setStatus("Enter 1 to 3650 days.")
            }
            TextAction("Keep until I delete") { pendingDays = 0 }
            TextAction("Delete all saved chats") { clear = true }
            Title("Data sent")
            Note("Each request sends its visible question, selected chat context, and current attachment to the chosen provider. Audio features send data to the provider named before recording or playback. Your provider's data settings apply, including in a one-off chat.")
            if (demoGateway != null) {
                Menu(if (state.demo) "Turn off emulator demo" else "Turn on emulator demo", "Use simulated replies without API keys") {
                    model.toggleDemo()
                }
            }
        }
    }
}

@Composable
private fun KeyEditor(state: ChatState, model: ChatViewModel) {
    var input by remember(state.keyProvider) { mutableStateOf("") }
    ListPage {
        item {
            Title("${state.keyProvider.label} API key")
            Note("Paste or type a project key. It is stored encrypted on this device.")
            Input(input, { input = it }, "API key", enabled = !state.keyBusy, password = true)
            state.status?.let { Note(it) }
            Action(if (state.keyBusy) "Checking…" else "Check and save", enabled = !state.keyBusy) { model.saveKey(input) }
        }
    }
}

@Composable
private fun Models(state: ChatState, model: ChatViewModel) {
    var query by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf(if (state.failedFree) Provider.Router else state.active?.model?.provider ?: Provider.Router) }
    var geminiFreeOnly by remember {
        mutableStateOf(state.active?.model?.let { it.provider != Provider.Gemini || it.freeTierAvailable } ?: true)
    }
    val favorites = state.saved.preferences.favorites
    val models = state.models.filter {
        it.ref.provider == provider && it.matches(query) && (provider != Provider.Gemini || !geminiFreeOnly || it.ref.freeTierAvailable)
    }.sortedWith(compareByDescending<Model> { it.ref in favorites }.thenByDescending { it.ref == ModelRef.auto }.thenBy { it.name })
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Provider.entries.forEach {
                TextAction(it.label, Modifier.weight(1f), selected = provider == it) { provider = it }
            }
        }
        Input(query, { query = it }, "Search models")
        state.status?.let { Note(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (provider == Provider.Gemini) {
                CompactButton("All", label = "All models", primary = !geminiFreeOnly) { geminiFreeOnly = false }
                CompactButton("Free tier", label = "Free-tier models", primary = geminiFreeOnly) { geminiFreeOnly = true }
                Spacer(Modifier.weight(1f))
            }
            TextAction(if (state.loadingModels) "Refreshing…" else "Refresh models", enabled = !state.loadingModels) { model.refreshModels() }
        }
        if (provider == Provider.Gemini && provider in state.connected) {
            Note("Your Google project sets billing and quota.")
        }
        if (!state.loadingModels && query.isNotBlank() && models.isEmpty()) Note("No matching models.")
        if (provider.mayBill && provider !in state.connected && !state.demo) {
            Note("Add this provider's API key in Settings to load the models available to your project.")
            TextAction("Open Settings") { model.open(Page.Settings) }
        }
        LazyColumn {
            items(models, key = { it.ref.id }) { item ->
                val capabilities = listOfNotNull(
                    item.ref.accessLabel,
                    "Photo".takeIf { item.vision }, "PDF".takeIf { item.pdf }, "Search".takeIf { item.search },
                    "URL".takeIf { item.url }, "Reasoning".takeIf { item.efforts.isNotEmpty() },
                ).joinToString(" · ")
                Menu(item.name, capabilities) { model.choose(item) }
                TextAction(if (item.ref in favorites) "Remove favorite" else "Favorite") { model.favorite(item.ref) }
            }
        }
    }
}

@Composable
private fun SwitchModel(state: ChatState, model: ChatViewModel) {
    val target = state.candidate ?: return
    val chat = state.active ?: return
    var mode by remember(target.ref) { mutableStateOf(if (target.ref.provider != chat.model.provider) ContextMode.Full else chat.context) }
    ListPage {
        item {
            Title(target.name)
            if (target.ref.provider.mayBill) Note(target.ref.requestBillingLabel)
            ContextMode.entries.filter { it != ContextMode.Recent || chat.context == ContextMode.Recent && target.ref.provider == chat.model.provider }.forEach {
                Choice(it.label, mode == it) { mode = it }
            }
            Note(chat.copy(context = mode).sendDescription(target.ref.provider, state.attachment))
            if (mode == ContextMode.Summary) {
                Note(chat.copy(context = ContextMode.Summary).contextSummary() ?: "There are no earlier messages.")
            }
            Action("Confirm switch") { model.switch(target, mode) }
        }
    }
}

@Composable
private fun ChatOptions(state: ChatState, model: ChatViewModel) {
    val chat = state.active ?: return
    var rename by remember { mutableStateOf(chat.title) }
    var delete by remember { mutableStateOf(false) }
    if (delete) Confirm("Delete this chat?", "This removes its saved transcript and draft from this phone.",
        "Delete", { model.delete(); delete = false }, { delete = false })
    ListPage {
        item {
            if (!state.privateChat) {
                Input(rename, { rename = it }, "Chat name")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextAction("Rename") { model.rename(rename) }
                    TextAction(if (chat.pinned) "Unpin" else "Pin") { model.pin() }
                    TextAction("Delete") { delete = true }
                }
            }
            Title("Answer length")
            Length.entries.forEach { Choice(it.name, chat.length == it) { model.length(it) } }
            Title("Reasoning effort")
            Note("Auto uses the provider's default.")
            (state.model?.effortOptions ?: listOf(Effort.Auto)).forEach { Choice(it.label, chat.effort == it) { model.effort(it) } }
            Title("Context")
            ContextMode.entries.forEach { Choice(it.label, chat.context == it) { model.context(it) } }
            if (chat.context == ContextMode.Summary) chat.contextSummary()?.let { Note(it) }
            Title("Research")
            Research.entries.forEach { option ->
                val supported = when (option) {
                    Research.Search -> state.model?.search == true
                    Research.Url -> state.model?.url == true
                    else -> true
                }
                Choice(option.label, state.research == option, supported) { model.research(option) }
            }
            if (state.model?.search != true || state.model?.url != true) Note("Research tools depend on the selected model. URL reading uses a compatible Gemini model.")
        }
    }
}

@Composable
private fun MessageOptions(state: ChatState, index: Int, model: ChatViewModel, onRead: (String) -> Unit) {
    val message = state.active?.messages?.getOrNull(index) ?: return
    ListPage {
        item {
            Menu(if (message.fromUser) "Edit in a new branch" else "Retry in a new branch", "Keep the original chat") { model.branch(index) }
            if (!message.fromUser) Menu("Read aloud", "OpenRouter Free or Gemini") { onRead(message.text) }
            message.usage?.let { usage ->
                Title("Usage")
                Note(listOfNotNull(usage.input?.let { "$it input" }, usage.output?.let { "$it output" },
                    usage.reasoning?.let { "$it reasoning" }).joinToString(" · ").ifBlank { "Token counts unavailable" })
                Note(when (val price = usage.estimatedDollars) {
                    0.0 -> "Free OpenRouter request"
                    null -> "Cost estimate unavailable"
                    else -> String.format(java.util.Locale.US, "Paid-tier token estimate US$%.6f", price)
                })
            }
            if (message.sources.isNotEmpty()) Title("Sources")
        }
        sources(message.sources)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sources(sources: List<Source>) {
    if (sources.isEmpty()) return
    items(sources, key = { it.url }) { source ->
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        var copied by remember(source.url) { mutableStateOf(false) }
        Menu(source.title, if (copied) "Link copied" else source.url) {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Source URL", source.url)))
                copied = true
            }
        }
    }
    item { Note("Tap a source to copy its link.") }
}

@Composable
private fun Attachments(state: ChatState, model: ChatViewModel, shared: () -> List<String>, chooseShared: (String) -> Unit) {
    var files by remember { mutableStateOf<List<String>?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun refresh() { files = withContext(Dispatchers.IO) { shared().sorted() } }
    LaunchedEffect(Unit) { refresh() }
    ListPage {
        item {
            state.status?.let { Note(it) }
            Note("In Tool Manager, open Light Chat → Imports to add a JPG, PNG, WebP, PDF, or text file. Choose a file, then choose whether to keep it for follow-ups.")
            TextAction("Refresh imports") { scope.launch { refresh() } }
            if (files?.isEmpty() == true) Note("No files found in Imports.")
            if (state.attachment != null) TextAction("Remove current attachment") { model.attach(null) }
        }
        items(files.orEmpty()) { name -> Menu(name, name.substringAfterLast('.', "").uppercase().takeIf { it.isNotEmpty() }) { chooseShared(name) } }
    }
}

internal fun makeAttachment(name: String, input: InputStream): Attachment {
    val inferred = when (name.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "txt", "md", "csv", "log" -> "text/plain"
        else -> null
    }
    val mime = inferred ?: throw ApiProblem("This file type is unsupported.")
    val limit = if (mime == "text/plain") 60_000 else 8 * 1024 * 1024
    val data = input.readNBytes(limit + 1)
    if (data.isEmpty() || data.size > limit) throw ApiProblem("Choose a non-empty file under ${if (mime == "text/plain") "60 KB" else "8 MB"}.")
    return Attachment(name.take(160), mime, data)
}

@Composable
internal fun Title(text: String) =
    LightText(text, LightTextVariant.Paragraph, modifier = Modifier.padding(top = 13.dp, bottom = 3.dp))

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier) =
    LightText(text, LightTextVariant.Detail, lighten = true, modifier = modifier.padding(vertical = 4.dp))

@Composable
internal fun ListPage(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) =
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(14.dp), content = content)

@Composable
internal fun Input(
    text: String, change: (String) -> Unit, label: String, enabled: Boolean = true,
    singleLine: Boolean = true, password: Boolean = false, numeric: Boolean = false,
) {
    val color = LightThemeTokens.colors.content
    OutlinedTextField(
        value = text, onValueChange = change, label = { LightText(label, LightTextVariant.Detail) },
        enabled = enabled, singleLine = singleLine, maxLines = if (singleLine) 1 else 4,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = !password,
            keyboardType = when {
                password -> KeyboardType.Password
                numeric -> KeyboardType.Number
                else -> KeyboardType.Text
            },
        ),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = color, unfocusedTextColor = color,
            focusedBorderColor = color, unfocusedBorderColor = color.copy(alpha = .6f), cursorColor = color),
    )
}

@Composable
internal fun TextAction(
    label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    selected: Boolean = false, action: () -> Unit,
) {
    Box(modifier.heightIn(min = 42.dp).lightClickable(enabled = enabled, role = Role.Button, onClick = action).padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center) {
        LightText(label, LightTextVariant.Detail, underline = true, lighten = !enabled,
            color = if (selected) LightThemeTokens.colors.content else null)
    }
}

@Composable
internal fun Action(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, action: () -> Unit) {
    val color = LightThemeTokens.colors.content
    Box(modifier.heightIn(min = 44.dp).border(1.dp, color.copy(alpha = if (enabled) .7f else .25f))
        .lightClickable(enabled = enabled, role = Role.Button, onClick = action).padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center) {
        LightText(label, LightTextVariant.Button, lighten = !enabled)
    }
}

@Composable
private fun CompactButton(
    text: String, modifier: Modifier = Modifier, label: String = text, enabled: Boolean = true,
    primary: Boolean = false, action: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    Box(
        modifier.heightIn(min = 46.dp)
            .background(if (enabled && primary) colors.content else colors.background)
            .border(1.dp, colors.content.copy(alpha = if (enabled) .7f else .3f))
            .lightClickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = action)
            .then(if (label != text) Modifier.semantics { contentDescription = label } else Modifier)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text, LightTextVariant.Detail, lighten = !enabled,
            color = if (enabled && primary) colors.background else null, maxLines = 1)
    }
}

@Composable
private fun DetailsDialog(title: String, close: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        containerColor = LightThemeTokens.colors.background,
        title = { LightText(title, LightTextVariant.Paragraph) },
        text = { Column { content() } },
        confirmButton = { TextButton(onClick = close) { LightText("Done", LightTextVariant.Detail) } },
    )
}

@Composable
internal fun Menu(label: String, detail: String? = null, enabled: Boolean = true, action: () -> Unit) {
    Column(Modifier.fillMaxWidth().lightClickable(enabled = enabled, role = Role.Button, onClick = action).padding(vertical = 10.dp)) {
        LightText(label, LightTextVariant.Paragraph, lighten = !enabled)
        if (detail != null) Note(detail)
    }
    Divider()
}

@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean = true, action: () -> Unit) =
    Menu(if (selected) "●  $label" else "○  $label", enabled = enabled, action = action)

@Composable
private fun Divider() = HorizontalDivider(color = LightThemeTokens.colors.content.copy(alpha = .25f))

@Composable
internal fun Confirm(title: String, text: String, label: String, accept: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss, containerColor = LightThemeTokens.colors.background,
        title = { LightText(title, LightTextVariant.Paragraph) }, text = { LightText(text, LightTextVariant.Detail) },
        confirmButton = { TextButton(onClick = accept) { LightText(label, LightTextVariant.Button) } },
        dismissButton = { TextButton(onClick = dismiss) { LightText("Cancel", LightTextVariant.Button) } },
    )
}
