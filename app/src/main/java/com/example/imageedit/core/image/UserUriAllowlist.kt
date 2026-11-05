package com.example.imageedit.core.image

import java.util.concurrent.ConcurrentHashMap

/**
 * URIs the user explicitly picked in this session (local background / texture).
 * Remote plans and restored histories may only reference these; anything else
 * is rejected before a file is opened.
 */
object UserUriAllowlist {
    private val allowed = ConcurrentHashMap.newKeySet<String>()

    fun allow(uri: String?) {
        if (!uri.isNullOrBlank()) allowed += uri
    }

    fun contains(uri: String?): Boolean = !uri.isNullOrBlank() && allowed.contains(uri)

    fun snapshot(): Set<String> = allowed.toSet()

    fun clear() {
        allowed.clear()
    }
}
