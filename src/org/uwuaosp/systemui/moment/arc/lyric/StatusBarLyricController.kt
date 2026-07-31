/*
 * Copyright (C) 2026 The uwuAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.uwuaosp.systemui.moment.arc.lyric

import android.app.ActivityManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Keeps lyric presentation isolated from the SystemUI status-bar implementation. The service
 * accepts only notification-listener broadcasts and validates all user configuration again before
 * adding its trusted status bar panel.
 */
class StatusBarLyricController(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: LinearLayout? = null
    private var iconView: ImageView? = null
    private var textView: TextView? = null
    private var translationView: TextView? = null
    private var currentNotificationKey: String? = null
    private var currentSourcePackage: String? = null

    private val timeoutRunnable = Runnable { clear() }

    fun show(
        sourcePackage: String?,
        text: String?,
        translation: String?,
        iconPackage: String?,
        notificationKey: String?,
    ) {
        if (!isEnabled() || sourcePackage.isNullOrBlank() || text.isNullOrBlank() ||
            notificationKey.isNullOrBlank() || !isPackageAllowed(sourcePackage)) {
            clear()
            return
        }

        val holder = ensureView() ?: return
        currentNotificationKey = notificationKey
        currentSourcePackage = sourcePackage
        textView?.text = text.take(MAX_TEXT_LENGTH)
        val visibleTranslation = if (isTranslationEnabled()) translation.orEmpty()
            .take(MAX_TEXT_LENGTH) else ""
        translationView?.apply {
            this.text = visibleTranslation
            visibility = if (visibleTranslation.isEmpty()) View.GONE else View.VISIBLE
        }
        iconView?.apply {
            setImageDrawable(resolveIcon(iconPackage ?: sourcePackage))
            visibility = if (isClockRight() && shouldHideIconOnClockRight()) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }
        holder.visibility = View.VISIBLE
        scheduleTimeout()
    }

    fun clear(notificationKey: String? = null) {
        if (notificationKey != null && notificationKey != currentNotificationKey) return
        handler.removeCallbacks(timeoutRunnable)
        currentNotificationKey = null
        currentSourcePackage = null
        view?.visibility = View.GONE
    }

    fun refreshSettings() {
        if (!isEnabled() || currentSourcePackage?.let(::isPackageAllowed) != true) {
            clear()
            return
        }
        view?.let {
            windowManager.updateViewLayout(it, createLayoutParams())
            iconView?.visibility = if (isClockRight() && shouldHideIconOnClockRight()) {
                View.GONE
            } else {
                View.VISIBLE
            }
            translationView?.visibility = if (isTranslationEnabled() &&
                !translationView?.text.isNullOrEmpty()) View.VISIBLE else View.GONE
        }
    }

    fun destroy() {
        handler.removeCallbacks(timeoutRunnable)
        view?.let {
            runCatching { windowManager.removeViewImmediate(it) }
        }
        view = null
        iconView = null
        textView = null
        translationView = null
        currentNotificationKey = null
        currentSourcePackage = null
    }

    private fun ensureView(): LinearLayout? {
        view?.let { return it }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(PADDING_HORIZONTAL_DP), 0, dp(PADDING_HORIZONTAL_DP), 0)
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
        }
        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ICON_SIZE_DP), dp(ICON_SIZE_DP)).also {
                it.marginEnd = dp(ICON_MARGIN_END_DP)
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(resolveTextColor())
        }
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val lyric = createTextView(LYRIC_TEXT_SP)
        val translated = createTextView(TRANSLATION_TEXT_SP).apply { visibility = View.GONE }
        labels.addView(lyric)
        labels.addView(translated)
        root.addView(icon)
        root.addView(labels)
        try {
            windowManager.addView(root, createLayoutParams())
        } catch (e: Exception) {
            Log.w(TAG, "Unable to add status bar lyric window", e)
            return null
        }
        view = root
        iconView = icon
        textView = lyric
        translationView = translated
        return root
    }

    private fun createTextView(sizeSp: Float) = TextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(resolveTextColor())
        includeFontPadding = false
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(if (isTranslationEnabled()) STATUS_BAR_TRANSLATION_HEIGHT_DP else STATUS_BAR_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "StatusBarLyric"
            gravity = if (isClockRight()) Gravity.TOP or Gravity.END else Gravity.TOP or Gravity.CENTER_HORIZONTAL
            fitInsetsTypes = 0
            privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY or
                WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
        }
    }

    private fun scheduleTimeout() {
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, CLEAR_TIMEOUT_MS)
    }

    private fun isEnabled(): Boolean = Settings.Secure.getIntForUser(
        context.contentResolver,
        Settings.Secure.STATUS_BAR_SHOW_LYRIC,
        0,
        ActivityManager.getCurrentUser(),
    ) != 0

    private fun isTranslationEnabled(): Boolean = Settings.Secure.getIntForUser(
        context.contentResolver,
        Settings.Secure.STATUS_BAR_LYRIC_SHOW_TRANSLATION,
        0,
        ActivityManager.getCurrentUser(),
    ) != 0

    private fun isClockRight(): Boolean = Settings.Secure.getIntForUser(
        context.contentResolver,
        Settings.Secure.STATUS_BAR_LYRIC_POSITION,
        POSITION_OVERLAY,
        ActivityManager.getCurrentUser(),
    ) == POSITION_CLOCK_RIGHT

    private fun shouldHideIconOnClockRight(): Boolean = Settings.Secure.getIntForUser(
        context.contentResolver,
        Settings.Secure.STATUS_BAR_LYRIC_HIDE_ICON_CLOCK_RIGHT,
        0,
        ActivityManager.getCurrentUser(),
    ) != 0

    private fun isPackageAllowed(packageName: String): Boolean {
        val packages = Settings.Secure.getStringForUser(
            context.contentResolver,
            Settings.Secure.STATUS_BAR_LYRIC_ALLOWED_PACKAGES,
            ActivityManager.getCurrentUser(),
        ) ?: return false
        return packages.split(';').any { it.trim() == packageName }
    }

    private fun resolveIcon(packageName: String): Drawable? = runCatching {
        context.packageManager.getApplicationIcon(packageName)
    }.getOrNull()

    private fun resolveTextColor(): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.textColorPrimary, value, true)) {
            value.data
        } else {
            Color.WHITE
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "StatusBarLyric"
        const val POSITION_OVERLAY = 0
        const val POSITION_CLOCK_RIGHT = 1
        const val MAX_TEXT_LENGTH = 512
        const val CLEAR_TIMEOUT_MS = 5_000L
        const val STATUS_BAR_HEIGHT_DP = 32
        const val STATUS_BAR_TRANSLATION_HEIGHT_DP = 44
        const val PADDING_HORIZONTAL_DP = 8
        const val ICON_SIZE_DP = 18
        const val ICON_MARGIN_END_DP = 6
        const val LYRIC_TEXT_SP = 13f
        const val TRANSLATION_TEXT_SP = 10f
    }
}
