package com.agent.accessibility.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.agent.accessibility.service.AgentAccessibilityService
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

data class AppInfo(
    val name: String,
    val packageName: String,
    val launchable: Boolean,
    val system: Boolean,
    val category: String?,
    val enabled: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("package", packageName)
        put("launchable", launchable)
        put("system", system)
        put("category", category ?: JSONObject.NULL)
        put("enabled", enabled)
    }
}

class AppRegistry(private val context: Context) {

    companion object {
        private const val TAG = "AppRegistry"
    }

    private val packageManager: PackageManager = context.packageManager
    private val loaded = AtomicBoolean(false)
    @Volatile private var cachedApps: List<AppInfo> = emptyList()
    private val cacheLock = Any()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            Log.d(TAG, "Package changed: ${intent.action}")
            invalidateCache()
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        context.registerReceiver(packageReceiver, filter)
        Log.d(TAG, "Registered package change receiver")
        refreshCache()
    }

    fun unregister() {
        try {
            context.unregisterReceiver(packageReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister receiver", e)
        }
    }

    fun invalidateCache() {
        synchronized(cacheLock) {
            cachedApps = emptyList()
            loaded.set(false)
        }
        Log.d(TAG, "Cache invalidated")
    }

    fun refreshCache(): List<AppInfo> {
        synchronized(cacheLock) {
            val result = enumerateApps()
            cachedApps = result
            loaded.set(true)
            Log.d(TAG, "Cache refreshed: ${result.size} apps")
            return result
        }
    }

    fun getApps(forceRefresh: Boolean = false): List<AppInfo> {
        if (forceRefresh || !loaded.get()) {
            return refreshCache()
        }
        return cachedApps
    }

    fun getLaunchableApps(): List<AppInfo> {
        return getApps().filter { it.launchable && it.enabled }
    }

    fun searchApps(query: String): List<SearchResult> {
        val allApps = getLaunchableApps()
        val lowerQuery = query.lowercase().trim()
        if (lowerQuery.isEmpty()) return emptyList()

        val results = mutableListOf<SearchResult>()
        for (app in allApps) {
            val score = AppMatchScorer.computeMatchScore(lowerQuery, app.name.lowercase())
            if (score > 0) {
                results.add(SearchResult(app = app, score = score))
            }
        }
        return results.sortedByDescending { it.score }
    }

    fun findBestMatch(query: String): AppMatch {
        val results = searchApps(query)
        if (results.isEmpty()) return AppMatch.NotFound(query)

        val best = results[0]
        if (results.size == 1) return AppMatch.Single(best.app)

        if (best.score >= MATCH_THRESHOLD) return AppMatch.Single(best.app)

        val secondBest = results[1]
        if (best.score == secondBest.score) {
            return AppMatch.Ambiguous(results.map { it.app })
        }

        val topScore = best.score
        val threshold = (topScore * 0.8).toInt()
        val candidates = results.filter { it.score >= threshold }.map { it.app }
        return if (candidates.size > 1) {
            AppMatch.Ambiguous(candidates)
        } else {
            AppMatch.Single(best.app)
        }
    }

    fun launchApp(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false

        // Try accessibility service first (works on most devices)
        val service = AgentAccessibilityService.instance
        if (service != null) {
            try {
                val serviceIntent = Intent(intent).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
                service.startActivity(serviceIntent)
                Log.d(TAG, "Launched $packageName via accessibility service")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Accessibility launch failed for $packageName, trying context", e)
            }
        }

        // Fallback: context.startActivity
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            Log.d(TAG, "Launched $packageName via context")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Launch failed for $packageName", e)
            false
        }
    }

    private fun enumerateApps(): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }

        val seen = mutableSetOf<String>()
        val result = mutableListOf<AppInfo>()

        for (ri in resolveInfos) {
            val pkgName = ri.activityInfo?.packageName ?: continue
            if (seen.contains(pkgName)) continue
            seen.add(pkgName)

            try {
                val appInfo = packageManager.getApplicationInfo(pkgName, 0)
                val label = packageManager.getApplicationLabel(appInfo).toString()
                val enabled = appInfo.enabled
                val system = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val category = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    categoryToString(appInfo.category)
                } else null

                result.add(AppInfo(
                    name = label,
                    packageName = pkgName,
                    launchable = true,
                    system = system,
                    category = category,
                    enabled = enabled
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get info for $pkgName", e)
            }
        }

        return result.sortedBy { it.name.lowercase() }
    }

    private fun categoryToString(category: Int): String? {
        return when (category) {
            ApplicationInfo.CATEGORY_UNDEFINED -> null
            ApplicationInfo.CATEGORY_GAME -> "game"
            ApplicationInfo.CATEGORY_AUDIO -> "audio"
            ApplicationInfo.CATEGORY_VIDEO -> "video"
            ApplicationInfo.CATEGORY_IMAGE -> "image"
            ApplicationInfo.CATEGORY_SOCIAL -> "social"
            ApplicationInfo.CATEGORY_NEWS -> "news"
            ApplicationInfo.CATEGORY_MAPS -> "maps"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
            10 -> "communication"
            11 -> "tools"
            12 -> "browser"
            13 -> "education"
            14 -> "entertainment"
            15 -> "finance"
            16 -> "shopping"
            17 -> "health"
            18 -> "travel"
            19 -> "utilities"
            20 -> "car"
            else -> null
        }
    }
}

data class SearchResult(val app: AppInfo, val score: Int)

sealed class AppMatch {
    data class Single(val app: AppInfo) : AppMatch()
    data class Ambiguous(val candidates: List<AppInfo>) : AppMatch()
    data class NotFound(val query: String) : AppMatch()
}

object AppMatchScorer {
    fun computeMatchScore(query: String, appNameLower: String): Int {
        val lowerQuery = query.lowercase()
        val lowerName = appNameLower.lowercase()
        if (lowerQuery.isEmpty() || lowerName.isEmpty()) return 0

        if (lowerName == lowerQuery) return MATCH_THRESHOLD
        if (lowerName.startsWith(lowerQuery)) return PREFIX_MATCH_SCORE + (lowerQuery.length * 10).coerceAtMost(90)
        if (lowerName.contains(lowerQuery)) return CONTAINS_MATCH_SCORE + (lowerQuery.length * 10).coerceAtMost(90)

        val fuzzy = computeFuzzyScore(lowerQuery, lowerName)
        if (fuzzy > 0) return fuzzy

        return 0
    }

    private fun computeFuzzyScore(query: String, target: String): Int {
        if (query.length < 2) return 0

        var qi = 0
        var matchCount = 0
        var firstMatchIndex = -1

        for (ti in target.indices) {
            if (qi < query.length && target[ti] == query[qi]) {
                if (firstMatchIndex == -1) firstMatchIndex = ti
                matchCount++
                qi++
            }
        }

        if (qi < query.length) return 0

        val matchRatio = matchCount.toDouble() / target.length
        if (matchRatio < 0.3) return 0

        val positionBonus = if (firstMatchIndex == 0) 100 else 0
        val lengthPenalty = ((target.length - query.length) * 2).coerceAtMost(50)

        return (300 + (matchRatio * 100).toInt() + positionBonus - lengthPenalty).coerceIn(100, 499)
    }
}
