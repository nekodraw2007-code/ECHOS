package com.echos.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

private data class WordEntry(val category: String, val text: String, val weight: Int = 1, val custom: Boolean = false)

class MainActivity : Activity(), SensorEventListener {
    private val bg = Color.rgb(7, 10, 16)
    private val panel = Color.rgb(17, 24, 35)
    private val panel2 = Color.rgb(25, 35, 49)
    private val accent = Color.rgb(88, 211, 190)
    private val white = Color.rgb(241, 246, 250)
    private val muted = Color.rgb(143, 158, 177)
    private val orange = Color.rgb(245, 174, 91)
    private val red = Color.rgb(240, 111, 111)

    private lateinit var sensors: SensorManager
    private var magnetic: Sensor? = null
    private var magneticUncalibrated: Sensor? = null
    private var accelerometer: Sensor? = null
    private var accelerometerUncalibrated: Sensor? = null
    private var gyroscope: Sensor? = null
    private var gyroscopeUncalibrated: Sensor? = null
    private var gameRotationVector: Sensor? = null

    private val baseEntries = mutableListOf<WordEntry>()
    private val customEntries = mutableListOf<WordEntry>()
    private val customCategories = mutableSetOf<String>()
    private val activeCategories = mutableSetOf<String>()
    private val history = mutableListOf<String>()
    private val calibrationValues = mutableListOf<Float>()

    private var sensitivity = 0.82f
    private var sessionActive = false
    private var calibrating = false
    private var calibrated = false
    private var baseline = 0f
    private var noiseFloor = 0.08f
    private var magneticMagnitude = 0f
    private var magneticDelta = 0f
    private var accelerationMagnitude = 0f
    private var rotationMagnitude = 0f
    private var lastSignatureAt = 0L
    private var nextWordAt = 0L
    private var lastWord = ""
    private var lastWordAt = 0L
    private var shuffledWordIndexes = mutableListOf<Int>()
    private var sessionStartedAt = 0L
    private var currentMode = "MAGNÉTIQUE"

