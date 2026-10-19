package dev.droidprobe.okhttp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathGlobTest {
    @Test
    fun `single star matches exactly one segment`() {
        assertTrue(PathGlob.matches("/orders/*/confirm", "/orders/abc/confirm"))
        assertFalse(PathGlob.matches("/orders/*/confirm", "/orders/a/b/confirm"))
        assertFalse(PathGlob.matches("/orders/*/confirm", "/orders/confirm"))
        assertTrue(PathGlob.matches("/orders/v*", "/orders/v3"))
        assertFalse(PathGlob.matches("/orders/v*", "/orders/x3"))
    }

    @Test
    fun `double star matches any number of segments`() {
        assertTrue(PathGlob.matches("/api/**/verify", "/api/verify"))
        assertTrue(PathGlob.matches("/api/**/verify", "/api/a/b/c/verify"))
        assertTrue(PathGlob.matches("/**", "/anything/at/all"))
        assertFalse(PathGlob.matches("/api/**/verify", "/api/a/b/c/check"))
    }

    @Test
    fun `literal characters are not regex`() {
        assertTrue(PathGlob.matches("/a.b/c", "/a.b/c"))
        assertFalse(PathGlob.matches("/a.b/c", "/aXb/c"))
        assertTrue(PathGlob.matches("orders/1", "/orders/1"))
    }
}
