package com.agent.accessibility.mcp

import android.content.Context
import android.content.SharedPreferences

class AuthManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mcp_auth", Context.MODE_PRIVATE)

    // DEVELOPMENT TOKEN — revert to generateToken() for production
    val token: String = "11223344"

    fun validate(authHeader: String?): Boolean {
        if (authHeader == null) return false
        val provided = authHeader.removePrefix("Bearer ").trim()
        return provided == token
    }
}
