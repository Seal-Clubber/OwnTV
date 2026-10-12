package tv.own.owntv.features.sports

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Exercise production HTTP requests, native cache and restriction handling without real network access. */
class SportsApiTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/fotmob/$name.json")!!.bufferedReader().use { it.readText() }
    private class Preferences : ContextWrapper(null) {
        val values = ConcurrentHashMap<String, Any>()
        private val editor: SharedPreferences.Editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString", "putLong" -> { if (args!![1] != null) values[args[0] as String] = args[1] else values.remove(args[0]); proxy }
                "apply" -> null
                "commit" -> true
                else -> proxy
            }
        } as SharedPreferences.Editor
        private val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString", "getLong" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        fun expire() { values.keys.filter { it.startsWith("time:") }.forEach { values[it] = System.currentTimeMillis() - 11 * 60_000L } }
    }
    private fun fixture(): String {
        val fixture = JSONObject(sample("team-8695")).getJSONObject("fixtures").getJSONObject("allFixtures").getJSONArray("fixtures").let { rows ->
            (0 until rows.length()).map(rows::getJSONObject).first { it.getInt("id") == 6106284 }
        }
        fixture.getJSONObject("status").put("utcTime", Instant.now().plusSeconds(14_400).toString())
        return JSONObject().put("details", JSONObject().put("id", 8695).put("name", "Fenerbahçe"))
            .put("fixtures", JSONObject().put("allFixtures", JSONObject().put("fixtures", JSONArray().put(fixture)))).toString()
    }
    @Test fun requestsUseValidatedPathsAndCacheFixturesAndCountryFeeds() = runBlocking {
        val context = Preferences()
        val count = AtomicInteger()
        val teamJson = fixture()
        val kickoff = FotMobJson.fixtures(teamJson, System.currentTimeMillis()).single().kickoffMs!!
        val stations = JSONObject(sample("tv-NL"))
        stations.getJSONArray("6106284").getJSONObject(0).put("startTime", "/Date($kickoff)/")
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            count.incrementAndGet()
            val request = chain.request()
            synchronized(paths) { paths += request.url.encodedPath }
            val json = when (request.url.encodedPath) {
                "/api/data/teams" -> { assertEquals("8695", request.url.queryParameter("id")); teamJson }
                "/api/data/tvlistings" -> {
                    assertNull(request.url.queryParameter("ids"))
                    if (request.url.queryParameter("countryCode") == "NL") stations.toString() else "{}"
                }
                "/yayin-akisi" -> "<script id=\"__NEXT_DATA__\" type=\"application/json\">${sample("trt-schedule")}</script>"
                else -> error("unexpected endpoint")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(json.toResponseBody()).build()
        }.build()
        val api = SportsApi(context, client)
        val team = SportsTeam.defaults.first { it.id == "8695" }
        val matches = api.next(team).matches
        assertEquals("Ziggo Sport 5", api.broadcasts(matches).listings["6106284"]!!.first().name)
        api.next(team); api.broadcasts(matches); api.team("8695")
        assertEquals(4, count.get())
        assertTrue(paths.contains("/api/data/tvlistings"))
    }
    @Test fun offlineFixtureCacheIsLabelledAndExpiredBroadcastsAreNotReused() = runBlocking {
        val context = Preferences()
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        val teamJson = fixture()
        val kickoff = FotMobJson.fixtures(teamJson, System.currentTimeMillis()).single().kickoffMs!!
        val stations = JSONObject(sample("tv-NL"))
        stations.getJSONArray("6106284").getJSONObject(0).put("startTime", "/Date($kickoff)/")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val json = when (request.url.encodedPath) {
                "/api/data/teams" -> teamJson
                "/api/data/tvlistings" -> if (request.url.queryParameter("countryCode") == "NL") stations.toString() else "{}"
                else -> "<script id=\"__NEXT_DATA__\">${sample("trt-schedule")}</script>"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (failed.get()) 503 else 200).message("status").body(json.toResponseBody()).build()
        }.build()
        val api = SportsApi(context, client)
        val team = SportsTeam.defaults.first { it.id == "8695" }
        val matches = api.next(team).matches
        assertFalse(api.broadcasts(matches).listings.values.flatten().isEmpty())
        context.expire(); failed.set(true)
        assertTrue(api.next(team).stale)
        val listings = api.broadcasts(matches)
        assertTrue(listings.unavailable)
        assertTrue(listings.listings.values.flatten().isEmpty())
    }
    @Test fun forbiddenResponseStopsFurtherRequestsToFotMob() = runBlocking {
        val count = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            count.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("Forbidden").body("{}".toResponseBody()).build()
        }.build()
        val api = SportsApi(Preferences(), client)
        assertTrue(runCatching { api.next(SportsTeam.defaults.first()) }.isFailure)
        assertTrue(runCatching { api.findTeams("Fenerbahce") }.isFailure)
        assertEquals(1, count.get())
    }
    @Test fun teamSearchUsesARealPathRatherThanEncodingTheSlash() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/api/data/search/suggest", chain.request().url.encodedPath)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(sample("search").toResponseBody()).build()
        }.build()
        assertTrue(SportsApi(Preferences(), client).findTeams("Fenerbahce").any { it.id == "8695" })
    }

    @Test fun liveFeedRefreshesAfterOneMinuteAndOfflineScoreIsMarkedStale() = runBlocking {
        val context = Preferences()
        val count = AtomicInteger()
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        val live = JSONObject(fixture()).getJSONObject("fixtures").getJSONObject("allFixtures").getJSONArray("fixtures").getJSONObject(0)
        live.getJSONObject("status").put("utcTime", Instant.now().minusSeconds(3600).toString()).put("started", true).put("finished", false)
        live.put("notStarted", false)
        live.getJSONObject("home").put("score", 2)
        live.getJSONObject("away").put("score", 1)
        val json = JSONObject().put("leagues", JSONArray().put(JSONObject().put("name", "Example").put("matches", JSONArray().put(live)))).toString()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/api/data/matches", chain.request().url.encodedPath)
            count.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (failed.get()) 503 else 200).message("status").body(json.toResponseBody()).build()
        }.build()
        val api = SportsApi(context, client)
        assertEquals(2, api.today().matches.single().homeScore)
        api.today(); assertEquals(1, count.get())
        context.values.keys.filter { it.startsWith("time:") }.forEach { context.values[it] = System.currentTimeMillis() - 61_000 }
        api.today(); assertEquals(2, count.get())
        context.expire(); failed.set(true)
        val stale = api.today()
        assertTrue(stale.stale)
        assertTrue(stale.matches.single().scoreStale)
    }
}
