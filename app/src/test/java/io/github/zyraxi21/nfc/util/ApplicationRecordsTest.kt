package io.github.zyraxi21.nfc.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationRecordsTest {
    @Test
    fun acceptsPackageNamesIncludingWhitespaceAndDebugSuffix() {
        listOf("com.example.app", " io.github.zyraxi21.nfc.debug ", "com.example_app.target2")
            .forEach { assertTrue(it, isValidApplicationPackageName(it)) }
    }

    @Test
    fun rejectsLabelsUrlsAndMalformedPackageNames() {
        listOf("", "  ", "微信", "Example App", "https://example.com", "com..app",
            "com.example.", ".com.example", "com.123app", "com.example-app", "com.example.\napp")
            .forEach { assertFalse(it, isValidApplicationPackageName(it)) }
    }
}
