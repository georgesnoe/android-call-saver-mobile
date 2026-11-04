# Call Saver

Background Android app that records cellular calls, stores them on the phone first, and uploads them to a company API with a durable retry queue.

- No launcher entry and no app icon.
- Starts automatically after boot through a foreground service.
- No runtime UI. `SetupActivity` exists only for one-time permission provisioning and has no launcher icon.
- Local-first uploads: a file is deleted only after the server returns a 2xx response.
- Works offline: recordings stay on disk and retry with exponential backoff when the network returns.
- Built by GitHub Actions and published as a GitHub Release APK.

---

## Read this first: Android call-audio restrictions

This is the one part of the request that Android itself limits.

On **stock, unrooted Android 10 and newer**, a normal third-party app cannot reliably capture both sides of a cellular call:

- `VOICE_CALL`, `VOICE_UPLINK` and `VOICE_DOWNLINK` require the signature/privileged `CAPTURE_AUDIO_OUTPUT` permission.
- Declaring that permission in the manifest is not enough. It is only granted to a privileged/system app.
- `MIC` and `VOICE_COMMUNICATION` are generally available, but during a cellular call the OS gives the call audio priority and some devices silence third-party capture.
- Google restricts using Accessibility services for call recording, and Play policy treats call recording as sensitive.

The app therefore tries, in order: `VOICE_CALL` → `VOICE_COMMUNICATION` → `VOICE_RECOGNITION` → `MIC` → `DEFAULT`, and logs which source worked. It will record **where the device permits it**. On many stock devices the fallback records only your side of the conversation, or very little.

For guaranteed two-way recording, use one of these deployment models:

1. A company-owned/rooted or privileged device where `CAPTURE_AUDIO_OUTPUT` is granted (install as a system app with a privapp allow-list entry).
2. A device/OEM with a built-in call recorder, with this app used only as the uploader (not implemented as an importer).
3. A server-side telephony/VoIP provider that records at the network.
4. An external recorder.

This app intentionally targets **SDK 33**. Android 14+ blocks starting a `microphone` foreground service from `BOOT_COMPLETED` for apps targeting 34+, and Android 15 extends those restrictions. Targeting 33 keeps the boot-start design working on current devices. If you later move to target 35+ (required for Play distribution), the boot/recording architecture must change. Test on your exact phone models and OS builds before relying on this for customer records.

Also comply with local consent/law for recording calls.

---

## How it works

```
BOOT_COMPLETED / MY_PACKAGE_REPLACED
        │
        ▼
CallSaverService (foreground, microphone type, START_STICKY)
        │
        ├── TelephonyCallback / PHONE_STATE receiver → detect ringing/offhook/idle
        ├── CallRecorder → MediaRecorder → internal filesDir/recordings
        │       each part: call_<time>_<session>_pN.aac + .json sidecar
        │       rotates at 45 MiB (server limit is 50 MB)
        │
        ├── call ends → CallLog + Contacts lookup → type/phoneNumber/name
        │       every part in the session is marked ready=true
        │
        └── UploadService (foreground, dataSync)
                POST multipart/form-data to API_BASE_URL + API_UPLOAD_PATH
                headers: x-access-code (configurable), Idempotency-Key
                ★ delete audio only after HTTP 2xx ★
```

Details that matter:

- Audio is AAC in an ADTS container (`.aac`). ADTS remains decodable if the process dies mid-recording; MPEG-4/AAC (`.m4a`) is used automatically if a device rejects ADTS.
- Files are written to app-internal storage, so no storage permission is needed.
- Every part has an atomically written JSON sidecar with retry state. A crash cannot leave a half-written sidecar.
- On startup the app repairs state: cleans completed uploads (`.done` markers), adopts audio files that lost their sidecar, and releases interrupted sessions.
- The uploader sends `Content-Length` (OkHttp file body), `Idempotency-Key`, `X-Recording-Id` and `X-Content-SHA256`. A retry after a timeout can be deduplicated by the server.
- Failed uploads retry with 30 s → 6 h backoff, indefinitely. The file is kept.
- `413`, `400`, `401`, `403` etc. are kept for a long retry (24 h) instead of deleting data.
- One upload runs at a time; the upload foreground service holds a wake lock until it finishes.
- `START_STICKY` plus boot receivers bring the watcher back. Battery whitelisting and OEM autostart settings are still required on many phones.

---

## Build configuration

All values are **Gradle build-time environment variables**. They are compiled into `BuildConfig`; changing them requires a rebuild. They are read from an environment variable or a Gradle `-P` property of the same name.