    private lateinit var statusView: TextView
    private lateinit var fieldView: TextView
    private lateinit var deltaView: TextView
    private lateinit var wordView: TextView
    private lateinit var sessionButton: Button
    private lateinit var sensitivityLabel: TextView
    private lateinit var sensorLabel: TextView
    private lateinit var historyView: TextView
    private lateinit var categoriesButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        magnetic = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        magneticUncalibrated = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
        accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometerUncalibrated = sensors.getDefaultSensor(35) // TYPE_ACCELEROMETER_UNCALIBRATED
        gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        gyroscopeUncalibrated = sensors.getDefaultSensor(16) // TYPE_GYROSCOPE_UNCALIBRATED
        gameRotationVector = sensors.getDefaultSensor(15) // TYPE_GAME_ROTATION_VECTOR
        loadDictionary()
        loadPreferences()
        buildInterface()
        refreshSensorStatus()
    }

    private fun loadDictionary() {
        try {
            val json = assets.open("dictionary_fr.json").bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val array = root.getJSONArray("entries")
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                baseEntries.add(WordEntry(item.getString("category"), item.getString("text"), item.optInt("weight", 1)))
            }
        } catch (e: Exception) {
            baseEntries.clear()
            baseEntries.addAll(listOf(
                WordEntry("Réponses", "OUI"), WordEntry("Réponses", "NON"),
                WordEntry("Émotions", "PEUR"), WordEntry("Lieux", "MAISON")
            ))
        }
    }

    private fun loadPreferences() {
        val p = getSharedPreferences("echos_preferences", MODE_PRIVATE)
        sensitivity = p.getFloat("sensitivity", 0.82f).coerceIn(0f, 1f)
        baseline = p.getFloat("baseline", 0f)
        noiseFloor = p.getFloat("noise_floor", 0.08f)
        calibrated = p.getBoolean("calibrated", false)
        val cats = p.getString("active_categories", "")!!.split("\n").filter { it.isNotBlank() }
        val categoryAliases = mapOf("Personnes" to "Personnes et identité", "Sensations" to "Sensations et corps")
        if (cats.isEmpty()) activeCategories.addAll(baseEntries.map { it.category }.distinct())
        else activeCategories.addAll(cats.map { categoryAliases[it] ?: it })
        val savedCats = p.getString("custom_categories", "")!!.split("\n").filter { it.isNotBlank() }
        customCategories.addAll(savedCats)
        try {
            val arr = JSONArray(p.getString("custom_entries", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                customEntries.add(WordEntry(o.getString("category"), o.getString("text"), 1, true))
            }
        } catch (_: Exception) {}
        // One-time migration from ECHOS 1.0.x custom-word storage.
        val legacyWords = p.getString("custom_words", "")!!.split("\n").filter { it.isNotBlank() }
        if (legacyWords.isNotEmpty() && customEntries.isEmpty()) {
            customCategories.add("Personnalisés")
            activeCategories.add("Personnalisés")
            legacyWords.forEach { customEntries.add(WordEntry("Personnalisés", it, 1, true)) }
            saveCustomDictionary()
        }
        // The expanded library introduces new categories; activate them on the first v2 dictionary launch.
        if (!p.getBoolean("dictionary_v2_initialized", false)) {
            activeCategories.addAll(baseEntries.map { it.category }.distinct())
            p.edit().putBoolean("dictionary_v2_initialized", true).apply()
        }
        history.addAll(p.getString("history", "")!!.split("\n").filter { it.isNotBlank() }.take(60))
    }

    private fun buildInterface() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        scroll.addView(page)
        page.addView(text("E C H O S", 28, white, true))
        page.addView(text("DICTIONNAIRE SENSORIEL", 11, accent, true))
        page.addView(text("MESURES PHYSIQUES EN TEMPS RÉEL · HORS LIGNE", 9, muted))
        page.addView(space(18))

        val wordCard = card()
        wordCard.addView(text("DERNIER MOT DÉTECTÉ", 10, accent, true))
        wordView = text("—", 38, white, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(112)
            setPadding(dp(6), dp(16), dp(6), dp(16))
        }
        wordCard.addView(wordView, match())
        wordCard.addView(text("Les mots proviennent des signatures des capteurs. Leur origine n'est pas vérifiable.", 10, muted).apply { gravity = Gravity.CENTER })
        page.addView(wordCard)
        page.addView(space(10))

        val meter = card()
        meter.addView(text("CHAMP MAGNÉTIQUE", 10, accent, true))
        fieldView = text("— µT", 30, white, true)
        meter.addView(fieldView)
        deltaView = text("Variation : —", 12, muted)
        meter.addView(deltaView)
        val meterBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(panel2)
        }
        meter.addView(meterBar, LinearLayout.LayoutParams(-1, dp(7)).apply { topMargin = dp(10) })
        page.addView(meter)
        page.addView(space(10))

        statusView = text("Initialisation des capteurs…", 11, orange, true).apply {
            gravity = Gravity.CENTER
            background = rounded(panel2, 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        page.addView(statusView, match())
        page.addView(space(10))

        val controls = card()
        controls.addView(text("SESSION", 11, accent, true))
        sessionButton = button("DÉMARRER LA SESSION") { toggleSession() }
        controls.addView(sessionButton, match())
        controls.addView(button("CALIBRER LES CAPTEURS") { startCalibration() }, match())
        page.addView(controls)
        page.addView(space(10))

        val settings = card()
        settings.addView(text("SENSIBILITÉ DU DICTIONNAIRE", 11, accent, true))
        sensitivityLabel = text("", 12, white)
        settings.addView(sensitivityLabel)
        settings.addView(SeekBar(this).apply {
            max = 100
            progress = (sensitivity * 100).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    sensitivity = progress / 100f
                    updateSensitivityLabel()
                    savePreferences()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })
        categoriesButton = button("CATÉGORIES ACTIVES") { chooseCategories() }
        settings.addView(categoriesButton, match())
        settings.addView(button("CONSULTER LES 1 000 MOTS") { showDictionary() }, match())
        settings.addView(button("MON DICTIONNAIRE PERSONNEL") { manageCustomDictionary() }, match())
        page.addView(settings)
        page.addView(space(10))

        val sensorsCard = card()
        sensorsCard.addView(text("CAPTEURS DE L'APPAREIL", 11, accent, true))
        sensorLabel = text("", 12, muted)
        sensorsCard.addView(sensorLabel)
        page.addView(sensorsCard)
        page.addView(space(10))

        val historyCard = card()
        historyCard.addView(text("HISTORIQUE DES MOTS", 11, accent, true))
        historyView = text("Aucun mot détecté.", 12, muted)
        historyCard.addView(historyView)
        historyCard.addView(button("EFFACER L'HISTORIQUE") {
            history.clear()
            persistHistory()
            refreshHistory()
        }, match())
        page.addView(historyCard)
        page.addView(space(14))
        page.addView(text("ECHOS est un outil expérimental. Les capteurs mesurent des phénomènes physiques ordinaires ; les mots affichés ne démontrent pas une communication avec l'invisible.", 10, muted))
        setContentView(scroll)
        updateSensitivityLabel()
        refreshHistory()
    }

    private fun updateSensitivityLabel() {
        val percent = (sensitivity * 100).toInt()
        val level = when {
            percent < 25 -> "Faible"
            percent < 55 -> "Modérée"
            percent < 80 -> "Élevée"
            else -> "Très élevée"
        }
        sensitivityLabel.text = "$percent % · $level"
    }

    private fun refreshSensorStatus() {
        if (!::sensorLabel.isInitialized) return
        val hasMag = magnetic != null || magneticUncalibrated != null
        sensorLabel.text = "Magnétomètre : " + if (hasMag) "disponible" else "absent"
        sensorLabel.append("\nAccéléromètre : " + if (accelerometer != null || accelerometerUncalibrated != null) "disponible" else "absent")
        sensorLabel.append("\nGyroscope / rotation : " + if (gyroscope != null || gyroscopeUncalibrated != null || gameRotationVector != null) "disponible" else "absent")
        currentMode = if (hasMag) "MAGNÉTIQUE" else "MOUVEMENT"
        statusView.text = when {
            !hasMag && accelerometer == null && accelerometerUncalibrated == null && gyroscope == null && gyroscopeUncalibrated == null && gameRotationVector == null -> "Aucun capteur compatible détecté"
            calibrated -> "Capteurs prêts · calibration enregistrée"
            else -> "Capteurs prêts · calibration facultative"
        }
        statusView.setTextColor(if (hasMag || accelerometer != null || accelerometerUncalibrated != null || gyroscope != null || gyroscopeUncalibrated != null || gameRotationVector != null) orange else red)
        fieldView.text = if (hasMag) "%.1f µT".format(Locale.FRANCE, magneticMagnitude) else "INDISPONIBLE"
    }

    override fun onResume() {
        super.onResume()
        val magSensor = magnetic ?: magneticUncalibrated
        if (magSensor != null) sensors.registerListener(this, magSensor, SensorManager.SENSOR_DELAY_GAME)
        accelerometer?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accelerometerUncalibrated?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscopeUncalibrated?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gameRotationVector?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        sensors.unregisterListener(this)
        sessionActive = false
        if (::sessionButton.isInitialized) sessionButton.text = "DÉMARRER LA SESSION"
        super.onPause()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> {
                val x = v[0]; val y = v[1]; val z = v[2]
                val preciseMagnitude = sqrt(x.toDouble()*x + y.toDouble()*y + z.toDouble()*z)
                magneticMagnitude = preciseMagnitude.toFloat()
                magneticDelta = abs(preciseMagnitude - baseline.toDouble()).toFloat()
                if (calibrating) collectCalibration(magneticMagnitude)
                else if (sessionActive) processMagneticSignature(x.toDouble(), y.toDouble(), z.toDouble(), preciseMagnitude)
                if (::fieldView.isInitialized) {
                    fieldView.text = "%.1f µT".format(Locale.FRANCE, magneticMagnitude)
                    deltaView.text = "Variation depuis calibration : %.2f µT".format(Locale.FRANCE, magneticDelta)
                }
            }
            Sensor.TYPE_ACCELEROMETER, 35 -> {
                val preciseAcceleration = sqrt(v[0].toDouble()*v[0] + v[1].toDouble()*v[1] + v[2].toDouble()*v[2])
                accelerationMagnitude = preciseAcceleration.toFloat()
                if (calibrating && magnetic == null && magneticUncalibrated == null) collectCalibration(accelerationMagnitude)
                else if (sessionActive) processMotionSignature(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), preciseAcceleration, "accéléromètre")
            }
            Sensor.TYPE_GYROSCOPE, 16, 15 -> {
                val preciseRotation = sqrt(v[0].toDouble()*v[0] + v[1].toDouble()*v[1] + v[2].toDouble()*v[2])
                rotationMagnitude = preciseRotation.toFloat()
                if (calibrating && magnetic == null && magneticUncalibrated == null) collectCalibration(rotationMagnitude)
                else if (sessionActive) processMotionSignature(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), preciseRotation, "gyroscope")
            }
        }
    }

    private fun startCalibration() {
        if (magnetic == null && magneticUncalibrated == null && accelerometer == null &&
            accelerometerUncalibrated == null && gyroscope == null &&
            gyroscopeUncalibrated == null && gameRotationVector == null) {
            toast("Aucun capteur compatible sur cet appareil.")
            return
        }
        calibrationValues.clear()
        calibrating = true
        calibrated = false
        sessionActive = false
        sessionButton.text = "DÉMARRER LA SESSION"
        statusView.text = "CALIBRATION EN COURS · GARDE LE TÉLÉPHONE IMMOBILE"
        statusView.setTextColor(orange)
        addHistory("Calibration démarrée")
    }

    private fun collectCalibration(value: Float) {
        if (!value.isFinite()) return
        calibrationValues.add(value)
        if (calibrationValues.size >= 150) finishCalibration()
        else if (calibrationValues.size % 25 == 0) statusView.text = "Calibration · ${calibrationValues.size}/150 mesures"
    }

    private fun finishCalibration() {
        if (calibrationValues.size < 20) return
        val sorted = calibrationValues.sorted()
        baseline = sorted[sorted.size / 2]
        val deviations = sorted.map { abs(it - baseline) }.sorted()
        noiseFloor = (deviations[deviations.size / 2] * 1.4826f).coerceAtLeast(0.015f)
        calibrated = true
        calibrating = false
        getSharedPreferences("echos_preferences", MODE_PRIVATE).edit()
            .putFloat("baseline", baseline).putFloat("noise_floor", noiseFloor).putBoolean("calibrated", true).apply()
        statusView.text = "CALIBRATION TERMINÉE · ${calibrationValues.size} MESURES"
        statusView.setTextColor(accent)
        addHistory("Calibration terminée · référence %.2f".format(Locale.FRANCE, baseline))
    }

    private fun toggleSession() {
        if (sessionActive) {
            sessionActive = false
            sessionButton.text = "DÉMARRER LA SESSION"
            statusView.text = "Session arrêtée"
            statusView.setTextColor(muted)
            addHistory("Session arrêtée")
            return
        }
        val hasAnySensor = magnetic != null || magneticUncalibrated != null || accelerometer != null ||
            accelerometerUncalibrated != null || gyroscope != null ||
            gyroscopeUncalibrated != null || gameRotationVector != null
        if (!hasAnySensor) {
            toast("Aucun capteur de mouvement ou magnétique disponible sur cet appareil.")
            return
        }
        // La calibration est facultative : les signatures sont calculées depuis les valeurs brutes.
        if (baseEntries.isEmpty()) {
            toast("Le dictionnaire n'a pas pu être chargé.")
            return
        }
        sessionActive = true
        sessionStartedAt = System.currentTimeMillis()
        motionSamples = 0
        motionEnergy = 0.0
        nextWordAt = System.currentTimeMillis() + 1800L
        lastSignatureAt = 0L
        sessionButton.text = "ARRÊTER LA SESSION"
        statusView.text = "SESSION ACTIVE · ANALYSE DES SIGNATURES"
        statusView.setTextColor(accent)
        addHistory("Session démarrée · mode $currentMode")
    }

    // Moteur de signatures inspiré de la mécanique observable de GhostTube.
    // Les deux voies utilisent les décimales des mesures, une fenêtre de sensibilité et un index dictionnaire.
    // Une sortie de secours évite un écran éternellement vide quand aucune signature ne correspond.
    private fun processMagneticSignature(x: Double, y: Double, z: Double, magnitude: Double) {
        val now = System.currentTimeMillis()
        if (now - lastSignatureAt < 900L || now < nextWordAt) return
        lastSignatureAt = now
        val pool = activeWordPool()
        if (pool.isEmpty()) return
        ensureSensorOrder(pool.size)
        val signatureMagnitude = sqrt(x * x * x * x + y * y * y * y + z * z * z * z)
        val digits = decimalDigits(signatureMagnitude, dropLeading = 3, rotate = false)
        val prefixLength = ((pool.size.toLong() * 10_000L).toString().length - 1).coerceIn(1, digits.length)
        val raw = digits.take(prefixLength).toLongOrNull()
        val tolerance = (10 + sensitivity * 90f).toInt()
        val remainder = raw?.rem(10_000L)
        val index = if (raw != null && remainder != null && (remainder == 0L || remainder <= tolerance.toLong())) (raw - remainder) / 10_000L else null
        if (index != null && index > 0L && index < pool.size.toLong()) {
            orderedEntry(pool, index.toInt())?.let { entry ->
                emitWord(entry, "signature magnétique · " + "%.1f".format(Locale.FRANCE, magnitude) + " µT")
                nextWordAt = now + (70f + (1f - sensitivity) * 120f).toLong() * 1000L
            }
        } else emitFallbackIfDue(pool, signatureMagnitude, "secours magnétique", now)
        shuffledWordIndexes.shuffle()
    }

    private var motionSamples = 0
    private var lastMotionValue = 0.0
    private var motionEnergy = 0.0

    private fun processMotionSignature(x: Double, y: Double, z: Double, magnitude: Double, source: String) {
        if (!magnitude.isFinite()) return
        val now = System.currentTimeMillis()
        motionSamples++
        val difference = abs(magnitude - lastMotionValue)
        lastMotionValue = magnitude
        motionEnergy = motionEnergy * 0.88 + difference * 0.12
        if (now - lastSignatureAt < 900L || now < nextWordAt) return
        val pool = activeWordPool()
        if (pool.isEmpty()) return
        ensureSensorOrder(pool.size)
        lastSignatureAt = now
        val digits = decimalDigits(magnitude, dropLeading = 0, rotate = true)
        val gate = digits.take(4).toIntOrNull() ?: -1
        val tolerance = (sensitivity * 30f).toInt() + 1
        val indexEnd = (kotlin.math.floor(kotlin.math.log10(pool.size.toDouble())).toInt() + 5).coerceIn(5, digits.length)
        val index = if (gate >= 0 && (gate <= tolerance || gate >= 10_000 - tolerance) && indexEnd > 5) digits.substring(5, indexEnd).toIntOrNull() else null
        if (index != null && index > 0 && index < pool.size) {
            orderedEntry(pool, index)?.let { entry ->
                emitWord(entry, "signature " + source + " · énergie " + "%.4f".format(Locale.FRANCE, motionEnergy))
                nextWordAt = now + (11f + (1f - sensitivity) * 45f).toLong() * 1000L
            }
        } else emitFallbackIfDue(pool, magnitude + motionEnergy, "secours " + source, now)
        shuffledWordIndexes.shuffle()
    }

    private fun decimalDigits(value: Double, dropLeading: Int, rotate: Boolean): String {
        val decimal = BigDecimal(abs(value).toString()).toPlainString()
        val fraction = decimal.substringAfter(".", "0").filter { it.isDigit() }.ifEmpty { "0" }
        var digits = fraction.drop(dropLeading).ifEmpty { "0" }
        while (digits.length <= 20) digits += digits
        digits = digits.take(20)
        return if (rotate && digits.length >= 20) digits.substring(3) + digits.substring(0, 3) else digits
    }

    private fun ensureSensorOrder(size: Int) {
        if (shuffledWordIndexes.size != size) {
            shuffledWordIndexes = (0 until size).toMutableList()
            shuffledWordIndexes.shuffle()
        }
    }

    private fun orderedEntry(pool: List<WordEntry>, index: Int): WordEntry? {
        if (index !in shuffledWordIndexes.indices) return null
        return pool.getOrNull(shuffledWordIndexes[index])
    }

    private fun emitFallbackIfDue(pool: List<WordEntry>, signal: Double, reason: String, now: Long) {
        val reference = maxOf(sessionStartedAt, lastWordAt)
        if (now - reference < 12_000L || pool.isEmpty()) return
        val raw = abs((signal * 1_000_000.0).toLong()) xor (now / 1_200L)
        val index = Math.floorMod(raw, pool.size.toLong()).toInt()
        emitWord(pool[index], reason + " · aucune signature exploitable")
        nextWordAt = now + 8_000L
    }
    private fun activeWordPool(): List<WordEntry> {
        val base = baseEntries.filter { it.category in activeCategories && it.text.isNotBlank() }
            .flatMap { entry -> List(entry.weight.coerceIn(1, 5)) { entry } }
        val custom = customEntries.filter { it.category in activeCategories && it.text.isNotBlank() }
            .flatMap { entry -> List(7) { entry } }
        return base + custom
    }

    private fun selectWeighted(pool: List<WordEntry>, rawIndex: Int): WordEntry {
        val total = pool.sumOf { it.weight.coerceAtLeast(1) }
        var target = rawIndex.coerceAtLeast(0) % total
        for (entry in pool) {
            target -= entry.weight.coerceAtLeast(1)
            if (target < 0) {
                if (entry.text == lastWord && pool.size > 1) return pool[(pool.indexOf(entry) + 1) % pool.size]
                return entry
            }
        }
        return pool.first()
    }

    private fun emitWord(entry: WordEntry, reason: String) {
        if (entry.text == lastWord && activeWordPool().size > 1) return
        lastWord = entry.text
        lastWordAt = System.currentTimeMillis()
        runOnUiThread {
            wordView.text = entry.text
            addHistory("Mot : ${entry.text} · ${entry.category} · $reason")
            vibrate()
        }
    }

    private fun chooseCategories() {
        val names = (baseEntries.map { it.category }.distinct() + customCategories).distinct().sorted()
        val checks = names.map { name ->
            CheckBox(this).apply {
                text = name
                setTextColor(white)
                isChecked = name in activeCategories
                setPadding(dp(16), dp(7), dp(16), dp(7))
            }
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; checks.forEach { addView(it) } }
        AlertDialog.Builder(this).setTitle("Catégories du dictionnaire")
            .setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton("Appliquer") { _, _ ->
                activeCategories.clear()
                checks.filter { it.isChecked }.forEach { activeCategories.add(it.text.toString()) }
                if (activeCategories.isEmpty()) {
                    activeCategories.addAll(baseEntries.map { it.category }.distinct())
                    toast("Au moins une catégorie doit rester active.")
                }
                savePreferences()
                categoriesButton.text = "${activeCategories.size} catégories actives"
            }.setNegativeButton("Annuler", null).show()
    }

    private fun showDictionary() {
        val dialog = AlertDialog.Builder(this).setTitle("Dictionnaire · ${baseEntries.size + customEntries.size} entrées").create()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setBackgroundColor(bg)
        }
        val search = EditText(this).apply {
            hint = "Rechercher un mot ou une phrase…"
            setSingleLine(true)
            setTextColor(white)
            setHintTextColor(muted)
            background = rounded(panel2, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val spinner = Spinner(this)
        val cats = listOf("Toutes les catégories") + (baseEntries.map { it.category }.distinct() + customCategories).distinct().sorted()
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cats)
        val count = text("", 10, muted)
        val list = TextView(this).apply {
            setTextColor(white)
            textSize = 13f
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val scroller = ScrollView(this).apply { addView(list) }
        fun render() {
            val q = search.text.toString().trim().lowercase(Locale.FRANCE)
            val cat = spinner.selectedItem?.toString() ?: cats[0]
            val filtered = (baseEntries + customEntries).filter {
                (cat == cats[0] || it.category == cat) && (q.isEmpty() || it.text.lowercase(Locale.FRANCE).contains(q))
            }
            count.text = "${filtered.size} entrée(s) affichée(s)"
            list.text = filtered.joinToString("\n") { "• ${it.text}   —   ${it.category}" }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, countOld: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, countNew: Int) { render() }
            override fun afterTextChanged(s: Editable?) {}
        })
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { render() }
        }
        root.addView(search, match())
        root.addView(spinner, match())
        root.addView(count)
        root.addView(scroller, LinearLayout.LayoutParams(-1, dp(390)))
        dialog.setView(root)
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Fermer") { _, _ -> dialog.dismiss() }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accent)
        render()
    }

    private fun manageCustomDictionary() {
        val options = arrayOf("Ajouter une catégorie", "Ajouter un mot ou une phrase", "Consulter / supprimer mes mots")
        AlertDialog.Builder(this).setTitle("Dictionnaire personnel")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptText("Nouvelle catégorie", "Ex. : Famille") { value ->
                        val name = value.trim()
                        if (name.isNotEmpty() && name !in baseEntries.map { it.category } && name !in customCategories) {
                            customCategories.add(name)
                            activeCategories.add(name)
                            saveCustomDictionary()
                            savePreferences()
                            categoriesButton.text = "${activeCategories.size} catégories actives"
                        } else toast("Cette catégorie existe déjà.")
                    }
                    1 -> addCustomWord()
                    2 -> listCustomWords()
                }
            }.show()
    }

    private fun addCustomWord() {
        if (customCategories.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Catégorie nécessaire")
                .setMessage("Crée d'abord une catégorie personnelle.")
                .setPositiveButton("Créer") { _, _ -> manageCustomDictionary() }
                .setNegativeButton("Annuler", null).show()
            return
        }
        val input = EditText(this).apply { hint = "Mot ou phrase"; setTextColor(white) }
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, customCategories.toList().sorted())
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(input)
            addView(spinner)
        }
        AlertDialog.Builder(this).setTitle("Ajouter au dictionnaire personnel").setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                val value = input.text.toString().trim().uppercase(Locale.FRANCE)
                val category = spinner.selectedItem?.toString() ?: return@setPositiveButton
                if (value.isNotBlank() && value.length <= 80 && customEntries.none { it.text.equals(value, true) }) {
                    customEntries.add(WordEntry(category, value, 1, true))
                    activeCategories.add(category)
                    saveCustomDictionary()
                    savePreferences()
                    toast("Entrée ajoutée.")
                } else toast("Entrée vide, trop longue ou déjà présente.")
            }.setNegativeButton("Annuler", null).show()
    }

    private fun listCustomWords() {
        if (customEntries.isEmpty()) {
            toast("Ton dictionnaire personnel est vide.")
            return
        }
        val labels = customEntries.map { "${it.text}  ·  ${it.category}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Mes entrées · toucher pour supprimer")
            .setItems(labels) { _, which ->
                val removed = customEntries.removeAt(which)
                saveCustomDictionary()
                toast("« ${removed.text} » supprimé.")
            }.setNegativeButton("Fermer", null).show()
    }

    private fun promptText(title: String, hintText: String, onSave: (String) -> Unit) {
        val input = EditText(this).apply { hint = hintText; setSingleLine(true); setTextColor(white) }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("Créer") { _, _ -> onSave(input.text.toString()) }
            .setNegativeButton("Annuler", null).show()
    }

    private fun saveCustomDictionary() {
        val arr = JSONArray()
        customEntries.forEach {
            arr.put(JSONObject().put("category", it.category).put("text", it.text))
        }
        getSharedPreferences("echos_preferences", MODE_PRIVATE).edit()
            .putString("custom_entries", arr.toString())
            .putString("custom_categories", customCategories.joinToString("\n"))
            .apply()
    }

    private fun savePreferences() {
        getSharedPreferences("echos_preferences", MODE_PRIVATE).edit()
            .putFloat("sensitivity", sensitivity)
            .putFloat("baseline", baseline)
            .putFloat("noise_floor", noiseFloor)
            .putBoolean("calibrated", calibrated)
            .putString("active_categories", activeCategories.joinToString("\n"))
            .apply()
    }

    private fun addHistory(message: String) {
        val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE).format(Date())
        history.add(0, "$stamp · $message")
        while (history.size > 60) history.removeAt(history.lastIndex)
        persistHistory()
        refreshHistory()
    }

    private fun persistHistory() {
        getSharedPreferences("echos_preferences", MODE_PRIVATE).edit()
            .putString("history", history.joinToString("\n")).apply()
    }

    private fun refreshHistory() {
        if (::historyView.isInitialized) historyView.text =
            if (history.isEmpty()) "Aucun mot détecté." else history.take(12).joinToString("\n\n")
    }

    private fun vibrate() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (v.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                else {
                    @Suppress("DEPRECATION")
                    v.vibrate(100)
                }
            }
        } catch (_: Exception) {}
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(panel, 16)
        elevation = dp(2).toFloat()
    }
    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(3), 0, dp(5))
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 11f; isAllCaps = false; setTextColor(white)
        background = rounded(Color.rgb(29, 65, 72), 11)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(7) }
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun match() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
