# Boson Identity

The Android app that holds a user's Boson identity: the user key, the devices it authorizes, the
account's security, and, for node operators, the node's administration.

The design is in `plans/2026-10-03-boson-identity-android-app.md` (BosonNetwork plans folder).

## What works so far (phases 0 and 1)

- **Create or restore an identity.** A new key is made on the phone. A restored one comes from the
  key file the user portal writes, the key's text (Base58 or hex), or its QR code.
- **Back up the key** as a file, a QR code or text, then check the backup by two 4-character groups
  of the key. Key screens are kept out of screenshots and the recent-apps preview.
- **Protect it.** The user key is a 32-byte seed sealed by an Android Keystore AES-256-GCM key that
  needs the fingerprint or screen lock for every use. The app's own device key is sealed by a key
  that stays usable for 5 minutes after the phone or app was unlocked.
- **Join a home node:** by its QR code or address. A new identity registers with the node's proof of
  work, with this phone as its first device. A restored identity the node already knows adds this
  phone as a device, authorized by the user key.
- **Identity screen:** the identity, its home node, and a protection checklist (backup, screen lock,
  node, passphrase, recovery).

## Modules

| Module | Contents |
|---|---|
| `app` | Activity, navigation, dependency injection |
| `core:model` | Errors and identity models |
| `core:vault` | Keystore protection, the system authentication prompt, the vault file, key backup formats |
| `core:boson` | Connections to Directors: guest, device and user clients |
| `core:designsystem` | The Boson brand theme, identicons, ids, shared components |
| `core:qr` | QR codes and scanning |
| `feature:onboarding` | Create, restore, back up, protect, join a node |
| `feature:identity` | The identity screen |

## Building

Requires JDK 17 and the Android SDK (compile SDK 36). The app runs on Android 11 (API 30) and later.

```
./gradlew assembleDebug testDebugUnitTest
```

The Boson libraries come from Maven Central, or from `mavenLocal()` for an unreleased snapshot
(`mvn clean install` in the Boson repository).

The debug build allows cleartext HTTP to `10.0.2.2` and `localhost`, so it can talk to a local test
Director from the emulator. The emulator needs a screen lock (for example
`adb shell locksettings set-pin 1111`), since keys can only be protected on a phone that has one.
