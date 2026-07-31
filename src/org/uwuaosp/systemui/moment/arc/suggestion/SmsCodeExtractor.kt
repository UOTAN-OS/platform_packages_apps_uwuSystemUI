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

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import org.json.JSONArray
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

internal object SmsCodeExtractor {
    fun extract(context: Context, rawText: CharSequence?): String? {
        val text = rawText?.take(MAX_INPUT_LENGTH)?.toString().orEmpty()
        if (text.isBlank() || !isEnabled(context)) return null

        val serialized =
            Settings.Secure.getStringForUser(
                context.contentResolver,
                KEY_RULES,
                UserHandle.myUserId(),
            )
                ?: return null
        val rules = runCatching { JSONArray(serialized) }.getOrNull() ?: return null
        val count = minOf(rules.length(), MAX_RULE_COUNT)
        for (index in 0 until count) {
            val rule = rules.optJSONObject(index) ?: continue
            if (!rule.optBoolean("enabled", true)) continue
            val expression = rule.optString("pattern")
            if (expression.isBlank() || expression.length > MAX_PATTERN_LENGTH) continue
            val matcher =
                try {
                    Pattern.compile(expression).matcher(text)
                } catch (_: PatternSyntaxException) {
                    continue
                }
            if (!matcher.find()) continue
            for (group in 1..matcher.groupCount()) {
                val candidate = matcher.group(group)?.trim().orEmpty()
                if (candidate.isNotEmpty() && candidate.length <= MAX_CODE_LENGTH) {
                    return candidate
                }
            }
            val candidate = matcher.group()?.trim().orEmpty()
            if (candidate.isNotEmpty() && candidate.length <= MAX_CODE_LENGTH) {
                return candidate
            }
        }
        return null
    }

    private fun isEnabled(context: Context): Boolean {
        return Settings.Secure.getIntForUser(
            context.contentResolver,
            KEY_ENABLED,
            0,
            UserHandle.myUserId(),
        ) == 1
    }

    private const val KEY_ENABLED = "uwuaosp_sms_code_suggestion_enabled"
    private const val KEY_RULES = "uwuaosp_sms_code_rules"
    private const val MAX_RULE_COUNT = 32
    private const val MAX_PATTERN_LENGTH = 512
    private const val MAX_INPUT_LENGTH = 4096
    private const val MAX_CODE_LENGTH = 32
}
