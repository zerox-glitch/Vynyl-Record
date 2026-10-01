# Privacy

**Vynyl Record collects nothing, sends nothing, and has no way to send anything.**

That is not a policy the app promises to follow; it is a property of the installed APK. The manifest does not
request the `INTERNET` permission, so Android denies every socket this app could ever try to open, in every
build, whatever the code does. There is no account, no login, no server address, no API key and no
configuration file: after installation the app never needs a network connection for any feature, and it runs
completely in airplane mode.

This document says the same thing in more detail, and ends with how to check it for yourself.

## What is on the device, and where

Everything the app knows lives in the app's own private storage (`/data/data/<package>/`), which other apps
cannot read and which is deleted when the app is uninstalled.

| What | Where | Notes |
| --- | --- | --- |
| The recording (source audio) | `files/records/{recordId}/source.wav` | uncompressed PCM, so it can be re-pressed without generation loss |
| The pressed master | `files/records/{recordId}/master.wav` | |
| Cover artwork | `files/records/{recordId}/cover.png` | drawn on the device |
| Waveform peaks | `files/records/{recordId}/waveform.json` | 768 numbers |
| Metadata: titles, names, dedications, occasions, dates, preset, style, render state | Room database `vynyl.db` | **metadata only — never audio** |
| Imported background music, textures, effects | `files/assets/{id}.wav` | copies of files the user picked |
| Exports | `files/exports/` | until the user saves or shares them |
| Scratch: renders in progress, staged assets, share copies | `cache/` | deleted as soon as it is no longer needed, and by "Clear temporary files" |
| Settings | DataStore `vynyl.preferences_pb` | quality, format, graphics, lock, layout choices |

Nothing is written outside those directories except a file the user explicitly saves through the system file
picker (SAF), and a copy handed to another app through `FileProvider` when the user shares an export.

## Permissions, and what each one is for

The app requests four permissions. Each is used, and none of them reaches the network, the file system at
large, or the user's other data.

| Permission | Why | When it is asked for |
| --- | --- | --- |
| `RECORD_AUDIO` | to record the voice | the first time the record button is pressed — never at launch |
| `POST_NOTIFICATIONS` | the press progress notification | the first time a press starts (Android 13+). Refusing it costs the notification, not the press |
| `FOREGROUND_SERVICE` | so a press survives the user leaving the screen or locking the phone | declared, not requested |
| `FOREGROUND_SERVICE_DATA_SYNC` | the service type for that worker — a file being written, not media playback and not the microphone | declared, not requested |

Deliberately **not** requested: `INTERNET`, `ACCESS_NETWORK_STATE`, storage permissions
(`READ_/WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`), location, contacts, phone state, camera, the
advertising ID, and anything for analytics or crash reporting. `android:allowBackup` and
`android:fullBackupContent` are both **false**, so the system will not copy the library off the device either.

`AppContractTest` (instrumentation) asserts all of this against the merged manifest of the installed APK,
including a blocklist of the permissions above.

## Sharing and exporting: the user's decision, every time

* **Share** stages a copy of an export in the cache and hands a single `content://` URI to the app the user
  picks in the Android Sharesheet. The provider is not exported, and the grant is for that one file. The
  receiving app is the user's choice, and what it does with the file afterwards is outside this app's control
  — the app tells the user that plainly rather than implying otherwise.
* **Save to a folder** goes through `ACTION_CREATE_DOCUMENT`, so the user picks the destination and the app
  writes exactly one file there.
* **Back up / restore** goes through `ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT` too: the backup archive
  is a ZIP the user chooses the location of. There is no automatic backup, no cloud target and no scheduled
  upload.
* There are no share links, no QR codes and no upload tokens. A record shared with someone else is a file the
  user sent through their own messaging app.

## The optional lock

If the user turns on the lock in Settings, the app requires the device's biometric or the device credential
(`BIOMETRIC_STRONG | DEVICE_CREDENTIAL`) before showing the library. The biometric itself is handled entirely
by the platform: this app never sees, stores or transmits a fingerprint or face template, and it cannot —
`BiometricPrompt` reports only success or failure. The lock has a configurable timeout, and if the device has
no biometric hardware the credential fallback is used rather than locking the user out of their own records.
Notifications can additionally hide the record's title, for a locked screen other people can see.

## Deleting things

* **Deleting a record** removes its audio, artwork and waveform, then its row. It is one deletion, and it
  means what it says.
* **Clear temporary files** removes render scratch, staged assets and share copies. It never touches records
  or exports.
* **Uninstalling** the app removes everything in its private storage. That is why the app says so in
  onboarding (page three) and offers a backup: records that were never exported are gone for good.

Deletions are immediate and not recoverable by the app. There is no trash, no undo history and no shadow
copy — but a delete of a record asks for confirmation first, and duplicates and exports are how the user is
expected to keep a second copy of anything precious.

## What the app does not do

* No analytics, telemetry, crash reporting, attribution or A/B testing of any kind.
* No advertising ID, no device identifiers, no fingerprinting.
* No hosted AI: the vinyl character is a deterministic DSP chain on the device, not a model in a data centre.
* No accounts, profiles, sessions or cloud sync.
* No remote configuration, feature flags or update checks. The app never calls out, so it cannot be told to.
* No collection of anything about the people whose voices are recorded. The recording is a file on the device
  that owns it.

## Children

The app is a personal device tool rather than a service; it has no accounts and no data collection to consent
to. A child's voice recorded by a parent stays on that parent's phone until the parent exports it or deletes
it. Because there is no server, there is no third party for that recording to reach.

## How to verify this yourself

You do not have to take the app's word for any of it:

1. **Airplane mode.** Turn on airplane mode, force-stop the app, and use it: record, press, play, export,
   restore a backup. Everything works.
2. **The permission dump.**
   ```bash
   adb shell dumpsys package com.vynylrecord.app.debug | grep -A 20 "requested permissions"
   ```
   `INTERNET` is not in the list. Neither is a storage or location permission.
3. **The manifest in the APK.**
   ```bash
   adb shell aapt2 dump permissions app-debug.apk   # or: aapt dump permissions
   ```
4. **Watch the sockets.** With the app running, `adb shell dumpsys netstats detail | grep vynylrecord`
   shows nothing: an app without `INTERNET` cannot create a connection at all.
5. **The source.** `AndroidManifest.xml` in this repository is short and commented; there is no network
   library in `app/build.gradle.kts` and no HTTP client anywhere in `src/main`.

## If this ever changes

A future feature that needed a network — a shared listening link, for example — would require adding the
`INTERNET` permission, a server, an account model and a privacy policy that describes them. That would be a
different product, and it would be visible in this repository's manifest as a one-line diff rather than
something that quietly appears in a release. Until then, the statement at the top of this document is a
property of the software, not a promise.
