package com.agent.accessibility.mcp

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.SecureRandom

class AuthManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mcp_auth", Context.MODE_PRIVATE)

    val token: String by lazy {
        prefs.getString("auth_token", null) ?: generateToken()
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val newToken = Base64.encodeToString(bytes, Base64.NO_WRAP)
        prefs.edit().putString("auth_token", newToken).apply()
        return newToken
    }

    fun validate(authHeader: String?): Boolean {
        if (authHeader == null) return false
        val provided = authHeader.removePrefix("Bearer ").trim()
        return provided == token
    }
}
