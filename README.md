# FORM HR Bridge

FORM HR Bridge turns a Wear OS watch into a standard Bluetooth Low Energy Heart Rate Sensor. It is designed to test whether FORM goggles will accept heart-rate notifications from a software peripheral instead of a Polar Verity Sense.

## What the build does

- Advertises the standard Heart Rate service (`0x180D`).
- Hosts Heart Rate Measurement (`0x2A37`, notify) and Body Sensor Location (`0x2A38`, read).
- Sends the standard two-byte packet for ordinary values; for example, 145 BPM is `00 91`.
- Uses the confirmed working Polar OH1 fingerprint from the watch's public controller address.
- Sends only live heart rate from the Pixel Watch through Wear OS Health Services; there is no manual mode or placeholder BPM.
- Runs direct heart-rate capture and the FORM bridge in a foreground health service, so streaming continues at the watch face or while another app is open.
- Uses the Pixel Watch's wake-up `Sensor.TYPE_HEART_RATE` source without claiming the system exercise slot, allowing a separate watch workout app to run concurrently.
- Waits for the first real watch measurement before it begins advertising to FORM.
- Shows BLE connections and notification subscriptions on the watch.

## Run the hardware test

1. Enable wireless debugging on the watch and connect ADB to its current IP and port.
2. For the first installation (or any APK update), deploy and start everything from PowerShell:

   ```powershell
   .\tools\deploy-to-watch.ps1 -Device "WATCH_IP:ADB_PORT"
   ```

   After a watch reboot, the APK remains installed; only restart its embedded public-address helper:

   ```powershell
   .\tools\start-public-address-bridge.ps1 -Device "WATCH_IP:ADB_PORT"
   ```

   Replace `WATCH_IP:ADB_PORT` with the address and port shown in the watch's wireless debugging settings.

3. Wear the watch snugly, open FormHR, then tap **Start watch HR** and grant heart-rate access. The app starts advertising only after the watch produces a real measurement. You can then return to the watch face or open another app; the ongoing notification indicates that FormHR is still active and includes a **Stop** action.
4. In FORM, pair `Polar OH1 37447F17` if it is not already saved.

If FORM sees Test 3 but pairing fails, leave the broadcast running long enough to inspect the on-watch **GATT TRACE**. It reports whether FORM connected, which characteristics it read, whether it enabled `2A37` notifications, and the disconnect status.

The app temporarily changes the watch's system Bluetooth name because Android's legacy BLE advertising API cannot provide an arbitrary per-advertisement local name. The original name is restored when broadcasting stops and is also recovered when the app next opens after an interrupted run.

The public-address mode is the confirmed working FORM path. FORM paired, subscribed to Battery Level (`2A19`) and Heart Rate Measurement (`2A37`), and displayed the watch's transmitted BPM. The helper requests `PUBLIC_DEVICE_ADDRESS` through Android's signature-only `BLUETOOTH_PRIVILEGED` API; the regular APK sends live BPM values to it over an on-watch loopback socket. Its bytecode is bundled in the APK, it continues running after the ADB session disconnects, and the app automatically reconnects after a temporary helper interruption. The helper must still be launched again after the watch reboots.

`1234567C` is intentional: unlike the earlier placeholder `12345678`, its final hexadecimal nibble satisfies Polar's published device-ID checksum algorithm. The manufacturer packet in Test 3 embeds the matching seven-digit prefix `1234567`. Test 4 uses `37447F17`, which is the checksum-expanded form of the `37447F` identity in Polar's public OH1 packet example.

## Current boundaries

- A stock third-party Wear OS APK cannot request the public Bluetooth advertiser address. Test 6 therefore needs the included ADB helper after each reboot. A fully standalone build would require a rooted/system-app watch installation or an external BLE peripheral.
- Test 3 exposes the public Verity Sense standard-service fingerprint: HRS, BAS, and DIS. It does not emulate Polar's proprietary PMD service.
- Direct heart-rate delivery is watch firmware dependent. The Pixel Watch exposes a wake-up `TYPE_HEART_RATE` sensor, but other Wear OS models may not provide the same background behavior.
- BLE peripheral mode is hardware/firmware dependent. The app reports a clear error if the watch has no advertiser support.

## Build from PowerShell

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

The debug APK is generated at `app\build\outputs\apk\debug\app-debug.apk`.
