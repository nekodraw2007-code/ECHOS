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
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class MainActivity : Activity(), SensorEventListener {
    private val bg = Color.rgb(7, 10, 18)
    private val panel = Color.rgb(17, 25, 39)
    private val panel2 = Color.rgb(25, 37, 54)
    private val cyan = Color.rgb(98, 221, 234)
    private val white = Color.rgb(241, 246, 252)
    private val muted = Color.rgb(145, 161, 182)
    private val green = Color.rgb(89, 220, 164)
    private val amber = Color.rgb(255, 185, 105)

    private lateinit var sensorManager: SensorManager
    private var magnetometer: Sensor? = null
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var linearAcceleration: Sensor? = null

    private var mode = 0
    private var sessionActive = false
    private var calibrating = false
    private var calibrated = false
    private var calibrationStart = 0L
    private var calibrationCount = 0
    private var calibrationTotal = 0.0
    private var magneticMagnitude = 0f
    private var accelerationMagnitude = 0f
    private var rotationMagnitude = 0f
    private var motionMagnitude = 0f
    private var magneticBaseline = 0f
    private var motionBaseline = 0f
    private var smoothedVariation = 0f
    private var threshold = 2.0f
    private var cooldownSeconds = 9
    private var lastWord = ""
    private var lastWordAt = 0L
    private var consecutiveSamples = 0

    private val history = mutableListOf<String>()
    private val customWords = mutableListOf<String>()
    private val activeCategories = mutableSetOf<String>()

    private lateinit var statusText: TextView
    private lateinit var readingText: TextView
    private lateinit var readingDetail: TextView
    private lateinit var currentWord: TextView
    private lateinit var sensorDetails: TextView
    private lateinit var historyDetails: TextView
    private lateinit var sessionButton: Button
    private lateinit var modeButton: Button
    private lateinit var categoriesButton: Button
    private lateinit var sensitivityText: TextView
    private lateinit var cooldownText: TextView

    private val dictionary = linkedMapOf(
        "Réponses" to listOf("OUI","NON","PEUT-ÊTRE","JE NE SAIS PAS","POURQUOI","COMMENT","QUI","QUAND","OÙ","MERCI","PARDON","ÉCOUTE","PARLE","RÉPÈTE","ATTENDS","STOP","JE SUIS LÀ","JE NE PEUX PAS","JE T'ENTENDS","JE ME SOUVIENS","JE VEUX PARLER","PAS MAINTENANT","PLUS TARD","C'EST BIEN","C'EST FAUX","AIDE-MOI","OÙ ES-TU","ES-TU SEUL","ES-TU PLUSIEURS","JE COMPRENDS","JE NE COMPRENDS PAS","CONTINUE","SILENCE","JE PARS","JE RESTE","RECOMMENCE","C'EST FINI","C'EST IMPORTANT"),
        "Personnes" to listOf("MÈRE","PÈRE","ENFANT","FAMILLE","AMI","SŒUR","FRÈRE","FEMME","HOMME","GRAND-MÈRE","GRAND-PÈRE","INCONNU","VISITEUR","PRÉNOM","NOM","VOISIN","PROCHE","ONCLE","TANTE","COUSIN","COUSINE","PETIT-FILS","PETITE-FILLE","BÉBÉ","ADOLESCENT","PARENT","ÉPOUX","ÉPOUSE","PARTENAIRE","COLLÈGUE","INCONNU","PASSANT","GROUPE","FILS","FILLE","JUMEAU","ANCIEN AMI"),
        "Émotions" to listOf("AMOUR","PEUR","JOIE","TRISTESSE","COLÈRE","CALME","SOLITUDE","PARDON","REGRET","BONHEUR","INQUIÉTUDE","CONFIANCE","DOULEUR","PAIX","MANQUE","ESPOIR","TRAHISON","NOSTALGIE","CULPABILITÉ","HONTE","SURPRISE","CURIOSITÉ","DÉCEPTION","SOULAGEMENT","JALOUSIE","COURAGE","TENDRESSE","FRUSTRATION","FATIGUE","DÉSIR","DOUTE","SÉRÉNITÉ","PANIQUE","MÉLANCOLIE","GRATITUDE","AFFECTION"),
        "Lieux" to listOf("MAISON","CHAMBRE","CUISINE","JARDIN","PORTE","FENÊTRE","GRENIER","CAVE","DEHORS","DEDANS","ICI","LÀ-BAS","PRÈS","LOIN","ÉCOLE","ÉGLISE","FORÊT","EAU","HÔPITAL","CIMETIÈRE","GARE","ROUTE","PONT","RIVIÈRE","LAC","MER","MONTAGNE","VILLAGE","VILLE","APPARTEMENT","COULOIR","ESCALIER","BALCON","GRENIER","SOUS-SOL","GARAGE","GRENIER","BORD DE MER","CAMPAGNE"),
        "Temps et souvenirs" to listOf("HIER","AUJOURD'HUI","DEMAIN","AVANT","APRÈS","MAINTENANT","BIENTÔT","LONGTEMPS","ENFANCE","SOUVENIR","DATE","ANNÉE","MATIN","SOIR","NUIT","JOUR","ÉTÉ","HIVER","PRINTEMPS","AUTOMNE","MINUIT","MIDI","AUBE","CRÉPUSCULE","SEMAINE","MOIS","LUNDI","MARDI","MERCREDI","JEUDI","VENDREDI","SAMEDI","DIMANCHE","PASSÉ","FUTUR","EN CE MOMENT","IL Y A LONGTEMPS","DERNIÈRE FOIS","PREMIÈRE FOIS","ANNIVERSAIRE","NAISSANCE","DÉPART","RETOUR"),
        "Objets" to listOf("PHOTO","LIVRE","TABLE","CHAISE","LIT","MONTRE","BIJOU","CLÉ","TÉLÉPHONE","MIROIR","LAMPE","BOÎTE","LETTRE","VÊTEMENT","JOUET","MUSIQUE","SAC","PORTEFEUILLE","BAGUE","COLLIER","BRACELET","BOUCLE","CHAUSSURE","MANTEAU","CHAPEAU","VALISE","PENDULE","HORLOGE","RADIO","TÉLÉVISION","ORDINATEUR","CLAVIER","STYLO","PAPIER","ENVELOPPE","TASSE","VERRE","ASSIETTE","FOURCHETTE","COUTEAU","PHOTO DE FAMILLE","MÉDAILLE","CROIX"),
        "Sensations" to listOf("FROID","CHAUD","LUMIÈRE","OMBRE","BRUIT","SILENCE","ODEUR","VENT","PRESSION","MOUVEMENT","VIBRATION","PROCHE","DERRIÈRE","DEVANT","TOUCHE","SOUFFLE","FRISSON","PICOTEMENT","CHALEUR","FRAÎCHEUR","PESANTEUR","LÉGÈRETÉ","VERTIGE","ÉTOURDISSEMENT","ÉCHO","RÉSONANCE","CLAQUEMENT","PAS","GRINCEMENT","PARFUM","FUMÉE","OBSCURITÉ","CLARTÉ","ÉBLOUISSEMENT","FOURMILLEMENT","PRESSION SUR L'ÉPAULE","CONTACT"),
        "Actions" to listOf("VIENS","RESTE","PARTIR","REVIENS","AIDE","SUIVRE","OUVRIR","FERMER","MONTRER","ATTENDRE","AVANCER","RECULER","REGARDER","ENTENDRE","DORMIR","RÉVEILLER","CHERCHER","TROUVER","CACHER","SE CACHER","SE RAPPELER","OUBLIER","PROTÉGER","PRÉVENIR","APPELER","RÉPONDRE","TOUCHER","SENTIR","SOURIRE","PLEURER","RIRE","CHANTER","ÉCRIRE","LIRE","VENIR","S'ÉLOIGNER","S'APPROCHER","MONTER","DESCENDRE","ATTENDRE ICI"),
        "Nombres" to listOf("UN","DEUX","TROIS","QUATRE","CINQ","SIX","SEPT","HUIT","NEUF","DIX","BEAUCOUP","PEU","PREMIER","DERNIER","ZÉRO","CENT","ONZE","DOUZE","TREIZE","QUATORZE","QUINZE","VINGT","TRENTE","QUARANTE","CINQUANTE","SOIXANTE","SOIXANTE-DIX","QUATRE-VINGTS","QUATRE-VINGT-DIX","MILLE","BEAUCOUP","PLUS","MOINS","MOITIÉ","DOUBLE","PREMIER","DEUXIÈME","TROISIÈME")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        linearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val prefs = getSharedPreferences("echos_preferences", MODE_PRIVATE)
        threshold = prefs.getFloat("threshold", 2f)
        cooldownSeconds = prefs.getInt("cooldown", 9)
        mode = prefs.getInt("mode", MODE_AUTO).coerceIn(MODE_AUTO, MODE_MAGNETIC)
        customWords.addAll(prefs.getString("custom_words", "")!!.split("\n").filter { it.isNotBlank() })
        history.addAll(prefs.getString("history", "")!!.split("\n").filter { it.isNotBlank() }.take(60))
        val savedCategories = prefs.getString("active_categories", "")!!.split("\n").filter { it.isNotBlank() }
        activeCategories.addAll(if (savedCategories.isEmpty()) dictionary.keys else savedCategories.filter { it in dictionary.keys })
        if (activeCategories.isEmpty()) activeCategories.addAll(dictionary.keys)
        buildInterface()
        refreshSensorInfo()
        refreshHistory()
    }

    private fun buildInterface() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(30))
        }
        scroll.addView(page)
        page.addView(label("E C H O S", 29, white, true))
        page.addView(label("EXPÉRIENCE SENSORIELLE", 11, cyan, true))
        page.addView(label("MESURES LOCALES · FONCTIONNE HORS LIGNE", 10, muted))
        page.addView(gap(18))

        val meter = card()
        meter.addView(label("LECTURE DES CAPTEURS", 11, cyan, true))
        readingText = label("EN ATTENTE", 31, white, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(6))
        }
        meter.addView(readingText)
        readingDetail = label("Les mesures apparaîtront ici.", 12, muted).apply { gravity = Gravity.CENTER }
        meter.addView(readingDetail)
        page.addView(meter)
        page.addView(gap(10))

        statusText = label("Initialisation…", 12, amber, true).apply {
            gravity = Gravity.CENTER
            background = rounded(panel2, 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        page.addView(statusText, fullWidth())
        page.addView(gap(10))

        val communication = card()
        communication.addView(label("SESSION", 12, cyan, true))
        currentWord = label("—", 42, white, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(22), 0, dp(22))
        }
        communication.addView(currentWord)
        communication.addView(label("Les mots sont sélectionnés par un algorithme après une variation mesurée. Leur origine n'est pas vérifiable.", 12, muted))
        sessionButton = actionButton("DÉMARRER LA SESSION") { toggleSession() }
        communication.addView(sessionButton, fullWidth())
        page.addView(communication)
        page.addView(gap(10))

        val calibration = card()
        calibration.addView(label("CALIBRATION", 12, cyan, true))
        calibration.addView(label("Pose le téléphone sur une surface stable et lance la calibration pour établir une référence de mesure.", 12, muted))
        calibration.addView(actionButton("CALIBRER LES CAPTEURS") { startCalibration() }, fullWidth())
        page.addView(calibration)
        page.addView(gap(10))

        val settings = card()
        settings.addView(label("RÉGLAGES DE L'EXPÉRIENCE", 12, cyan, true))
        modeButton = actionButton(modeLabel()) { chooseMode() }
        settings.addView(modeButton, fullWidth())
        categoriesButton = actionButton("Toutes les catégories") { chooseCategories() }
        settings.addView(categoriesButton, fullWidth())
        settings.addView(actionButton("AJOUTER UN MOT PERSONNALISÉ") { addCustomWord() }, fullWidth())
        sensitivityText = label("", 12, white)
        settings.addView(sensitivityText)
        settings.addView(SeekBar(this).apply {
            max = 98
            progress = ((threshold - 0.2f) * 10).toInt().coerceIn(0, 98)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    threshold = 0.2f + value / 10f
                    sensitivityText.text = "Seuil de déclenchement : %.1f".format(Locale.FRANCE, threshold)
                    saveSettings()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        })
        cooldownText = label("", 12, white)
        settings.addView(cooldownText)
        settings.addView(SeekBar(this).apply {
            max = 29
            progress = (cooldownSeconds - 1).coerceIn(0, 29)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    cooldownSeconds = value + 1
                    cooldownText.text = "Délai anti-répétition : " + cooldownSeconds + " s"
                    saveSettings()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        })
        sensitivityText.text = "Seuil de déclenchement : %.1f".format(Locale.FRANCE, threshold)
        cooldownText.text = "Délai anti-répétition : " + cooldownSeconds + " s"
        page.addView(settings)
        page.addView(gap(10))

        val hardware = card()
        hardware.addView(label("CAPTEURS DE L'APPAREIL", 12, cyan, true))
        sensorDetails = label("", 12, muted)
        hardware.addView(sensorDetails)
        page.addView(hardware)
        page.addView(gap(10))

        val journal = card()
        journal.addView(label("JOURNAL DE SESSION", 12, cyan, true))
        historyDetails = label("Aucun événement.", 12, muted)
        journal.addView(historyDetails)
        journal.addView(actionButton("EFFACER L'HISTORIQUE") {
            history.clear()
            saveHistory()
            refreshHistory()
        }, fullWidth())
        page.addView(journal)
        page.addView(gap(14))
        page.addView(label("ECHOS est une expérience ludique et expérimentale. Les capteurs mesurent des phénomènes physiques ordinaires ; les mots sont générés par le logiciel et ne démontrent pas une communication avec l'invisible.", 11, muted))
        setContentView(scroll)
    }

    private fun modeLabel(): String = when (mode) {
        1 -> "Mode mouvement (accéléromètre + gyroscope)"
        2 -> "Mode magnétomètre uniquement"
        else -> "Mode automatique (magnétomètre si présent)"
    }

    private fun activeMode(): Int = when (mode) {
        1 -> 1
        2 -> 2
        else -> if (magnetometer != null) 2 else 1
    }

    private fun sensorAvailable(): Boolean = if (activeMode() == 2) {
        magnetometer != null
    } else {
        accelerometer != null || gyroscope != null || linearAcceleration != null
    }

    private fun chooseMode() {
        val options = arrayOf("Automatique — magnétomètre si présent", "Mouvement — accéléromètre et gyroscope", "Magnétomètre uniquement")
        AlertDialog.Builder(this).setTitle("Mode de détection")
            .setSingleChoiceItems(options, mode) { dialog, which ->
                mode = which
                calibrated = false
                sessionActive = false
                getSharedPreferences("echos_preferences", MODE_PRIVATE).edit().putInt("mode", mode).apply()
                sessionButton.text = "DÉMARRER LA SESSION"
                modeButton.text = modeLabel()
                refreshSensorInfo()
                dialog.dismiss()
            }.setNegativeButton("Annuler", null).show()
    }

    private fun chooseCategories() {
        val checks = dictionary.keys.map { name ->
            CheckBox(this).apply {
                text = name
                setTextColor(white)
                isChecked = name in activeCategories
                setPadding(dp(16), dp(7), dp(16), dp(7))
            }
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            checks.forEach { addView(it) }
        }
        AlertDialog.Builder(this).setTitle("Catégories du dictionnaire")
            .setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton("Appliquer") { _, _ ->
                activeCategories.clear()
                checks.filter { it.isChecked }.forEach { activeCategories.add(it.text.toString()) }
                if (activeCategories.isEmpty()) {
                    activeCategories.addAll(dictionary.keys)
                    Toast.makeText(this, "Active au moins une catégorie.", Toast.LENGTH_SHORT).show()
                }
                categoriesButton.text = if (activeCategories.size == dictionary.size) "Toutes les catégories" else activeCategories.size.toString() + " catégories actives"
            }.setNegativeButton("Annuler", null).show()
    }

    private fun addCustomWord() {
        val input = EditText(this).apply {
            hint = "Ex. : SOUVENIR"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
        }
        AlertDialog.Builder(this).setTitle("Ajouter un mot")
            .setView(input)
            .setPositiveButton("Ajouter") { _, _ ->
                val value = input.text.toString().trim().uppercase(Locale.FRANCE)
                if (value.isNotBlank() && value.length <= 32 && value !in customWords) {
                    customWords.add(value)
                    getSharedPreferences("echos_preferences", MODE_PRIVATE).edit()
                        .putString("custom_words", customWords.joinToString("\n")).apply()
                    Toast.makeText(this, "Mot ajouté.", Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("Annuler", null).show()
    }

    private fun startCalibration() {
        if (!sensorAvailable()) {
            Toast.makeText(this, "Aucun capteur compatible avec ce mode.", Toast.LENGTH_LONG).show()
            return
        }
        sessionActive = false
        sessionButton.text = "DÉMARRER LA SESSION"
        calibrating = true
        calibrated = false
        calibrationStart = System.currentTimeMillis()
        calibrationCount = 0
        calibrationTotal = 0.0
        smoothedVariation = 0f
        consecutiveSamples = 0
        statusText.text = "Calibration en cours · garde le téléphone immobile"
        statusText.setTextColor(amber)
    }

    private fun toggleSession() {
        if (sessionActive) {
            sessionActive = false
            sessionButton.text = "DÉMARRER LA SESSION"
            statusText.text = "Session arrêtée"
            statusText.setTextColor(muted)
            addHistory("Session arrêtée")
            return
        }
        if (!sensorAvailable()) {
            Toast.makeText(this, "Aucun capteur compatible avec ce mode.", Toast.LENGTH_LONG).show()
            return
        }
        if (!calibrated) {
            Toast.makeText(this, "Effectue d'abord une calibration.", Toast.LENGTH_SHORT).show()
            return
        }
        sessionActive = true
        lastWordAt = System.currentTimeMillis()
        consecutiveSamples = 0
        sessionButton.text = "ARRÊTER LA SESSION"
        statusText.text = "SESSION ACTIVE · analyse des variations"
        statusText.setTextColor(green)
        addHistory("Session démarrée · " + modeLabel())
    }

    override fun onResume() {
        super.onResume()
        listOfNotNull(magnetometer, accelerometer, gyroscope, linearAcceleration)
            .distinctBy { it.type }
            .forEach { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        refreshSensorInfo()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        if (sessionActive) {
            sessionActive = false
            if (::sessionButton.isInitialized) sessionButton.text = "DÉMARRER LA SESSION"
            if (::statusText.isInitialized) {
                statusText.text = "Session mise en pause"
                statusText.setTextColor(muted)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magneticMagnitude = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                if (activeMode() == 2) processSample(abs(magneticMagnitude - magneticBaseline), magneticMagnitude)
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                accelerationMagnitude = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                if (activeMode() == 1) processMotion()
            }
            Sensor.TYPE_ACCELEROMETER -> if (linearAcceleration == null) {
                accelerationMagnitude = abs(sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) - SensorManager.GRAVITY_EARTH)
                if (activeMode() == 1) processMotion()
            }
            Sensor.TYPE_GYROSCOPE -> {
                rotationMagnitude = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                if (activeMode() == 1) processMotion()
            }
        }
        updateReading()
    }

    private fun processMotion() {
        motionMagnitude = accelerationMagnitude * 0.65f + rotationMagnitude * 1.8f
        processSample(abs(motionMagnitude - motionBaseline), motionMagnitude)
    }

    private fun processSample(variation: Float, rawValue: Float) {
        if (!variation.isFinite() || !rawValue.isFinite()) return
        if (calibrating) {
            calibrationTotal += rawValue.toDouble()
            calibrationCount++
            if (calibrationCount >= 45 || System.currentTimeMillis() - calibrationStart >= 5000L) {
                calibrating = false
                if (calibrationCount >= 10) {
                    val reference = (calibrationTotal / calibrationCount).toFloat()
                    if (activeMode() == 2) magneticBaseline = reference else motionBaseline = reference
                    calibrated = true
                    statusText.text = "Calibration terminée · " + calibrationCount + " mesures"
                    statusText.setTextColor(green)
                    addHistory("Calibration terminée")
                } else {
                    statusText.text = "Mesures insuffisantes · recommence"
                    statusText.setTextColor(amber)
                }
            } else {
                statusText.text = "Calibration · " + calibrationCount + " mesures"
            }
            return
        }
        if (!calibrated) return
        smoothedVariation = if (smoothedVariation == 0f) variation else smoothedVariation * 0.78f + variation * 0.22f
        if (!sessionActive) return
        consecutiveSamples = if (smoothedVariation >= threshold) consecutiveSamples + 1 else 0
        val now = System.currentTimeMillis()
        if (consecutiveSamples >= 5 && now - lastWordAt >= cooldownSeconds * 1000L) {
            consecutiveSamples = 0
            lastWordAt = now
            val selected = selectWord()
            currentWord.text = selected
            addHistory("Mot : " + selected + " · variation %.2f".format(Locale.FRANCE, smoothedVariation))
            vibrate()
        }
    }

    private fun selectWord(): String {
        val words = dictionary.filterKeys { it in activeCategories }.values.flatten() + customWords
        val candidates = words.filter { it != lastWord }
        val source = if (candidates.isNotEmpty()) candidates else words
        val selected = source[Random.nextInt(source.size)]
        lastWord = selected
        return selected
    }

    private fun updateReading() {
        if (!::readingText.isInitialized) return
        if (activeMode() == 2) {
            readingText.text = if (magnetometer == null) "INDISPONIBLE" else "%.1f µT".format(Locale.FRANCE, magneticMagnitude)
            readingDetail.text = if (calibrated) "Écart à la référence : %.2f µT".format(Locale.FRANCE, abs(magneticMagnitude - magneticBaseline)) else "Champ magnétique mesuré sur les trois axes"
        } else {
            readingText.text = "MOUVEMENT"
            readingDetail.text = "Accéléromètre : %.2f · Gyroscope : %.2f".format(Locale.FRANCE, accelerationMagnitude, rotationMagnitude)
        }
    }

    private fun refreshSensorInfo() {
        if (!::sensorDetails.isInitialized) return
        sensorDetails.text = "Magnétomètre : " + if (magnetometer != null) "disponible" else "absent"
        sensorDetails.append("\nAccéléromètre : " + if (accelerometer != null || linearAcceleration != null) "disponible" else "absent")
        sensorDetails.append("\nGyroscope : " + if (gyroscope != null) "disponible" else "absent")
        sensorDetails.append("\nMode actif : " + modeLabel())
        statusText.text = when {
            !sensorAvailable() -> "Aucun capteur compatible avec le mode choisi"
            calibrated -> "Capteurs prêts · calibration effectuée"
            activeMode() == 1 -> "Mode mouvement disponible · calibration nécessaire"
            else -> "Magnétomètre disponible · calibration nécessaire"
        }
        statusText.setTextColor(if (sensorAvailable()) amber else Color.RED)
    }

    private fun addHistory(message: String) {
        val stamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(Date())
        history.add(0, stamp + " · " + message)
        while (history.size > 60) history.removeAt(history.lastIndex)
        saveHistory()
        refreshHistory()
    }

    private fun refreshHistory() {
        if (::historyDetails.isInitialized) {
            historyDetails.text = if (history.isEmpty()) "Aucun événement pour le moment." else history.take(15).joinToString("\n\n")
        }
    }

    private fun saveHistory() {
        getSharedPreferences("echos_preferences", MODE_PRIVATE).edit()
            .putString("history", history.joinToString("\n")).apply()
    }

    private fun saveSettings() {
        getSharedPreferences("echos_preferences", MODE_PRIVATE).edit()
            .putFloat("threshold", threshold)
            .putInt("cooldown", cooldownSeconds)
            .apply()
    }

    private fun vibrate() {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(110, VibrationEffect.DEFAULT_AMPLITUDE))
                else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(110)
                }
            }
        } catch (_: Exception) {}
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(panel, 18)
        elevation = dp(2).toFloat()
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(3), 0, dp(4))
    }

    private fun actionButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 12f
        isAllCaps = false
        setTextColor(white)
        background = rounded(Color.rgb(28, 65, 82), 12)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun gap(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun fullWidth() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MODE_MOTION = 1
        private const val MODE_MAGNETIC = 2
    }
}
