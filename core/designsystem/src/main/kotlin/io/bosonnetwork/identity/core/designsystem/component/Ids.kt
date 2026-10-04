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

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.security.MessageDigest

/** A long id shortened for display: its first and last four characters ("7Hq3...xK2p"). */
fun shortId(id: String): String = if (id.length <= 12) id else "${id.take(4)}...${id.takeLast(4)}"

/** A long id in groups of four, for reading it aloud or comparing it by eye. */
fun groupedId(id: String): String = id.chunked(4).joinToString(" ")

/**
 * An id as text: monospace, grouped in fours, and read by TalkBack group by group, so that both a
 * sighted and a non-sighted user can compare it piece by piece.
 */
@Composable
fun IdText(id: String, modifier: Modifier = Modifier, short: Boolean = false, style: TextStyle = LocalTextStyle.current) {
    val shown = if (short) shortId(id) else groupedId(id)
    Text(
        text = shown,
        style = style.copy(fontFamily = FontFamily.Monospace),
        modifier = modifier.semantics { contentDescription = groupedId(if (short) shortId(id) else id).replace(" ", ", ") },
    )
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
