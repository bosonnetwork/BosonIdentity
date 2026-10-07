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

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import io.bosonnetwork.identity.core.model.AppLanguage
import io.bosonnetwork.identity.core.model.ThemeMode
import java.util.Locale

/**
 * The app's own settings: its theme and its language. Kept in plain shared preferences, since the
 * language is needed before anything else exists (in attachBaseContext), and nothing here is secret.
 */
object AppSettings {
    private const val PREFS = "app_settings"
    private const val KEY_THEME = "theme"
    private const val KEY_LANGUAGE = "language_tag"

    fun theme(context: Context): ThemeMode = ThemeMode.fromName(prefs(context).getString(KEY_THEME, null))

    fun setTheme(context: Context, theme: ThemeMode) {
        prefs(context).edit().putString(KEY_THEME, theme.name).apply()
    }

    fun language(context: Context): AppLanguage = AppLanguage.fromTag(prefs(context).getString(KEY_LANGUAGE, null))

    /**
     * Makes [language] the app's language and restarts the app in it: every screen, and the strings
     * view models already hold, then speak it. The write is a synchronous commit, not apply: the
     * process ends right after, and an apply() still on its way to disk would be lost with it.
     */
    fun setLanguageAndRestart(context: Context, language: AppLanguage) {
        prefs(context).edit().putString(KEY_LANGUAGE, language.tag).commit()
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) context.startActivity(launch)
        Runtime.getRuntime().exit(0)
    }

    /**
     * A context whose resources speak the chosen language, or [base] unchanged when the app follows
     * the phone. Used in attachBaseContext of the application and the activity.
     */
    fun wrap(base: Context): Context {
        val language = language(base)
        if (language == AppLanguage.SYSTEM) return base
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
