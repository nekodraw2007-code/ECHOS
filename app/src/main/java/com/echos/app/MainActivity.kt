package com.echos.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.util.Log
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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.random.Random

private data class WordEntry(val category: String, val text: String, val weight: Int = 1, val custom: Boolean = false)

class MainActivity : Activity(), SensorEventListener {
    companion object { private const val GOOGLE_SIGN_IN_REQUEST = 7104 }
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
    private var magneticCandidateSamples = 0
    private var magneticCandidateSince = 0L
    private var lastMagneticEventAt = 0L
    private val motionTriggerActive = mutableMapOf<String, Boolean>()
    private val motionReferenceMagnitude = mutableMapOf<String, Double>()
    private var lastProximityNear: Boolean? = null
    private val registeredSensorTypes = mutableSetOf<Int>()
    private var speechEngine: TextToSpeech? = null
    private var speechReady = false
    private var neuralVoiceEngine: NeuralVoiceEngine? = null
    private var neuralSpeechThread: Thread? = null
    private var dynamicVoicesEnabled = true
    private var dynamicVoices: List<Voice> = emptyList()
    private var defaultFrenchVoice: Voice? = null
    private lateinit var voiceInfoView: TextView
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
    private lateinit var accountStatusView: TextView
    private lateinit var googleSignInButton: Button
    private lateinit var signOutButton: Button