| Variable | Required | Default | Purpose |
|---|---:|---|---|
| `API_BASE_URL` | yes | — | e.g. `https://api.company.com` |
| `API_UPLOAD_PATH` | no | `/api/upload` | Path appended to the base URL |
| `API_ACCESS_CODE` | yes | — | Value sent in the access-code header |
| `API_ACCESS_HEADER` | no | `x-access-code` | Header name |
| `DEFAULT_COUNTRY_CODE` | no | `+228` | Prefix for local numbers |
| `RECORDING_AUDIO_SOURCE` | no | `auto` | `auto`, `voice_call`, `voice_communication`, `voice_recognition`, `mic`, `default` |
| `RECORDING_BITRATE` | no | `32000` | AAC bitrate (12–128 kbps) |
| `ALLOW_CLEARTEXT` | no | `false` | Permit an `http://` API (do not use in production) |
| `VERSION_NAME` | no | `1.0.0` | APK version name |
| `VERSION_CODE` | no | `1` | APK version code |
| `ANDROID_KEYSTORE_PATH` | no | — | Release keystore path |
| `ANDROID_KEYSTORE_PASSWORD` | no | — | Keystore password |
| `ANDROID_KEY_ALIAS` | no | — | Key alias |
| `ANDROID_KEY_PASSWORD` | no | — | Key password |

Example local build (PowerShell):

```powershell
$env:API_BASE_URL="https://api.company.com"
$env:API_ACCESS_CODE="your-code"
$env:API_UPLOAD_PATH="/api/upload"
.\gradlew.bat testDebugUnitTest assembleRelease
```

The APK is at `app/build/outputs/apk/release/`. Without keystore variables it is debug-signed.

`local.properties` must point at your Android SDK and is git-ignored:

```properties
sdk.dir=C\:\\Users\\you\\Android
```

---

## GitHub Actions

Workflow: `.github/workflows/android.yml`

It runs on tags matching `v*` and on manual `workflow_dispatch`, runs the unit tests, builds `assembleRelease`, uploads the APK + SHA-256 + R8 mapping as artifacts, and publishes the APK to the GitHub Release for a tag.

### Repository variables

Set these under **Settings → Secrets and variables → Actions → Variables**:

- `API_BASE_URL` (e.g. `https://api.company.com`)
- `API_UPLOAD_PATH` (optional, default `/api/upload`)
- `API_ACCESS_HEADER` (optional, default `x-access-code`)
- `DEFAULT_COUNTRY_CODE` (optional, default `+228`)
- `RECORDING_AUDIO_SOURCE`, `RECORDING_BITRATE`, `ALLOW_CLEARTEXT` (optional)

### Repository secrets

- `API_ACCESS_CODE` — required
- `ANDROID_KEYSTORE_BASE64` — base64 of your release keystore (recommended)
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Create a keystore and encode it:

```bash
keytool -genkeypair -v -keystore release.keystore -alias callsaver \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.keystore > release.keystore.b64
```

Paste the contents of `release.keystore.b64` into `ANDROID_KEYSTORE_BASE64`. If it is absent the workflow warns and builds a debug-signed APK; that APK works for testing but cannot be updated over a differently signed install. Use one stable release key for production.

Release:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The workflow rejects a non-HTTPS `API_BASE_URL` unless `ALLOW_CLEARTEXT=true`.

---

## Install and provision

The app has no launcher entry by design. One-time setup is required to grant runtime permissions:

```bash
adb install -r CallSaver-1.0.0-1.apk
adb shell am start -n com.callsaver.app/.SetupActivity
```

`SetupActivity` shows only the system permission dialogs and the battery-optimization prompt, starts the service, and finishes. It never shows an app screen.

Fully headless provisioning (still needs the permission dialog unless the device is managed/rooted):

```bash
adb shell pm grant com.callsaver.app android.permission.RECORD_AUDIO
adb shell pm grant com.callsaver.app android.permission.READ_PHONE_STATE
adb shell pm grant com.callsaver.app android.permission.READ_CALL_LOG
adb shell pm grant com.callsaver.app android.permission.READ_CONTACTS
adb shell pm grant com.callsaver.app android.permission.POST_NOTIFICATIONS   # Android 13+
adb shell cmd deviceidle whitelist +com.callsaver.app
adb shell am start -n com.callsaver.app/.SetupActivity
```

After provisioning, the boot receiver starts the service on every boot. To start it immediately without rebooting, launch `SetupActivity` again (or reboot).

### OEM notes

On Xiaomi/MIUI, Oppo/ColorOS, Vivo, Transsion (Tecno/Infinix/itel) and similar, enable the app's autostart/background-run setting and exempt it from battery optimization. Some OEMs kill foreground services unless the app is locked in the recent-apps list. These are device policy settings, not something an APK can override.

### Privileged installation (optional)

The manifest declares `CAPTURE_AUDIO_OUTPUT`, but a normal install does not receive it. On a company-owned device where you can install as a privileged app, add it to the privapp allow-list so `VOICE_CALL` can work:

```xml
<!-- /system/etc/permissions/privapp-permissions-company.xml -->
<permissions>
  <privapp-permissions package="com.callsaver.app">
    <permission name="android.permission.CAPTURE_AUDIO_OUTPUT"/>
  </privapp-permissions>
</permissions>
```

This requires a rooted/custom system image or MDM with system-app deployment. Do not assume it works on a retail phone.

---

