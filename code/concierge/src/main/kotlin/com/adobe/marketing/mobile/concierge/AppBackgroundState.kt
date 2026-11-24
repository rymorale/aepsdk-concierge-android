/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.adobe.marketing.mobile.concierge

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the whole app is in the background, from [ProcessLifecycleOwner], so moving between the
 * app's own activities doesn't count. Read [inBackground] on the main thread the first time.
 */
internal object AppBackgroundState {
    val inBackground: StateFlow<Boolean> by lazy {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val state = MutableStateFlow(!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { state.value = false }
            override fun onStop(owner: LifecycleOwner) { state.value = true }
        })
        state.asStateFlow()
    }
}
