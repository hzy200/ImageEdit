package com.example.imageedit

import com.example.imageedit.core.image.UserUriAllowlist
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserUriAllowlistTest {
    @After
    fun tearDown() {
        UserUriAllowlist.clear()
    }

    @Test
    fun onlyExplicitlyAllowedUrisPass() {
        UserUriAllowlist.clear()
        assertFalse(UserUriAllowlist.contains(null))
        assertFalse(UserUriAllowlist.contains("content://remote/invented"))

        UserUriAllowlist.allow("content://user/picked")
        assertTrue(UserUriAllowlist.contains("content://user/picked"))
        assertFalse(UserUriAllowlist.contains("content://remote/other"))
        assertTrue(UserUriAllowlist.snapshot().contains("content://user/picked"))

        UserUriAllowlist.clear()
        assertFalse(UserUriAllowlist.contains("content://user/picked"))
    }
}
