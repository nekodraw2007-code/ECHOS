package com.echos.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
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
    val speakerId: Int,
    val archive: String = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
    val modelFile: String = "duration_predictor.int8.onnx",
    val approximateSize: String = "~100 Mo"
)

internal object FrenchNeuralVoices {
    // Supertonic 3 provides ten speaker identities and supports French (lang=fr).
    // All entries share one model directory and one downloaded archive.
    val catalog = (0..9).map { sid ->
        FrenchNeuralVoice(
            id = "supertonic_fr_$sid",
            label = "Voix française ${sid + 1}",
            speakerId = sid
        )
    }

    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
    private const val MODEL_DIR = "supertonic-fr"

    fun directory(context: Context, voice: FrenchNeuralVoice) =
        File(context.filesDir, "neural-voices/$MODEL_DIR")

    fun isInstalled(context: Context, voice: FrenchNeuralVoice): Boolean {
        val dir = directory(context, voice)
        return listOf(
            "duration_predictor.int8.onnx",
            "text_encoder.int8.onnx",
            "vector_estimator.int8.onnx",
            "vocoder.int8.onnx",
            "tts.json",
            "unicode_indexer.bin",
            "voice.bin"
        ).all { File(dir, it).isFile && File(dir, it).length() > 0L }
    }

    fun downloadAndInstall(context: Context, voice: FrenchNeuralVoice, progress: (Int) -> Unit) {
        val base = File(context.filesDir, "neural-voices")
        if (!base.exists() && !base.mkdirs()) throw IllegalStateException("Impossible de créer le dossier des voix.")
        val archiveFile = File(base, voice.archive)
        val tempFolder = File(base, "install-supertonic-fr-tmp")
        val finalFolder = directory(context, voice)
        var expectedTotal = -1L

        // GitHub release assets support HTTP Range. Keep a partial archive so an
        // interrupted mobile connection can resume instead of restarting at 0%.
        var completed = false
        var attempt = 0
        var lastError: Exception? = null
        progress(0)
        while (!completed && attempt < 7) {
            attempt++
            var connection: HttpURLConnection? = null
            try {
                val offset = archiveFile.takeIf { it.isFile }?.length() ?: 0L
                connection = (URL(BASE + voice.archive).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 120000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "ECHOS-Android")
                    setRequestProperty("Accept-Encoding", "identity")
                    if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
                }
                connection.connect()
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_REQUESTED_RANGE_NOT_SATISFIABLE) {
                    // A stale/complete partial file cannot be resumed: restart cleanly.
                    archiveFile.delete()
                    expectedTotal = -1L
                    throw java.io.IOException("Reprise HTTP 416, nouveau téléchargement nécessaire")
                }
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw java.io.IOException("Serveur vocal HTTP $code")
                }

                var startOffset = offset
                if (code == HttpURLConnection.HTTP_OK) {
                    // Server ignored Range: overwrite rather than append duplicate bytes.
                    startOffset = 0L
                    expectedTotal = connection.contentLengthLong
                } else {
                    val range = connection.getHeaderField("Content-Range")
                        ?: throw java.io.IOException("Réponse de reprise sans Content-Range")
                    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(range.trim())
                        ?: throw java.io.IOException("En-tête Content-Range invalide : $range")
                    val rangeStart = match.groupValues[1].toLong()
                    if (rangeStart != offset) throw java.io.IOException("Reprise incohérente : $range")
                    expectedTotal = match.groupValues[3].toLongOrNull() ?: -1L
                }

