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

package io.bosonnetwork.identity.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * The Boson brand palette (Boson.Brand, and the website's tokens.css) on Material 3 roles. Teal, the
 * brand's "signal", is the primary color; violet, its "pole", is the tertiary color and marks
 * everything administrative. Dynamic color is deliberately not used: the identity app looks the same
 * on every phone, so users recognize the real one.
 */

private val Light = lightColorScheme(
    primary = Color(0xFF0E7490),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3EEF3),
    onPrimaryContainer = Color(0xFF053844),
    secondary = Color(0xFF55618A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE3F0),
    onSecondaryContainer = Color(0xFF26324D),
    tertiary = Color(0xFF5B4BD6),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE6E2FF),
    onTertiaryContainer = Color(0xFF22186B),
    background = Color(0xFFE8ECF6),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFE8ECF6),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFDFE5F2),
    onSurfaceVariant = Color(0xFF55618A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F6FC),
    surfaceContainer = Color(0xFFEEF1F8),
    surfaceContainerHigh = Color(0xFFE4E8F3),
    surfaceContainerHighest = Color(0xFFDFE5F2),
    outline = Color(0xFF828794),
    outlineVariant = Color(0xFFC9D2E6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF4FD6C4),
    onPrimary = Color(0xFF07101F),
    primaryContainer = Color(0xFF0F3F4A),
    onPrimaryContainer = Color(0xFFBFF4EC),
    secondary = Color(0xFF8B98B6),
    onSecondary = Color(0xFF0B1020),
    secondaryContainer = Color(0xFF243252),
    onSecondaryContainer = Color(0xFFC7D0E4),
    tertiary = Color(0xFF7C8CF8),
    onTertiary = Color(0xFF0B1020),
    tertiaryContainer = Color(0xFF2B2F6B),
    onTertiaryContainer = Color(0xFFDCE0FF),
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFE8ECF6),
    surface = Color(0xFF0B1020),
    onSurface = Color(0xFFE8ECF6),
    surfaceVariant = Color(0xFF182238),
    onSurfaceVariant = Color(0xFF8B98B6),
    surfaceContainerLowest = Color(0xFF080C18),
    surfaceContainerLow = Color(0xFF0D1428),
    surfaceContainer = Color(0xFF131A2E),
    surfaceContainerHigh = Color(0xFF182238),
    surfaceContainerHighest = Color(0xFF1E2942),
    outline = Color(0xFF5C667E),
    outlineVariant = Color(0xFF243252),
)

/** The brand colors Material 3 has no role for. */
@Immutable
data class BrandColors(
    /** Cards on the page ground: white on the light plate, the brand panel on the dark one. */
    val card: Color,
    /** "Done", "protected": the brand's ok green. */
    val success: Color,
    /** "Needs attention": the brand's warning amber. */
    val warning: Color,
)

private val LightBrand = BrandColors(card = Color(0xFFFFFFFF), success = Color(0xFF0F7A4F), warning = Color(0xFF8A5A00))
private val DarkBrand = BrandColors(card = Color(0xFF131A2E), success = Color(0xFF57E19A), warning = Color(0xFFF2C879))

private val LocalBrandColors = staticCompositionLocalOf { LightBrand }

/** The brand's own colors beside [MaterialTheme.colorScheme]. */
val MaterialTheme.brand: BrandColors
    @Composable get() = LocalBrandColors.current

// The website's radii: 8 for small elements, 12 for cards, 18 for sheets and dialogs.
private val BrandShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun BosonIdentityTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors: ColorScheme = if (darkTheme) Dark else Light
    CompositionLocalProvider(LocalBrandColors provides if (darkTheme) DarkBrand else LightBrand) {
        MaterialTheme(colorScheme = colors, shapes = BrandShapes, content = content)
    }
}
