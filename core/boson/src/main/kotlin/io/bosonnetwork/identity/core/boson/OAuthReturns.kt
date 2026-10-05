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

package io.bosonnetwork.identity.core.boson

import java.net.URLDecoder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** What a node sent the app back with, after a sign-in or a passphrase reset at an account provider. */
sealed interface OAuthReturn {
    /** A sign-in: the OAuth session token, to link the account to the identity. */
    data class Token(val token: String) : OAuthReturn

    /** A passphrase reset: the reset grant, to set a new passphrase with. */
    data class Grant(val grant: String) : OAuthReturn

    /** The provider or the node refused; [code] is the node's reason, such as `reset_failed`. */
    data class Error(val code: String) : OAuthReturn
}

/**
 * Returns from account providers. The app opens the provider in a browser tab, with
 * [REDIRECT_URI] as the return address; the node sends the browser there with the result in the URL
 * fragment, and Android hands that App Link to this app alone (the site's assetlinks.json names it).
 * The activity [deliver]s it, and the flow that started the round trip collects it from [returns].
 */
class OAuthReturns {
    private val _returns = MutableSharedFlow<OAuthReturn>(extraBufferCapacity = 4)

    /** The results as they arrive. */
    val returns: SharedFlow<OAuthReturn> = _returns.asSharedFlow()

    /** Hands over what an App Link carried; ignores links that carry nothing understood. */
    fun deliver(fragment: String?) {
        parse(fragment)?.let { _returns.tryEmit(it) }
    }

    companion object {
        /**
         * Where nodes send the browser back to: an https App Link, which Android gives only to this app,
         * so no other app can catch a token or a grant meant for it.
         */
        const val REDIRECT_URI = "https://bosonnetwork.io/identity/auth"

        /** Reads `token=`, `grant=` or `error=` out of a return's URL fragment. */
        fun parse(fragment: String?): OAuthReturn? {
            if (fragment.isNullOrBlank()) return null
            val params = fragment.removePrefix("#").split('&').mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq) to URLDecoder.decode(part.substring(eq + 1), "UTF-8")
            }.toMap()
            params["error"]?.takeIf { it.isNotBlank() }?.let { return OAuthReturn.Error(it) }
            params["grant"]?.takeIf { it.isNotBlank() }?.let { return OAuthReturn.Grant(it) }
            params["token"]?.takeIf { it.isNotBlank() }?.let { return OAuthReturn.Token(it) }
            return null
        }
    }
}
