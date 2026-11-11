# Call Recorder (Android 10+)

Records phone calls automatically while a call is active and saves them to
Music/CallRecordings on the phone.

## Build the APK

### Option A - Android Studio (computer)
1. Install Android Studio, choose File > Open, select this folder.
2. Wait for Gradle sync, then Build > Build Bundle(s)/APK(s) > Build APK(s).
3. APK: app/build/outputs/apk/debug/app-debug.apk

### Option B - GitHub (no installs)
1. Create a new GitHub repository and upload everything in this folder
   (keep the .github folder).
2. Open the Actions tab > "Build APK" > Run workflow.
3. When it finishes, download the "CallRecorder-APK" artifact (a zip containing app-debug.apk).

## Install
Copy the APK to the phone, open it, and allow "Install unknown apps" when asked.

## Use
Open the app, turn on "Auto-record phone calls", grant the permissions, and
tick "Keep running in background" in battery settings. Re-open the app after
restarting the phone.

## Limits
Android 10+ blocks apps from capturing the other person's voice on most phones.
The app tries the call-audio source first and falls back to the microphone.
If you only hear yourself, use speakerphone.
