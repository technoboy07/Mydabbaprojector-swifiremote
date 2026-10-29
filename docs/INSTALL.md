# Installing and first use

The projector has no Play Store, so the APK is sideloaded.

## 1. Allow APK installation on the projector

*Settings > Security* (on some RK3128 firmwares *Settings > More settings > Security*) and turn on
**Unknown sources**. On Android 7 this is one global switch. You can turn it off again after
installing.

## 2. Transfer the APK

**USB stick (no PC tools needed).** Copy `app-debug.apk` (or your signed release APK) to a USB
stick, plug it into the projector, and open it with the built-in file manager.

**ADB over USB.** Enable developer options: *Settings > About > tap "Build number" 7 times*. Then
*Developer options > USB debugging*. Connect the projector's USB/OTG port to your PC:

```bash
adb devices                 # accept the prompt on the projector
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**ADB over the network (only on a network you trust).** Many RK3128 firmwares have *Developer
options > Network ADB* or listen on port 5555 when USB debugging is enabled:

```bash
adb connect PROJECTOR_IP:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb disconnect
```

Network ADB gives full shell access to anyone on the LAN. Turn it off when you're done. The remote
app itself never needs ADB.

## 3. Install

With a file manager, choose the APK and then **Install**. With ADB, `adb install -r` prints `Success`.

## 4. Launch

Open **M2 Smart Remote** from the launcher (it registers for both the normal and the TV/leanback
launcher). It starts the server immediately. The left side shows *Server: Running* and the address,
for example `192.168.1.42 : 8765`.

## 5. Enable the accessibility service (manual, required once)

Select **Enable accessibility service**, which opens *Settings > Accessibility*. Choose
**M2 Smart Remote** and switch it **On**, then confirm. Press Back. The app should now show
*Accessibility: On*.

The app never enables this itself. If this firmware's Settings app has no Accessibility page, you
can enable it yourself from a PC:

```bash
adb shell settings put secure enabled_accessibility_services com.m2smartremote/com.m2smartremote.input.RemoteAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

The first command replaces any other enabled accessibility services. If you use others, append
ours with a `:` separator instead.

Without accessibility, only volume and media keys work.

## 6. Same network

Connect the projector (*Settings > Network*) and your phone to the **same Wi-Fi network**. Guest
networks and "AP isolation" usually block phone-to-device traffic. The projector can also be the
hotspot or be on Ethernet; the app picks the right address automatically.

## 7. Open the remote on the phone

Keep M2 Smart Remote open on the projector. On the phone, either:

- scan the QR code with the camera app and open the link in **Chrome**, or
- type the address shown (`http://192.168.x.x:8765/`) into Chrome.

## 8. Pair

If you scanned the QR code, pairing happens automatically. Otherwise type the 16-character code
shown under the QR (dashes are optional; O/0 and I/1 are treated the same) and tap **Pair**.

- Pairing only works while the projector is showing the M2 Smart Remote screen.
- Each code works once and changes after every pairing, after 5 wrong attempts, and every 10 minutes.
- **Remember this phone** keeps the pairing after you close the tab (stored in Chrome's local
  storage). Otherwise it lasts until the tab is closed.

The status pill turns green (**Connected**). Tip: *Chrome menu > Add to Home screen* gives you a
one-tap icon.

## 9. Test navigation

1. Press **Home** on the phone. The projector should go to its launcher.
2. Press the arrows. A blue outline (or the app's own focus highlight) moves between items.
3. Press **OK** to open the selected item, and **Back** to return.
4. Hold an arrow to repeat. Releasing stops immediately.
5. Try **Touchpad** mode: drag to move the white cursor, tap to click, use two fingers (or the
   strip on the right) to scroll.
6. Try volume +/−. The projector shows its volume bar.

## 10. Stop and revoke

- **On the phone:** *⋮ > Disconnect this phone* revokes this phone's session.
  *⋮ > Stop server on projector* shuts the server down; it can then only be restarted on the
  projector.
- **On the projector:** **Stop remote server** (also in the notification), and
  **Disconnect all phones** to revoke every session. *Settings* lists paired phones and can
  generate a new pairing code.

Stopping the server persists across reboots: it stays off until you press **Start remote server**.
With *Start the remote server when the projector boots* on (the default), an enabled server comes
back after a reboot.
