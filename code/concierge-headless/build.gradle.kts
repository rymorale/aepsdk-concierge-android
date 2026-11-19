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

import com.adobe.marketing.mobile.gradle.BuildConstants

plugins {
    id("aep-library")
}

val headlessModuleName: String by project
val headlessModuleVersion: String by project
val coroutinesVersion = "1.7.3"

// Headless Brand Concierge client. It must not depend on the AEP Mobile SDK or on Compose.
aepLibrary {
    namespace = "com.adobe.marketing.mobile.concierge.headless"
    moduleName = headlessModuleName
    moduleVersion = headlessModuleVersion
    enableSpotless = true
    enableCheckStyle = true
    enableDokkaDoc = true

    publishing {
        gitRepoName = "aepsdk-concierge-android"
        mavenRepoDescription = "Headless Android client for the Adobe Brand Concierge conversation API"

        addMavenDependency("org.jetbrains.kotlin", "kotlin-stdlib-jdk8", BuildConstants.Versions.KOTLIN)
        addMavenDependency("org.jetbrains.kotlinx", "kotlinx-coroutines-android", coroutinesVersion)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")

    testImplementation("junit:junit:4.13.2")
}
