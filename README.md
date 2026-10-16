# Boson Identity

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2013%2B-green.svg)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-purple.svg)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue.svg)](https://developer.android.com/jetpack/compose)

Boson Identity is the Android app that holds your [Boson](https://bosonnetwork.io) identity. It keeps
your user key on the phone, under the phone's own lock, and uses it to approve everything that acts in
your name: new devices, web sign-ins, and your account's security. If you run a Boson super node, it is
also where you administer it.

On Boson, your identity is a key pair you hold, not an account a server holds for you. A super node
(your *home node*) hosts your account and services, but it never sees your user key.

- **Status:** early release (0.2.1); see the [CHANGELOG](CHANGELOG.md). Runs on Android 13 (API 33)
  and later.
- **Languages:** English, Simplified Chinese.

## Contents

- [Features](#features)
- [How your key is protected](#how-your-key-is-protected)
- [For app developers](#for-app-developers)
- [Build from source](#build-from-source)
- [Testing](#testing)
- [Contributing](#contributing)
- [License](#license)

## Features

The app has four tabs: **Identity**, **Devices**, **Security** and **Settings**.

**Identity**
- Create an identity, or restore one from a key backup (file, QR code or typed text). Move one from an
  old phone with one scan and a code.
- Keep several identities on one phone and switch between them.
- Join a home node by its QR code or address, through the node's proof of work or as an existing member.
- Edit your profile (name, bio, avatar). The profile lives on the phone first and syncs with the home
  node.
- Publish your profile as a public card that anyone can look up by your ID. The home node's web
  gateway keeps announcing it while your phone is off.
- One **Scan** button reads every code the app answers: a new device's pairing code, a web page's
  sign-in code, and a node console's administrator enrollment code.

**Devices**
- List, rename and remove the devices of your account.
- Approve a new device by its pairing code. The user key goes only to a device that asks for it, sealed
  to its pairing code.
- Approve a web page's "Sign in with Boson Identity": pick the number the page shows, and approve with
  your key.

**Security**
- A summary of what needs attention, then each protection with its status.
- The account passphrase, its recovery codes, and resetting a forgotten one.
- Linked sign-in accounts (OAuth).
- The security log, with new events marked until you review them.
- An opt-in passkey backup of your key, and renewing this phone's device key.

**Administration** (for node administrators)
- Become a node's administrator by scanning the code its console shows.
- The Dashboard: the node's overview, users, plans, federation and blacklist. It works in admin mode:
  a 10-minute session issued with your user key, which ends when you leave the page.

**Settings**
- Theme and language, this phone (moving the identity to a new phone, removing it), and about.

## How your key is protected

- **Per use.** Your user key is sealed by an AES-256-GCM key in the Android Keystore (StrongBox when the
  phone has one). Every use asks for your fingerprint or screen lock.
- **This phone's device key** stays usable for 5 minutes after you unlock it, so routine requests to
  your home node don't prompt each time.
- **The key leaves the phone only when you say so:**
  - as a backup you make;
  - sealed to a new device you approve that asks for it;
  - when you move the identity to a new phone.

  Your home node never sees it.
- **Passkey backup (opt-in).** A copy of the key in Google's Block Store, opened by a synced passkey
  on a new phone. It can be turned on only after you have saved and checked your own backup, and it is
  never used to sign in.

## For app developers

Other apps on the same phone can ask Boson Identity for an identity's home node, and ask it to approve
them as a new device of an identity. The user sees which app asks and decides; no key or secret passes
through these requests. See [Asking Boson Identity from another app](docs/app-requests.md) for the
intents, extras and error codes.

## Build from source

### Prerequisites

| Requirement | Version |
|---|---|
| JDK | 17 |
| Android SDK | Platform `android-36` |
| Gradle | 8.13, through the bundled wrapper (nothing to install) |
| Device or emulator | Android 13 (API 33) or later, **with a screen lock** |

The Android SDK location comes from `local.properties` (`sdk.dir=...`) or `ANDROID_HOME`. Android
Studio writes `local.properties` for you when it first opens the project. The Boson libraries come from
Maven Central with the other dependencies, so there is nothing else to install.

### Build the app

```bash
git clone https://github.com/bosonnetwork/BosonIdentity.git
cd BosonIdentity
./gradlew assembleDebug        # debug APK in app/build/outputs/apk/debug/
./gradlew installDebug         # build and install on a connected device or emulator
```

**On an emulator,** set a screen lock first, since keys can only be protected on a phone that has one:

```bash
adb shell locksettings set-pin 1111
```

The debug build allows cleartext HTTP to `10.0.2.2`, `localhost` and `127.0.0.1`, so the emulator can
reach a test super node running on your computer. Release builds are HTTPS only.

### Release builds

```bash
./gradlew assembleRelease
```

Release builds are minified and resource-shrunk with R8. Signing material is never committed; supply it
as Gradle properties or environment variables:

| Property / env var | Meaning |
|---|---|
| `IDENTITY_KEYSTORE_FILE` | Path to the keystore |
| `IDENTITY_KEYSTORE_PASSWORD` | Keystore password |
| `IDENTITY_KEY_ALIAS` | Signing key alias |
| `IDENTITY_KEY_PASSWORD` | Signing key password |

Without them, `assembleRelease` still produces an unsigned APK.

### Notes

- `build-logic` holds a small Gradle plugin that every module applies. Vert.x 5.1 cannot start on
  Android without it: the plugin opens a private field that ART refuses to reach.
- The Boson stack (Vert.x, Netty, Jackson) is dexed into the app, so Java 8+ API desugaring is on, and
  overlapping `META-INF` files are excluded from packaging (see the root `build.gradle.kts`).
- A full build takes a lot of memory: `gradle.properties` gives Gradle 6 GB.

## Testing

```bash
./gradlew testDebugUnitTest    # JVM unit tests, all modules
./gradlew lintDebug            # Android Lint
```

The unit tests run on the JVM (MockK, Turbine) and need no device or node.

## Contributing

Contributions are welcome - code, documentation, translations, and bug reports alike.

1. Fork the repository and create a feature branch.
2. Make your change and add tests where applicable.
3. Ensure `./gradlew testDebugUnitTest lintDebug` passes.
4. Open a pull request describing the change and how you verified it.

**Reporting issues.** Open a GitHub issue with the app version (Settings shows it), your Android
version and device, and clear reproduction steps.

**Reporting vulnerabilities.** Please do *not* open a public issue for a security problem. Email
[support@bosonnetwork.io](mailto:support@bosonnetwork.io) with the details and give us a chance to
ship a fix first.

Please read the [Code of Conduct](CODE_OF_CONDUCT.md) before contributing.

## License

[MIT](LICENSE)
