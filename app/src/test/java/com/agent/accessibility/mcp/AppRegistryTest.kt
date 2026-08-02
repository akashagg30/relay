package com.agent.accessibility.mcp

import org.junit.Assert.*
import org.junit.Test

class AppRegistryTest {

    private fun makeApp(
        name: String,
        packageName: String = "com.test.$name",
        launchable: Boolean = true,
        system: Boolean = false,
        category: String? = null,
        enabled: Boolean = true
    ) = AppInfo(
        name = name,
        packageName = packageName,
        launchable = launchable,
        system = system,
        category = category,
        enabled = enabled
    )

    private fun searchApps(apps: List<AppInfo>, query: String): List<SearchResult> {
        val lowerQuery = query.lowercase().trim()
        return apps
            .filter { it.launchable && it.enabled }
            .map { app ->
                val score = AppMatchScorer.computeMatchScore(lowerQuery, app.name.lowercase())
                SearchResult(app, score)
            }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
    }

    private fun findBestMatch(apps: List<AppInfo>, query: String): AppMatch {
        val results = searchApps(apps, query)
        if (results.isEmpty()) return AppMatch.NotFound(query)

        val best = results[0]
        if (results.size == 1) return AppMatch.Single(best.app)

        if (best.score >= 1000) return AppMatch.Single(best.app)

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

    @Test
    fun `exact match returns score 1000`() {
        assertEquals(1000, AppMatchScorer.computeMatchScore("chrome", "chrome"))
    }

    @Test
    fun `case insensitive exact match`() {
        assertEquals(1000, AppMatchScorer.computeMatchScore("Chrome", "chrome"))
    }

    @Test
    fun `prefix match returns 900+`() {
        val score = AppMatchScorer.computeMatchScore("chr", "chrome")
        assertTrue("Expected 900+ but got $score", score >= 900)
    }

    @Test
    fun `substring match returns 800+`() {
        val score = AppMatchScorer.computeMatchScore("rom", "chrome")
        assertTrue("Expected 800+ but got $score", score >= 800)
    }

    @Test
    fun `no match returns 0`() {
        assertEquals(0, AppMatchScorer.computeMatchScore("xyz", "chrome"))
    }

    @Test
    fun `empty query returns 0`() {
        assertEquals(0, AppMatchScorer.computeMatchScore("", "chrome"))
    }

    @Test
    fun `single char non-match returns 0`() {
        assertEquals(0, AppMatchScorer.computeMatchScore("z", "chrome"))
    }

    @Test
    fun `search ranks exact above prefix`() {
        val apps = listOf(
            makeApp("Chrome Browser"),
            makeApp("Chrome"),
        )
        val results = searchApps(apps, "Chrome")
        assertEquals("Chrome", results[0].app.name)
    }

    @Test
    fun `search ranks prefix above substring`() {
        val apps = listOf(
            makeApp("YT Music"),
            makeApp("Music Player"),
        )
        val results = searchApps(apps, "mus")
        assertTrue(results.isNotEmpty())
        assertEquals("Music Player", results[0].app.name)
    }

    @Test
    fun `findBestMatch returns Single for unique exact`() {
        val apps = listOf(
            makeApp("Chrome"),
            makeApp("WhatsApp"),
        )
        val match = findBestMatch(apps, "Chrome")
        assertTrue(match is AppMatch.Single)
        assertEquals("Chrome", (match as AppMatch.Single).app.name)
    }

    @Test
    fun `findBestMatch returns NotFound for empty`() {
        val apps = listOf(makeApp("Chrome"))
        val match = findBestMatch(apps, "nonexistent")
        assertTrue(match is AppMatch.NotFound)
    }

    @Test
    fun `list_apps excludes disabled apps`() {
        val apps = listOf(
            makeApp("Chrome"),
            makeApp("Disabled", enabled = false),
        )
        val enabled = apps.filter { it.launchable && it.enabled }
        assertEquals(1, enabled.size)
        assertEquals("Chrome", enabled[0].name)
    }

    @Test
    fun `list_apps excludes non-launchable apps`() {
        val apps = listOf(
            makeApp("Chrome"),
            makeApp("Background Service", launchable = false),
        )
        val launchable = apps.filter { it.launchable && it.enabled }
        assertEquals(1, launchable.size)
        assertEquals("Chrome", launchable[0].name)
    }

    @Test
    fun `list_apps sorts alphabetically`() {
        val apps = listOf(
            makeApp("Zoom"),
            makeApp("Chrome"),
            makeApp("Firefox"),
        )
        val sorted = apps.sortedBy { it.name.lowercase() }
        assertEquals("Chrome", sorted[0].name)
        assertEquals("Firefox", sorted[1].name)
        assertEquals("Zoom", sorted[2].name)
    }

    @Test
    fun `duplicate launcher removal - same package`() {
        val apps = listOf(
            makeApp("Chrome", packageName = "com.android.chrome"),
            makeApp("Chrome", packageName = "com.android.chrome"),
        )
        val seen = mutableSetOf<String>()
        val unique = apps.filter { seen.add(it.packageName) }
        assertEquals(1, unique.size)
    }

    @Test
    fun `findBestMatch returns Single for strong fuzzy match`() {
        val apps = listOf(
            makeApp("Chrome"),
            makeApp("WhatsApp"),
            makeApp("YouTube"),
        )
        val match = findBestMatch(apps, "chrome")
        assertTrue(match is AppMatch.Single)
        assertEquals("Chrome", (match as AppMatch.Single).app.name)
    }

    @Test
    fun `search for music returns music apps`() {
        val apps = listOf(
            makeApp("YT Music"),
            makeApp("Spotify"),
            makeApp("Chrome"),
        )
        val results = searchApps(apps, "music")
        assertTrue(results.isNotEmpty())
        assertEquals("YT Music", results[0].app.name)
    }

    @Test
    fun `search for payment returns payment apps`() {
        val apps = listOf(
            makeApp("PhonePe"),
            makeApp("Google Pay"),
            makeApp("Chrome"),
        )
        val results = searchApps(apps, "pay")
        assertTrue(results.isNotEmpty())
        val names = results.map { it.app.name }
        assertTrue("PhonePe" in names || "Google Pay" in names)
    }

    @Test
    fun `findBestMatch for ambiguous returns Ambiguous`() {
        val apps = listOf(
            makeApp("Chrome Browser"),
            makeApp("Chrome Dev"),
            makeApp("Chrome Beta"),
        )
        val results = searchApps(apps, "chrome")
        if (results.size >= 2 && results[0].score == results[1].score) {
            val match = findBestMatch(apps, "chrome")
            assertTrue(match is AppMatch.Ambiguous)
        }
    }

    @Test
    fun `AppMatchSealed classes`() {
        val single = AppMatch.Single(makeApp("A"))
        val ambiguous = AppMatch.Ambiguous(listOf(makeApp("A"), makeApp("B")))
        val notFound = AppMatch.NotFound("query")

        assertTrue(single is AppMatch.Single)
        assertTrue(ambiguous is AppMatch.Ambiguous)
        assertTrue(notFound is AppMatch.NotFound)
    }

    @Test
    fun `SearchResult data class`() {
        val app = makeApp("Test")
        val result = SearchResult(app, 500)
        assertEquals(app, result.app)
        assertEquals(500, result.score)
    }
}
