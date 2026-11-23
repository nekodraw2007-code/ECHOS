# ECHOS — application Android

ECHOS est une application Android autonome, développée dans ce dépôt indépendant.

## Version 1.0.1
- Lecture du magnétomètre en µT et affichage des axes X, Y et Z.
- Mode alternatif avec accéléromètre et gyroscope.
- Mode automatique, mode mouvement et mode magnétomètre.
- Calibration des mesures avant chaque session.
- Dictionnaire français réparti en neuf catégories thématiques.
- Activation/désactivation des catégories, mémorisée entre les lancements.
- Ajout de mots personnalisés, conservés localement.
- Réglage de la sensibilité et du délai anti-répétition, conservés localement.
- Historique local des événements.
- Interface sombre et icône dédiée.
- Fonctionnement hors connexion ; aucune permission Internet n'est demandée.

## Compiler l'APK
Chaque push sur `main` lance GitHub Actions. Le workflow **Build ECHOS APK** compile `app-debug.apk` et le publie dans l'artefact `ECHOS-APK` pendant 14 jours. Le workflow peut aussi être lancé manuellement depuis l'onglet Actions.

Télécharger l'artefact ZIP, le décompresser, puis ouvrir `app-debug.apk` sur Android. Cette version est signée avec la clé de débogage Android : elle convient à l'installation et aux tests, pas à une publication Play Store.

## Première utilisation
1. Ouvrir ECHOS et choisir le mode automatique ou un mode manuel.
2. Poser le téléphone sur une surface stable.
3. Lancer **Calibrer les capteurs** et attendre la fin de la calibration.
4. Démarrer la session.
5. Ajuster le seuil, le délai anti-répétition et les catégories dans les réglages.

Les objets métalliques, aimants, haut-parleurs, câbles et appareils électriques peuvent influencer les mesures du magnétomètre.

## Transparence
Les capteurs mesurent des phénomènes physiques ordinaires. Les mots sont choisis par un algorithme local à partir de variations mesurées ; ils ne constituent pas une preuve de communication avec une entité ou un défunt.

## Configuration
- Android natif (Kotlin et vues Android natives)
- Min SDK 23
- Compile/Target SDK 35
- Java 17, Gradle 8.9, Android Gradle Plugin 8.7.3


Version 1.4.0 : détection des variations des capteurs inertiels avec référence adaptative, y compris sans magnétomètre.

Version 1.5.0 : diction de synthèse vocale française normalisée, découverte des voix françaises installées (locales et réseau), test individuel des voix et sélection dynamique pilotée par la signature des capteurs. Le mode dynamique désactivé conserve une lecture française standard.
