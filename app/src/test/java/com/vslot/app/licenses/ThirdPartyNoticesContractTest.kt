package com.vslot.app.licenses

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.readText

class ThirdPartyNoticesContractTest {
    private val noticePath = Path.of("src/main/assets/third_party_notices.txt")

    @Test
    fun noticeCoversEveryLockedAppMetricaRuntimeModule() {
        val notice = noticePath.readText()
        val lockedModules = Path.of("gradle.lockfile")
            .readText()
            .lineSequence()
            .filter { line ->
                line.startsWith("io.appmetrica.analytics:") &&
                    line.substringAfter("=", missingDelimiterValue = "")
                        .split(',')
                        .any { configuration -> configuration == "releaseRuntimeClasspath" }
            }
            .map { line -> line.substringBefore("=") }
            .toSet()

        assertEquals(20, lockedModules.size)
        assertFalse(lockedModules.any { coordinate -> coordinate.contains("analytics-appsetid") })
        lockedModules.forEach { coordinate ->
            assertEquals("Expected one notice entry for $coordinate", 1, notice.windowCount(coordinate))
        }
    }

    @Test
    fun noticeContainsTheCompleteReviewedMitLicense() {
        val notice = noticePath.readText()
        val actual = notice.substring(
            startIndex = notice.indexOf("The MIT License (MIT)"),
            endIndex = notice.indexOf("AndroidX DataStore repackaged Protocol Buffers runtime")
        ).trim()

        assertEquals(EXPECTED_APPMETRICA_MIT, actual)
    }

    @Test
    fun noticeContainsTheCompleteReviewedProtobufBsdLicenseAndCopyright() {
        val notice = noticePath.readText()
        val actual = notice.substring(notice.indexOf("Copyright 2008 Google Inc.")).trim()

        assertEquals(EXPECTED_PROTOBUF_BSD, actual)
    }

    @Test
    fun noticeCoversThePublicSuffixListEmbeddedInOkHttp() {
        val notice = noticePath.readText()

        assertTrue(notice.contains("com.squareup.okhttp3:okhttp:4.11.0 | Apache-2.0,MPL-2.0"))
        assertTrue(notice.contains("OkHttp Public Suffix List"))
        assertTrue(notice.contains("https://publicsuffix.org/list/public_suffix_list.dat"))
        assertTrue(notice.contains("https://mozilla.org/MPL/2.0/"))
    }

    @Test
    fun settingsExposeTheLocalNoticeWithoutWebViewOrNetwork() {
        val fragment = Path.of("src/main/java/com/vslot/app/ui/settings/SettingsFragment.kt").readText()
        val dialog = Path.of(
            "src/main/java/com/vslot/app/ui/dialog/ThirdPartyNoticesDialogFragment.kt"
        ).readText()
        val portrait = Path.of("src/main/res/layout/fragment_settings.xml").readText()
        val landscape = Path.of("src/main/res/layout-land/fragment_settings.xml").readText()

        assertTrue(fragment.contains("binding.noticesButton.setOnClickListener"))
        assertTrue(fragment.contains("ThirdPartyNoticesDialogFragment().show("))
        assertTrue(dialog.contains("THIRD_PARTY_NOTICES_ASSETS.joinToString"))
        assertTrue(dialog.contains("assets.open(assetName)"))
        assertTrue(dialog.contains("third_party_embedded_licenses.txt"))
        assertTrue(dialog.contains("setTextIsSelectable(true)"))
        assertTrue(dialog.contains("DialogFragment"))
        assertFalse(dialog.contains("WebView"))
        assertFalse(dialog.contains("ACTION_VIEW"))
        assertTrue(portrait.contains("android:id=\"@+id/noticesButton\""))
        assertTrue(landscape.contains("android:id=\"@+id/noticesButton\""))
        assertTrue(portrait.contains("@drawable/label_third_party_notices"))
        assertTrue(landscape.contains("@drawable/label_third_party_notices"))
    }

    @Test
    fun releaseAndQaPackagingChecksAreWired() {
        val buildScript = Path.of("build.gradle.kts").readText()

        assertTrue(buildScript.contains("tasks.register(\"verifyThirdPartyNoticesSource\")"))
        assertTrue(buildScript.contains("tasks.register(\"generateEmbeddedThirdPartyLicenses\")"))
        assertTrue(buildScript.contains("tasks.register(\"verifyQaThirdPartyNotices\")"))
        assertTrue(buildScript.contains("tasks.register(\"verifyReleaseThirdPartyNotices\")"))
        assertTrue(buildScript.contains("zip.getEntry(\"assets/\$thirdPartyNoticesAssetName\")"))
        assertTrue(buildScript.contains("dependsOn(\"mergeReleaseAssets\", verifyThirdPartyNoticesSource)"))
        assertTrue(buildScript.contains("task.name.contains(\"lint\", ignoreCase = true)"))
        assertTrue(buildScript.contains("verifyThirdPartyNotices,"))
        assertFalse(
            "Unit tests must not depend on production release inputs",
            buildScript.substringAfter("tasks.withType<Test>().configureEach")
                .substringBefore("val verifyStoreReadiness")
                .contains("verifyStoreReadiness")
        )
    }

    private fun String.windowCount(needle: String): Int {
        var count = 0
        var start = 0
        while (true) {
            val index = indexOf(needle, start)
            if (index < 0) return count
            count += 1
            start = index + needle.length
        }
    }

    private companion object {
        val EXPECTED_APPMETRICA_MIT = """
            The MIT License (MIT)

            Copyright (c) 2023 YANDEX LLC

            Permission is hereby granted, free of charge, to any person obtaining a copy
            of this software and associated documentation files (the "Software"), to deal
            in the Software without restriction, including without limitation the rights
            to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
            copies of the Software, and to permit persons to whom the Software is
            furnished to do so, subject to the following conditions:

            The above copyright notice and this permission notice shall be included in
            all copies or substantial portions of the Software.

            THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
            IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
            FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
            AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
            LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
            OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
            THE SOFTWARE.
        """.trimIndent()

        val EXPECTED_PROTOBUF_BSD = """
            Copyright 2008 Google Inc. All rights reserved.

            Redistribution and use in source and binary forms, with or without
            modification, are permitted provided that the following conditions are met:

                * Redistributions of source code must retain the above copyright
            notice, this list of conditions and the following disclaimer.
                * Redistributions in binary form must reproduce the above
            copyright notice, this list of conditions and the following disclaimer
            in the documentation and/or other materials provided with the
            distribution.
                * Neither the name of Google Inc. nor the names of its
            contributors may be used to endorse or promote products derived from
            this software without specific prior written permission.

            THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
            "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
            LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
            A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
            OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
            SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
            LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
            DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
            THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
            (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
            OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

            Code generated by the Protocol Buffer compiler is owned by the owner of the
            input file used when generating it. This code is not standalone and requires
            a support library to be linked with it. This support library is itself
            covered by the above license.
        """.trimIndent()
    }
}
