# Building the APK

## Pinned toolchain

| Component | Version | Why |
|---|---|---|
| JDK | **17** | Required by Android Gradle Plugin 8.x |
| Gradle | **8.14.3** (wrapper, downloaded automatically) | Supported by AGP 8.12 |
| Android Gradle Plugin | **8.12.0** | Stable; supports compileSdk 36 |
| compileSdk | 36 | Compile against current headers. This does **not** raise the runtime requirement |
| minSdk | 24 | `dispatchGesture` (touchpad) needs API 24 |
| targetSdk | **25** | Matches the projector exactly, so runtime behaviour is the same as the platform it was tested on. The APK is sideloaded, never published, so Play's target-SDK rules don't apply |
| Java language level | 1.8 | Desugared by D8; no Java 8+ library APIs are used (no `java.time`, no `java.util.Base64`) |
| Dependencies | `org.nanohttpd:nanohttpd:2.3.1`, `com.google.zxing:core:3.3.3` | Both are pure Java with no native code, so the APK is ABI-independent and runs on 32-bit ARM (`armeabi-v7a`) unchanged |

The project uses no AndroidX, Kotlin, or native libraries.

## 1. Install the JDK and Android SDK

**Option A: Android Studio (easiest).** Install a recent Android Studio (Narwhal or newer). It
bundles a JDK 17+ runtime and the SDK manager. In *SDK Manager*, install **Android SDK Platform 36**
and **Android SDK Build-Tools 35**.

**Option B: command line (Linux).**

```bash
sudo apt install openjdk-17-jdk
# Download "Command line tools only" from developer.android.com/studio, unzip to ~/Android/Sdk/cmdline-tools/latest
export ANDROID_HOME=$HOME/Android/Sdk
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-36" "build-tools;35.0.0" "platform-tools"
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
```

## 2. Configure Gradle

You don't need to install Gradle; `./gradlew` downloads 8.14.3. Point the build at your SDK with
either `ANDROID_HOME` or a `local.properties` file (git-ignored):

```properties
sdk.dir=/home/you/Android/Sdk
```

If more than one JDK is installed, make sure 17 is used: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`.

## 3. Open in Android Studio

*File > Open* and select the project root (the folder that contains `settings.gradle`). Let the
Gradle sync finish. In *Settings > Build > Gradle*, set the Gradle JDK to 17 or newer.

## 4. Build a debug APK

```bash
./gradlew assembleDebug
```

## 5. Where the APK is

```
app/build/outputs/apk/debug/app-debug.apk                    (~380 KB, debug-signed, installable)
app/build/outputs/apk/release/app-release.apk                (if keystore.properties exists)
app/build/outputs/apk/release/app-release-unsigned.apk       (otherwise)
```

## 6. Inspecting build errors

```bash
./gradlew assembleDebug --stacktrace        # full exception for Gradle errors
./gradlew assembleDebug --info              # verbose task output
./gradlew lintDebug                         # report: app/build/reports/lint-results-debug.html
./gradlew testDebugUnitTest                 # report: app/build/reports/tests/testDebugUnitTest/index.html
```

Common causes:

- *"Unsupported class file major version"* or *"requires Java 17"*: Gradle is running on the wrong
  JDK. Set `JAVA_HOME`.
- *"SDK location not found"*: create `local.properties` or set `ANDROID_HOME`.
- *"Failed to find target android-36"*: install it with `sdkmanager "platforms;android-36"`.

## 7. Release APK

Release builds are shrunk with R8 (84 KB). They need to be signed:

```bash
keytool -genkeypair -v -keystore m2remote.jks -alias m2remote -keyalg RSA -keysize 2048 -validity 10000
```

Create `keystore.properties` in the project root. Both it and `*.jks` are git-ignored, so never
commit them:

```properties
storeFile=m2remote.jks
storePassword=...
keyAlias=m2remote
keyPassword=...
```

```bash
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

Keep the keystore: future updates must be signed with the same key, or Android refuses to install
them over the existing app. A debug APK cannot be upgraded to a release APK in place (different
signature). Uninstall first; you will need to re-enable the accessibility service and re-pair.
