package com.kaixu.desktop

import com.kaixu.desktop.presentation.ui.resources.isDesktopResourceUrl
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ResourceUrlTest {
    @Test
    fun browserLinksMustHaveAValidHttpHostAndUriSyntax() {
        for (url in listOf("https://example.com/a?x=1#part", "http://localhost:8080/", "https://[::1]/")) {
            assertTrue(url, isDesktopResourceUrl(url))
        }
        for (url in listOf("javascript:alert(1)", "file:///C:/private.txt", "https://[::::]/", "https://example.com/<bad>", "https://example.com/%xx", "https://user:password@example.com/")) {
            assertFalse(url, isDesktopResourceUrl(url))
        }
    }
}