    private var firebaseAuth: FirebaseAuth? = null
    private var firestore: FirebaseFirestore? = null
    private var customSyncListener: ListenerRegistration? = null
    private var historySyncListener: ListenerRegistration? = null
    private var settingsSyncListener: ListenerRegistration? = null
    private var settingsInitialSyncDone = false
    private var syncStatusMessage = "En attente de synchronisation"
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var applyingRemoteSync = false
    private var customInitialSyncDone = false

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
        initializeFirebaseSync()
        initializeSpeech()
        buildInterface()
        refreshSensorStatus()
        refreshAccountUi()
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
        // Active une seule fois les nouvelles catégories du dictionnaire 1.2.0
        // sur les installations existantes, sans réactiver les catégories
        // que l'utilisateur avait volontairement désactivées par la suite.
        if (!p.getBoolean("dictionary_v4_categories_initialized", false)) {
            val newCategories = setOf("Questions et identité", "Couleurs", "Moyens de transport", "Pays", "Objets du quotidien")
            activeCategories.addAll(baseEntries.map { it.category }.filter { it in newCategories })
            p.edit().putBoolean("dictionary_v4_categories_initialized", true).apply()
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
        // Zone de résultat principale : le mot déclenché reste visible en très grand.
        val detectedCard = card().apply {
            background = rounded(Color.rgb(8, 22, 43), 20)
            setPadding(dp(18), dp(22), dp(18), dp(22))
            gravity = Gravity.CENTER
        }
        detectedCard.addView(text("DERNIER MOT DÉTECTÉ", 10, accent, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.10f
        })
        wordView = text("EN ATTENTE", 38, white, true).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, dp(16), 0, dp(12))
            maxLines = 2
            includeFontPadding = true
        }
        detectedCard.addView(wordView, LinearLayout.LayoutParams(-1, -2))
        home.addView(detectedCard)
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
                .setPositiveButton("Effacer") { _, _ -> clearHistoryEverywhere() }
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
        voiceCard.addView(text("VOIX FRANÇAISES", 10, accent, true))
        voiceInfoView = text("Recherche des voix françaises installées…", 11, muted)
        voiceCard.addView(voiceInfoView)
        voiceCard.addView(button("VOIX NEURONALES HORS LIGNE · GÉRER / INSTALLER") { manageNeuralVoicePacks() }, match())
        voiceCard.addView(button("VOIX ANDROID (SECOURS)") { showFrenchVoicePicker() }, match())
        voiceCard.addView(Switch(this).apply {
            text = "Voix dynamiques"
            setTextColor(white)
            isChecked = dynamicVoicesEnabled
            setOnCheckedChangeListener { _, enabled ->
                dynamicVoicesEnabled = enabled
                getSharedPreferences("echos_preferences", MODE_PRIVATE).edit()
                    .putBoolean("dynamic_voices_enabled", enabled)
                    .putLong("preferences_updated_at", System.currentTimeMillis()).apply()
                syncPreferencesToCloud()
                if (!enabled) speechEngine?.stop()
            }
        }, match())
        settingsPage.addView(voiceCard)
        settingsPage.addView(space(12))
        val accountCard = card()
        accountCard.addView(text("COMPTE ET SYNCHRONISATION", 10, accent, true))
        accountStatusView = text("Non connecté · données enregistrées sur cet appareil", 12, muted)
        accountCard.addView(accountStatusView)
        googleSignInButton = button("SE CONNECTER AVEC GOOGLE") { startGoogleSignIn() }
        accountCard.addView(googleSignInButton, match())
        signOutButton = button("SE DÉCONNECTER") { signOutGoogle() }
        accountCard.addView(signOutButton, match())
        settingsPage.addView(accountCard)
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
            if (!speechReady) {
                runOnUiThread {
                    if (::voiceInfoView.isInitialized) voiceInfoView.text = "Synthèse vocale indisponible. Vérifie le moteur de synthèse Android."
                }
                return@TextToSpeech
            }
            val engine = speechEngine ?: return@TextToSpeech
            try {
                val french = Locale.FRANCE
                if (engine.isLanguageAvailable(french) >= TextToSpeech.LANG_AVAILABLE) engine.language = french
                val voices = engine.voices ?: emptySet()
                // Garder toutes les voix françaises, y compris celles nécessitant le réseau.
                // Les voix locales sont privilégiées pour rester utilisables hors ligne.
                dynamicVoices = voices
                    .filter { it.locale.language.equals("fr", ignoreCase = true) }
                    .sortedWith(
                        compareBy<Voice> { it.isNetworkConnectionRequired }
                            .thenBy { !it.locale.country.equals("FR", ignoreCase = true) }
                            .thenBy { it.name.lowercase(Locale.ROOT) }
                    )
                defaultFrenchVoice = dynamicVoices.firstOrNull { !it.isNetworkConnectionRequired }
                    ?: dynamicVoices.firstOrNull()
                defaultFrenchVoice?.let { engine.voice = it }
                engine.setPitch(1.0f)
                engine.setSpeechRate(0.92f)
                runOnUiThread {
                    refreshNeuralVoiceStatus()
                }
            } catch (e: Exception) {
                Log.e("ECHOS_TTS", "Initialisation des voix françaises impossible", e)
            }
        }
    }

    private fun showFrenchVoicePicker() {
        if (!speechReady) {
            toast("La synthèse vocale n'est pas encore prête.")
            return
        }
        if (dynamicVoices.isEmpty()) {
            toast("Aucune voix française installée dans le moteur Android.")
            return
        }
        val labels = dynamicVoices.map { voice ->
            val connection = if (voice.isNetworkConnectionRequired) "En ligne" else "Hors ligne"
            "${voice.name} · ${voice.locale.displayName} · $connection"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Tester les voix françaises")
            .setItems(labels) { _, which ->
                val selected = dynamicVoices[which]
                try {
                    speechEngine?.voice = selected
                    speechEngine?.setPitch(1.0f)
                    speechEngine?.setSpeechRate(0.92f)
                    val sample = "Bonjour. Ceci est un test de la voix française d'ECHOS."
                    speechEngine?.speak(sample, TextToSpeech.QUEUE_FLUSH, Bundle(), "echos-voice-test-${System.nanoTime()}")
                    toast("Voix sélectionnée : ${selected.name}")
                } catch (e: Exception) {
                    Log.e("ECHOS_TTS", "Test de voix impossible", e)
                    toast("Cette voix n'a pas pu être lancée.")
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun prepareFrenchSpeech(rawText: String): String {
        var text = rawText.trim()
            .replace("&", " et ")
            .replace("/", " ")
            .replace(Regex("[_•…]+"), " ")
            // Les tirets de questions françaises ne doivent plus créer une pause longue.
            .replace(Regex("[–—-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.FRANCE)
            .trim()
        val questionCue = Regex("^(est ce|es tu|êtes vous|qui|quoi|que|où|quand|comment|pourquoi|combien|avez vous|as tu|peux tu|veux tu|sais tu|y a t il)\\b", RegexOption.IGNORE_CASE)
        if (questionCue.containsMatchIn(text) && !text.endsWith("?")) text = text.trimEnd('.', '!', '…') + " ?"
        text = text.replace(Regex("\\s+([?!.,])"), "$1")
            .replace(Regex("([?!.,])(?=\\S)"), "$1 ")
            .replace(Regex("\\s+"), " ")
        return text
    }

    private fun fingerprintHexValue(fingerprint: String, index: Int, fallback: Int): Int {
        if (fingerprint.isEmpty()) return fallback
        return Character.digit(fingerprint[Math.floorMod(index, fingerprint.length)], 16)
            .takeIf { it >= 0 } ?: fallback
    }

    private fun refreshNeuralVoiceStatus() {
        if (!::voiceInfoView.isInitialized) return
        val installed = installedNeuralVoices()
        voiceInfoView.text = if (installed.isEmpty()) {
            "Aucun pack neural installé. Installe une voix indépendante pour utiliser la synthèse hors Android. Les voix Android restent disponibles en secours."
        } else {
            "Moteur neural hors ligne disponible · ${installed.size} pack(s) installé(s) : ${installed.joinToString { it.label }}. Les signatures sélectionnent une voix stable."
        }
    }

    private fun installedNeuralVoices(): List<FrenchNeuralVoice> =
        FrenchNeuralVoices.catalog.filter { FrenchNeuralVoices.isInstalled(this, it) }

    private fun manageNeuralVoicePacks() {
        val voices = FrenchNeuralVoices.catalog
        val prefs = getSharedPreferences("echos_preferences", MODE_PRIVATE)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(12))
        }
        val explanation = text(
            "Choisis une voix. Les packs non installés ont un bouton de téléchargement visible. " +
                "Après installation, la voix fonctionne hors ligne.",
            13, white
        )
        explanation.setPadding(0, 0, 0, dp(12))
        content.addView(explanation)

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(content)
        }
        val managerDialog = AlertDialog.Builder(this)
            .setTitle("Voix neuronales françaises")
            .setView(scroll)
            .setNegativeButton("Fermer", null)
            .create()

        fun chooseAndPreview(voice: FrenchNeuralVoice) {
            prefs.edit().putString("neural_voice_selected", voice.id)
                .putLong("preferences_updated_at", System.currentTimeMillis()).apply()
            syncPreferencesToCloud()
            toast("Voix neuronale sélectionnée : ${voice.label}")
            Thread {
                try {
                    val engine = neuralVoiceEngine ?: NeuralVoiceEngine(this).also { neuralVoiceEngine = it }
                    engine.speak("Bonjour. Cette voix neuronale fonctionne hors ligne dans ECHOS.", voice, 0, 0.94f, 4)
                } catch (e: Exception) {
                    Log.e("ECHOS_NEURAL", "Test neural impossible", e)
                    runOnUiThread { toast("Erreur moteur neural : ${e.localizedMessage ?: "inconnue"}") }
                }
            }.start()
        }

        fun startVoiceDownload(voice: FrenchNeuralVoice) {
            managerDialog.dismiss()
            val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }
            val status = text("Connexion au serveur vocal…", 13, white)
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(8))
                addView(status)
                addView(progress, LinearLayout.LayoutParams(-1, dp(12)))
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle("Téléchargement : ${voice.label}")
                .setView(box)
                .setNegativeButton("Masquer", null)
                .create()
            dialog.show()
            Thread {
                try {
                    FrenchNeuralVoices.downloadAndInstall(this, voice) { value ->
                        runOnUiThread {
                            progress.progress = value
                            status.text = when {
                                value >= 90 -> "Extraction et vérification du pack…"
                                value == 0 -> "Connexion au serveur vocal…"
                                else -> "Téléchargement : $value %"
                            }
                        }
                    }
                    runOnUiThread {
                        dialog.dismiss()
                        prefs.edit().putString("neural_voice_selected", voice.id)
                            .putLong("preferences_updated_at", System.currentTimeMillis()).apply()
                        syncPreferencesToCloud()
                        toast("Pack vocal installé et sélectionné.")
                        refreshNeuralVoiceStatus()
                        manageNeuralVoicePacks()
                    }
                } catch (e: Exception) {
                    Log.e("ECHOS_NEURAL", "Installation pack vocal échouée", e)
                    runOnUiThread {
                        dialog.dismiss()
                        toast("Téléchargement échoué : ${e.localizedMessage ?: e.javaClass.simpleName}")
                        manageNeuralVoicePacks()
                    }
                }
            }.start()
        }

        voices.forEach { voice ->
            val installed = FrenchNeuralVoices.isInstalled(this, voice)
            val selected = prefs.getString("neural_voice_selected", "") == voice.id
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(10))
            }
            val label = text(voice.label, 15, white)
            row.addView(label)
            val state = text(
                if (installed) (if (selected) "Installée · sélectionnée" else "Installée · prête hors ligne")
                else "Non installée · ${voice.approximateSize}",
                12, white
            )
            state.setPadding(0, dp(3), 0, dp(6))
            row.addView(state)
            val action = Button(this).apply {
                text = if (installed) (if (selected) "Tester cette voix" else "Sélectionner et tester")
                    else "Télécharger le pack (${voice.approximateSize})"
                isAllCaps = false
                setOnClickListener {
                    if (installed) {
                        managerDialog.dismiss()
                        chooseAndPreview(voice)
                    } else {
                        startVoiceDownload(voice)
                    }
                }
            }
            row.addView(action, LinearLayout.LayoutParams(-1, dp(48)))
            content.addView(row)
            if (voice != voices.last()) {
                val separator = View(this).apply {
                    setBackgroundColor(0x44FFFFFF)
                }
                content.addView(separator, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
        managerDialog.show()
    }

    private fun speakWithSensorVoice(rawText: String, fingerprint: String) {
        val installedNeural = installedNeuralVoices()
        if (installedNeural.isNotEmpty()) {
            val prefs = getSharedPreferences("echos_preferences", MODE_PRIVATE)
            val selectedId = prefs.getString("neural_voice_selected", null)
            val voice = if (dynamicVoicesEnabled && installedNeural.size > 1) {
                installedNeural[fingerprintHexValue(fingerprint, 4, 0) % installedNeural.size]
            } else {
                installedNeural.firstOrNull { it.id == selectedId } ?: installedNeural.first()
            }
            val seed = fingerprintHexValue(fingerprint, 8, 8)
            val style = if (dynamicVoicesEnabled) seed % 5 else 4
            val rate = when (style) {
                0 -> 0.84f + (fingerprintHexValue(fingerprint, 7, 8) / 15f) * 0.12f
                3 -> 0.78f + (fingerprintHexValue(fingerprint, 7, 8) / 15f) * 0.10f
                else -> 0.88f + (fingerprintHexValue(fingerprint, 7, 8) / 15f) * 0.18f
            }
            val neural = neuralVoiceEngine ?: NeuralVoiceEngine(this).also { neuralVoiceEngine = it }
            val question = prepareFrenchSpeech(rawText).endsWith("?")
            neural.stop()
            speechEngine?.stop()
            neuralSpeechThread?.interrupt()
            neuralSpeechThread = Thread {
                try {
                    neural.speak(prepareFrenchSpeech(rawText), voice, 0, rate, style, question)
                } catch (e: Exception) {
                    Log.e("ECHOS_NEURAL", "Synthèse neuronale impossible", e)
                    // The previous Android TTS path remains a safe fallback if model/runtime fails.
                    runOnUiThread { toast("La voix neuronale a échoué. Retour à la voix Android.") }
                    runOnUiThread { speakWithSensorVoiceAndroid(rawText, fingerprint) }
                }
            }.also { it.start() }
            return
        }
        if (!speechReady) return
        val engine = speechEngine ?: return
        val spokenText = prepareFrenchSpeech(rawText)
        if (spokenText.isBlank()) return
        speakWithSensorVoiceAndroid(rawText, fingerprint)
    }

    private fun speakWithSensorVoiceAndroid(rawText: String, fingerprint: String) {
        if (!speechReady) return
        val engine = speechEngine ?: return
        val spokenText = prepareFrenchSpeech(rawText)
        if (spokenText.isBlank()) return
        try {
            val question = spokenText.endsWith("?")
            val styleSeed = fingerprintHexValue(fingerprint, 8, 8)
            if (dynamicVoicesEnabled && dynamicVoices.isNotEmpty()) {
                // Même empreinte = même voix et même profil vocal (dont chuchotement).
                val voiceSeed = fingerprintHexValue(fingerprint, 4, 0) * 16 +
                    fingerprintHexValue(fingerprint, 5, 0)
                val pitchSeed = fingerprintHexValue(fingerprint, 6, 8)
                val rateSeed = fingerprintHexValue(fingerprint, 7, 8)
                engine.voice = dynamicVoices[voiceSeed % dynamicVoices.size]
                when (styleSeed % 5) {
                    0 -> { // Chuchotement simulé
                        engine.setPitch(0.88f + (pitchSeed / 15f) * 0.08f + if (question) 0.06f else 0f)
                        engine.setSpeechRate(0.78f + (rateSeed / 15f) * 0.08f)
                    }
                    1 -> { // Grave
                        engine.setPitch(0.78f + (pitchSeed / 15f) * 0.10f + if (question) 0.06f else 0f)
                        engine.setSpeechRate(0.86f + (rateSeed / 15f) * 0.08f)
                    }
                    2 -> { // Aérien, plus aigu
                        engine.setPitch(1.10f + (pitchSeed / 15f) * 0.16f + if (question) 0.06f else 0f)
                        engine.setSpeechRate(0.86f + (rateSeed / 15f) * 0.12f)
                    }
                    3 -> { // Ralenti / instable
                        engine.setPitch(0.92f + (pitchSeed / 15f) * 0.18f + if (question) 0.06f else 0f)
                        engine.setSpeechRate(0.82f + (rateSeed / 15f) * 0.16f)
                    }
                    else -> {
                        engine.setPitch(0.94f + (pitchSeed / 15f) * 0.12f + if (question) 0.06f else 0f)
                        engine.setSpeechRate(0.90f + (rateSeed / 15f) * 0.16f)
                    }
                }
            } else {
                defaultFrenchVoice?.let { engine.voice = it }
                engine.setPitch(if (question) 1.04f else 1.0f)
                engine.setSpeechRate(if (question) 0.88f else 0.92f)
            }
            // Android n'offre pas un vrai whisper universel : on simule avec volume bas.
            val whisper = dynamicVoicesEnabled && styleSeed % 5 == 0
            val params = Bundle().apply { putFloat("volume", if (whisper) 0.30f else 1.0f) }
            engine.speak(spokenText, TextToSpeech.QUEUE_FLUSH, params, "echos-${System.nanoTime()}")
        } catch (e: Exception) {
            Log.e("ECHOS_TTS", "Lecture du mot impossible : $rawText", e)
        }
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
                // Les callbacks capteurs tournent sur des HandlerThreads :
                // toute mise à jour des vues doit être exécutée sur le thread UI.
                if (::fieldView.isInitialized) {
                    val magnitudeText = "%.1f µT".format(Locale.FRANCE, magneticMagnitude)
                    val deltaText = "Variation depuis calibration : %.2f µT".format(Locale.FRANCE, magneticDelta)
                    runOnUiThread {
                        if (::fieldView.isInitialized) fieldView.text = magnitudeText
                        if (::deltaView.isInitialized) deltaView.text = deltaText
                    }
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
        magneticCandidateSamples = 0
        magneticCandidateSince = 0L
        lastMagneticEventAt = 0L
        motionTriggerActive.clear()
        motionReferenceMagnitude.clear()
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

    // ECHOS reprend le principe public de GhostTube : une signature numérique issue
    // des capteurs sélectionne une entrée. L'algorithme propriétaire exact de GhostTube
    // n'étant pas publié, cette implémentation est indépendante et ne prétend pas le copier.
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

    // Sélection équilibrée en deux étapes : d'abord une catégorie, puis une entrée
    // de cette catégorie. Le nombre d'entrées d'une catégorie ne lui donne donc
    // aucun avantage : 465 prénoms ne doivent pas écraser les autres thèmes.
    // La signature issue du capteur reste la seule source de sélection.
    private fun selectSignatureEntry(pool: List<WordEntry>, signature: String): WordEntry? {
        if (pool.isEmpty() || signature.isEmpty()) return null
        val groups = pool.groupBy { it.category }.toSortedMap()
        if (groups.isEmpty()) return null
        val numeric = runCatching { java.math.BigInteger(signature) }.getOrDefault(java.math.BigInteger.ZERO)
        val categoryIndex = numeric.mod(java.math.BigInteger.valueOf(groups.size.toLong())).toInt()
        val selected = groups.values.elementAtOrNull(categoryIndex) ?: return null
        if (selected.isEmpty()) return null
        val entrySeed = runCatching { java.math.BigInteger(signature.reversed()) }
            .getOrDefault(numeric)
        val totalWeight = selected.sumOf { it.weight.coerceAtLeast(1) }
        var target = entrySeed.mod(java.math.BigInteger.valueOf(totalWeight.toLong())).toInt()
        for (entry in selected) {
            target -= entry.weight.coerceAtLeast(1)
            if (target < 0) return entry
        }
        return selected.lastOrNull()
    }

    private fun cooldownForSignature(signature: String): Long {
        val tail = signature.takeLast(2).toIntOrNull() ?: 0
        return (12L + tail % 24L) * 1_000L
    }

    @Synchronized
    private fun processMagneticSignature(x: Double, y: Double, z: Double, magnitude: Double) {
        if (!magnitude.isFinite() || magnitude <= 0.0 || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val now = System.currentTimeMillis()
        val current = doubleArrayOf(x, y, z)
        val referenceVector = magneticReferenceVector
        if (!magneticReference.isFinite() || referenceVector == null) {
            magneticReferenceVector = current
            magneticReference = magnitude
            magneticCandidateSamples = 0
            return
        }

        // Compare le vecteur complet pour mesurer les changements de champ dans
        // les trois axes, tout en conservant la norme comme indicateur moins
        // sensible aux rotations du téléphone.
        val vectorDelta = sqrt(
            (x - referenceVector[0]) * (x - referenceVector[0]) +
            (y - referenceVector[1]) * (y - referenceVector[1]) +
            (z - referenceVector[2]) * (z - referenceVector[2])
        )
        val magnitudeDelta = abs(magnitude - magneticReference)
        val calibratedNoise = if (calibrated) noiseFloor.toDouble() * 3.0 else 0.0
        val sensitivityCurve = sensitivity.toDouble().pow(1.65)
        val threshold = maxOf(0.18, calibratedNoise, 1.45 - sensitivityCurve * 1.27)
        magneticDelta = magnitudeDelta.toFloat()

        // Mise à jour très progressive de la référence en période calme; les
        // petits changements ne s'accumulent pas en fausses alertes.
        if (vectorDelta < threshold * 0.55 && magnitudeDelta < threshold * 0.55) {
            for (i in 0..2) referenceVector[i] = referenceVector[i] * 0.985 + current[i] * 0.015
            magneticReference = magneticReference * 0.985 + magnitude * 0.015
            magneticCandidateSamples = 0
            magneticCandidateSince = 0L
            return
        }

        val eventThreshold = maxOf(threshold, if (calibrated) noiseFloor.toDouble() * 2.0 else 0.0)
        if (vectorDelta < eventThreshold && magnitudeDelta < eventThreshold) {
            magneticCandidateSamples = 0
            magneticCandidateSince = 0L
            return
        }

        // Validation temporelle : plusieurs lectures consécutives doivent
        // confirmer la variation avant qu'un mot soit émis.
        if (magneticCandidateSamples == 0) magneticCandidateSince = now
        magneticCandidateSamples++
        val requiredSamples = if (sensitivity >= 0.92f) 2 else 3
        val requiredDuration = if (sensitivity >= 0.92f) 35L else 70L
        if (magneticCandidateSamples < requiredSamples || now - magneticCandidateSince < requiredDuration || now < nextWordAt) return
        if (now - lastMagneticEventAt < 350L) return

        val pool = activeWordPool()
        if (pool.isEmpty()) return

        // Empreinte sensorielle reproductible et quantifiée : axes, norme,
        // écart vectoriel et variation de norme. Ce n'est pas une preuve
        // d'origine paranormale ni une reproduction du code propriétaire.
        val signature = signatureDigits(
            (x * 10.0).toLong().toDouble(),
            (y * 10.0).toLong().toDouble(),
            (z * 10.0).toLong().toDouble(),
            (magnitude * 10.0).toLong().toDouble(),
            (vectorDelta * 10.0).toLong().toDouble(),
            (magnitudeDelta * 100.0).toLong().toDouble()
        )
        val entry = selectSignatureEntry(pool, signature) ?: return

        magneticReferenceVector = current
        magneticReference = magnitude
        magneticCandidateSamples = 0
        magneticCandidateSince = 0L
        lastMagneticEventAt = now
        lastMagneticSignature = signature
        nextWordAt = now + cooldownForSignature(signature)
        emitWord(
            entry,
            "empreinte magnétique · vecteur Δ %.2f µT · norme Δ %.2f µT".format(Locale.FRANCE, vectorDelta, magnitudeDelta),
            signature
        )
    }

    @Synchronized
    private fun processMotionSignature(x: Double, y: Double, z: Double, source: String) {
        val now = System.currentTimeMillis()
        val norm = sqrt(x * x + y * y + z * z)
        if (!norm.isFinite()) return

        // Le mouvement est évalué par rapport au niveau de repos propre à chaque
        // capteur, et non par sa valeur brute. Cela évite de traiter la gravité comme
        // une anomalie et réduit les déclenchements dus aux petits mouvements continus.
        val reference = motionReferenceMagnitude[source]
        if (reference == null) {
            motionReferenceMagnitude[source] = norm
            return
        }
        val deviation = abs(norm - reference)
        val sensCurve = sensitivity.toDouble().pow(1.45)
        val threshold = when (source) {
            "gyroscope" -> 0.055 + (1.0 - sensCurve) * 0.24
            "accéléromètre linéaire" -> 0.12 + (1.0 - sensCurve) * 0.48
            else -> 0.12 + (1.0 - sensCurve) * 0.48
        }
        val effectiveThreshold = maxOf(threshold, if (calibrated) noiseFloor.toDouble() * 2.5 else 0.0)
        val wasActive = motionTriggerActive[source] == true

        // Mise à jour lente de la référence uniquement lorsque le capteur est calme.
        // Une variation brusque reste donc détectable sans que le mouvement normal
        // fasse dériver immédiatement le seuil.
        if (deviation < effectiveThreshold * 0.60) {
            motionReferenceMagnitude[source] = reference * 0.97 + norm * 0.03
            motionTriggerActive[source] = false
            return
        }
        if (wasActive || deviation < effectiveThreshold || now < nextWordAt) return

        val pool = activeWordPool()
        if (pool.isEmpty()) return
        val signature = signatureDigits(x, y, z, norm, deviation)
        val entry = selectSignatureEntry(pool, signature) ?: return

        motionTriggerActive[source] = true
        nextWordAt = now + cooldownForSignature(signature)
        emitWord(
            entry,
            "variation environnementale · $source · Δ %.3f".format(Locale.FRANCE, deviation),
            signature
        )
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
            if (::wordView.isInitialized) {
                wordView.text = entry.text
                wordView.setTextColor(white)
                wordView.animate().cancel()
                wordView.alpha = 0.35f
                wordView.animate().alpha(1f).setDuration(260L).start()
            }
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

    private fun initializeFirebaseSync() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                Log.w("ECHOS_SYNC", "Firebase non configuré : google-services.json manquant.")
                return
            }
            firebaseAuth = FirebaseAuth.getInstance()
            firestore = FirebaseFirestore.getInstance()
            firebaseAuth?.addAuthStateListener { auth ->
                val user = auth.currentUser
                runOnUiThread { refreshAccountUi() }
                if (user == null) {
                    customSyncListener?.remove()
                    historySyncListener?.remove()
                    settingsSyncListener?.remove()
                    customSyncListener = null
                    historySyncListener = null
                    settingsSyncListener = null
                    customInitialSyncDone = false
                    settingsInitialSyncDone = false
                    syncStatusMessage = "Compte déconnecté · données conservées sur l'appareil"
                } else {
                    syncStatusMessage = "Connexion au cloud…"
                    attachCloudListeners(user.uid)
                    syncHistoryToCloud()
                }
            }
        } catch (e: Exception) {
            Log.e("ECHOS_SYNC", "Initialisation Firebase impossible", e)
        }
    }

    private fun refreshAccountUi() {
        if (!::accountStatusView.isInitialized) return
        val user = firebaseAuth?.currentUser
        if (user != null) {
            accountStatusView.text = "Connecté : ${user.displayName ?: user.email ?: "Compte Google"}\n$syncStatusMessage"
            googleSignInButton.isEnabled = false
            googleSignInButton.text = "COMPTE GOOGLE CONNECTÉ"
            signOutButton.isEnabled = true
        } else {
            val configured = firebaseAuth != null
            accountStatusView.text = if (configured) "Non connecté · connexion Google disponible" else
                "Firebase non configuré. Ajoute le fichier google-services.json du projet Firebase pour activer la connexion."
            googleSignInButton.isEnabled = configured
            googleSignInButton.text = "SE CONNECTER AVEC GOOGLE"
            signOutButton.isEnabled = false
        }
    }

    // Connexion Google via le sélecteur natif Google Play Services.
    // Ce flux ouvre explicitement l'écran de choix de compte, puis échange l'ID token avec Firebase.
    private fun startGoogleSignIn() {
        if (firebaseAuth == null) {
            toast("Firebase n'est pas initialisé. Vérifie google-services.json.")
            return
        }
        val webClientIdRes = resources.getIdentifier("default_web_client_id", "string", packageName)
        if (webClientIdRes == 0) {
            toast("Identifiant OAuth Web introuvable dans google-services.json.")
            return
        }
        googleSignInButton.isEnabled = false
        googleSignInButton.text = "OUVERTURE DE GOOGLE…"
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail().requestIdToken(getString(webClientIdRes)).build()
        val client = GoogleSignIn.getClient(this, options)
        // Clear stale cached sign-in state before opening the chooser.
        client.signOut().addOnCompleteListener {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(client.signInIntent, GOOGLE_SIGN_IN_REQUEST)
            } catch (e: Exception) {
                Log.e("ECHOS_SYNC", "Impossible d'ouvrir le sélecteur Google", e)
                toast("Impossible d'ouvrir Google : ${e.localizedMessage ?: e.javaClass.simpleName}")
                refreshAccountUi()
            }
        }
    }

    @Deprecated("Google Sign-In activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != GOOGLE_SIGN_IN_REQUEST) return
        // Google Play Services may use RESULT_CANCELED for failures too.
        // Always inspect the returned Intent to expose the real ApiException.
        if (data == null) {
            Log.w("ECHOS_SYNC", "Google returned no result Intent (resultCode=$resultCode)")
            toast("Google n'a renvoyé aucun résultat. Vérifie Google Play Services et OAuth.")
            refreshAccountUi()
            return
        }
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken.isNullOrBlank()) {
                Log.e("ECHOS_SYNC", "Google n'a pas retourné d'ID token pour ${account.email}")
                toast("Google n'a pas fourni de jeton. Vérifie le client OAuth Web.")
                refreshAccountUi()
                return
            }
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            firebaseAuth?.signInWithCredential(credential)
                ?.addOnSuccessListener {
                    Log.i("ECHOS_SYNC", "Connexion Google/Firebase réussie : ${it.user?.uid}")
                    toast("Connexion Google réussie. Synchronisation activée.")
                    refreshAccountUi()
                }
                ?.addOnFailureListener { error ->
                    Log.e("ECHOS_SYNC", "Firebase a refusé le jeton Google", error)
                    toast("Échec Firebase : ${error.localizedMessage ?: error.javaClass.simpleName}")
                    refreshAccountUi()
                } ?: run {
                    toast("Firebase n'est plus disponible.")
                    refreshAccountUi()
                }
        } catch (e: ApiException) {
            Log.e("ECHOS_SYNC", "Google Sign-In statusCode=${e.statusCode}, resultCode=$resultCode", e)
            val detail = when (e.statusCode) {
                10 -> "Configuration OAuth incorrecte : package ou SHA-1 de cet APK."
                16 -> "Réauthentification refusée par Google. Vérifie la signature SHA-1 de l'APK et Google Play Services."
                12501 -> "Sélection Google annulée par l'utilisateur."
                else -> e.localizedMessage ?: "Erreur Google"
            }
            toast("Échec Google [${e.statusCode}] : $detail")
            refreshAccountUi()
        } catch (e: Exception) {
            Log.e("ECHOS_SYNC", "Erreur inattendue pendant la connexion Google", e)
            toast("Erreur Google : ${e.localizedMessage ?: e.javaClass.simpleName}")
            refreshAccountUi()
        }
    }

    private fun signOutGoogle() {
        firebaseAuth?.signOut()
        GoogleSignIn.getClient(this, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut()
            .addOnCompleteListener {
                toast("Déconnecté. Les données locales sont conservées.")
                refreshAccountUi()
            }
    }

    private fun cloudUserDocument(uid: String) =
        firestore?.collection("users")?.document(uid)?.collection("echos")?.document("dictionary")

    private fun attachCloudListeners(uid: String) {
        val db = firestore ?: return
        customSyncListener?.remove()
        historySyncListener?.remove()
        settingsSyncListener?.remove()
        customInitialSyncDone = false
        settingsInitialSyncDone = false

        customSyncListener = cloudUserDocument(uid)?.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e("ECHOS_SYNC", "Écoute dictionnaire", error)
                return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener
            if (!snapshot.exists()) {
                syncCustomDictionaryToCloud()
                return@addSnapshotListener
            }
            val remoteEntries = (snapshot.get("entries") as? List<*>)?.mapNotNull { raw ->
                val item = raw as? Map<*, *> ?: return@mapNotNull null
                val category = item["category"] as? String ?: return@mapNotNull null
                val word = item["text"] as? String ?: return@mapNotNull null
                WordEntry(category, word, 1, true)
            } ?: emptyList()
            val remoteCategories = (snapshot.get("categories") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            applyingRemoteSync = true
            if (!customInitialSyncDone) {
                val merged = (customEntries + remoteEntries).distinctBy { it.category.lowercase(Locale.ROOT) + "|" + it.text.lowercase(Locale.ROOT) }
                customEntries.clear()
                customEntries.addAll(merged)
                customCategories.addAll(remoteCategories)
                customInitialSyncDone = true
                saveCustomDictionary()
                refreshCustomWordsIfReady()
                applyingRemoteSync = false
                syncCustomDictionaryToCloud()
            } else {
                customEntries.clear()
                customEntries.addAll(remoteEntries)
                customCategories.clear()
                customCategories.addAll(remoteCategories)
                saveCustomDictionary()
                refreshCustomWordsIfReady()
                applyingRemoteSync = false
            }
        }

        settingsSyncListener = db.collection("users").document(uid).collection("echos").document("settings")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("ECHOS_SYNC", "Écoute des réglages", error)
                    setSyncStatus("Erreur de lecture cloud : ${error.localizedMessage ?: "accès refusé"}")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                if (!snapshot.exists()) {
                    settingsInitialSyncDone = true
                    syncPreferencesToCloud()
                    return@addSnapshotListener
                }
                val remoteUpdatedAt = snapshot.getLong("updatedAt") ?: 0L
                val localUpdatedAt = getSharedPreferences("echos_preferences", MODE_PRIVATE).getLong("preferences_updated_at", 0L)
                if (!settingsInitialSyncDone && localUpdatedAt > remoteUpdatedAt) {
                    settingsInitialSyncDone = true
                    syncPreferencesToCloud()
                    return@addSnapshotListener
                }
                settingsInitialSyncDone = true
                applyingRemoteSync = true
                sensitivity = (snapshot.getDouble("sensitivity")?.toFloat() ?: sensitivity).coerceIn(0f, 1f)
                baseline = snapshot.getDouble("baseline")?.toFloat() ?: baseline
                noiseFloor = snapshot.getDouble("noiseFloor")?.toFloat() ?: noiseFloor
                calibrated = snapshot.getBoolean("calibrated") ?: calibrated
                dynamicVoicesEnabled = snapshot.getBoolean("dynamicVoicesEnabled") ?: dynamicVoicesEnabled
                val neuralVoiceSelected = snapshot.getString("neuralVoiceSelected")
                if (!neuralVoiceSelected.isNullOrBlank()) {
                    getSharedPreferences("echos_preferences", MODE_PRIVATE).edit()
                        .putString("neural_voice_selected", neuralVoiceSelected).apply()
                }
                val remoteCategories = (snapshot.get("activeCategories") as? List<*>)?.filterIsInstance<String>()
                if (remoteCategories != null) {
                    activeCategories.clear()
                    activeCategories.addAll(remoteCategories)
                }
                getSharedPreferences("echos_preferences", MODE_PRIVATE).edit()
                    .putFloat("sensitivity", sensitivity)
                    .putFloat("baseline", baseline)
                    .putFloat("noise_floor", noiseFloor)
                    .putBoolean("calibrated", calibrated)
                    .putBoolean("dynamic_voices_enabled", dynamicVoicesEnabled)
                    .putString("neural_voice_selected", neuralVoiceSelected ?: getSharedPreferences("echos_preferences", MODE_PRIVATE).getString("neural_voice_selected", "siwis"))
                    .putString("active_categories", activeCategories.joinToString("\n"))
                    .putLong("preferences_updated_at", remoteUpdatedAt)
                    .apply()
                applyingRemoteSync = false
                if (::sensitivityLabel.isInitialized) updateSensitivityLabel()
                if (::categoriesButton.isInitialized) categoriesButton.text = "${activeCategories.size} catégories actives"
                setSyncStatus("Réglages synchronisés")
            }

        historySyncListener = db.collection("users").document(uid).collection("echos_history")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("ECHOS_SYNC", "Écoute historique", error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                val remote = snapshot.documents.mapNotNull { it.getString("line") }
                if (remote.isNotEmpty()) {
                    applyingRemoteSync = true
                    val merged = (history + remote).distinct()
                    history.clear()
                    history.addAll(merged.sortedByDescending { historySortKey(it) })
                    getSharedPreferences("echos_preferences", MODE_PRIVATE).edit()
                        .putString("history", history.joinToString("\n")).apply()
                    refreshHistory()
                    applyingRemoteSync = false
                }
                syncHistoryToCloud()
            }
    }

    private fun refreshCustomWordsIfReady() {
        if (::customWordsContainer.isInitialized) refreshCustomWords()
    }

    private fun historySortKey(line: String): Long {
        val dateText = line.substringBefore(" · ")
        return runCatching { SimpleDateFormat("dd/MM/yyyy, HH:mm:ss", Locale.FRANCE).parse(dateText)?.time ?: 0L }.getOrDefault(0L)
    }

    private fun stableDocumentId(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun syncCustomDictionaryToCloud() {
        val user = firebaseAuth?.currentUser ?: return
        val doc = cloudUserDocument(user.uid) ?: return
        val entries = customEntries.map { mapOf("category" to it.category, "text" to it.text) }
        val payload = mapOf(
            "entries" to entries,
            "categories" to customCategories.toList(),
            "updatedAt" to System.currentTimeMillis()
        )
        doc.set(payload, SetOptions.merge())
            .addOnSuccessListener { setSyncStatus("Dictionnaire sauvegardé") }
            .addOnFailureListener {
                Log.e("ECHOS_SYNC", "Envoi dictionnaire", it)
                setSyncStatus("Échec sauvegarde dictionnaire : ${it.localizedMessage ?: "vérifie les règles Firestore"}")
            }
    }

    private fun clearHistoryEverywhere() {
        history.clear()
        applyingRemoteSync = true
        getSharedPreferences("echos_preferences", MODE_PRIVATE).edit()
            .putString("history", "").apply()
        applyingRemoteSync = false
        refreshHistory()
        val user = firebaseAuth?.currentUser ?: return
        val collection = firestore?.collection("users")?.document(user.uid)?.collection("echos_history") ?: return
        collection.get().addOnSuccessListener { snapshot ->
            val batches = snapshot.documents.chunked(450)
            batches.forEach { docs ->
                val batch = firestore?.batch() ?: return@forEach
                docs.forEach { batch.delete(it.reference) }
                batch.commit()
            }
        }.addOnFailureListener { Log.e("ECHOS_SYNC", "Suppression historique cloud", it) }
    }

    private fun syncHistoryToCloud() {
        val user = firebaseAuth?.currentUser ?: return
        val db = firestore ?: return
        val collection = db.collection("users").document(user.uid).collection("echos_history")
        history.forEach { line ->
            val id = stableDocumentId(line)
            collection.document(id).set(
                mapOf("line" to line, "createdAt" to historySortKey(line)),
                SetOptions.merge()
            ).addOnFailureListener {
                Log.e("ECHOS_SYNC", "Envoi historique", it)
                setSyncStatus("Échec sauvegarde historique : ${it.localizedMessage ?: "vérifie les règles Firestore"}")
            }
        }
    }

    private fun syncPreferencesToCloud() {
        val user = firebaseAuth?.currentUser ?: return
        val db = firestore ?: return
        val payload = mapOf(
            "sensitivity" to sensitivity.toDouble(),
            "baseline" to baseline.toDouble(),
            "noiseFloor" to noiseFloor.toDouble(),
            "calibrated" to calibrated,
            "dynamicVoicesEnabled" to dynamicVoicesEnabled,
            "neuralVoiceSelected" to getSharedPreferences("echos_preferences", MODE_PRIVATE).getString("neural_voice_selected", "siwis"),
            "activeCategories" to activeCategories.toList(),
            "updatedAt" to getSharedPreferences("echos_preferences", MODE_PRIVATE).getLong("preferences_updated_at", System.currentTimeMillis())
        )
        db.collection("users").document(user.uid).collection("echos").document("settings")
            .set(payload, SetOptions.merge())
            .addOnSuccessListener { setSyncStatus("Données et réglages sauvegardés") }
            .addOnFailureListener {
                Log.e("ECHOS_SYNC", "Envoi des réglages", it)
                setSyncStatus("Échec sauvegarde réglages : ${it.localizedMessage ?: "vérifie les règles Firestore"}")
            }
    }

    private fun setSyncStatus(message: String) {
        syncStatusMessage = message
        runOnUiThread {
            val user = firebaseAuth?.currentUser
            if (::accountStatusView.isInitialized && user != null) {
                accountStatusView.text = "Connecté : ${user.displayName ?: user.email ?: "Compte Google"}\n$message"
            }
        }
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
        if (!applyingRemoteSync) syncCustomDictionaryToCloud()
    }

    private fun savePreferences() {
        val updatedAt = System.currentTimeMillis()
        getSharedPreferences("echos_preferences", MODE_PRIVATE).edit()
            .putFloat("sensitivity", sensitivity)
            .putFloat("baseline", baseline)
            .putFloat("noise_floor", noiseFloor)
            .putBoolean("calibrated", calibrated)
            .putBoolean("dynamic_voices_enabled", dynamicVoicesEnabled)
            .putString("active_categories", activeCategories.joinToString("\n"))
            .putLong("preferences_updated_at", updatedAt)
            .apply()
        if (!applyingRemoteSync) syncPreferencesToCloud()
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
        if (!applyingRemoteSync) syncHistoryToCloud()
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
        neuralSpeechThread?.interrupt()
        neuralVoiceEngine?.stop()
        neuralVoiceEngine?.release()
        neuralVoiceEngine = null
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