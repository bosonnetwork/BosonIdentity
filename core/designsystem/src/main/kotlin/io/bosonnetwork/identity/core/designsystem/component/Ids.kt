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

package io.bosonnetwork.identity.core.designsystem.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.security.MessageDigest

/** How many characters of an id show at each end of its abbreviation. */
private const val ID_ENDS = 6

/**
 * An id as the app shows it: the first and last six characters of its Base58 string around an
 * ellipsis ("7Hq3wZ\u2026NoPxK2p"). Base58 ids are shown this way everywhere but under their own QR
 * code, which shows them whole ([IdText] with `full`); copying gives the whole id too.
 */
fun shortId(id: String): String =
    if (id.length <= ID_ENDS * 2 + 1) id else "${id.take(ID_ENDS)}\u2026${id.takeLast(ID_ENDS)}"

/**
 * A long secret in groups of four, for writing it down and typing it back: the key backup uses it.
 * Ids are never grouped; they are abbreviated with [shortId].
 */
fun groupedId(id: String): String = id.chunked(4).joinToString(" ")

/**
 * What TalkBack reads for an id: its two ends spelled out character by character, so that it can be
 * compared with what another screen says; with [full], all of it.
 */
fun spokenId(id: String, full: Boolean = false): String {
    if (full || id.length <= ID_ENDS * 2 + 1) return id.toList().joinToString(" ")
    return id.take(ID_ENDS).toList().joinToString(" ") + ", \u2026, " + id.takeLast(ID_ENDS).toList().joinToString(" ")
}

/**
 * An id as text: abbreviated ([shortId]) in monospace. With [onClick], the id is a button, typically
 * opening its QR code; [clickLabel] says what it does, for TalkBack. With [full], the whole id, centered,
 * wrapped onto lines of balanced length when it doesn't fit on one: for the id's own QR code.
 */
@Composable
fun IdText(
    id: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null,
    full: Boolean = false,
) {
    val clickable = if (onClick != null) {
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick)
    } else {
        Modifier
    }
    Text(
        text = if (full) id else shortId(id),
        style = if (full) {
            style.copy(fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, lineBreak = LineBreak.Heading)
        } else {
            style.copy(fontFamily = FontFamily.Monospace)
        },
        color = color,
        maxLines = if (full) Int.MAX_VALUE else 1,
        modifier = modifier.then(clickable).semantics { contentDescription = spokenId(id, full) },
    )
}

/** Puts [text] on the clipboard, labelled [label]. Ids and addresses are public: no sensitive flag. */
fun copyToClipboard(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * A picture of an id: a symmetric 5 x 5 pattern and a color, both from the id's SHA-256. Users compare
 * pictures far more reliably than strings of letters, so every id is shown with its identicon.
 */
@Composable
fun Identicon(id: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val pattern = remember(id) { IdenticonPattern.of(id) }
    Canvas(modifier.size(size).clip(RoundedCornerShape(size / 5))) {
        drawRect(pattern.background)
        val cell = this.size.width / 6f
        val inset = cell / 2f
        for (row in 0 until 5) for (col in 0 until 5) {
            if (pattern.cells[row * 5 + col]) {
                drawRect(pattern.foreground, Offset(inset + col * cell, inset + row * cell), Size(cell, cell))
            }
        }
    }
}

/**
 * An identity's picture: its avatar when it has one that reads as an image, in the identicon's place
 * and shape; its identicon otherwise. Everywhere an identity is shown, so it looks the same everywhere.
 */
@Composable
fun IdentityPicture(
    id: String,
    avatar: ByteArray?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    contentDescription: String? = null,
) {
    val image = remember(avatar) {
        avatar?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    if (image == null) {
        Identicon(id, modifier, size)
        return
    }
    Image(
        image,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(RoundedCornerShape(size / 5)),
    )
}

/** The deterministic part of [Identicon], separated so it can be tested. */
class IdenticonPattern private constructor(val cells: BooleanArray, val foreground: Color, val background: Color) {
    companion object {
        fun of(id: String): IdenticonPattern {
            val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            // Columns 0..2 come from the hash; 3 and 4 mirror 1 and 0.
            val cells = BooleanArray(25)
            for (row in 0 until 5) for (col in 0 until 3) {
                val on = (hash[row * 3 + col].toInt() and 1) == 1
                cells[row * 5 + col] = on
                cells[row * 5 + (4 - col)] = on
            }
            val hue = ((hash[20].toInt() and 0xFF) shl 8 or (hash[21].toInt() and 0xFF)) % 360
            return IdenticonPattern(
                cells,
                Color.hsl(hue.toFloat(), 0.55f, 0.45f),
                Color.hsl(hue.toFloat(), 0.35f, 0.92f),
            )
        }
    }
}
