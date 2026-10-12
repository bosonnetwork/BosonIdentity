/*
 * Copyright (c) 2023 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.bosonnetwork.identity

/**
 * What other apps on the phone may ask Boson Identity, by an explicit intent to this package, started
 * for a result. Documented for app developers in `docs/app-requests.md`; the names are part of that
 * API and never change.
 *
 * Every request names its caller ([android.app.Activity.getCallingPackage]); Boson Identity shows the
 * app's name and package and the user decides. Only public data leaves through a result: no key or
 * secret ever does.
 */
object AppRequests {
    /**
     * Asks for an identity's home node, so that the app joins the same node. The user picks the
     * identity (proposed: [EXTRA_USER_ID] if given). Result: [EXTRA_USER_ID] and [EXTRA_HOME_NODE].
     */
    const val ACTION_GET_HOME_NODE = "io.bosonnetwork.identity.action.GET_HOME_NODE"

    /**
     * Asks to approve the app as a new device of an identity, from the pairing code its request on the
     * home node got ([EXTRA_PAIRING_CODE]). The identity is [EXTRA_USER_ID] if it is on this phone,
     * otherwise the user picks. Result: [EXTRA_USER_ID] once approved.
     */
    const val ACTION_APPROVE_DEVICE = "io.bosonnetwork.identity.action.APPROVE_DEVICE"

    /** An identity's id, Base58. */
    const val EXTRA_USER_ID = "io.bosonnetwork.identity.extra.USER_ID"

    /** The home node, as its Boson string `boson:supernode:1:<node id>:<url>`. */
    const val EXTRA_HOME_NODE = "io.bosonnetwork.identity.extra.HOME_NODE"

    /** The pairing code, as its Boson string `boson:pair:1:...`. */
    const val EXTRA_PAIRING_CODE = "io.bosonnetwork.identity.extra.PAIRING_CODE"

    /** Why a request ended without a result: one of the `ERROR_` values. */
    const val EXTRA_ERROR = "io.bosonnetwork.identity.extra.ERROR"

    /** The user left without answering. */
    const val ERROR_CANCELED = "canceled"

    /** The request isn't one of these, misses what it needs, or wasn't started for a result. */
    const val ERROR_INVALID_REQUEST = "invalid_request"

    /** There is no identity on this phone. */
    const val ERROR_NO_IDENTITY = "no_identity"

    /** No identity on this phone has a home node yet. */
    const val ERROR_NO_HOME_NODE = "no_home_node"

    /** The user denied the device. */
    const val ERROR_DENIED = "denied"

    /** The pairing request expired, or was already answered, before it was approved. */
    const val ERROR_EXPIRED = "expired"
}
