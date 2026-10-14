# Asking Boson Identity from another app

Boson Identity keeps a user's Boson identities on their phone. Other apps on the same phone can ask it
for two things:

- **an identity's home node**, so that the app joins the same node;
- **approval of the app as a new device** of an identity, from the pairing code the app got from the
  node.

Any app may ask. Boson Identity shows the user which app asks (its name and package, as Android reports
the caller), and the user decides. Only public data comes back. No key or secret ever passes through
these intents: approval hands a user key over, when the app's request asks for one, sealed to the
pairing code and carried by the node.

## Calling

Address Boson Identity by package and action, and start the intent **for a result**
(`registerForActivityResult(StartActivityForResult())` or `startActivityForResult`). An intent not
started for a result has no caller to show, and is refused (`invalid_request`).

```kotlin
val intent = Intent("io.bosonnetwork.identity.action.GET_HOME_NODE").setPackage("io.bosonnetwork.identity")
```

On Android 11 and later, declare the package in your manifest, so that you can see it:

```xml
<queries>
    <package android:name="io.bosonnetwork.identity" />
</queries>
```

Check that the action resolves (`packageManager.resolveActivity(intent, 0) != null`) before offering it:
an older Boson Identity doesn't answer these actions.

## Actions

All names start with `io.bosonnetwork.identity.`; extras are strings.

### `action.GET_HOME_NODE`

| | Extra | |
| --- | --- | --- |
| In (optional) | `extra.USER_ID` | The identity to propose, Base58. The user may choose another. |
| Out, `RESULT_OK` | `extra.USER_ID` | The identity the user chose, Base58. |
| | `extra.HOME_NODE` | Its home node, as the Boson string `boson:supernode:1:<node id>:<url>`. |

The user picks the identity; one without a home node can't be chosen.

### `action.APPROVE_DEVICE`

| | Extra | |
| --- | --- | --- |
| In | `extra.PAIRING_CODE` | The pairing code, `boson:pair:1:<registration id>:<key>`, from the app's device registration request on the identity's home node. |
| In (optional) | `extra.USER_ID` | The identity to join, Base58. |
| Out, `RESULT_OK` | `extra.USER_ID` | The identity that approved the device. |

The identity is the one asked for when it is on the phone, else the only one, else the user picks.
The user then reviews the request as with a scanned code, and approves or denies it. Keep waiting for
the registration's answer from the node as usual: `RESULT_OK` says the user approved, the node's answer
is what completes the registration.

## Errors

`RESULT_CANCELED` carries `extra.ERROR`:

| Value | |
| --- | --- |
| `canceled` | The user left without answering. |
| `invalid_request` | Not one of these actions, a missing or malformed extra, or not started for a result. |
| `no_identity` | There is no identity on this phone. |
| `no_home_node` | No identity on this phone has a home node yet. |
| `denied` | The user denied the device. |
| `expired` | The pairing request expired, or was already answered, before it was approved. |

These names are part of the API and don't change; new values may be added.
