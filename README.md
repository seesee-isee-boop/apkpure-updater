# APKPure Updater

[![Build](../../actions/workflows/build.yml/badge.svg)](../../actions/workflows/build.yml)

Petite appli Android perso (Kotlin, un seul module, seule dépendance : WorkManager) qui surveille
les mises à jour de tes applis sur APKPure :

1. liste les applis installées, tu coches celles à surveiller (ou tu ajoutes un package à la main avec **+**, ex. `com.cygames.umamusume`) ;
2. bouton **Vérifier les mises à jour** : compare la version installée avec la dernière dispo sur APKPure ;
3. **Télécharger** : récupère l'APK/XAPK adapté à l'architecture du téléphone dans `Téléchargements/APKPureUpdater/`, puis propose d'installer (APK) ou d'ouvrir avec SAI (XAPK) ;
4. option **Vérif auto toutes les 12 h** (menu ⋮) en arrière-plan avec notification.

Thème clair et sombre selon le réglage du téléphone.

> ⚠ APKPure n'est pas une source officielle : les fichiers peuvent être modifiés, en retard ou
> différents de ceux du Play Store. L'API utilisée n'est pas documentée et peut casser n'importe quand.
> Projet sans lien avec APKPure.

Android 10 minimum (minSdk 29).

## Compiler

Il faut un JDK 17+ et le SDK Android (platform 36, build-tools 36.0.0). Sur Debian/Ubuntu,
`setup.sh` installe tout :

```bash
bash setup.sh   # une seule fois : JDK + SDK Android, écrit local.properties
bash build.sh   # tests + APK debug -> ./apkpure-updater.apk
```

Ou directement avec le wrapper Gradle, si le SDK est déjà là (`ANDROID_HOME` ou `local.properties`) :

```bash
./gradlew testDebugUnitTest assembleDebug
```

Installation via USB : `adb install -r apkpure-updater.apk`.

## Permissions

| Permission | Pourquoi |
| --- | --- |
| `INTERNET` | interroger APKPure, télécharger |
| `QUERY_ALL_PACKAGES` | lister les applis installées (incompatible Play Store, OK en perso) |
| `REQUEST_INSTALL_PACKAGES` | lancer l'installateur système sur l'APK téléchargé |
| `POST_NOTIFICATIONS` | notification de la vérif automatique |

## Comment ça interroge APKPure

Même méthode qu'[apkeep](https://github.com/EFForg/apkeep) 1.1.0 — voir `ApkPureClient.kt` :
`GET https://api.pureapk.com/m/v3/cms/app_version?hl=en-US&package_name=<pkg>` avec les en-têtes
`x-cv: 3172501`, `x-sv: 29`, `x-abis: <ABI de l'appareil>`, `x-gp: 1`, puis regex sur la réponse binaire
(`(X?APKJ)..(https?://…)` → URL, `XAPKJ` = .xapk ; `([[:alnum:].-]+):\([[:xdigit:]]{40,}` → versions).

La comparaison de versions se fait sur les `versionName` (l'API ne donne pas facilement le
`versionCode`) : quand les deux ne sont pas comparables, l'appli l'indique et te laisse juger.

## Code

| Fichier | Rôle |
| --- | --- |
| `ApkPureClient.kt` | requête + parsing de la réponse, comparaison de versions (testé dans `ApkPureClientTest.kt`) |
| `Store.kt` | préférences (applis surveillées, réglages), vérification d'un package |
| `UpdateWorker.kt` | vérification périodique + notification |
| `MainActivity.kt` | interface (construite en code, sans XML) |

## Licence

[GPL-3.0](LICENSE) © 2026 caperuisseau
