# Marco

Find a misplaced phone in the room from another phone. You call "Marco", it answers "Polo".

Open **Find** on one phone. A hot/cold gauge driven by Bluetooth signal strength, with beeps that speed up as you get closer, leads you to the other phone. Tap **Ring** and it plays its alarm at full volume, even on silent, while vibrating and flashing for 30 s. No internet, account or Google services needed.

## Install

On each phone (Android 12+), download `marco-v*.apk` from [Releases](../../releases) and open it. Allow installing apps from your browser or file manager when asked.

## Pair

1. Open Marco on both phones and allow Bluetooth and notifications.
2. On phone A, tap **Show pairing code**. On phone B, tap **Scan pairing code**.
3. Check that the 6-digit safety codes match, name the other phone, then tap **Pair** on B and **Done** on A.

To pair more phones, use **Pair another phone** on the home screen. Then accept the **Do Not Disturb** and **battery** cards and try **Test ring**.

## Find

Tap **Find &lt;name&gt;** and walk slowly with the phone held in front of you, pausing for about a second at each spot. When it reads **Hot**, tap **Ring**, or turn on **Auto-ring when close**.

## How it works

Each pairing shares a random 256-bit key through the QR code. The phone being found advertises a Bluetooth ID derived from that key that changes every 15 minutes, so only its paired phones can recognize it. Ring commands are signed with that key over a single-use challenge, so a recorded command can't be replayed. No system Bluetooth pairing is involved.

## Troubleshooting

- **Never found:** check that Bluetooth is on on both phones. Some brands kill background apps, so allow the battery exemption (see [dontkillmyapp.com](https://dontkillmyapp.com)).
- **Silent under Do Not Disturb:** grant Marco Do Not Disturb access.

## Build & release

Build locally with a JDK 17–25 and the Android SDK (platform 35): `./gradlew assembleDebug`.

Pushing a `v*` tag builds a signed APK and publishes it as a GitHub release. This needs the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.
