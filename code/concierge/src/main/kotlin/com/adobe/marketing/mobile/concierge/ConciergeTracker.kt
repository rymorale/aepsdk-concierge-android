/*
  Copyright 2025 Adobe. All rights reserved.
  This file is licensed to you under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License. You may obtain a copy
  of the License at http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software distributed under
  the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
  OF ANY KIND, either express or implied. See the License for the specific language
  governing permissions and limitations under the License.
*/

package com.adobe.marketing.mobile.concierge

import com.adobe.marketing.mobile.Edge
import com.adobe.marketing.mobile.ExperienceEvent
import com.adobe.marketing.mobile.services.Log

/**
 * Sends Concierge tracking events to the AEP Edge Network as XDM Experience Events.
 *
 * Each event carries an XDM `eventType` of the form `brandconcierge.<action>`
 * (e.g. `brandconcierge.query:submitted`) and event-specific data under the
 * `_brandconcierge` XDM field group. Routing and downstream processing
 * (Analytics, CJA, Event Forwarding, etc.) is configured on the datastream
 * in the Adobe Experience Platform UI.
 *
 * Constants for event names and data keys are defined in
 * [ConciergeConstants.Tracking].
 */
internal object ConciergeTracker {

    private const val TAG = "ConciergeTracker"

    /**
     * Sends a Concierge tracking event to the AEP Edge Network.
     *
     * @param conciergeEventType The Concierge event type value (e.g. "query:submitted")
     * @param data Additional event-specific data to include under the `_brandconcierge` XDM field group
     */
    fun track(conciergeEventType: String, data: Map<String, Any?> = emptyMap()) {
        val xdmData = mutableMapOf<String, Any?>(
            "eventType" to "${ConciergeConstants.Tracking.XDM_EVENT_TYPE_PREFIX}$conciergeEventType",
            "_brandconcierge" to data
        )

        val experienceEvent = ExperienceEvent.Builder()
            .setXdmSchema(xdmData)
            .build()

        Edge.sendEvent(experienceEvent, null)

        Log.trace(
            ConciergeConstants.EXTENSION_NAME,
            TAG,
            "Sent Edge tracking event: $conciergeEventType"
        )
    }
}
