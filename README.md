# SHADOWit – Android (Kotlin)

Native Kotlin Android app that hosts the SHADOWit web app (`app/src/main/assets/index.html`)
in a WebView, with native handling for file pickers, backup saving, microphone permission,
JS dialogs, full-screen video, keep-screen-on and external links.

## Build on GitHub
1. Create a new repo and push this folder's contents (the `.github` folder must be included).
2. Open the **Actions** tab → *Build Android APK* runs automatically (or press **Run workflow**).
3. Download the APK from the run's **Artifacts** (`SHADOWit-release-apk` / `SHADOWit-debug-apk`).

## Build locally
Android Studio (Koala+): open this folder → Run. Or: `./gradlew assembleDebug`.

## Update the web app
Replace `app/src/main/assets/index.html` and rebuild.

## Notes
- Release APK is signed with the debug key so it installs directly. Use your own keystore for Play Store.
- Package id: `com.shadowit.app` (change in `app/build.gradle.kts`).
- minSdk 26 (Android 8.0).