                val totalBefore = startOffset
                connection.inputStream.use { input ->
                    FileOutputStream(archiveFile, startOffset > 0L).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var total = totalBefore
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            total += count
                            if (expectedTotal > 0L) {
                                val percent = (total * 45L / expectedTotal).toInt().coerceIn(0, 45)
                                progress(percent)
                            }
                        }
                        output.fd.sync()
                    }
                }
                val actual = archiveFile.length()
                if (expectedTotal > 0L && actual != expectedTotal) {
                    throw java.io.IOException("Téléchargement incomplet : $actual / $expectedTotal octets")
                }
                if (actual <= 0L) throw java.io.IOException("Le serveur a envoyé un fichier vide")
                completed = true
            } catch (e: Exception) {
                lastError = e
                if (attempt >= 7) break
                // Keep the partial file for the next Range request.
                try { Thread.sleep((1000L * attempt).coerceAtMost(6000L)) }
                catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.IOException("Téléchargement interrompu")
                }
            } finally {
                connection?.disconnect()
            }
        }
        if (!completed) {
            throw java.io.IOException(
                "Téléchargement interrompu après $attempt tentatives. " +
                    "Les données déjà reçues sont conservées pour reprendre. " +
                    (lastError?.localizedMessage ?: "Vérifie la connexion Internet.")
            )
        }

        try {
            tempFolder.deleteRecursively()
            if (!tempFolder.mkdirs()) throw IllegalStateException("Impossible de préparer l'extraction du pack.")
            progress(46)
            BZip2CompressorInputStream(FileInputStream(archiveFile)).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    var entry = tar.nextTarEntry
                    var extractedBytes = 0L
                    val estimatedExtractBytes = 220L * 1024L * 1024L
                    while (entry != null) {
                        val currentEntry = entry
                        val safeName = currentEntry.name.replace('\\\\', '/').trimStart('/')
                        if (safeName.split('/').any { it == ".." }) throw SecurityException("Archive invalide")
                        val output = File(tempFolder, safeName)
                        if (currentEntry.isDirectory) output.mkdirs()
                        else {
                            output.parentFile?.mkdirs()
                            FileOutputStream(output).use { out ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val n = tar.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                    extractedBytes += n
                                    val pct = (46 + extractedBytes * 42 / estimatedExtractBytes).toInt().coerceIn(46, 88)
                                    progress(pct)
                                }
                            }
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }
            progress(90)
            val root = tempFolder.listFiles()?.singleOrNull()?.takeIf { it.isDirectory } ?: tempFolder
            val required = listOf(
                "duration_predictor.int8.onnx",
                "text_encoder.int8.onnx",
                "vector_estimator.int8.onnx",
                "vocoder.int8.onnx",
                "tts.json",
                "unicode_indexer.bin",
                "voice.bin"
            )
            val missing = required.filter { !File(root, it).isFile || File(root, it).length() <= 0L }
            if (missing.isNotEmpty()) throw IllegalStateException("Pack Supertonic incomplet : " + missing.joinToString())

            val installStage = File(base, "supertonic-fr-install-stage")
            installStage.deleteRecursively()
            if (!installStage.mkdirs()) throw IllegalStateException("Impossible de préparer l'installation du pack.")
            root.listFiles()?.forEach { item ->
                val target = File(installStage, item.name)
                if (item.isDirectory) item.copyRecursively(target, overwrite = true)
                else item.copyTo(target, overwrite = true)
            }
            finalFolder.deleteRecursively()
            if (!installStage.renameTo(finalFolder)) {
                installStage.copyRecursively(finalFolder, overwrite = true)
                installStage.deleteRecursively()
            }
            if (!isInstalled(context, voice)) throw IllegalStateException("La validation finale du pack Supertonic a échoué.")
            progress(100)
            // Only delete the archive after successful extraction and validation.
            archiveFile.delete()
        } finally {
            tempFolder.deleteRecursively()
        }
    }
}

internal class NeuralVoiceEngine(private val context: Context) {
    private var loadedId: String? = null
    private var tts: OfflineTts? = null
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var activeTrack: AudioTrack? = null

