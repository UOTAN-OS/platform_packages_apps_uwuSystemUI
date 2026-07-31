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
import android.view.View
import com.android.systemui.plugins.OverlayPlugin
import com.android.systemui.plugins.annotations.Requires

@Requires(target = OverlayPlugin::class, version = OverlayPlugin.VERSION)
class SmartSuggestionsPlugin : OverlayPlugin {
    private var controller: SmartSuggestionsController? = null

    override fun onCreate(sysuiContext: Context, pluginContext: Context) {
        controller = SmartSuggestionsController(pluginContext).also { it.start() }
    }

    override fun onDestroy() {
        controller?.stop()
        controller = null
    }

    override fun setup(statusBar: View?, navBar: View?) = Unit
}
