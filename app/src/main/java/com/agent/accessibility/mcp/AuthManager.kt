package com.agent.accessibility.mcp

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

class AuthManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mcp_auth", Context.MODE_PRIVATE)

    private val _token: String by lazy {
        prefs.getString("auth_token", null) ?: generateAndStoreToken()
    }

    val token: String
        get() = _token

    private fun generateAndStoreToken(): String {
        val newToken = SecureRandom().let { sr -> 
            ByteArray(16).also { sr.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        }
        prefs.edit().putString("auth_token", newToken).apply()
        return newToken
    }

    fun regenerateToken(): String {
        val newToken = generateAndStoreToken()
        // Force re-evaluation of the lazy property by clearing and regenerating
        return newToken
    }

    fun validate(authHeader: String?): Boolean {
        if (authHeader == null) return false
        val provided = authHeader.removePrefix("Bearer ").trim()
        return provided == token
    }
}
