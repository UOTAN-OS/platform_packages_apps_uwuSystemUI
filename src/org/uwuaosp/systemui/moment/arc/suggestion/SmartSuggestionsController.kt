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

package org.uwuaosp.systemui.moment.arc.suggestion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.plugins.uwu.UwuSuggestionContract

internal class SmartSuggestionsController(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var started = false
    private var headsetConnected = false
    private var musicSuggestionVisible = false

    private val settingsObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                updateTorchSuggestion()
                if (!musicEnabled()) {
                    dismissMusicSuggestion()
                }
            }
        }

    private val audioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                updateHeadsetState()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                updateHeadsetState()
            }
        }

    private val systemReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_USER_SWITCHED -> {
                        headsetConnected = hasSupportedOutputDevice()
                        dismiss()
                        updateTorchSuggestion()
                    }
                    Intent.ACTION_SCREEN_OFF -> dismiss()
                }
            }
        }

    fun start() {
        if (started) return
        started = true

        val resolver = context.contentResolver
        listOf(
                KEY_TORCH_ENABLED,
                Settings.Secure.FLASHLIGHT_ENABLED,
                KEY_MUSIC_ENABLED,
                KEY_MUSIC_PACKAGE,
            )
            .forEach { key ->
                resolver.registerContentObserver(
                    Settings.Secure.getUriFor(key),
                    false,
                    settingsObserver,
                    UserHandle.USER_ALL,
                )
            }

        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, handler)
        headsetConnected = hasSupportedOutputDevice()
        context.registerReceiver(
            systemReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_SWITCHED)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        updateTorchSuggestion()
        if (headsetConnected) {
            showMusicSuggestion()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.contentResolver.unregisterContentObserver(settingsObserver) }
        runCatching { audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback) }
        runCatching { context.unregisterReceiver(systemReceiver) }
        handler.removeCallbacksAndMessages(null)
        dismiss()
    }

    private fun updateTorchSuggestion() {
        val shouldShow =
            secureBoolean(KEY_TORCH_ENABLED) &&
                secureBoolean(Settings.Secure.FLASHLIGHT_ENABLED)
        if (shouldShow) {
            sendCommand(UwuSuggestionContract.ACTION_SHOW_TORCH)
        } else if (!musicSuggestionVisible) {
            dismiss()
        }
    }

    private fun updateHeadsetState() {
        val connected = hasSupportedOutputDevice()
        if (connected == headsetConnected) return
        headsetConnected = connected
        if (connected) {
            showMusicSuggestion()
        } else {
            dismissMusicSuggestion()
        }
    }

    private fun showMusicSuggestion() {
        if (!musicEnabled()) return
        val packageName = secureString(KEY_MUSIC_PACKAGE)
        if (packageName.isBlank() || context.packageManager.getLaunchIntentForPackage(packageName) == null) {
            return
        }
        musicSuggestionVisible = true
        sendCommand(UwuSuggestionContract.ACTION_SHOW_MUSIC) {
            putExtra(UwuSuggestionContract.EXTRA_PACKAGE_NAME, packageName)
        }
        handler.removeCallbacks(autoDismissMusic)
        handler.postDelayed(autoDismissMusic, MUSIC_AUTO_DISMISS_MS)
    }

    private fun dismissMusicSuggestion() {
        if (!musicSuggestionVisible) return
        musicSuggestionVisible = false
        handler.removeCallbacks(autoDismissMusic)
        dismiss()
    }

    private val autoDismissMusic =
        Runnable {
            musicSuggestionVisible = false
            dismiss()
        }

    private fun dismiss() {
        sendCommand(UwuSuggestionContract.ACTION_DISMISS)
    }

    private fun sendCommand(action: String, configure: Intent.() -> Unit = {}) {
        val intent =
            Intent(action)
                .setPackage(UwuSuggestionContract.HOST_PACKAGE)
                .apply(configure)
        context.sendBroadcastAsUser(
            intent,
            UserHandle.CURRENT,
            UwuSuggestionContract.STATUS_BAR_PERMISSION,
        )
    }

    private fun musicEnabled(): Boolean = secureBoolean(KEY_MUSIC_ENABLED)

    private fun secureBoolean(key: String): Boolean {
        return Settings.Secure.getIntForUser(
            context.contentResolver,
            key,
            0,
            UserHandle.USER_CURRENT,
        ) == 1
    }

    private fun secureString(key: String): String {
        return Settings.Secure.getStringForUser(
            context.contentResolver,
            key,
            UserHandle.USER_CURRENT,
        ).orEmpty()
    }

    private fun hasSupportedOutputDevice(): Boolean {
        return audioManager
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.any { device ->
                when (device.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER,
                    AudioDeviceInfo.TYPE_BLE_BROADCAST,
                    AudioDeviceInfo.TYPE_HEARING_AID -> true
                    else -> false
                }
            } == true
    }

    private companion object {
        const val KEY_TORCH_ENABLED = "uwuaosp_torch_suggestion_enabled"
        const val KEY_MUSIC_ENABLED = "uwuaosp_music_suggestion_enabled"
        const val KEY_MUSIC_PACKAGE = "uwuaosp_music_suggestion_package"
        const val MUSIC_AUTO_DISMISS_MS = 30_000L
    }
}
