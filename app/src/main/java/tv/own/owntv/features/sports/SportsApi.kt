package tv.own.owntv.features.sports

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SportsFeed(val matches: List<SportsMatch>, val stale: Boolean, val closedIds: Set<String> = emptySet())
data class SportsBroadcastFeed(val listings: Map<String, List<SportsBroadcast>>, val unavailable: Boolean = false)
/** FotMob is primary. Only public football ids are sent; caching is native to Android, so CORS is irrelevant. */
class SportsApi(context: Context, client: OkHttpClient) {
    private val cache = context.applicationContext.getSharedPreferences("sports_public_cache", Context.MODE_PRIVATE)
    private val http = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    private val blockedHosts = ConcurrentHashMap<String, Long>()
    private data class Cached(val json: String, val stale: Boolean, val ageMs: Long = 0)
    private class HttpFailure(val status: Int) : IOException("Football feed HTTP $status")
    private suspend fun get(endpoint: String, parameters: Map<String, String>, force: Boolean = false, fixtures: Boolean = false): Cached {
        val url = "https://www.fotmob.com/api/data/".toHttpUrl().newBuilder().addPathSegments(endpoint)
            .apply { parameters.toSortedMap().forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        return getCached(url, force, fixtures, ttlMs = if (endpoint == "matches") 60_000L else 10 * 60_000L) { text ->
            if (endpoint == "teams") {
                val root = JSONObject(text)
                require(root.getJSONObject("details").opt("id")?.toString() == parameters["id"])
                JSONObject().put("details", root.getJSONObject("details")).put("fixtures", root.getJSONObject("fixtures")).toString()
            } else {
                require(org.json.JSONTokener(text).nextValue().let { it is JSONObject || it is org.json.JSONArray })
                text
            }
        }
    }
    private suspend fun getCached(url: HttpUrl, force: Boolean, fixtures: Boolean, ttlMs: Long = 10 * 60_000L, extract: (String) -> String): Cached {
        val key = "fotmob:${url}"
        val now = System.currentTimeMillis()
        val saved = cache.getString(key, null)
        val age = now - cache.getLong("time:$key", 0)
        val ttl = if (force) minOf(60_000L, ttlMs) else ttlMs
        if (saved != null && age in 0..ttl) return Cached(saved, false, age)
        return try {
            check(now >= (blockedHosts[url.host] ?: 0))
            val text = suspendCancellableCoroutine<String> { continuation ->
                val call = http.newCall(Request.Builder().url(url).build())
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (!continuation.isActive) return
                            val result = runCatching {
                                if (!it.isSuccessful) {
                                    if (it.code in setOf(403, 429)) {
                                        val wait = it.header("Retry-After")?.toLongOrNull()?.times(1_000)?.coerceIn(60_000, 86_400_000) ?: 10 * 60_000L
                                        blockedHosts[url.host] = System.currentTimeMillis() + wait
                                    }
                                    throw HttpFailure(it.code)
                                }
                                val output = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                it.body.byteStream().use { input ->
                                    while (true) {
                                        val size = input.read(buffer)
                                        if (size < 0) break
                                        check(output.size() + size <= 4_000_000)
                                        output.write(buffer, 0, size)
                                    }
                                }
                                output.toByteArray().toString(Charsets.UTF_8)
                            }
                            result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                        }
                    }
                })
            }
            val json = withContext(Dispatchers.Default) { extract(text) }
            withContext(Dispatchers.IO) { cache.edit().putString(key, json).putLong("time:$key", System.currentTimeMillis()).apply() }
            Cached(json, false)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // Stale fixture dates may be labelled; stale broadcaster assignments are never confirmed.
            if (fixtures && saved != null && age in 0..86_400_000L) Cached(saved, true, age) else throw e
        }
    }
    suspend fun next(team: SportsTeam, force: Boolean = false): SportsFeed {
        require(team.id.isNotEmpty() && team.id.all(Char::isDigit))
        val data = get("teams", mapOf("id" to team.id), force, fixtures = true)
        return SportsFeed(withContext(Dispatchers.Default) { FotMobJson.fixtures(data.json, System.currentTimeMillis()).map { it.copy(scoreStale = data.stale || data.ageMs > 90_000L) } }, data.stale)
    }
    suspend fun today(force: Boolean = false): SportsFeed {
        val day = DateTimeFormatter.BASIC_ISO_DATE.format(Instant.now().atZone(ZoneId.of("Europe/Amsterdam")).toLocalDate())
        val data = get("matches", mapOf("date" to day), force, fixtures = true)
        return withContext(Dispatchers.Default) {
            SportsFeed(FotMobJson.dailyMatches(data.json, System.currentTimeMillis()).map { it.copy(scoreStale = data.stale || data.ageMs > 90_000L) }, data.stale, FotMobJson.closedDailyIds(data.json))
        }
    }
    suspend fun broadcasts(matches: List<SportsMatch>, force: Boolean = false): SportsBroadcastFeed = coroutineScope {
        if (matches.isEmpty()) return@coroutineScope SportsBroadcastFeed(emptyMap())
        // ids-specific requests returned empty objects in live probes; full country feeds work.
        val jobs = listOf("NL", "TR").map { country -> async {
            try {
                val data = get("tvlistings", mapOf("countryCode" to country), force)
                Result.success(withContext(Dispatchers.Default) { FotMobJson.broadcasts(data.json, country, matches) })
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
        } }
        val countries = jobs.map { it.await() }
        val merged = matches.associate { match -> match.id to countries.flatMap { it.getOrNull()?.get(match.id).orEmpty() } }.toMutableMap()
        if (merged.values.any { rows -> rows.none { it.country == "Turkey" } }) {
            try {
                val url = "https://www.trt1.com.tr/yayin-akisi".toHttpUrl()
                val data = getCached(url, force, fixtures = false, extract = FotMobJson::embeddedJson)
                val official = withContext(Dispatchers.Default) { FotMobJson.trtBroadcasts(data.json, matches) }
                official.forEach { (id, rows) ->
                    if (merged[id].orEmpty().none { it.country == "Turkey" }) merged[id] = merged[id].orEmpty() + rows
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Missing schedule remains unconfirmed. */ }
        }
        SportsBroadcastFeed(merged, unavailable = countries.any { it.isFailure })
    }
    suspend fun team(id: String): SportsTeam? {
        require(id.isNotEmpty() && id.all(Char::isDigit))
        return FotMobJson.team(get("teams", mapOf("id" to id), fixtures = true).json)
    }
    suspend fun findTeams(query: String): List<SportsTeam> = FotMobJson.teams(get("search/suggest", mapOf("term" to query.take(100), "hits" to "20", "lang" to "en")).json)
}
