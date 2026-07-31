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

import android.app.Notification
import android.content.Intent
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Translates the established lyric ticker notification protocol into a broadcast that only
 * SystemUI, which owns the plugin host, can receive.
 */
class LyricNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification
        if (!isLyricNotification(notification)) {
            return
        }
        val text = notification.tickerText?.toString() ?: return
        val sourcePackage = lyricSourcePackage(sbn, notification)
        sendUpdate(
            Intent(StatusBarLyricPlugin.ACTION_LYRIC_UPDATE)
                .putExtra(StatusBarLyricPlugin.EXTRA_SOURCE_PACKAGE, sourcePackage)
                .putExtra(StatusBarLyricPlugin.EXTRA_TEXT, text)
                .putExtra(
                    StatusBarLyricPlugin.EXTRA_TRANSLATION,
                    notification.extras.getString(EXTRA_TICKER_TRANSLATION),
                )
                .putExtra(
                    StatusBarLyricPlugin.EXTRA_ICON_PACKAGE,
                    notification.extras.getString(EXTRA_TICKER_ICON_PACKAGE, sourcePackage),
                )
                .putExtra(StatusBarLyricPlugin.EXTRA_NOTIFICATION_KEY, sbn.key),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        sendUpdate(
            Intent(StatusBarLyricPlugin.ACTION_LYRIC_CLEAR)
                .putExtra(StatusBarLyricPlugin.EXTRA_NOTIFICATION_KEY, sbn.key),
        )
    }

    private fun sendUpdate(intent: Intent) {
        intent.setPackage(packageName)
        sendBroadcastAsUser(intent, UserHandle.CURRENT, STATUS_BAR_PERMISSION)
    }

    private fun isLyricNotification(notification: Notification): Boolean {
        return notification.tickerText != null &&
            notification.flags and FLAG_ALWAYS_SHOW_TICKER != 0 &&
            notification.flags and FLAG_ONLY_UPDATE_TICKER != 0
    }

    private fun lyricSourcePackage(
        sbn: StatusBarNotification,
        notification: Notification,
    ): String {
        return if (sbn.packageName == LYRIC_FETCH_PACKAGE) {
            notification.extras.getString(EXTRA_TICKER_ICON_PACKAGE, sbn.packageName)
        } else {
            sbn.packageName
        }
    }

    private companion object {
        const val STATUS_BAR_PERMISSION = "android.permission.STATUS_BAR"
        const val LYRIC_FETCH_PACKAGE = "cn.binbin323.statuslyricext"
        const val EXTRA_TICKER_ICON_PACKAGE = "ticker_icon_package"
        const val EXTRA_TICKER_TRANSLATION = "ticker_translation"
        // Kept protocol-compatible with uwu-16.2 without adding obsolete public Notification APIs.
        const val FLAG_ALWAYS_SHOW_TICKER = 0x01000000
        const val FLAG_ONLY_UPDATE_TICKER = 0x02000000
    }
}
