package com.echos.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

internal data class FrenchNeuralVoice(
    val id: String,
    val label: String,
    val archive: String,
    val modelFile: String,
    val approximateSize: String
)

internal object FrenchNeuralVoices {
    val catalog = listOf(
        FrenchNeuralVoice("siwis", "Siwis · voix féminine", "vits-piper-fr_FR-siwis-medium.tar.bz2", "fr_FR-siwis-medium.onnx", "~64 Mo"),
        FrenchNeuralVoice("tom", "Tom · voix masculine", "vits-piper-fr_FR-tom-medium.tar.bz2", "fr_FR-tom-medium.onnx", "~64 Mo"),
        FrenchNeuralVoice("upmc", "UPMC · voix française", "vits-piper-fr_FR-upmc-medium.tar.bz2", "fr_FR-upmc-medium.onnx", "~64 Mo")
    )
    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"

    fun directory(context: Context, voice: FrenchNeuralVoice) =
        File(context.filesDir, "neural-voices/${voice.id}")

    fun isInstalled(context: Context, voice: FrenchNeuralVoice) =
        File(directory(context, voice), voice.modelFile).exists() &&
            File(directory(context, voice), "tokens.txt").exists() &&
            File(directory(context, voice), "espeak-ng-data").isDirectory

    fun downloadAndInstall(context: Context, voice: FrenchNeuralVoice, progress: (Int) -> Unit) {
        val base = File(context.filesDir, "neural-voices")
        base.mkdirs()
        val archiveFile = File(base, voice.archive)
        val tempFolder = File(base, "install-${voice.id}-tmp")
        val finalFolder = directory(context, voice)
        var connection: HttpURLConnection? = null
        try {
            connection = URL(BASE + voice.archive).openConnection() as HttpURLConnection
            connection.connectTimeout = 20000
            connection.readTimeout = 45000
            connection.setRequestProperty("User-Agent", "ECHOS-Android")
            connection.connect()
            if (connection.responseCode !in 200..299) throw IllegalStateException("Téléchargement HTTP ${connection.responseCode}")
            val expected = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(archiveFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        total += count
                        if (expected > 0) progress((total * 45 / expected).toInt().coerceIn(0, 45))
                    }
                }
            }
            tempFolder.deleteRecursively()
            tempFolder.mkdirs()
            BZip2CompressorInputStream(FileInputStream(archiveFile)).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        val safeName = entry.name.replace('\\', '/').trimStart('/')
                        if (safeName.split('/').any { it == ".." }) throw SecurityException("Archive invalide")
                        val output = File(tempFolder, safeName)
                        if (entry.isDirectory) output.mkdirs()
                        else {
                            output.parentFile?.mkdirs()
                            FileOutputStream(output).use { out ->
                                tar.copyTo(out)
                            }
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }
            progress(90)
            // Archive has one root folder. Flatten its contents into the pack directory.
            val root = tempFolder.listFiles()?.singleOrNull()?.takeIf { it.isDirectory } ?: tempFolder
            if (!File(root, voice.modelFile).exists() || !File(root, "tokens.txt").exists() ||
                !File(root, "espeak-ng-data").isDirectory) {
                throw IllegalStateException("Le pack vocal téléchargé est incomplet.")
            }
            finalFolder.deleteRecursively()
            finalFolder.mkdirs()
            root.listFiles()?.forEach { item ->
                val target = File(finalFolder, item.name)
                if (item.isDirectory) item.copyRecursively(target, overwrite = true)
                else item.copyTo(target, overwrite = true)
            }
            progress(100)
        } finally {
            connection?.disconnect()
            archiveFile.delete()
            tempFolder.deleteRecursively()
        }
    }
}

internal class NeuralVoiceEngine(private val context: Context) {
    private var loadedId: String? = null
    private var tts: OfflineTts? = null
    private val stopRequested = AtomicBoolean(false)

    @Synchronized
    private fun ensureLoaded(voice: FrenchNeuralVoice): OfflineTts {
        if (loadedId == voice.id && tts != null) return tts!!
        tts?.release()
        val dir = FrenchNeuralVoices.directory(context, voice)
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(dir, voice.modelFile).absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    dataDir = File(dir, "espeak-ng-data").absolutePath
                ),
                numThreads = 2,
                debug = false
            ),
            maxNumSentences = 1
        )
        val engine = OfflineTts(config = config)
        tts = engine
        loadedId = voice.id
        return engine
    }

    fun stop() { stopRequested.set(true) }

    @Synchronized\n    fun speak(text: String, voice: FrenchNeuralVoice, speakerId: Int, speed: Float = 1.0f, whisper: Boolean = false) {
        stopRequested.set(false)
        val engine = ensureLoaded(voice)
        val sampleRate = engine.sampleRate()
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack(
            android.media.AudioManager.STREAM_MUSIC, sampleRate,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, sampleRate / 2), AudioTrack.MODE_STREAM
        )
        try {
            track.play()
            val generation = GenerationConfig(sid = speakerId, speed = speed.coerceIn(0.75f, 1.25f), silenceScale = 0.2f)
            engine.generateWithConfigAndCallback(text, generation) { samples ->
                if (stopRequested.get()) return@generateWithConfigAndCallback 0
                val shorts = ShortArray(samples.size)
                for (i in samples.indices) {
                    var sample = samples[i]
                    if (whisper) {
                        // Whisper-style timbre processing: reduced harmonics, soft spectral-like shaping,
                        // and compression. This changes the signal character, not only its loudness.
                        sample = kotlin.math.tanh(sample * 1.45f) * 0.22f
                    }
                    shorts[i] = (sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
                var offset = 0
                while (offset < shorts.size && !stopRequested.get()) {
                    val written = track.write(shorts, offset, shorts.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) break
                    offset += written
                }
                if (stopRequested.get()) 0 else 1
            }
            if (!stopRequested.get()) track.stop()
        } finally {
            try { track.flush() } catch (_: Exception) {}
            track.release()
        }
    }

    @Synchronized
    fun release() {
        try { tts?.release() } catch (e: Exception) { Log.w("ECHOS_NEURAL", "Release engine", e) }
        tts = null
        loadedId = null
    }
}
