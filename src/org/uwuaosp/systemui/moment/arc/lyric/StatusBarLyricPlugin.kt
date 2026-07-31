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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import com.android.systemui.plugins.OverlayPlugin
import com.android.systemui.plugins.annotations.Requires

@Requires(target = OverlayPlugin::class, version = OverlayPlugin.VERSION)
class StatusBarLyricPlugin : OverlayPlugin {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var sysuiContext: Context
    private lateinit var controller: StatusBarLyricController
    private var receiverRegistered = false
    private var observerRegistered = false

    private val settingsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) = controller.refreshSettings()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_LYRIC_UPDATE -> controller.show(
                    sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE),
                    text = intent.getStringExtra(EXTRA_TEXT),
                    translation = intent.getStringExtra(EXTRA_TRANSLATION),
                    iconPackage = intent.getStringExtra(EXTRA_ICON_PACKAGE),
                    notificationKey = intent.getStringExtra(EXTRA_NOTIFICATION_KEY),
                )
                ACTION_LYRIC_CLEAR -> controller.clear(
                    intent.getStringExtra(EXTRA_NOTIFICATION_KEY),
                )
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_USER_SWITCHED -> controller.clear()
            }
        }
    }

    override fun onCreate(sysuiContext: Context, pluginContext: Context) {
        this.sysuiContext = sysuiContext
        controller = StatusBarLyricController(sysuiContext)
        sysuiContext.registerReceiverAsUser(
            receiver,
            UserHandle.ALL,
            IntentFilter().apply {
                addAction(ACTION_LYRIC_UPDATE)
                addAction(ACTION_LYRIC_CLEAR)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_SWITCHED)
            },
            STATUS_BAR_PERMISSION,
            mainHandler,
            Context.RECEIVER_EXPORTED,
        )
        receiverRegistered = true
        listOf(
            Settings.Secure.STATUS_BAR_SHOW_LYRIC,
            Settings.Secure.STATUS_BAR_LYRIC_POSITION,
            Settings.Secure.STATUS_BAR_LYRIC_SHOW_TRANSLATION,
            Settings.Secure.STATUS_BAR_LYRIC_HIDE_ICON_CLOCK_RIGHT,
            Settings.Secure.STATUS_BAR_LYRIC_ALLOWED_PACKAGES,
        ).forEach {
            sysuiContext.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(it),
                false,
                settingsObserver,
                UserHandle.USER_ALL,
            )
        }
        observerRegistered = true
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.destroy()
        if (::sysuiContext.isInitialized && receiverRegistered) {
            runCatching { sysuiContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        if (::sysuiContext.isInitialized && observerRegistered) {
            runCatching { sysuiContext.contentResolver.unregisterContentObserver(settingsObserver) }
            observerRegistered = false
        }
    }

    override fun setup(statusBar: View?, navBar: View?) = Unit

    companion object {
        const val ACTION_LYRIC_UPDATE = "org.uwuaosp.systemui.action.LYRIC_UPDATE"
        const val ACTION_LYRIC_CLEAR = "org.uwuaosp.systemui.action.LYRIC_CLEAR"
        const val EXTRA_SOURCE_PACKAGE = "source_package"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TRANSLATION = "translation"
        const val EXTRA_ICON_PACKAGE = "icon_package"
        const val EXTRA_NOTIFICATION_KEY = "notification_key"
        const val STATUS_BAR_PERMISSION = "android.permission.STATUS_BAR"
    }
}