    @Synchronized
    private fun ensureLoaded(voice: FrenchNeuralVoice): OfflineTts {
        if (loadedId == "supertonic-fr" && tts != null) return tts!!
        try { tts?.release() } catch (_: Exception) {}
        val dir = FrenchNeuralVoices.directory(context, voice)
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = File(dir, "duration_predictor.int8.onnx").absolutePath,
                    textEncoder = File(dir, "text_encoder.int8.onnx").absolutePath,
                    vectorEstimator = File(dir, "vector_estimator.int8.onnx").absolutePath,
                    vocoder = File(dir, "vocoder.int8.onnx").absolutePath,
                    ttsJson = File(dir, "tts.json").absolutePath,
                    unicodeIndexer = File(dir, "unicode_indexer.bin").absolutePath,
                    voiceStyle = File(dir, "voice.bin").absolutePath
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu"
            ),
            maxNumSentences = 1
        )
        val engine = OfflineTts(config = config)
        tts = engine
        loadedId = "supertonic-fr"
        return engine
    }

    fun stop() {
        stopRequested.set(true)
        try {
            activeTrack?.pause()
            activeTrack?.flush()
        } catch (_: Exception) {
            // Track may already be stopping or released.
        }
    }

    @Synchronized
    fun speak(
        text: String,
        voice: FrenchNeuralVoice,
        speakerId: Int,
        speed: Float = 1.0f,
        style: Int = 4,
        question: Boolean = false
    ) {
        stopRequested.set(false)
        require(FrenchNeuralVoices.isInstalled(context, voice)) { "Le pack Supertonic français n'est pas installé." }
        val engine = ensureLoaded(voice)
        val generation = GenerationConfig(
            sid = speakerId.coerceIn(0, 9),
            speed = speed.coerceIn(0.88f, 1.12f),
            numSteps = 8,
            silenceScale = 0.2f,
            extra = mapOf("lang" to "fr")
        )

        // Generate without a JNI streaming callback. This avoids the native callback
        // crash seen on ART and gives us the complete audio before playback starts.
        val audio = engine.generateWithConfig(text, generation)
        if (stopRequested.get()) return
        require(audio.sampleRate in 8000..48000) { "Fréquence audio invalide : " + audio.sampleRate + " Hz" }
        require(audio.samples.isNotEmpty()) { "Le moteur Supertonic n'a généré aucun échantillon audio." }

        val minBuffer = AudioTrack.getMinBufferSize(
            audio.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuffer > 0) { "AudioTrack.getMinBufferSize a échoué : " + minBuffer }
        val targetBytes = maxOf(minBuffer.toLong(), audio.sampleRate.toLong())
        val bufferSizeBytes = (((targetBytes + 1L) / 2L) * 2L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val track = AudioTrack(
            android.media.AudioManager.STREAM_MUSIC,
            audio.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeBytes,
            AudioTrack.MODE_STREAM
        )
        require(track.state == AudioTrack.STATE_INITIALIZED) {
            "AudioTrack non initialisé (sample rate=" + audio.sampleRate + ")"
        }
        activeTrack = track
        try {
            if (question) track.playbackRate = (audio.sampleRate * 1.02f).toInt()
            val pcm = ShortArray(audio.samples.size)
            for (i in audio.samples.indices) {
                // Keep the generated voice intact; only leave a little headroom.
                val sample = (audio.samples[i] * 0.96f).coerceIn(-1.0f, 1.0f)
                pcm[i] = (sample * 32767.0f).toInt().toShort()
            }

            track.play()
            var offset = 0
            while (offset < pcm.size && !stopRequested.get()) {
                val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                if (written < 0) throw IllegalStateException("AudioTrack.write a échoué : " + written)
                if (written == 0) {
                    try {
                        Thread.sleep(5L)
                    } catch (_: InterruptedException) {
                        stopRequested.set(true)
                        Thread.currentThread().interrupt()
                        return
                    }
                } else {
                    offset += written
                }
            }
            if (!stopRequested.get() && offset == pcm.size) {
                val timeoutMs = maxOf(5000L, (pcm.size * 1000L / audio.sampleRate) + 5000L)
                val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
                while (!stopRequested.get() &&
                    (track.playbackHeadPosition.toLong() and 0xffffffffL) < pcm.size.toLong() &&
                    android.os.SystemClock.elapsedRealtime() < deadline
                ) {
                    try {
                        Thread.sleep(10L)
                    } catch (_: InterruptedException) {
                        stopRequested.set(true)
                        Thread.currentThread().interrupt()
                        return
                    }
                }
                if (!stopRequested.get() &&
                    (track.playbackHeadPosition.toLong() and 0xffffffffL) < pcm.size.toLong()
                ) {
                    throw IllegalStateException(
                        "Lecture audio incomplète : " + track.playbackHeadPosition + "/" + pcm.size + " trames."
                    )
                }
                if (!stopRequested.get()) track.stop()
            }
        } finally {
            activeTrack = null
            if (stopRequested.get()) {
                try { track.pause(); track.flush() } catch (_: Exception) {}
            }
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
