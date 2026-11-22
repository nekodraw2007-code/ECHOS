# ECHOS — application Android

ECHOS est une application Android autonome, développée dans ce dépôt indépendant.

## Première version
- Lecture du magnétomètre (µT) lorsqu'il est présent.
- Mode alternatif avec accéléromètre et gyroscope.
- Sélection automatique du capteur disponible ou choix manuel du mode.
- Calibration des mesures.
- Dictionnaire français organisé en neuf catégories.
- Activation/désactivation des catégories et ajout de mots personnalisés.
- Réglage de la sensibilité et du délai anti-répétition.
- Historique local des événements.
- Fonctionnement hors connexion.

## Compiler l'APK
Chaque push sur `main` lance GitHub Actions. Le workflow **Build ECHOS APK** compile `app-debug.apk` et le publie dans l'artefact `ECHOS-APK` pendant 14 jours. Le workflow peut aussi être lancé manuellement depuis l'onglet Actions.

Télécharge l'artefact ZIP, décompresse-le, puis ouvre `app-debug.apk` sur Android.

## Transparence
Les capteurs mesurent des phénomènes physiques ordinaires. Les mots sont choisis par un algorithme local à partir de variations mesurées ; ils ne constituent pas une preuve de communication avec une entité ou un défunt.

## Configuration
- Android natif (Kotlin et vues Android natives)
- Min SDK 23
- Compile/Target SDK 35
- Java 17, Gradle 8.9, Android Gradle Plugin 8.7.3
