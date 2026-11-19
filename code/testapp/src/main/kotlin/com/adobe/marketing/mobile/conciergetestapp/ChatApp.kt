/*
 * Copyright 2025 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.adobe.marketing.mobile.conciergetestapp

import android.app.Application
import com.adobe.marketing.mobile.LoggingMode
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.concierge.Concierge

class ChatApp : Application() {
    companion object {
        private const val LOG_TAG = "ChatApp"
        private const val APP_ID = "bf7248f92b53/c39620205a4c/launch-321b019dcbf6-development"
    }

    override fun onCreate() {
        super.onCreate()
        MobileCore.setApplication(this)
        MobileCore.setLogLevel(LoggingMode.VERBOSE)
        MobileCore.initialize(this, APP_ID) {
            Concierge.setEdgeTrackingEnabled(true)
        }
        var configMap = mapOf( "concierge.configId" to "1c13a804-a0b6-46ee-83df-d0134461d03d",
            "concierge.server" to "edge.adobedc.net")
        MobileCore.updateConfiguration(configMap)
    }
}



// use with surface "web://dsg-bc-stage.awesome-sites.corp.adobe.com/"
//     val surfaces = listOf( "web://dsg-bc-stage.awesome-sites.corp.adobe.com/")
