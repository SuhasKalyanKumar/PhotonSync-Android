package me.suhaskalyan.photonsync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Offline Vosk speech engine, same model and grammar as the desktop voice_listener_worker.
 * The microphone is only open between [start] and [stop]; recognised phrases go to [onCommand] on the main thread.
 */
class VoiceEngine(private val context: Context, private val onCommand: (String) -> Unit) {

    companion object {
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        private const val SAMPLE_RATE = 16000f

        val COLOR_DICTIONARY = mapOf(
            "red" to intArrayOf(255, 0, 0), "green" to intArrayOf(0, 255, 0), "blue" to intArrayOf(0, 0, 255),
            "yellow" to intArrayOf(255, 255, 0), "cyan" to intArrayOf(0, 255, 255), "magenta" to intArrayOf(255, 0, 255),
            "white" to intArrayOf(255, 255, 255), "orange" to intArrayOf(255, 128, 0), "amber" to intArrayOf(255, 191, 0),
            "gold" to intArrayOf(255, 215, 0), "peach" to intArrayOf(255, 203, 164), "pink" to intArrayOf(255, 105, 180),
            "hot pink" to intArrayOf(255, 20, 147), "purple" to intArrayOf(128, 0, 128), "violet" to intArrayOf(238, 130, 238),
            "lavender" to intArrayOf(230, 230, 250), "indigo" to intArrayOf(75, 0, 130), "sky blue" to intArrayOf(135, 206, 235),
            "light blue" to intArrayOf(173, 216, 230), "ice blue" to intArrayOf(200, 235, 255), "navy" to intArrayOf(0, 0, 128),
            "teal" to intArrayOf(0, 128, 128), "turquoise" to intArrayOf(64, 224, 208), "aqua" to intArrayOf(0, 255, 255),
            "lime" to intArrayOf(50, 205, 50), "mint" to intArrayOf(152, 255, 152), "forest green" to intArrayOf(34, 139, 34),
            "emerald" to intArrayOf(80, 200, 120), "crimson" to intArrayOf(220, 20, 60), "maroon" to intArrayOf(128, 0, 0),
            "coral" to intArrayOf(255, 127, 80), "brown" to intArrayOf(139, 69, 19),
        )

        private val VALID_PHRASES = listOf(
            "turn on", "turn off", "power on", "power off", "on", "off",
            "screen sync", "sync", "normal",
            "low brightness", "medium brightness", "high brightness", "low", "medium", "high",
        ) + COLOR_DICTIONARY.keys

        // "[unk]" lets everyday chatter fall through instead of being forced onto the nearest command
        private val GRAMMAR = JSONArray(VALID_PHRASES + "[unk]").toString()
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var model: Model? = null
    private var speech: SpeechService? = null
    @Volatile private var wanted = false

    fun start() {
        wanted = true
        executor.execute {
            if (speech != null) return@execute
            val m = model ?: loadModel() ?: return@execute
            model = m
            if (!wanted) return@execute
            try {
                val service = SpeechService(Recognizer(m, SAMPLE_RATE, GRAMMAR), SAMPLE_RATE)
                service.startListening(listener)
                speech = service
                SyncState.log("🎙️ Voice engine listening")
            } catch (e: Exception) {
                SyncState.log("⚠️ Voice Engine Error: ${e.message}")
            }
        }
    }

    fun stop() {
        wanted = false
        executor.execute {
            val service = speech ?: return@execute
            service.stop()
            service.shutdown()
            speech = null
            SyncState.log("🔇 Voice engine stopped (mic closed)")
        }
    }

    fun release() {
        stop()
        executor.execute {
            model?.close()
            model = null
        }
        executor.shutdown()
    }

    private val listener = object : RecognitionListener {
        override fun onResult(hypothesis: String) {
            val text = JSONObject(hypothesis).optString("text")
                .replace("[unk]", "").trim().replace(Regex("\\s+"), " ").lowercase()
            if (text.isNotEmpty()) onCommand(text)
        }

        override fun onPartialResult(hypothesis: String) {}

        override fun onFinalResult(hypothesis: String) {}

        override fun onError(exception: Exception) {
            SyncState.log("⚠️ Voice Engine Error: ${exception.message}")
        }

        override fun onTimeout() {}
    }

    // -------------------------------------------------------------
    // AUTOMATIC VOSK MODEL DOWNLOADER
    // -------------------------------------------------------------
    private fun loadModel(): Model? {
        val dir = File(context.filesDir, MODEL_NAME)
        if (!File(dir, "am/final.mdl").exists()) {
            SyncState.log("⏳ Downloading voice model (~40MB, one time only)...")
            try {
                downloadModel(dir)
            } catch (e: Exception) {
                SyncState.log("⚠️ Failed to download voice model: ${e.message}")
                return null
            }
            SyncState.log("✅ Voice model ready")
        }
        return try {
            Model(dir.absolutePath)
        } catch (e: Exception) {
            SyncState.log("⚠️ Voice model failed to load: ${e.message}")
            null
        }
    }

    private fun downloadModel(dir: File) {
        val staging = File(context.filesDir, "model-download").apply { deleteRecursively(); mkdirs() }
        val stagingRoot = staging.canonicalPath + File.separator
        val connection = URL(MODEL_URL).openConnection().apply {
            connectTimeout = 15_000
            readTimeout = 30_000 // a stalled download reports an error instead of hanging forever
        }
        ZipInputStream(connection.getInputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(staging, entry.name)
                if (!out.canonicalPath.startsWith(stagingRoot)) continue // ignore paths escaping the folder
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        dir.deleteRecursively()
        if (!File(staging, MODEL_NAME).renameTo(dir)) error("could not unpack model")
        staging.deleteRecursively()
    }
}
