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
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Renders the upstream lyric layouts inside the status-bar containers supplied to OverlayPlugin.
 * Overlay mode replaces the whole start side. Clock-right mode keeps the clock and replaces the
 * notification-icon area, matching the uwu-16.2 implementation.
 */
class StatusBarLyricController(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())

    private var statusBar: View? = null
    private var overlayParent: ViewGroup? = null
    private var inlineParent: ViewGroup? = null
    private var leftSide: View? = null
    private var notificationIconArea: View? = null

    private var view: LinearLayout? = null
    private var iconView: ImageView? = null
    private var textView: TextView? = null
    private var translationView: TextView? = null
    private var currentNotificationKey: String? = null
    private var currentSourcePackage: String? = null
    private var previousLeftVisibility = View.VISIBLE
    private var previousNotificationVisibility = View.VISIBLE

    private val timeoutRunnable = Runnable { clear() }

    fun attach(statusBar: View?) {
        if (statusBar == null || this.statusBar === statusBar) return
        detachView()
        this.statusBar = statusBar
        overlayParent = findViewGroup(statusBar, "status_bar_start_side_content")
        inlineParent = findViewGroup(statusBar, "start_side_notif_and_chip_container")
        leftSide = findView(statusBar, "status_bar_start_side_except_heads_up")
        notificationIconArea = findView(statusBar, "notification_icon_area")
        if (currentNotificationKey != null) {
            ensureView()?.let {
                moveToActiveParent(it)
                applyNativeVisibility()
                it.visibility = View.VISIBLE
            }
        }
    }

    fun show(
        sourcePackage: String?,
        text: String?,
        translation: String?,
        icon: Icon?,
        iconPackage: String?,
        notificationKey: String?,
    ) {
        if (!isEnabled() || sourcePackage.isNullOrBlank() || text.isNullOrBlank() ||
            notificationKey.isNullOrBlank() || !isPackageAllowed(sourcePackage)) {
            clear()
            return
        }

        val holder = ensureView() ?: return
        if (currentNotificationKey == null) captureNativeVisibility()
        currentNotificationKey = notificationKey
        currentSourcePackage = sourcePackage
        moveToActiveParent(holder)
        applyNativeVisibility()

        textView?.text = text.take(MAX_TEXT_LENGTH)
        val visibleTranslation = if (isTranslationEnabled()) {
            translation.orEmpty().take(MAX_TEXT_LENGTH)
        } else {
            ""
        }
        translationView?.apply {
            this.text = visibleTranslation
            visibility = if (visibleTranslation.isEmpty()) View.GONE else View.VISIBLE
        }
        iconView?.apply {
            setImageDrawable(resolveIcon(icon, iconPackage ?: sourcePackage))
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
        restoreNativeVisibility()
    }

    fun refreshSettings() {
        if (!isEnabled() || currentSourcePackage?.let(::isPackageAllowed) != true) {
            clear()
            return
        }
        view?.let {
            restoreNativeVisibility()
            moveToActiveParent(it)
            captureNativeVisibility()
            applyNativeVisibility()
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
        restoreNativeVisibility()
        detachView()
        statusBar = null
        overlayParent = null
        inlineParent = null
        leftSide = null
        notificationIconArea = null
        currentNotificationKey = null
        currentSourcePackage = null
    }

    private fun ensureView(): LinearLayout? {
        view?.let { return it }
        if (activeParent() == null) return null

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(LYRIC_PADDING_END_DP), 0)
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
        }
        val iconSize = sysuiDimen("status_bar_icon_size", dp(DEFAULT_ICON_SIZE_DP))
        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).also {
                it.marginEnd = dp(LYRIC_ICON_MARGIN_END_DP)
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(resolveTextColor())
        }
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        val lyric = createTextView(LYRIC_TEXT_SP, 14f)
        val translated = createTextView(TRANSLATION_TEXT_SP, 10f).apply {
            visibility = View.GONE
        }
        labels.addView(lyric)
        labels.addView(translated)
        root.addView(icon)
        root.addView(labels)

        view = root
        iconView = icon
        textView = lyric
        translationView = translated
        moveToActiveParent(root)
        return root
    }

    private fun createTextView(resourceName: String, fallbackSp: Float) = TextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            if (resourceName == LYRIC_TEXT_SP) 14f else 10f,
        )
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextSize(TypedValue.COMPLEX_UNIT_SP, fallbackSp)
        setTextColor(resolveTextColor())
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
    }

    private fun moveToActiveParent(child: View) {
        val target = activeParent() ?: return
        if (child.parent === target) return
        (child.parent as? ViewGroup)?.removeView(child)
        target.addView(
            child,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_VERTICAL or Gravity.START,
            ),
        )
    }

    private fun activeParent(): ViewGroup? = if (isClockRight()) inlineParent else overlayParent

    private fun captureNativeVisibility() {
        previousLeftVisibility = leftSide?.visibility ?: View.VISIBLE
        previousNotificationVisibility = notificationIconArea?.visibility ?: View.VISIBLE
    }

    private fun applyNativeVisibility() {
        if (isClockRight()) {
            leftSide?.visibility = previousLeftVisibility
            notificationIconArea?.visibility = View.INVISIBLE
        } else {
            leftSide?.visibility = View.INVISIBLE
            notificationIconArea?.visibility = previousNotificationVisibility
        }
    }

    private fun restoreNativeVisibility() {
        leftSide?.visibility = previousLeftVisibility
        notificationIconArea?.visibility = previousNotificationVisibility
    }

    private fun detachView() {
        (view?.parent as? ViewGroup)?.removeView(view)
        view = null
        iconView = null
        textView = null
        translationView = null
    }

    private fun findView(root: View, name: String): View? {
        val id = context.resources.getIdentifier(name, "id", context.packageName)
        return if (id != 0) root.findViewById(id) else null
    }

    private fun findViewGroup(root: View, name: String): ViewGroup? = findView(root, name) as? ViewGroup

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

    private fun resolveIcon(icon: Icon?, packageName: String): Drawable? {
        icon?.loadDrawable(context)?.let { return it }
        return runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
    }

    private fun resolveTextColor(): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.textColorPrimary, value, true)) {
            value.data
        } else {
            Color.WHITE
        }
    }

    private fun sysuiDimen(name: String, fallback: Int): Int {
        val id = context.resources.getIdentifier(name, "dimen", context.packageName)
        return if (id != 0) context.resources.getDimensionPixelSize(id) else fallback
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val POSITION_OVERLAY = 0
        const val POSITION_CLOCK_RIGHT = 1
        const val MAX_TEXT_LENGTH = 512
        const val CLEAR_TIMEOUT_MS = 5_000L
        const val LYRIC_PADDING_END_DP = 4
        const val LYRIC_ICON_MARGIN_END_DP = 4
        const val DEFAULT_ICON_SIZE_DP = 20
        const val LYRIC_TEXT_SP = "lyric_text"
        const val TRANSLATION_TEXT_SP = "lyric_translation"
    }
}
