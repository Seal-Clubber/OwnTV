package tv.own.owntv.features.sports

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** Parsers follow captured, validated FotMob responses. Unknown shapes fail without invented fields. */
object FotMobJson {
    private fun JSONObject.text(key: String): String? = optString(key).trim().takeUnless { it.isEmpty() || it == "null" }
    private fun JSONObject.numericId(key: String): String? = text(key)?.takeIf { value -> value.all(Char::isDigit) }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
    private fun instant(value: String?): Long? = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    fun badge(id: String): String? = id.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { "https://images.fotmob.com/image_resources/logo/teamlogo/$it.png" }
    private fun fixture(row: JSONObject, now: Long, league: String? = null): SportsMatch? {
        val status = row.optJSONObject("status") ?: return null
        if (status.optBoolean("finished") || status.optBoolean("cancelled")) return null
        val live = status.optBoolean("started")
        if (!live && row.optBoolean("notStarted", true).not()) return null
        val scheduled = instant(status.text("utcTime")) ?: return null
        if ((!live && scheduled < now) || (live && scheduled !in (now - 6 * 60 * 60_000L)..now)) return null
        val id = row.numericId("id") ?: return null
        val home = row.optJSONObject("home") ?: return null
        val away = row.optJSONObject("away") ?: return null
        val homeName = home.text("name") ?: return null
        val awayName = away.text("name") ?: return null
        val homeId = home.numericId("id")
        val awayId = away.numericId("id")
        val dateTbd = status.optBoolean("matchDateTbd")
        val timeTbd = status.optBoolean("matchTimeTbd")
        return SportsMatch(id, "$homeName vs $awayName", homeName, awayName, homeId, awayId,
            kickoffMs = if (dateTbd || timeTbd) null else scheduled,
            date = Instant.ofEpochMilli(scheduled).atZone(ZoneId.of("Europe/Amsterdam")).toLocalDate().toString(),
            league = row.optJSONObject("tournament")?.text("name") ?: league.orEmpty(),
            homeBadge = homeId?.let(::badge), awayBadge = awayId?.let(::badge), dateUnconfirmed = dateTbd,
            isLive = live,
            homeScore = if (live) home.text("score")?.toIntOrNull()?.takeIf { it >= 0 } else null,
            awayScore = if (live) away.text("score")?.toIntOrNull()?.takeIf { it >= 0 } else null,
            liveTime = if (live) status.optJSONObject("liveTime")?.text("short")?.filterNot { it == '\u200e' || it == '\u200f' } else null)
    }
    fun fixtures(json: String, now: Long): List<SportsMatch> {
        val root = JSONObject(json)
        val rows = root.optJSONObject("fixtures")?.optJSONObject("allFixtures")?.optJSONArray("fixtures")
            ?: throw IllegalArgumentException("FotMob fixture shape changed")
        return rows.objects().mapNotNull { fixture(it, now) }.distinctBy { it.id }.sortedBy { it.sortTimeMs }
    }
    fun team(json: String): SportsTeam? {
        val details = JSONObject(json).optJSONObject("details") ?: return null
        val id = details.numericId("id") ?: return null
        val name = details.text("name") ?: return null
        return (SportsTeam.defaults.find { it.id == id } ?: SportsTeam(id, name)).copy(badge = badge(id))
    }
    fun teams(json: String): List<SportsTeam> = JSONArray(json).objects().flatMap { group ->
        group.optJSONArray("suggestions")?.objects().orEmpty().mapNotNull { row ->
            if (row.text("type") != "team") return@mapNotNull null
            val id = row.numericId("id") ?: return@mapNotNull null
            val name = row.text("name") ?: return@mapNotNull null
            (SportsTeam.defaults.find { it.id == id } ?: SportsTeam(id, name)).copy(badge = badge(id))
        }
    }.distinctBy { it.id }
    fun dailyMatches(json: String, now: Long): List<SportsMatch> {
        val leagues = JSONObject(json).optJSONArray("leagues") ?: throw IllegalArgumentException("FotMob daily shape changed")
        return leagues.objects().flatMap { league -> league.optJSONArray("matches")?.objects().orEmpty().mapNotNull { fixture(it, now, league.text("name")) } }.distinctBy { it.id }
    }
    fun closedDailyIds(json: String): Set<String> {
        val leagues = JSONObject(json).optJSONArray("leagues") ?: throw IllegalArgumentException("FotMob daily shape changed")
        return leagues.objects().flatMap { it.optJSONArray("matches")?.objects().orEmpty() }.mapNotNull { row ->
            val status = row.optJSONObject("status") ?: return@mapNotNull null
            if (status.optBoolean("finished") || status.optBoolean("cancelled")) row.numericId("id") else null
        }.toSet()
    }
    /** Country feed is an object keyed by FotMob match id, not an array of broadcaster names. */
    fun broadcasts(json: String, countryCode: String, matches: List<SportsMatch>): Map<String, List<SportsBroadcast>> {
        require(countryCode == "NL" || countryCode == "TR")
        val root = JSONObject(json)
        require(root.keys().asSequence().all { key -> key.isNotEmpty() && key.all(Char::isDigit) }) { "FotMob TV listing shape changed" }
        val country = if (countryCode == "NL") "Netherlands" else "Turkey"
        return matches.associate { match ->
            val kickoff = match.kickoffMs
            val listings = root.optJSONArray(match.id)?.objects().orEmpty().mapNotNull { row ->
                if (row.numericId("matchId") != match.id || kickoff == null || match.dateUnconfirmed) return@mapNotNull null
                val start = row.text("startTime")?.let { value ->
                    Regex("^/Date\\((\\d+)\\)/$").matchEntire(value)?.groupValues?.get(1)?.toLongOrNull() ?: instant(value)
                } ?: return@mapNotNull null
                if (abs(start - kickoff) > 2 * 60 * 60_000L) return@mapNotNull null
                val tags = (row.optJSONArray("tags") ?: JSONArray()).let { array -> (0 until array.length()).map { array.optString(it).lowercase() } }
                if (tags.any { it in setOf("replay", "repeat", "highlights") }) return@mapNotNull null
                val station = row.optJSONObject("station") ?: return@mapNotNull null
                val name = station.text("name") ?: station.text("callSign") ?: return@mapNotNull null
                SportsBroadcast(name, country, streaming = station.text("type") == "Stream")
            }.distinctBy { SportsMatching.broadcasterKey(it.name) }
            match.id to listings
        }
    }
    fun attach(matches: List<SportsMatch>, feeds: List<Map<String, List<SportsBroadcast>>>): List<SportsMatch> = matches.map { match ->
        match.copy(broadcasts = feeds.flatMap { it[match.id].orEmpty() }.distinctBy { it.country to SportsMatching.broadcasterKey(it.name) })
    }
    /** The schedule carries local dates; TRT's 'Z' clock strings cannot safely be used as UTC times. */
    fun trtBroadcasts(json: String, matches: List<SportsMatch>): Map<String, List<SportsBroadcast>> {
        val content = mutableListOf<JSONObject>()
        fun visit(value: Any?, depth: Int) {
            if (depth > 12) return
            when (value) {
                is JSONObject -> {
                    if (value.has("tvChannels") && value.has("date")) content += value
                    else value.keys().forEach { visit(value.opt(it), depth + 1) }
                }
                is JSONArray -> value.objects().forEach { visit(it, depth + 1) }
            }
        }
        val components = JSONObject(json).optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("pageComponents")
            ?: throw IllegalArgumentException("TRT schedule shape changed")
        visit(components, 0)
        require(content.isNotEmpty()) { "TRT schedule has no dated rows" }
        return matches.associate { match ->
            val kickoff = match.kickoffMs
            val day = kickoff?.let { Instant.ofEpochMilli(it).atZone(ZoneId.of("Europe/Istanbul")).toLocalDate().toString() }
            val confirmed = !match.dateUnconfirmed && day != null && content.any { dated ->
                dated.text("date") == day && dated.optJSONArray("tvChannels")?.objects().orEmpty().any { channel ->
                    channel.text("title") == "TRT 1" && (channel.optJSONArray("upcoming")?.objects().orEmpty() + channel.optJSONArray("past")?.objects().orEmpty() + listOfNotNull(channel.optJSONObject("current"))).any { row ->
                        val title = row.text("title").orEmpty()
                        !row.optBoolean("isRepeat") && !SportsMatching.isReplay(title) &&
                            match.home?.let { SportsMatching.containsName(title, it) } == true &&
                            match.away?.let { SportsMatching.containsName(title, it) } == true
                    }
                }
            }
            match.id to if (confirmed) listOf(SportsBroadcast("TRT 1", "Turkey", SportsBroadcastSource.OFFICIAL)) else emptyList()
        }
    }
    fun embeddedJson(html: String): String = Regex("<script\\b[^>]*\\bid=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        .find(html)?.groupValues?.get(1)?.also { JSONObject(it) } ?: throw IllegalArgumentException("Public schedule JSON missing")
}