## Verify it is working

```bash
adb logcat -s CallSaver
adb shell dumpsys activity services com.callsaver.app
```

The app writes a log (no phone numbers or access codes) to:

```
/sdcard/Android/data/com.callsaver.app/files/logs/callsaver.log
```

Pull it with:

```bash
adb pull /sdcard/Android/data/com.callsaver.app/files/logs/callsaver.log
```

Test sequence:

1. Make an answered call in each direction.
2. Confirm log lines `Call recording started`, `Audio source: ...`, `Part ... closed`.
3. Put the phone in airplane mode / disable data.
4. The upload fails and the file remains. `dumpsys` shows the service; the log shows the retry.
5. Re-enable data. The log shows `Uploaded ... local copy removed`, and the API receives the multipart request.

If `Audio source: mic` appears after `voice_call` was rejected, that is the Android restriction described at the top. Use a privileged/rooted deployment or a device where the OS allows call capture.

---

## Server contract

`POST {API_BASE_URL}{API_UPLOAD_PATH}` with `multipart/form-data`:

| Field | Value |
|---|---|
| `type` | `incoming` or `outbound` |
| `phoneNumber` | `+<country_code><number>`; falls back to `+228` / configured code |
| `name` | Contact display name, or `unknown` |
| `file` | Audio file, ≤ 50 MB |

Headers:

```
x-access-code: <API_ACCESS_CODE>
Idempotency-Key: <recording UUID>
X-Recording-Id: <recording UUID>
X-Content-SHA256: <sha256 hex>
```

Server requirements for the “never lose a call” guarantee:

1. Return **2xx only after the file is durably stored**. Return a non-2xx status on any failure.
2. Read the complete multipart body before returning 2xx. Reject incomplete uploads (most web frameworks do this automatically).
3. Accept the audio MIME types `audio/aac`, `audio/mp4`, `audio/3gpp` (the app uses AAC/ADTS first).
4. Deduplicate on `Idempotency-Key` / `X-Recording-Id` so a retry after a network timeout does not create a second record.
5. Optionally verify `X-Content-SHA256`.
6. Enforce the 50 MB limit; the app rotates at 45 MiB.

The app treats any 2xx as success and reads/closes the response. It does not parse a response body, so a `200 {"success":false}` response would be misread as success. Use HTTP status codes for success/failure.

---

## Security notes

- **The access code is not secret inside the APK.** `BuildConfig` strings can be extracted. Treat it as an app identifier, not a strong credential. Prefer per-device or short-lived tokens, TLS, server-side rate limiting and an allow-list of source networks. mTLS or a token refresh endpoint is the proper long-term design.
- Recordings are stored in app-internal storage (`filesDir/recordings`) and are not world-readable. `allowBackup=false` keeps them out of cloud backups.
- Pending audio is deleted only after a 2xx response. On success a durable `.done` marker is written first, then the audio and sidecar are removed; recovery completes the cleanup if the process dies in between.
- Logs intentionally exclude phone numbers, names, access codes and response bodies.
- Use HTTPS in production.
- Uninstalling the app or clearing its data deletes pending recordings. Upload before uninstalling.

---

## Project layout

```
app/src/main/java/com/callsaver/app/
├── CallSaverApp.kt              Application, notification channels
├── Config.kt                    BuildConfig-backed configuration, URL building
├── CallRecorder.kt              MediaRecorder, source/container fallback, rotation
├── RecordingSession.kt          Session and part records
├── PendingStore.kt              Sidecars, atomic writes, recovery, upload queue
├── CallLogResolver.kt           Call type/number/contact resolution, network check
├── PhoneNumbers.kt              E.164-ish normalization, contact lookup
├── Uploader.kt                  OkHttp multipart upload, SHA-256, progress
├── UploadService.kt             Foreground upload worker + scheduler
├── SetupActivity.kt             One-time permission provisioning (no launcher entry)
├── Logx.kt                      logcat + pullable file log
├── receiver/BootReceiver.kt
├── receiver/PhoneStateReceiver.kt
└── service/CallSaverService.kt  Always-on foreground watcher/recorder
```

## Troubleshooting

| Symptom | Check |
|---|---|
| No recording at all | `RECORD_AUDIO`/`READ_PHONE_STATE` granted; logcat for `Call recording started`; OEM autostart/battery settings |
| Recording is silent or one-sided | Expected on stock Android 10+; see the platform limitation section |
| Service disappears after reboot | Battery optimization, OEM autostart, `RECEIVE_BOOT_COMPLETED` not revoked, app not force-stopped |
| Uploads never happen | `API_BASE_URL`/`API_ACCESS_CODE` set at build time; logcat `HTTP ...`; phone has network; API reachable over HTTPS |
| `HTTP 401/403` | Wrong access code or header name; rebuild with corrected secrets |
| `HTTP 413` | Server limit lower than 50 MB; lower `maxPartBytes` in `Config.kt` and rebuild |
| APK will not install over the old one | Different signing keys; install with the same release keystore |
