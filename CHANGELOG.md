# Changelog

All notable changes to Boson Identity are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and Boson Identity is pre-1.0: until 1.0,
minor versions may change its storage and the requests other apps make to it.

## 0.2.0 - Unreleased

First public release. Built on the Boson Network stack 3.2.0. Requires Android 13 (API 33) or later.

### Identity

- Create an identity on the phone, or restore one from a key backup: a key file, a QR code or typed
  text, read with its row numbers.
- Back up the key as a file, a QR code or numbered rows of groups, and check the backup by typing
  groups back.
- Move an identity to a new phone with one scan and a code.
- Keep several identities on one phone and switch between them, each shown with its avatar.
- Join a home node by its QR code or address, through the node's proof of work or as an existing
  member, and see its status and your plan.
- A profile with a name, bio and avatar, kept on the phone first and synced with the home node.
- A public profile card, published through the home node's web gateway, which keeps announcing it
  while the phone is off.
- One Scan button for a new device's pairing code, a web page's sign-in code and a node console's
  administrator enrollment code. User IDs are shown as `did:boson:` IDs.

### Devices

- List, rename and remove the devices of your account.
- Approve a new device by its pairing code. The user key goes only to a device that asks for it,
  sealed to its pairing code.
- Approve a web page's "Sign in with Boson Identity": pick the number the page shows, and approve
  with your key.

### Security

- A Security tab with a summary of what needs attention, then each protection with its status.
- The account passphrase, its recovery codes, and resetting a forgotten one.
- Linked sign-in accounts, returning through a verified App Link.
- The security log, with new events marked until you review them.
- An opt-in passkey backup: a copy of the user key in Block Store that a synced passkey opens on a
  new phone, turned on only after your own backup is checked.
- Renewing this phone's device key.

### Administration

- Become a node's administrator by scanning the code its console shows: claim it with your key and
  show the number the console asks for.
- The Dashboard, for administrators: the node's overview, users, plans, federation and blacklist,
  in admin mode - a 10-minute session issued with your user key, which ends when you leave the page.

### Requests from other apps

- Other apps on the phone can ask for an identity's home node, and ask to be approved as a new
  device of an identity. The user sees which app asks and decides. See
  [docs/app-requests.md](docs/app-requests.md).

### Application

- Four tabs: Identity, Devices, Security and Settings.
- The Boson brand theme in light and dark; English and Simplified Chinese.
- The user key is sealed by an AES-256-GCM Android Keystore key (in StrongBox where the phone has
  one) that asks for your fingerprint or screen lock on every use; this phone's device key stays
  usable for 5 minutes after unlocking.
- Release builds talk HTTPS only, and the identity never leaves through Android backup.
