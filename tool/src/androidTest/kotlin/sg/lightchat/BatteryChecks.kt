package sg.lightchat

import okhttp3.ResponseBody.Companion.toResponseBody
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightActivity
import kotlinx.coroutines.*

class BatteryChecks : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            checks()
            result.putString("stream", "\nPASS: battery regression and lifecycle checks\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(error))
            finish(Activity.RESULT_CANCELED, result)
        }
    }
    private fun main(action: () -> Unit) = runOnMainSync(action)
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out: $label" }
            Thread.sleep(20)
        }
    }
    private fun launch(): Activity = startActivitySync(Intent(Intent.ACTION_MAIN)
        .setClassName(targetContext.packageName, "com.thelightphone.sdk.LightActivity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun screen(activity: Activity): LightScreen<*, *> {
        val entries = LightActivity::class.java.getDeclaredField("backStack").apply { isAccessible = true }.get(activity) as List<*>
        val entry = entries.last()!!
        return entry.javaClass.getDeclaredField("screen").apply { isAccessible = true }.get(entry) as LightScreen<*, *>
    }
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun checks() {
        repeat(3) { iteration ->
            val activity = launch()
            lateinit var model: ChatViewModel
            main { model = screen(activity).viewModel as ChatViewModel }
            await("saved chats loaded") { model.state.value.loaded }
            await("foreground cleanup") { (field(model, "pruning") as? Job)?.isActive == true }
            main { callActivityOnPause(activity) }
            check(field(model, "pruning") == null) { "Cleanup timer remains active while paused" }
            main { callActivityOnResume(activity) }
            await("cleanup restarts") { (field(model, "pruning") as? Job)?.isActive == true }
            main {
                model.toggleDemo()
                model.newChat(false)
                model.draft("Battery regression $iteration")
                model.send()
            }
            await("reply") { !model.state.value.busy && model.state.value.active?.messages?.size == 2 }
            val id = model.state.value.active!!.id
            main { model.draft("Saved draft $iteration") }
            await("draft persisted") { ChatStore(targetContext.filesDir).load().chats.any { it.id == id && it.draft == "Saved draft $iteration" } }
            val job = model.viewModelScope.coroutineContext[Job]!!
            main { model.draft("Final draft $iteration"); activity.finish() }
            await("Activity destroyed") { activity.isDestroyed }
            check(!job.isActive) { "Destroyed Activity kept its ViewModel running" }
            check(field(model, "pruning") == null) { "Destroyed Activity retained cleanup" }
            await("last edit survives immediate close") { ChatStore(targetContext.filesDir).load().chats.any { it.id == id && it.messages.size == 2 && it.draft == "Final draft $iteration" } }
        }
        val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var voice: VoiceController
        main {
            voice = VoiceController(ChatApi(), audioScope, dictationLimitMs = 800L)
            check(voice.startDictating()) { "Microphone failed" }
        }
        await("recording limit releases microphone") { field(voice, "input") == null }
        check(voice.state.value.mode == VoiceMode.Recording) { "Captured dictation is no longer finishable" }
        var transcript = ""
        main { voice.finishDictating(true, { null }) { transcript = it } }
        await("dictation after limit") { transcript.isNotEmpty() }
        main { voice.prepareRead("Audio regression"); voice.read(Provider.Gemini, { null }, true) }
        await("read aloud") { voice.state.value.mode == VoiceMode.Playing }
        main { voice.pauseOrResume() }
        check(voice.state.value.mode == VoiceMode.Paused)
        main { voice.pauseOrResume(); voice.stop() }
        check(field(voice, "player") == null)
        main { voice.startLive({ null }, true) }
        await("demo live voice") { voice.state.value.mode == VoiceMode.Live }
        main { voice.close(); audioScope.cancel() }
        check(voice.state.value.mode == VoiceMode.Idle)
        // A delayed catalogue is cancelled on pause and can be fetched on return.
        val activity = launch()
        val cancelled = java.util.concurrent.atomic.AtomicInteger()
        val started = java.util.concurrent.atomic.AtomicInteger()
        val blocking = java.util.concurrent.atomic.AtomicBoolean(true)
        val api = ChatApi(okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            started.incrementAndGet()
            while (blocking.get() && !chain.call().isCanceled()) Thread.sleep(10)
            if (chain.call().isCanceled()) { cancelled.incrementAndGet(); throw java.io.IOException("cancelled fixture") }
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("Fixture").body("{\"data\":[]}".toResponseBody()).build()
        }.build())
        val keys = object : Keys {
            override suspend fun get(provider: Provider): String? = null
            override suspend fun put(provider: Provider, key: String?) {}
        }
        lateinit var model: ChatViewModel
        main { model = ChatViewModel(ChatStore(java.io.File(targetContext.cacheDir, "catalog-test")), keys, api) }
        await("fixture loaded") { model.state.value.loaded }
        main { model.refreshModels() }
        await("catalogue starts") { started.get() > 0 }
        main { model.onAppPause() }
        await("catalogue transport cancels") { cancelled.get() > 0 }
        check(!model.state.value.loadingModels)
        blocking.set(false)
        main {
            @Suppress("UNCHECKED_CAST")
            model.onScreenShow(screen(activity) as com.thelightphone.sdk.SimpleLightScreen<Unit>)
        }
        await("catalogue resumes") { started.get() >= 2 && !model.state.value.loadingModels }
        main {
            model.onAppPause()
            val owner = androidx.lifecycle.ViewModelStore()
            owner.put("test", model)
            owner.clear()
            activity.finish()
        }
    }
}
