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
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
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
    private var linearAccelerometer: Sensor? = null
    private var proximity: Sensor? = null
    private var accelerometerThread: HandlerThread? = null
    private var gyroscopeThread: HandlerThread? = null
    private var magneticThread: HandlerThread? = null
    private var proximityThread: HandlerThread? = null
    private val gravityVector = FloatArray(3)
    private var gravityInitialized = false
    private var magneticReference = Double.NaN
    private var lastProximityNear: Boolean? = null
    private val registeredSensorTypes = mutableSetOf<Int>()
    private var speechEngine: TextToSpeech? = null
    private var speechReady = false
    private var dynamicVoicesEnabled = true
    private var dynamicVoices: List<Voice> = emptyList()
    private val motionSamples = mutableMapOf<String, MutableList<Double>>()
    private val motionStableSince = mutableMapOf<String, Long>()

    private val baseEntries = mutableListOf<WordEntry>()
    private val customEntries = mutableListOf<WordEntry>()
    private val customCategories = mutableSetOf<String>()
    private val activeCategories = mutableSetOf<String>()
    private val history = mutableListOf<String>()
    private val calibrationValues = mutableListOf<Float>()

    private var sensitivity = 0.5f
    private var sessionActive = false
    private var calibrating = false
    private var calibrated = false
    private var baseline = 0f
    private var noiseFloor = 0.08f
    private var magneticMagnitude = 0f
    private var magneticDelta = 0f
    private var lastMagneticSignature = ""
    private var accelerationMagnitude = 0f
    private var rotationMagnitude = 0f
    private var nextWordAt = 0L
    private var shuffledWordIndexes = mutableListOf<Int>()
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
        accelerometerUncalibrated = if (accelerometer == null) sensors.getDefaultSensor(35) else null
        gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        gyroscopeUncalibrated = if (gyroscope == null) sensors.getDefaultSensor(16) else null
        linearAccelerometer = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        proximity = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        loadDictionary()
        loadPreferences()
        initializeSpeech()
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
        // GhostTube 6.2.5 initialise son coefficient interne B à 0,5.
        // Migration unique : corrige l'ancien défaut ECHOS trop élevé.
        if (!p.getBoolean("ghosttube_sensor_v1", false)) {
            sensitivity = 0.5f
            p.edit().putFloat("sensitivity", 0.5f).putBoolean("ghosttube_sensor_v1", true).apply()
        } else {
            sensitivity = p.getFloat("sensitivity", 0.5f).coerceIn(0f, 1f)
        }
        baseline = p.getFloat("baseline", 0f)
        noiseFloor = p.getFloat("noise_floor", 0.08f)
        calibrated = p.getBoolean("calibrated", false)
        dynamicVoicesEnabled = p.getBoolean("dynamic_voices_enabled", true)
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
        // Active une seule fois la nouvelle catégorie de prénoms pour les installations existantes.
        if (!p.getBoolean("first_names_v1_initialized", false)) {
            if (baseEntries.any { it.category == "Prénoms" }) activeCategories.add("Prénoms")
            p.edit().putBoolean("first_names_v1_initialized", true).apply()
        }
        // Migration : rend la nouvelle catégorie Âges disponible immédiatement
        // sans modifier les catégories que l'utilisateur a déjà désactivées.
        if (!p.getBoolean("ages_category_v1_initialized", false)) {
            if (baseEntries.any { it.category == "Âges" }) activeCategories.add("Âges")
            p.edit().putBoolean("ages_category_v1_initialized", true).apply()
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
        settings.addView(button("CONSULTER LE DICTIONNAIRE") { showDictionary() }, match())
        settings.addView(button("MON DICTIONNAIRE PERSONNEL") { manageCustomDictionary() }, match())
        settings.addView(Switch(this).apply {
            text = "VOIX DYNAMIQUES (empreintes des capteurs)"
            setTextColor(white)
            textSize = 13f
            isChecked = dynamicVoicesEnabled
            setOnCheckedChangeListener { _, enabled ->
                dynamicVoicesEnabled = enabled
                getSharedPreferences("echos_preferences", MODE_PRIVATE).edit().putBoolean("dynamic_voices_enabled", enabled).apply()
                if (!enabled) speechEngine?.stop()
                toast(if (enabled) "Voix dynamiques activées" else "Voix désactivées")
            }
        }.apply { setPadding(0, dp(12), 0, dp(8)) }, match())
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

    private fun initializeSpeech() {
        speechEngine = TextToSpeech(this) { status ->
            speechReady = status == TextToSpeech.SUCCESS
            if (speechReady) {
                val engine = speechEngine ?: return@TextToSpeech
                val french = if (Locale.getDefault().language == "fr") Locale.getDefault() else Locale.FRANCE
                if (engine.isLanguageAvailable(french) >= TextToSpeech.LANG_AVAILABLE) engine.language = french
                val voices = engine.voices ?: emptySet()
                dynamicVoices = voices.filter { it.locale.language == french.language && !it.isNetworkConnectionRequired }.sortedBy { it.name }
                    .ifEmpty { voices.filter { !it.isNetworkConnectionRequired }.sortedBy { it.name } }
            }
        }
    }

    private fun speakWithSensorVoice(text: String, fingerprint: String) {
        if (!dynamicVoicesEnabled || !speechReady || fingerprint.length < 8) return
        val engine = speechEngine ?: return
        try {
            val voiceDigit = fingerprint[5].digitToIntOrNull() ?: 0
            val pitchDigit = fingerprint[6].digitToIntOrNull() ?: 5
            val rateDigit = fingerprint[7].digitToIntOrNull() ?: 5
            if (dynamicVoices.isNotEmpty()) engine.voice = dynamicVoices[voiceDigit % dynamicVoices.size]
            engine.setPitch(0.82f + (pitchDigit / 9f) * 0.38f)
            engine.setSpeechRate(0.82f + (rateDigit / 9f) * 0.34f)
            val params = Bundle().apply { putFloat("volume", 0.85f) }
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "echos-${System.nanoTime()}")
        } catch (_: Exception) { }
    }

    private fun refreshSensorStatus() {
        if (!::sensorLabel.isInitialized) return
        val hasMag = magnetic != null || magneticUncalibrated != null
        sensorLabel.text = "Magnétomètre : " + if (hasMag) "disponible" else "absent"
        sensorLabel.append("\nAccéléromètre : " + if (accelerometer != null || accelerometerUncalibrated != null) "disponible" else "absent")
        sensorLabel.append("\nGyroscope : " + if (gyroscope != null || gyroscopeUncalibrated != null) "disponible" else "absent")
        sensorLabel.append("\nAccéléromètre linéaire : " + if (linearAccelerometer != null) "disponible" else "absent")
        sensorLabel.append("\nProximité : " + if (proximity != null) "disponible" else "absent")
        currentMode = if (hasMag) "MULTICAPTEURS" else "MOUVEMENT / PROXIMITÉ"
        val hasMotionOrProximity = accelerometer != null || accelerometerUncalibrated != null ||
            linearAccelerometer != null || gyroscope != null || gyroscopeUncalibrated != null || proximity != null
        statusView.text = when {
            !hasMag && !hasMotionOrProximity -> "Aucun capteur compatible détecté"
            calibrated -> "Capteurs prêts · calibration enregistrée"
            else -> "Capteurs prêts · calibration facultative"
        }
        statusView.setTextColor(if (hasMag || hasMotionOrProximity) orange else red)
        fieldView.text = if (hasMag) "%.1f µT".format(Locale.FRANCE, magneticMagnitude) else "INDISPONIBLE"
    }

    // GhostTube utilise un HandlerThread distinct par capteur et un délai de 2.
    // Les capteurs ne sont abonnés que pendant l'écoute.
    private fun startGhostTubeSensorListeners(): Int {
        stopGhostTubeSensorListeners()
        val magSensor = magnetic ?: magneticUncalibrated
        if (magSensor != null) {
            magneticThread = HandlerThread("MagnetometerSensorThread").apply { start() }
            if (sensors.registerListener(this, magSensor, 2, Handler(magneticThread!!.looper))) {
                registeredSensorTypes.add(magSensor.type)
            } else {
                magneticThread?.quitSafely()
                magneticThread = null
            }
        }
        val accelSensor = accelerometer ?: accelerometerUncalibrated
        if (accelSensor != null || linearAccelerometer != null) {
            accelerometerThread = HandlerThread("AccelerometerThread").apply { start() }
            val handler = Handler(accelerometerThread!!.looper)
            if (accelSensor != null && sensors.registerListener(this, accelSensor, 2, handler)) {
                registeredSensorTypes.add(accelSensor.type)
            }
            if (linearAccelerometer != null && sensors.registerListener(this, linearAccelerometer, 2, handler)) {
                registeredSensorTypes.add(linearAccelerometer!!.type)
            }
            if (accelSensor == null && linearAccelerometer == null ||
                accelSensor != null && accelSensor.type !in registeredSensorTypes ||
                linearAccelerometer != null && linearAccelerometer!!.type !in registeredSensorTypes) {
                if (registeredSensorTypes.none { it == Sensor.TYPE_ACCELEROMETER || it == 35 || it == Sensor.TYPE_LINEAR_ACCELERATION }) {
                    accelerometerThread?.quitSafely()
                    accelerometerThread = null
                }
            }
        }
        val gyroSensor = gyroscope ?: gyroscopeUncalibrated
        if (gyroSensor != null) {
            gyroscopeThread = HandlerThread("GyroscopeThread").apply { start() }
            if (sensors.registerListener(this, gyroSensor, 2, Handler(gyroscopeThread!!.looper))) {
                registeredSensorTypes.add(gyroSensor.type)
            } else {
                gyroscopeThread?.quitSafely()
                gyroscopeThread = null
            }
        }
        if (proximity != null) {
            proximityThread = HandlerThread("ProximitySensorThread").apply { start() }
            if (sensors.registerListener(this, proximity, 2, Handler(proximityThread!!.looper))) {
                registeredSensorTypes.add(proximity!!.type)
            } else {
                proximityThread?.quitSafely()
                proximityThread = null
            }
        }
        return registeredSensorTypes.size
    }

    private fun stopGhostTubeSensorListeners() {
        sensors.unregisterListener(this)
        registeredSensorTypes.clear()
        magneticThread?.quitSafely()
        accelerometerThread?.quitSafely()
        gyroscopeThread?.quitSafely()
        proximityThread?.quitSafely()
        magneticThread = null
        accelerometerThread = null
        gyroscopeThread = null
        proximityThread = null
    }

    override fun onResume() {
        super.onResume()
        if (sessionActive || calibrating) startGhostTubeSensorListeners()
    }

    override fun onPause() {
        stopGhostTubeSensorListeners()
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
                magneticDelta = if (baseline > 0f) abs(preciseMagnitude - baseline.toDouble()).toFloat() else 0f
                if (calibrating) collectCalibration(magneticMagnitude)
                else if (sessionActive) processMagneticSignature(x.toDouble(), y.toDouble(), z.toDouble(), preciseMagnitude)
                if (::fieldView.isInitialized) {
                    fieldView.text = "%.1f µT".format(Locale.FRANCE, magneticMagnitude)
                    deltaView.text = "Variation depuis calibration : %.2f µT".format(Locale.FRANCE, magneticDelta)
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val norm = sqrt(v[0].toDouble()*v[0] + v[1].toDouble()*v[1] + v[2].toDouble()*v[2])
                accelerationMagnitude = norm.toFloat()
                if (calibrating && magnetic == null && magneticUncalibrated == null) collectCalibration(accelerationMagnitude)
                else if (sessionActive) processMotionSignature(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), "accéléromètre linéaire")
            }
            Sensor.TYPE_ACCELEROMETER, 35 -> {
                val preciseAcceleration = sqrt(v[0].toDouble()*v[0] + v[1].toDouble()*v[1] + v[2].toDouble()*v[2])
                accelerationMagnitude = preciseAcceleration.toFloat()
                // À défaut de capteur linéaire, on retire la gravité par filtre passe-haut.
                // Cela évite qu'un téléphone simplement posé déclenche un mot à partir des 9,8 m/s².
                val alpha = 0.88f
                if (!gravityInitialized) {
                    for (i in 0..2) gravityVector[i] = v[i]
                    gravityInitialized = true
                }
                val linear = DoubleArray(3) { i ->
                    gravityVector[i] += alpha * (v[i] - gravityVector[i])
                    (v[i] - gravityVector[i]).toDouble()
                }
                if (linearAccelerometer == null) {
                    val linearNorm = sqrt(linear[0]*linear[0] + linear[1]*linear[1] + linear[2]*linear[2])
                    if (calibrating && magnetic == null && magneticUncalibrated == null) collectCalibration(linearNorm.toFloat())
                    else if (sessionActive) processMotionSignature(linear[0], linear[1], linear[2], "accéléromètre")
                }
            }
            Sensor.TYPE_GYROSCOPE, 15, 16 -> {
                val preciseRotation = sqrt(v[0].toDouble()*v[0] + v[1].toDouble()*v[1] + v[2].toDouble()*v[2])
                rotationMagnitude = preciseRotation.toFloat()
                if (calibrating && magnetic == null && magneticUncalibrated == null) collectCalibration(rotationMagnitude)
                else if (sessionActive) processMotionSignature(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), "gyroscope")
            }
            Sensor.TYPE_PROXIMITY -> {
                if (!sessionActive || v.isEmpty()) return
                val maxRange = event.sensor.maximumRange.toDouble()
                val isNear = v[0].toDouble() < maxRange
                val previous = lastProximityNear
                lastProximityNear = isNear
                if (previous != null && previous != isNear) processProximitySignature(v[0].toDouble(), maxRange, isNear)
            }
        }
    }

    private fun startCalibration() {
        if (magnetic == null && magneticUncalibrated == null && accelerometer == null &&
            accelerometerUncalibrated == null && linearAccelerometer == null && gyroscope == null &&
            gyroscopeUncalibrated == null) {
            toast("Aucun capteur ne permet une calibration sur cet appareil.")
            return
        }
        calibrationValues.clear()
        calibrating = true
        calibrated = false
        sessionActive = false
        stopGhostTubeSensorListeners()
        if (startGhostTubeSensorListeners() == 0) {
            calibrating = false
            toast("Android n'a pas pu activer les capteurs détectés.")
            statusView.text = "ÉCHEC D'ACTIVATION DES CAPTEURS"
            statusView.setTextColor(red)
            return
        }
        sessionButton.text = "DÉMARRER LA SESSION"
        statusView.text = "CALIBRATION EN COURS · GARDE LE TÉLÉPHONE IMMOBILE"
        statusView.setTextColor(orange)
        addHistory("Calibration démarrée")
    }

    // Les callbacks des capteurs peuvent arriver depuis plusieurs HandlerThreads.
    // L'accès à la liste est synchronisé et toute mise à jour UI passe par le thread principal.
    @Synchronized
    private fun collectCalibration(value: Float) {
        if (!calibrating || !value.isFinite()) return
        calibrationValues.add(value)
        val count = calibrationValues.size
        if (count >= 150) {
            // Verrouille immédiatement la collecte pour éviter plusieurs fins simultanées.
            calibrating = false
            runOnUiThread { finishCalibration() }
        } else if (count % 25 == 0) {
            runOnUiThread {
                if (calibrating) statusView.text = "Calibration · $count/150 mesures"
            }
        }
    }

    // Appelée exclusivement sur le thread UI.
    private fun finishCalibration() {
        if (calibrationValues.size < 150 || calibrated) return
        val sorted = calibrationValues.sorted()
        baseline = sorted[sorted.size / 2]
        val deviations = sorted.map { abs(it - baseline) }.sorted()
        noiseFloor = (deviations[deviations.size / 2] * 1.4826f).coerceAtLeast(0.015f)
        calibrated = true
        stopGhostTubeSensorListeners()
        getSharedPreferences("echos_preferences", MODE_PRIVATE).edit()
            .putFloat("baseline", baseline).putFloat("noise_floor", noiseFloor).putBoolean("calibrated", true).apply()
        statusView.text = "CALIBRATION TERMINÉE · ${calibrationValues.size} MESURES"
        statusView.setTextColor(accent)
        addHistory("Calibration terminée · référence %.2f".format(Locale.FRANCE, baseline))
    }

    private fun toggleSession() {
        if (sessionActive) {
            sessionActive = false
            stopGhostTubeSensorListeners()
            sessionButton.text = "DÉMARRER LA SESSION"
            statusView.text = "Session arrêtée"
            statusView.setTextColor(muted)
            addHistory("Session arrêtée")
            return
        }
        val hasAnySensor = magnetic != null || magneticUncalibrated != null || accelerometer != null ||
            accelerometerUncalibrated != null || linearAccelerometer != null || gyroscope != null ||
            gyroscopeUncalibrated != null || proximity != null
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
        nextWordAt = 0L
        magneticReference = if (calibrated && baseline > 0f && (magnetic != null || magneticUncalibrated != null)) baseline.toDouble() else Double.NaN
        lastProximityNear = null
        gravityInitialized = false
        motionSamples.clear()
        motionStableSince.clear()
        shuffledWordIndexes = (0 until activeWordPool().size).toMutableList()
        shuffledWordIndexes.shuffle()
        if (startGhostTubeSensorListeners() == 0) {
            sessionActive = false
            toast("Android n'a pas pu activer les capteurs détectés.")
            statusView.text = "ÉCHEC D'ACTIVATION DES CAPTEURS"
            statusView.setTextColor(red)
            return
        }
        sessionButton.text = "ARRÊTER LA SESSION"
        statusView.text = "SESSION ACTIVE · ANALYSE DES SIGNATURES"
        statusView.setTextColor(accent)
        addHistory("Session démarrée · mode $currentMode")
    }

    // Reproduction indépendante des deux voies de sélection observées dans GhostTube 6.2.5.
    // Aucun mot n'est généré si la signature ne satisfait pas les seuils du dictionnaire.
    private fun sensorFraction(value: Double): String {
        if (!value.isFinite()) return ""
        val decimal = BigDecimal(abs(value).toString()).toPlainString()
        return decimal.substringAfter(".", "")
    }

    private fun repeatDigitsTo20(source: String, inclusive: Boolean): String {
        if (source.isEmpty()) return ""
        var digits = source
        while (if (inclusive) digits.length <= 20 else digits.length < 20) digits += digits
        return digits.take(20)
    }

    @Synchronized
    private fun processMagneticSignature(x: Double, y: Double, z: Double, magnitude: Double) {
        val now = System.currentTimeMillis()
        if (!magnitude.isFinite() || magnitude <= 0.0 || now < nextWordAt) return

        // Référence initiale : le premier relevé ne déclenche jamais de mot.
        // Une variation réelle du champ est nécessaire, contrairement à l'ancienne
        // version qui émettait un mot à chaque fin de délai même avec un champ immobile.
        if (!magneticReference.isFinite()) {
            magneticReference = magnitude
            return
        }
        val deviation = abs(magnitude - magneticReference)
        val calibratedNoise = if (calibrated) noiseFloor.toDouble() else 0.0
        val threshold = maxOf(
            0.30,
            calibratedNoise * (3.5 - sensitivity * 2.7),
            0.35 + (1f - sensitivity) * 1.45f
        )
        if (deviation < threshold) {
            // Adaptation lente du niveau de référence pour suivre les dérives ambiantes.
            magneticReference = magneticReference * 0.998 + magnitude * 0.002
            return
        }

        val pool = activeWordPool()
        if (pool.isEmpty()) return
        ensureSensorOrder(pool.size)
        val rawFingerprint = listOf(x, y, z, magnitude, deviation).joinToString("|") {
            BigDecimal(it).stripTrailingZeros().toPlainString()
        }
        val fingerprint = rawFingerprint.filter { it.isDigit() }.take(20).padEnd(20, '0')
        if (fingerprint == lastMagneticSignature) {
            magneticReference = magnitude
            return
        }

        val cooldownSeconds = 8 + ((1f - sensitivity) * 32f).toInt()
        val timeSlot = now / (cooldownSeconds * 1_000L)
        val seed = (fingerprint + timeSlot.toString()).hashCode()
        val entry = selectBalancedEntry(pool, seed) ?: return

        lastMagneticSignature = fingerprint
        magneticReference = magnitude
        emitWord(entry, "variation magnétique · Δ %.2f µT".format(Locale.FRANCE, deviation), fingerprint)
        nextWordAt = now + cooldownSeconds * 1_000L
    }

    @Synchronized
    private fun processMotionSignature(x: Double, y: Double, z: Double, source: String) {
        val now = System.currentTimeMillis()
        if (now < nextWordAt) return
        val norm = sqrt(x * x + y * y + z * z)
        // Un gyroscope immobile et un accéléromètre dont la gravité a été retirée
        // ne doivent pas produire de signature artificielle composée de zéros.
        val minimumSignal = if (source == "gyroscope") 0.018 else 0.045
        if (!norm.isFinite() || norm < minimumSignal) {
            motionStableSince[source] = 0L
            return
        }
        if (!isMotionStable(source, norm, now)) return
        val pool = activeWordPool()
        if (pool.isEmpty()) return
        ensureSensorOrder(pool.size)

        // Signature numérique de la mesure stabilisée. La voie mouvement reste active
        // même lorsqu'un magnétomètre est présent : chaque capteur a son propre canal.
        val fraction = sensorFraction(norm)
        if (fraction.isEmpty()) return
        val first20 = repeatDigitsTo20(fraction, inclusive = false)
        if (first20.length < 20) return
        val signature = first20.substring(3, 20) + first20.substring(0, 3)
        val gate = signature.substring(0, 4).toIntOrNull() ?: return
        val tolerance = (sensitivity * 250f).toInt() + 1

        if (gate <= tolerance || gate >= 10_000 - tolerance) {
            val end = (kotlin.math.log10(pool.size.toDouble()).toInt() + 5).coerceAtMost(signature.length)
            if (end <= 5) return
            val index = signature.substring(5, end).toIntOrNull() ?: return
            val entry = selectBalancedEntry(pool, signature.hashCode() xor index) ?: return
            emitWord(entry, "signature $source · %.4f".format(Locale.FRANCE, norm), signature)
            val cooldownSeconds = 8 + ((1f - sensitivity) * 32f).toInt()
            nextWordAt = now + cooldownSeconds * 1_000L
            ensureSensorOrder(pool.size)
            shuffledWordIndexes.shuffle()
        }
    }

    @Synchronized
    private fun processProximitySignature(distance: Double, maxRange: Double, isNear: Boolean) {
        val now = System.currentTimeMillis()
        if (now < nextWordAt || maxRange <= 0.0) return
        val pool = activeWordPool()
        if (pool.isEmpty()) return
        val fingerprint = (distance.toString() + "|" + maxRange.toString() + "|" + if (isNear) "1" else "0")
            .filter { it.isDigit() }.take(20).padEnd(20, '0')
        val seed = (fingerprint + now / 10_000L).hashCode()
        val entry = selectBalancedEntry(pool, seed) ?: return
        emitWord(entry, if (isNear) "proximité · objet proche" else "proximité · objet éloigné", fingerprint)
        nextWordAt = now + (8 + ((1f - sensitivity) * 32f).toInt()) * 1_000L
    }

    private fun isMotionStable(source: String, magnitude: Double, now: Long): Boolean {
        val samples = motionSamples.getOrPut(source) { mutableListOf() }
        samples.add(magnitude)
        if (samples.size > 12) samples.removeAt(0)
        if (samples.size < 12) return false
        val spread = (samples.maxOrNull() ?: magnitude) - (samples.minOrNull() ?: magnitude)
        val threshold = if (source.contains("accéléromètre")) 0.035 else 0.006
        if (spread > threshold) { motionStableSince[source] = 0L; return false }
        val since = motionStableSince[source] ?: 0L
        if (since == 0L) { motionStableSince[source] = now; return false }
        return now - since >= 1_500L
    }

    private fun ensureSensorOrder(size: Int) {
        if (shuffledWordIndexes.size != size) shuffledWordIndexes = (0 until size).toMutableList()
    }

    private fun orderedEntry(pool: List<WordEntry>, index: Int): WordEntry? {
        ensureSensorOrder(pool.size)
        return shuffledWordIndexes.getOrNull(index)?.let { pool.getOrNull(it) }
    }

    // Équilibre le tirage par catégorie : une catégorie très volumineuse
    // (ex. les 465 prénoms) ne doit pas écraser les autres catégories.
    // La catégorie ET le mot restent déterminés par l'empreinte du capteur.
    private fun selectBalancedEntry(pool: List<WordEntry>, sensorSeed: Int): WordEntry? {
        if (pool.isEmpty()) return null
        val groups = pool.groupBy { it.category }.toSortedMap().values.toList()
        if (groups.isEmpty()) return null
        val categoryIndex = Math.floorMod(sensorSeed, groups.size)
        val selectedCategory = groups[categoryIndex]
        val mixedSeed = sensorSeed * 31 + (sensorSeed ushr 16) + 0x9E3779B9.toInt()
        val wordIndex = Math.floorMod(mixedSeed, selectedCategory.size)
        return selectedCategory.getOrNull(wordIndex)
    }

    private fun activeWordPool(): List<WordEntry> {
        // Une préférence de catégorie devenue obsolète ne doit jamais vider le dictionnaire.
        val base = baseEntries.filter { it.category in activeCategories && it.text.isNotBlank() }
        val custom = customEntries.filter { it.category in activeCategories && it.text.isNotBlank() }
        val selected = base + custom
        return if (selected.isNotEmpty()) selected
        else (baseEntries + customEntries).filter { it.text.isNotBlank() }
    }

    private fun emitWord(entry: WordEntry, reason: String, fingerprint: String) {
        runOnUiThread {
            wordView.text = entry.text
            addHistory("Mot : ${entry.text} · ${entry.category} · $reason")
            vibrate()
            speakWithSensorVoice(entry.text, fingerprint)
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

    override fun onDestroy() {
        speechEngine?.stop()
        speechEngine?.shutdown()
        speechEngine = null
        speechReady = false
        super.onDestroy()
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
