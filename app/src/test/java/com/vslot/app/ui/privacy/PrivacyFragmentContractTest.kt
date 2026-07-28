package com.vslot.app.ui.privacy

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyFragmentContractTest {
    @Test
    fun `blocked main frame navigation is visible and stops loading`() {
        val source = Path.of("src/main/java/com/vslot/app/ui/privacy/PrivacyFragment.kt").readText()

        assertTrue(source.contains("!allowed && request.isForMainFrame"))
        assertTrue(source.contains("view.stopLoading()"))
        assertTrue(source.contains("\"blocked_origin\""))
        assertTrue(source.contains("showError("))
    }
}
