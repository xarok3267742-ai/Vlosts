package com.vslot.app

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class MergedManifestSecurityTest {
    @Test
    fun `merged manifest exposes only reviewed components`() {
        val variant = BuildConfig.BUILD_TYPE
        val variantTitle = variant.replaceFirstChar(Char::uppercase)
        val manifestPath = Path.of(
            "build/intermediates/merged_manifests/$variant/process${variantTitle}Manifest/AndroidManifest.xml"
        )
        assertTrue("Merged $variant manifest is missing at $manifestPath", Files.exists(manifestPath))

        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifestPath.toFile())
        val components = COMPONENT_TAGS.flatMap { tag ->
            val nodes = document.getElementsByTagName(tag)
            (0 until nodes.length).map { nodes.item(it) as Element }
        }
        val requestedPermissions = document.getElementsByTagName("uses-permission").let { nodes ->
            (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .map { it.androidAttribute("name") }
                .toSet()
        }
        val componentNames = components.map { it.androidAttribute("name") }
        assertTrue(
            "Merged $variant manifest must not start VSlotApplication before credential unlock",
            components.none { it.androidAttribute("directBootAware") == "true" }
        )

        assertFalse(
            "Unused AppMetrica preinstall provider must not be merged",
            APP_METRICA_PRELOAD_PROVIDER in componentNames
        )
        val appMetricaStatusReceiver = components.single {
            it.androidAttribute("name") == APP_METRICA_NOTIFICATION_STATUS_RECEIVER
        }
        assertEquals(
            "AppMetrica notification-status receiver must not accept explicit third-party broadcasts",
            "false",
            appMetricaStatusReceiver.androidAttribute("exported")
        )
        val firebaseInitProvider = components.single {
            it.androidAttribute("name") == FIREBASE_INIT_PROVIDER
        }
        assertEquals(
            "Firebase initialization provider must not be exported",
            "false",
            firebaseInitProvider.androidAttribute("exported")
        )

        val exportedComponents = components
            .filter { it.androidAttribute("exported") == "true" }
        val actualExports = exportedComponents.associate { component ->
            component.androidAttribute("name") to ExportedComponent(
                tag = component.tagName,
                permission = component.androidAttribute("permission"),
                actions = component.descendantAndroidNames("action"),
                categories = component.descendantAndroidNames("category")
            )
        }
        assertEquals(
            "Merged $variant manifest contains duplicate exported component names",
            exportedComponents.size,
            actualExports.size
        )
        assertEquals(
            "Merged $variant manifest exported surface changed without security review",
            expectedExports(includeQaComponents = BuildConfig.QA_ENABLED),
            actualExports
        )

        val expectedPermissions = REVIEWED_PERMISSIONS +
            "${BuildConfig.APPLICATION_ID}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        assertEquals(
            "Merged $variant manifest permissions changed without security review",
            expectedPermissions,
            requestedPermissions
        )

        val queries = document.getElementsByTagName("queries")
        assertEquals("Merged $variant manifest must contain one queries declaration", 1, queries.length)
        val queriesElement = queries.item(0) as Element
        assertEquals(
            "Merged $variant manifest package visibility actions changed without privacy review",
            REVIEWED_QUERY_ACTIONS,
            queriesElement.descendantAndroidNames("action")
        )
        assertTrue(
            "Merged $variant manifest must not query packages by name",
            queriesElement.descendantAndroidNames("package").isEmpty()
        )
        assertTrue(
            "Merged $variant manifest must not query content providers",
            queriesElement.descendantAndroidNames("provider").isEmpty()
        )

        val internalReceiverPermission = document.getElementsByTagName("permission").let { nodes ->
            (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .singleOrNull {
                    it.androidAttribute("name") ==
                        "${BuildConfig.APPLICATION_ID}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
                }
        }
        assertTrue(
            "Internal receiver permission must be declared for $variant",
            internalReceiverPermission != null
        )
        assertEquals(
            "Internal receiver permission must remain signature-protected",
            "signature",
            internalReceiverPermission?.androidAttribute("protectionLevel")
        )

        val application = document.getElementsByTagName("application").item(0) as Element
        val metadata = document.getElementsByTagName("meta-data").let { nodes ->
            (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .associate { element ->
                    element.androidAttribute("name") to element.androidAttribute("value")
                }
        }
        assertEquals(
            "Firebase Messaging installation ID mode must remain disabled",
            "false",
            metadata["firebase_messaging_installation_id_enabled"]
        )
        assertEquals("App backup must remain disabled", "false", application.androidAttribute("allowBackup"))
        assertEquals(
            "Android 12+ cloud and device-transfer exclusions must remain attached",
            "@xml/data_extraction_rules",
            application.androidAttribute("dataExtractionRules")
        )
        assertEquals(
            "Legacy backup exclusions must remain attached",
            "@xml/backup_rules",
            application.androidAttribute("fullBackupContent")
        )
        assertEquals(
            "Cleartext network traffic must remain disabled",
            "false",
            application.androidAttribute("usesCleartextTraffic")
        )
        if (BuildConfig.QA_ENABLED) {
            if (BuildConfig.DEBUG) {
                assertEquals(
                    "Ordinary debug manifest must remain debuggable",
                    "true",
                    application.androidAttribute("debuggable")
                )
            } else {
                assertFalse(
                    "Release-like QA manifest must never be debuggable",
                    application.androidAttribute("debuggable") == "true"
                )
            }
            assertTrue("QA-enabled manifest must include QA activity", QA_RESULT_ACTIVITY in componentNames)
            assertTrue("QA-enabled manifest must include QA state receiver", QA_STATE_RECEIVER in componentNames)
        } else {
            assertFalse("Release manifest must never be debuggable", application.androidAttribute("debuggable") == "true")
            assertFalse("Release manifest must never be test-only", application.androidAttribute("testOnly") == "true")
            assertFalse("Release manifest must not include QA activity", QA_RESULT_ACTIVITY in componentNames)
            assertFalse("Release manifest must not include QA state receiver", QA_STATE_RECEIVER in componentNames)
        }
    }

    private fun Element.androidAttribute(name: String): String {
        return getAttributeNS(ANDROID_NAMESPACE, name)
    }

    private fun Element.descendantAndroidNames(tag: String): Set<String> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .map { it.androidAttribute("name") }
            .toSet()
    }

    private fun expectedExports(includeQaComponents: Boolean): Map<String, ExportedComponent> {
        return buildMap {
            put(
                MAIN_ACTIVITY,
                ExportedComponent(
                    tag = "activity",
                    permission = "",
                    actions = setOf("android.intent.action.MAIN"),
                    categories = setOf("android.intent.category.LAUNCHER")
                )
            )
            put(
                FIREBASE_INSTANCE_ID_RECEIVER,
                ExportedComponent(
                    tag = "receiver",
                    permission = "com.google.android.c2dm.permission.SEND",
                    actions = setOf("com.google.android.c2dm.intent.RECEIVE")
                )
            )
            put(
                PROFILE_INSTALL_RECEIVER,
                ExportedComponent(
                    tag = "receiver",
                    permission = "android.permission.DUMP",
                    actions = setOf(
                        "androidx.profileinstaller.action.INSTALL_PROFILE",
                        "androidx.profileinstaller.action.SKIP_FILE",
                        "androidx.profileinstaller.action.SAVE_PROFILE",
                        "androidx.profileinstaller.action.BENCHMARK_OPERATION"
                    )
                )
            )
            if (includeQaComponents) {
                put(
                    QA_RESULT_ACTIVITY,
                    ExportedComponent(tag = "activity", permission = "android.permission.DUMP")
                )
                put(
                    QA_STATE_RECEIVER,
                    ExportedComponent(
                        tag = "receiver",
                        permission = "android.permission.DUMP",
                        actions = setOf("com.vslot.app.debug.QA_STATE")
                    )
                )
            }
        }
    }

    private data class ExportedComponent(
        val tag: String,
        val permission: String,
        val actions: Set<String> = emptySet(),
        val categories: Set<String> = emptySet()
    )

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val APP_METRICA_PRELOAD_PROVIDER =
            "io.appmetrica.analytics.internal.PreloadInfoContentProvider"
        const val APP_METRICA_NOTIFICATION_STATUS_RECEIVER =
            "io.appmetrica.analytics.push.internal.receiver.AppMetricaPushNotificationStatusChangeHandler"
        const val FIREBASE_INSTANCE_ID_RECEIVER =
            "com.google.firebase.iid.FirebaseInstanceIdReceiver"
        const val FIREBASE_INIT_PROVIDER =
            "com.google.firebase.provider.FirebaseInitProvider"
        const val MAIN_ACTIVITY = "com.vslot.app.MainActivity"
        const val PROFILE_INSTALL_RECEIVER = "androidx.profileinstaller.ProfileInstallReceiver"
        const val QA_RESULT_ACTIVITY = "com.vslot.app.debug.QaResultDialogActivity"
        const val QA_STATE_RECEIVER = "com.vslot.app.debug.QaStateReceiver"
        val COMPONENT_TAGS = listOf("activity", "activity-alias", "service", "receiver", "provider")
        val REVIEWED_QUERY_ACTIONS = setOf(
            "ru.vk.store.sdk.install.referrer.InstallReferrerProvider"
        )
        val REVIEWED_PERMISSIONS = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.WAKE_LOCK",
            "com.google.android.c2dm.permission.RECEIVE",
            "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE"
        )
    }
}
