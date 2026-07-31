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

package org.uwuaosp.systemui.moment.arc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.view.View
import com.android.systemui.plugins.OverlayPlugin
import com.android.systemui.plugins.annotations.Requires

@Requires(target = OverlayPlugin::class, version = OverlayPlugin.VERSION)
class MomentArcPlugin : OverlayPlugin {
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var sysuiContext: Context
    private lateinit var pluginContext: Context
    private lateinit var controller: MomentArcController
    private var receiverRegistered = false
    private var settingsObserverRegistered = false

    private val settingsObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                if (!controller.isMomentArcEnabled()) {
                    controller.hide()
                }
            }
        }

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_SHOW_MOMENT_ARC -> {
                        controller.show(
                            isLeft = intent.getBooleanExtra(EXTRA_IS_LEFT, true),
                            initialTouchX = intent.getFloatExtra(EXTRA_TOUCH_X, -1f),
                            initialTouchY = intent.getFloatExtra(EXTRA_TOUCH_Y, -1f),
                        )
                    }
                    ACTION_UPDATE_MOMENT_ARC_TOUCH -> {
                        controller.onTouchCoordinates(
                            x = intent.getFloatExtra(EXTRA_TOUCH_X, -1f),
                            y = intent.getFloatExtra(EXTRA_TOUCH_Y, -1f),
                            isUp = intent.getBooleanExtra(EXTRA_IS_UP, false),
                            isCancelled = intent.getBooleanExtra(EXTRA_IS_CANCELLED, false),
                        )
                    }
                    ACTION_DISMISS_MOMENT_ARC -> controller.hide()
                    Intent.ACTION_SCREEN_OFF,
                    Intent.ACTION_USER_SWITCHED -> controller.hide()
                }
            }
        }

    override fun onCreate(sysuiContext: Context, pluginContext: Context) {
        this.sysuiContext = sysuiContext
        this.pluginContext = pluginContext
        controller = MomentArcController(sysuiContext, pluginContext)

        val filter =
            IntentFilter().apply {
                addAction(ACTION_SHOW_MOMENT_ARC)
                addAction(ACTION_UPDATE_MOMENT_ARC_TOUCH)
                addAction(ACTION_DISMISS_MOMENT_ARC)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_SWITCHED)
            }
        sysuiContext.registerReceiverAsUser(
            receiver,
            UserHandle.ALL,
            filter,
            STATUS_BAR_PERMISSION,
            mainHandler,
            Context.RECEIVER_EXPORTED,
        )
        receiverRegistered = true
        sysuiContext.contentResolver.registerContentObserver(
            android.provider.Settings.Secure.getUriFor(
                android.provider.Settings.Secure.MOMENT_ENABLED,
            ),
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        settingsObserverRegistered = true
        sysuiContext.contentResolver.registerContentObserver(
            android.provider.Settings.Secure.getUriFor(
                android.provider.Settings.Secure.MOMENT_ARC_GESTURE_ENABLED,
            ),
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
    }

    override fun onDestroy() {
        if (::controller.isInitialized) {
            controller.hide()
        }
        if (::sysuiContext.isInitialized && settingsObserverRegistered) {
            runCatching {
                sysuiContext.contentResolver.unregisterContentObserver(settingsObserver)
            }
            settingsObserverRegistered = false
        }
        if (::sysuiContext.isInitialized && receiverRegistered) {
            runCatching {
                sysuiContext.unregisterReceiver(receiver)
            }
            receiverRegistered = false
        }
    }

    override fun setup(statusBar: View?, navBar: View?) {
        // No-op. This plugin uses OverlayPlugin as a stable host entrypoint, but draws its own
        // trusted overlay window in response to framework gesture broadcasts.
    }

    companion object {
        const val ACTION_SHOW_MOMENT_ARC = "com.android.systemui.action.SHOW_MOMENT_ARC"
        const val ACTION_UPDATE_MOMENT_ARC_TOUCH =
            "com.android.systemui.action.UPDATE_MOMENT_ARC_TOUCH"
        const val ACTION_DISMISS_MOMENT_ARC =
            "com.android.systemui.action.DISMISS_MOMENT_ARC"
        const val EXTRA_IS_LEFT = "is_left"
        const val EXTRA_TOUCH_X = "touch_x"
        const val EXTRA_TOUCH_Y = "touch_y"
        const val EXTRA_IS_UP = "is_up"
        const val EXTRA_IS_CANCELLED = "is_cancelled"
        private const val STATUS_BAR_PERMISSION = "android.permission.STATUS_BAR"
    }
}
