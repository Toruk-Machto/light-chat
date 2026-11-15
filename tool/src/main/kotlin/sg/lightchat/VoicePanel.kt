package sg.lightchat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun VoicePanel(
    state: ChatState,
    audio: VoiceState,
    model: ChatViewModel,
    voice: VoiceController,
    askMic: suspend () -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<Provider?>(null) }
    var liveConfirm by remember { mutableStateOf(false) }
    val reading = state.page == Page.ReadAloud
    val active = audio.mode != VoiceMode.Idle
    val transcript = rememberLazyListState()
    val lines = remember(audio.liveText) { audio.liveText.lineSequence().filter(String::isNotBlank).toList() }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) transcript.scrollToItem(lines.lastIndex)
    }

    if (liveConfirm) Confirm(
        "Start Gemini Live?",
        "Gemini receives microphone audio while this screen is active. The free tier is available if your project qualifies.",
        "Start",
        {
            liveConfirm = false
            scope.launch { if (state.demo || askMic()) voice.startLive({ model.key(Provider.Gemini) }, state.demo) }
        },
        { liveConfirm = false },
    )
    confirm?.let { provider ->
        Confirm(
            "Read via ${provider.label}?",
            "This sends up to 4,000 characters of the answer to ${provider.label} for speech playback.",
            "Read",
            { confirm = null; voice.read(provider, { model.key(provider) }, state.demo) },
            { confirm = null },
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        state.status?.let { Note(it) }
        if (audio.note.isNotBlank()) Title(audio.note)
        LazyColumn(Modifier.weight(1f), state = transcript, contentPadding = PaddingValues(vertical = 10.dp)) {
            if (reading && !active && voice.textToRead != null) item {
                Menu("Read with OpenRouter Free", "Uses a free speech model when available") { confirm = Provider.Router }
                Menu("Read with Gemini", "Uses your Gemini project’s quota") { confirm = Provider.Gemini }
            }
            if (!reading && !active) item {
                Note("Speak naturally. You can interrupt Gemini while it’s replying.")
            }
            items(lines.size) { index -> Note(lines[index]) }
        }
        if (!reading && !active) Action("Start live voice", Modifier.fillMaxWidth()) { liveConfirm = true }
        if (active) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (audio.mode == VoiceMode.Live) TextAction("Mute reply", Modifier.weight(1f), action = voice::interrupt)
                if (audio.mode == VoiceMode.Playing || audio.mode == VoiceMode.Paused) {
                    val label = if (audio.mode == VoiceMode.Playing) "Pause" else "Resume"
                    TextAction(label, Modifier.weight(1f), action = voice::pauseOrResume)
                }
                Action("Stop", Modifier.weight(1f), action = voice::stop)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
