# Boson Identity

The Android app that holds a user's Boson identity: the user key, the devices it authorizes, the
account's security, and, for node operators, the node's administration.

The design is in `plans/2026-10-03-boson-identity-android-app.md` (BosonNetwork plans folder).

## What it does

- **Identity:** create or restore an identity (key file, QR code, text, or moved from an old phone),
  back up the key and check the backup, keep several identities and switch between them.
- **Protection:** the user key is a 32-byte seed sealed by an Android Keystore AES-256-GCM key that
  needs the fingerprint or screen lock for every use; the app's own device key stays usable for 5
  minutes after unlocking. An opt-in passkey backup keeps a copy in Block Store that a synced passkey
  opens on a new phone.
- **Home node:** join by QR code or address, through the node's proof of work or as an existing member;
  see its status and the plan; publish the profile as a public Boson DID Card through the node's web
  gateway, which keeps announcing it while the phone is off.
- **Devices:** list, rename and remove devices; approve new ones by their pairing code, handing over the
  identity key only to devices that ask for it; approve web sign-ins ("Sign in with Boson Identity").
- **Security:** the account passphrase and its recovery codes, resetting a forgotten one, linked
  accounts, the security log, renewing this phone's device key.
- **Administration:** for a node's administrators, the Dashboard: admin mode (a 10-minute token issued
  with the user key, ended when the page is left) and the node's users, plans, federation and blacklist.
- **Settings:** theme and language (English, Simplified Chinese), this phone, about.

The app has four tabs: Identity (the profile and the home node), Devices, Security (a summary of what
needs attention, and each protection with its status) and Settings. Ids are shown abbreviated.

## Modules

| Module | Contents |
|---|---|
| `app` | Activity, navigation, dependency injection |
| `core:model` | Errors and identity models |
| `core:vault` | Keystore protection, the system authentication prompt, the vault file, key backup formats |
| `core:boson` | Connections to Directors (guest, device and user clients) and the web gateway; profile cards |
| `core:designsystem` | The Boson brand theme, identicons, ids, shared components |
| `core:qr` | QR codes and scanning |
| `core:backup` | The opt-in passkey backup: Credential Manager PRF and Block Store |
| `feature:onboarding` | Create, restore, move from an old phone, back up, protect, join a node |
| `feature:identity` | The profile, its public card, the home node and the way into administration |
| `feature:devices` | Devices, device approval, web sign-in approval |
| `feature:security` | Passphrase, recovery, linked accounts, security log, this phone's key, passkey backup |
| `feature:settings` | Theme, language, this phone (moving, removing the identity), about |
| `feature:admin` | The admin page: admin mode and the node's administration |

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
