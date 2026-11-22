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
import android.view.ViewGroup
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
    private val bg = Color.rgb(5, 10, 22)
    private val panel = Color.rgb(13, 24, 43)
    private val panel2 = Color.rgb(20, 39, 67)
    private val accent = Color.rgb(53, 160, 255)
    private val white = Color.rgb(239, 246, 255)
    private val muted = Color.rgb(139, 162, 190)
    private val orange = Color.rgb(255, 190, 90)
    private val red = Color.rgb(255, 105, 125)

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
    private var magneticReferenceVector: DoubleArray? = null
    private val motionTriggerActive = mutableMapOf<String, Boolean>()
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
    private lateinit var recentWordsView: TextView
    private lateinit var customWordsContainer: LinearLayout

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
        history.addAll(p.getString("history", "")!!.split("\n").filter { it.isNotBlank() })
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            fitsSystemWindows = true
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setBackgroundColor(Color.rgb(3, 7, 17))
        }
        val brandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brand = text("ECHOS", 25, white, true).apply { letterSpacing = 0.16f }
        brandRow.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        brandRow.addView(text("● CAPTEURS ACTIFS", 10, accent, true))
        header.addView(brandRow)
        header.addView(text("DÉTECTION ENVIRONNEMENTALE", 10, muted, true))
        root.addView(header)

        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        fun makePage(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(22))
            setBackgroundColor(bg)
        }
        fun heading(title: String, subtitle: String): View {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(16)) }
            box.addView(text(title, 21, white, true))
            box.addView(text(subtitle, 11, muted))
            return box
        }
        fun scrollPage(page: LinearLayout): ScrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        }
        val pages = mutableListOf<View>()

        val home = makePage()
        home.addView(heading("Accueil", "Ta session ECHOS en un coup d'œil"))
        val startCard = card().apply {
            background = rounded(Color.rgb(10, 31, 58), 20)
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        startCard.addView(text("SESSION EN COURS", 10, accent, true))
        sessionButton = button("▶   DÉMARRER LA SESSION") { toggleSession() }.apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(accent, 14)
            setTextColor(Color.rgb(3, 12, 27))
            layoutParams = LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(14) }
        }
        startCard.addView(sessionButton)
        home.addView(startCard)
        home.addView(space(14))

        val sensCard = card()
        val sensRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sensRow.addView(text("SENSIBILITÉ", 11, accent, true), LinearLayout.LayoutParams(0, -2, 1f))
        sensitivityLabel = text("", 13, white, true)
        sensRow.addView(sensitivityLabel)
        sensCard.addView(sensRow)
        sensCard.addView(SeekBar(this).apply {
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
        home.addView(sensCard)
        home.addView(space(14))
        home.addView(text("MOTS RÉCENTS", 11, accent, true))
        recentWordsView = text("Aucun mot détecté pour le moment.", 13, muted)
        recentWordsView.setLineSpacing(dp(5).toFloat(), 1f)
        val recentCard = card()
        recentCard.addView(recentWordsView)
        home.addView(recentCard)
        home.addView(space(12))
        val sensorCard = card()
        sensorCard.addView(text("CAPTEURS", 10, accent, true))
        statusView = text("En attente", 12, white, true)
        sensorCard.addView(statusView)
        fieldView = text("Champ magnétique : —", 11, muted)
        sensorCard.addView(fieldView)
        deltaView = text("Variation : —", 11, muted)
        sensorCard.addView(deltaView)
        sensorLabel = text("", 10, muted)
        sensorCard.addView(sensorLabel)
        home.addView(sensorCard)
        pages.add(scrollPage(home))

        val historyPage = makePage()
        historyPage.addView(heading("Historique", "Tous les mots prononcés, du plus récent au plus ancien"))
        historyView = text("Aucun mot détecté.", 13, white)
        historyView.setLineSpacing(dp(6).toFloat(), 1f)
        val historyCard = card()
        historyCard.addView(historyView)
        historyPage.addView(historyCard)
        historyPage.addView(button("EFFACER L'HISTORIQUE") {
            AlertDialog.Builder(this).setTitle("Effacer l'historique ?")
                .setMessage("Tous les mots enregistrés seront supprimés.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Effacer") { _, _ -> history.clear(); persistHistory(); refreshHistory() }
                .show()
        }, match())
        pages.add(scrollPage(historyPage))

        val customPage = makePage()
        val customHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val customTitles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        customTitles.addView(text("Personnalisé", 21, white, true))
        customTitles.addView(text("Tes mots et phrases personnelles", 11, muted))
        customHeader.addView(customTitles, LinearLayout.LayoutParams(0, -2, 1f))
        val addButton = TextView(this).apply {
            text = "+"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(3, 12, 27))
            background = rounded(accent, 14)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { addCustomWord() }
        }
        customHeader.addView(addButton)
        customPage.addView(customHeader)
        customPage.addView(space(14))
        val customIntro = card()
        customIntro.addView(text("MON DICTIONNAIRE PERSONNEL", 10, accent, true))
        customIntro.addView(text("Ajoute tes propres mots et phrases. Ils sont sauvegardés localement sur cet appareil.", 11, muted))
        customPage.addView(customIntro)
        customPage.addView(space(10))
        customWordsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        customPage.addView(customWordsContainer)
        pages.add(scrollPage(customPage))

        val settingsPage = makePage()
        settingsPage.addView(heading("Paramètres", "Contrôle complet du dictionnaire et de l'application"))
        val dictionaryCard = card()
        dictionaryCard.addView(text("DICTIONNAIRE COMPLET", 10, accent, true))
        dictionaryCard.addView(text("${baseEntries.size + customEntries.size} mots et expressions", 20, white, true))
        dictionaryCard.addView(button("OUVRIR TOUT LE DICTIONNAIRE") { showDictionary() }, match())
        dictionaryCard.addView(button("GÉRER LES CATÉGORIES ACTIVES") { chooseCategories() }, match())
        categoriesButton = button("${activeCategories.size} catégories actives") { chooseCategories() }
        dictionaryCard.addView(categoriesButton, match())
        settingsPage.addView(dictionaryCard)
        settingsPage.addView(space(12))
        val sensorsCard = card()
        sensorsCard.addView(text("CAPTEURS ET CALIBRATION", 10, accent, true))
        sensorsCard.addView(button("CALIBRER LE CAPTEUR MAGNÉTIQUE") { startCalibration() }, match())
        sensorsCard.addView(text("La sensibilité se règle directement depuis l'accueil.", 11, muted))
        settingsPage.addView(sensorsCard)
        settingsPage.addView(space(12))
        val voiceCard = card()
        voiceCard.addView(text("VOIX", 10, accent, true))
        voiceCard.addView(Switch(this).apply {
            text = "Voix dynamiques"
            setTextColor(white)
            isChecked = dynamicVoicesEnabled
            setOnCheckedChangeListener { _, enabled ->
                dynamicVoicesEnabled = enabled
                getSharedPreferences("echos_preferences", MODE_PRIVATE).edit().putBoolean("dynamic_voices_enabled", enabled).apply()
                if (!enabled) speechEngine?.stop()
            }
        }, match())
        settingsPage.addView(voiceCard)
        settingsPage.addView(space(12))
        val aboutCard = card()
        aboutCard.addView(text("À PROPOS", 10, accent, true))
        aboutCard.addView(text("ECHOS associe les lectures des capteurs de ton téléphone à des signatures du dictionnaire. Les variations peuvent avoir des causes environnementales ordinaires et ne constituent pas une preuve de phénomène paranormal.", 11, muted))
        settingsPage.addView(aboutCard)
        pages.add(scrollPage(settingsPage))

        pages.forEachIndexed { index, page ->
            content.addView(page, FrameLayout.LayoutParams(-1, -1))
            page.visibility = if (index == 0) View.VISIBLE else View.GONE
        }
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(5), dp(6), dp(5), dp(8))
            setBackgroundColor(Color.rgb(3, 7, 17))
        }
        val labels = listOf("⌂\nACCUEIL", "◷\nHISTORIQUE", "+\nPERSONNALISÉ", "⚙\nPARAMÈTRES")
        labels.forEachIndexed { index, label ->
            val item = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 9f
                setLineSpacing(dp(2).toFloat(), 1f)
                setTextColor(if (index == 0) accent else muted)
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener {
                    pages.forEachIndexed { i, page -> page.visibility = if (i == index) View.VISIBLE else View.GONE }
                    for (child in 0 until nav.childCount) (nav.getChildAt(child) as? TextView)?.setTextColor(if (child == index) accent else muted)
                    if (index == 1) refreshHistory()
                    if (index == 2) refreshCustomWords()
                }
            }
            nav.addView(item, LinearLayout.LayoutParams(0, dp(58), 1f))
        }
        root.addView(nav)
        setContentView(root)
        updateSensitivityLabel()
        refreshHistory()
        refreshCustomWords()
        refreshSensorStatus()
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
                else if (sessionActive && norm >= 0.025) processMotionSignature(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), "accéléromètre linéaire")
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
                val linearNorm = sqrt(linear[0]*linear[0] + linear[1]*linear[1] + linear[2]*linear[2])
                if (calibrating && magnetic == null && magneticUncalibrated == null) collectCalibration(linearNorm.toFloat())
                else if (sessionActive) processMotionSignature(linear[0], linear[1], linear[2], "accéléromètre")
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
        magneticReference = Double.NaN
        magneticReferenceVector = null
        motionTriggerActive.clear()
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

    private fun signatureDigits(vararg values: Double): String {
        val raw = values.joinToString("") { value ->
            if (!value.isFinite()) "" else {
                val decimal = BigDecimal(abs(value)).stripTrailingZeros().toPlainString()
                decimal.substringAfter(".", "").ifEmpty { "0" }
            }
        }.filter { it.isDigit() }
        return repeatDigitsTo20(raw, inclusive = true)
    }

    // Associe directement l'empreinte numérique à une entrée stable du dictionnaire.
    // L'entrelacement des catégories évite qu'une catégorie volumineuse monopolise les sorties.
    private fun selectSignatureEntry(pool: List<WordEntry>, signature: String): WordEntry? {
        if (pool.isEmpty() || signature.isEmpty()) return null
        val groups = pool.groupBy { it.category }.toSortedMap()
        val ordered = mutableListOf<WordEntry>()
        val maxSize = groups.values.maxOfOrNull { it.size } ?: 0
        for (row in 0 until maxSize) {
            groups.values.forEach { group -> group.getOrNull(row)?.let { ordered.add(it) } }
        }
        if (ordered.isEmpty()) return null
        val numeric = runCatching { java.math.BigInteger(signature) }.getOrDefault(java.math.BigInteger.ZERO)
        val index = numeric.mod(java.math.BigInteger.valueOf(ordered.size.toLong())).toInt()
        return ordered[index]
    }

    private fun cooldownForSignature(signature: String): Long {
        val tail = signature.takeLast(2).toIntOrNull() ?: 0
        return (12L + tail % 24L) * 1_000L
    }

    @Synchronized
    private fun processMagneticSignature(x: Double, y: Double, z: Double, magnitude: Double) {
        if (!magnitude.isFinite() || magnitude <= 0.0) return
        val now = System.currentTimeMillis()
        if (!magneticReference.isFinite()) {
            magneticReferenceVector = doubleArrayOf(x, y, z)
            magneticReference = magnitude
            return
        }

        // La norme magnétique est indépendante de l'orientation du téléphone :
        // tourner l'appareil ne doit donc pas suffire à déclencher un mot.
        val magnitudeDelta = abs(magnitude - magneticReference)
        val calibratedNoise = if (calibrated) noiseFloor.toDouble() * 3.0 else 0.0
        val threshold = maxOf(0.45, calibratedNoise, 1.45 - sensitivity.toDouble() * 1.0)
        magneticDelta = magnitudeDelta.toFloat()
        if (magnitudeDelta < threshold || now < nextWordAt) return

        val pool = activeWordPool()
        if (pool.isEmpty()) return
        // GhostTube construit sa signature magnétique à partir de la norme du champ.
        // On reprend ce principe : chiffres décimaux de |B|, étendus à 20 chiffres.
        val signature = repeatDigitsTo20(sensorFraction(magnitude), inclusive = false)
        if (signature.length < 20) return
        val entry = selectSignatureEntry(pool, signature) ?: return

        magneticReference = magnitude
        magneticReferenceVector = doubleArrayOf(x, y, z)
        lastMagneticSignature = signature
        nextWordAt = now + cooldownForSignature(signature)
        emitWord(entry, "signature magnétique · Δ %.2f µT".format(Locale.FRANCE, magnitudeDelta), signature)
    }

    @Synchronized
    private fun processMotionSignature(x: Double, y: Double, z: Double, source: String) {
        val now = System.currentTimeMillis()
        val norm = sqrt(x * x + y * y + z * z)
        if (!norm.isFinite()) return
        val threshold = when (source) {
            "gyroscope" -> 0.12 + (1.0 - sensitivity.toDouble()) * 0.18
            "accéléromètre linéaire" -> 0.35 + (1.0 - sensitivity.toDouble()) * 0.45
            else -> 0.35 + (1.0 - sensitivity.toDouble()) * 0.45
        }
        val wasActive = motionTriggerActive[source] == true
        if (norm < threshold * 0.40) {
            motionTriggerActive[source] = false
            return
        }
        if (wasActive || norm < threshold || now < nextWordAt) return
        val pool = activeWordPool()
        if (pool.isEmpty()) return
        val signature = signatureDigits(x, y, z, norm)
        val entry = selectSignatureEntry(pool, signature) ?: return
        motionTriggerActive[source] = true
        nextWordAt = now + cooldownForSignature(signature)
        emitWord(entry, "signature de mouvement · $source · %.3f".format(Locale.FRANCE, norm), signature)
    }

    @Synchronized
    private fun processProximitySignature(distance: Double, maxRange: Double, isNear: Boolean) {
        val now = System.currentTimeMillis()
        if (now < nextWordAt || maxRange <= 0.0) return
        val pool = activeWordPool()
        if (pool.isEmpty()) return
        val signature = signatureDigits(distance, maxRange, if (isNear) 1.0 else 0.0)
        val entry = selectSignatureEntry(pool, signature) ?: return
        nextWordAt = now + cooldownForSignature(signature)
        emitWord(entry, if (isNear) "signature de proximité · proche" else "signature de proximité · éloigné", signature)
    }

    private fun isMotionStable(source: String, magnitude: Double, now: Long): Boolean {
        // Conservée pour compatibilité avec les appels existants :
        // le déclenchement est désormais événementiel, jamais basé sur
        // une période de stabilité du téléphone.
        return true
    }

    private fun ensureSensorOrder(size: Int) {
        if (shuffledWordIndexes.size != size) shuffledWordIndexes = (0 until size).toMutableList()
    }

    private fun orderedEntry(pool: List<WordEntry>, index: Int): WordEntry? {
        ensureSensorOrder(pool.size)
        return shuffledWordIndexes.getOrNull(index)?.let { pool.getOrNull(it) }
    }

    // Ancienne sélection conservée pour compatibilité avec les appels historiques.
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

    private fun refreshCustomWords() {
        if (!::customWordsContainer.isInitialized) return
        customWordsContainer.removeAllViews()
        if (customEntries.isEmpty()) {
            val empty = card()
            empty.addView(text("Aucun mot personnalisé pour le moment.\nAppuie sur + pour ajouter ton premier mot.", 13, muted))
            customWordsContainer.addView(empty)
            return
        }
        customEntries.forEachIndexed { index, entry ->
            val row = card()
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(text(entry.text, 15, white, true))
            info.addView(text(entry.category, 10, muted))
            line.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            val edit = TextView(this).apply {
                text = "✎"
                textSize = 20f
                setTextColor(accent)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { editCustomWord(index) }
            }
            val delete = TextView(this).apply {
                text = "×"
                textSize = 24f
                setTextColor(red)
                setPadding(dp(10), dp(6), dp(6), dp(6))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setMessage("Supprimer « ${entry.text} » ?")
                        .setNegativeButton("Annuler", null)
                        .setPositiveButton("Supprimer") { _, _ ->
                            customEntries.removeAt(index)
                            saveCustomDictionary()
                            refreshCustomWords()
                        }.show()
                }
            }
            line.addView(edit); line.addView(delete)
            row.addView(line)
            customWordsContainer.addView(row)
            customWordsContainer.addView(space(7))
        }
    }

    private fun editCustomWord(index: Int) {
        val entry = customEntries.getOrNull(index) ?: return
        val input = EditText(this).apply { setText(entry.text); setTextColor(white); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Modifier le mot").setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Enregistrer") { _, _ ->
                val value = input.text.toString().trim().uppercase(Locale.FRANCE)
                if (value.isNotBlank() && value.length <= 80) {
                    customEntries[index] = entry.copy(text = value)
                    saveCustomDictionary()
                    refreshCustomWords()
                }
            }.show()
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
            customCategories.add("Personnalisés")
            activeCategories.add("Personnalisés")
            saveCustomDictionary()
            savePreferences()
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
                    refreshCustomWords()
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
        val stamp = SimpleDateFormat("dd/MM/yyyy, HH:mm:ss", Locale.FRANCE).format(Date())
        history.add(0, "$stamp · $message")
        persistHistory()
        refreshHistory()
    }

    private fun persistHistory() {
        getSharedPreferences("echos_preferences", MODE_PRIVATE).edit()
            .putString("history", history.joinToString("\n")).apply()
    }

    private fun refreshHistory() {
        if (::historyView.isInitialized) {
            val grouped = history.groupBy { it.substringBefore(",") }
            historyView.text = if (history.isEmpty()) "Aucun mot détecté." else grouped.entries.joinToString("\n\n") { (date, entries) ->
                "▰  $date\n" + entries.joinToString("\n") { "• " + it.substringAfter(" · ") }
            }
        }
        if (::recentWordsView.isInitialized) {
            recentWordsView.text = if (history.isEmpty()) "Aucun mot détecté pour le moment."
            else history.take(5).joinToString("\n") { "• " + it.substringAfter("Mot : ").substringBefore(" · ") }
        }
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