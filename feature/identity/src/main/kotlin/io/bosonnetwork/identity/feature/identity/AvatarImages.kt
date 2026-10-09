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

package io.bosonnetwork.identity.feature.identity

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.identity.core.model.AppError
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A picked picture, made into an avatar: [image] is the JPEG to upload, [preview] what it looks like. */
class PreparedAvatar(val image: ByteArray, val preview: Bitmap)

/**
 * Makes a picked picture into an avatar: upright (the decoder applies the camera's orientation), the
 * centered square, [SIZE] pixels on a side at most, as a JPEG on white (a transparent picture would
 * otherwise turn black). Any picture the phone can decode will do, HEIC included; the node takes JPEG.
 */
class AvatarImages @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun prepare(uri: Uri): PreparedAvatar = withContext(Dispatchers.IO) {
        val decoded = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                // Decoded just large enough: the shorter side SIZE, which the square keeps whole.
                val shorter = minOf(info.size.width, info.size.height)
                if (shorter > SIZE) {
                    val scale = SIZE.toFloat() / shorter
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(SIZE),
                        (info.size.height * scale).roundToInt().coerceAtLeast(SIZE),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: Exception) {
            throw AppError.InvalidInput(context.getString(R.string.id_avatar_unreadable), e)
        }

        val side = minOf(decoded.width, decoded.height)
        val target = minOf(side, SIZE)
        val left = (decoded.width - side) / 2
        val top = (decoded.height - side) / 2
        val avatar = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        Canvas(avatar).apply {
            drawColor(Color.WHITE)
            drawBitmap(decoded, Rect(left, top, left + side, top + side), Rect(0, 0, target, target), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        decoded.recycle()

        val out = ByteArrayOutputStream()
        avatar.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        PreparedAvatar(out.toByteArray(), avatar)
    }

    companion object {
        /** The avatar's side, in pixels. */
        const val SIZE = 512
        private const val QUALITY = 88
    }
}
