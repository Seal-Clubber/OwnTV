package tv.own.owntv.features.sports

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs
import org.json.JSONObject
import tv.own.owntv.core.database.entity.ChannelEntity

/** null scope = all; an empty set = deliberately no lists. */
data class SportsPreferences(val teams: List<SportsTeam> = SportsTeam.defaults, val lists: Set<String>? = null)
data class SportsTeam(val id: String, val name: String, val aliases: List<String> = listOf(name), val badge: String? = null) {
    companion object {
        // Proper team names / search aliases are data, not interface copy.
        val defaults = listOf(
            SportsTeam("6595", "Türkiye", listOf("Türkiye", "Turkey", "Turkiye", "Turkije", "Turquie")),
            SportsTeam("8695", "Fenerbahçe", listOf("Fenerbahçe", "Fenerbahce", "Fener")),
            SportsTeam("8633", "Real Madrid"),
            SportsTeam("6723", "France", listOf("France", "Frankrijk", "Fransa")),
            SportsTeam("6708", "Netherlands", listOf("Netherlands", "Nederland", "Holland", "Hollanda", "Pays Bas")),
        )
    }
}
data class SportsList(val key: String, val name: String)
data class SportsMatch(
    val id: String,
    val title: String,
    val home: String? = null,
    val away: String? = null,
    val homeId: String? = null,
    val awayId: String? = null,
    val kickoffMs: Long? = null,
    val date: String? = null,
    val league: String = "",
    val guideChannels: List<Long> = emptyList(),
    val guideTitle: String? = null,
    val homeBadge: String? = null,
    val awayBadge: String? = null,
    val broadcasts: List<SportsBroadcast> = emptyList(),
    val dateUnconfirmed: Boolean = false,
    val isLive: Boolean = false,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val liveTime: String? = null,
    val scoreStale: Boolean = false,
) {
    val sortTimeMs: Long get() = kickoffMs ?: date?.let { runCatching { LocalDate.parse(it).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() } ?: Long.MAX_VALUE
    fun follows(team: SportsTeam): Boolean {
        if (homeId != null && awayId != null) return homeId == team.id || awayId == team.id
        return team.aliases.any { SportsMatching.containsName(home ?: title, it) || SportsMatching.containsName(away ?: title, it) }
    }
}
object SportsSchedule {
    fun merge(fixtures: List<SportsMatch>, daily: List<SportsMatch>, closedIds: Set<String>): List<SportsMatch> =
        (daily + fixtures).filter { it.id !in closedIds }.distinctBy { it.id }.sortedBy { it.sortTimeMs }
}

enum class SportsRegion(val code: String, val country: String) { NETHERLANDS("NL", "Netherlands"), TURKEY("TR", "Turkey") }
object SportsClock { fun amsterdam(ms: Long): java.time.ZonedDateTime = Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.of("Europe/Amsterdam")) }

enum class SportsBroadcastSource { FOTMOB, OFFICIAL, GUIDE }
data class SportsBroadcast(val name: String, val country: String, val source: SportsBroadcastSource = SportsBroadcastSource.FOTMOB, val streaming: Boolean = false)
enum class ChannelEvidence { GUIDE, BROADCASTER, MANUAL }
data class SportsChannelResult(val channel: ChannelEntity, val evidence: ChannelEvidence, val list: SportsList? = null)
data class SportsSnapshot(
    val lists: List<SportsList> = emptyList(),
    val channels: List<ChannelEntity> = emptyList(),
    val guideMatches: List<SportsMatch> = emptyList(),
    val providerNames: Map<Long, String> = emptyMap(),
    val memberships: Map<Long, List<SportsList>> = emptyMap(),
    val broadcasterKeys: Map<Long, List<String>> = emptyMap(),
)

object SportsMatching {
    fun broadcastRowKey(station: SportsBroadcast): String = "broadcast:${station.country}:${broadcasterKey(station.name)}"
    fun channelRowKey(result: SportsChannelResult): String = "channel:${result.channel.id}:${result.list?.key.orEmpty()}"

    private val marks = Regex("\\p{M}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    fun indexed(snapshot: SportsSnapshot): SportsSnapshot = snapshot.copy(broadcasterKeys = snapshot.channels.associate { channel ->
        channel.id to listOf(channel.name, snapshot.providerNames[channel.id].orEmpty()).map(::broadcasterKey).filter(String::isNotEmpty).distinct()
    })
    fun channelKeys(snapshot: SportsSnapshot, channel: ChannelEntity): List<String> = snapshot.broadcasterKeys[channel.id]
        ?: listOf(channel.name, snapshot.providerNames[channel.id].orEmpty()).map(::broadcasterKey)

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(marks, "").lowercase(Locale.ROOT)
        .replace('ı', 'i').replace(separators, " ").trim()
    fun containsName(title: String, name: String): Boolean {
        val haystack = normalize(title)
        val needle = normalize(name)
        return needle.isNotEmpty() && (" $haystack ").contains(" $needle ")
    }
    private val suffixes = setOf("raw", "hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "h264", "50fps", "60fps")
    private val countries = setOf("nl", "tr", "nld", "tur", "netherlands", "turkey", "turkiye", "nederland")
    fun broadcasterKey(name: String): String {
        val tokens = normalize(name).split(' ').filter { it.isNotBlank() }.toMutableList()
        while (tokens.firstOrNull() in countries) tokens.removeAt(0)
        while (tokens.lastOrNull() in suffixes || tokens.lastOrNull() in countries) tokens.removeAt(tokens.lastIndex)
        // Sport 1 and Sport1 are equal; Sport1 and Sport2 are never equal.
        return tokens.joinToString("")
    }
    fun sameBroadcaster(channel: String, station: String): Boolean = broadcasterKey(station).let {
        it.isNotEmpty() && broadcasterKey(channel) == it
    }
    /** Brand searches tolerate spaces, quality tags and numbered variants; numbered stations rank first. */
    /** A service listing identifies a brand, never a numbered TV broadcast. */
    fun relatedTvSearch(station: SportsBroadcast): String? {
        val key = broadcasterKey(station.name)
        return when {
            station.streaming && (key.startsWith("beinconnect") || key.startsWith("beinsportsconnect")) -> "beIN Sports"
            station.streaming && key == "tabii" -> "tabii Spor"
            else -> null
        }
    }
    fun quickSearchKey(station: String): String {
        val key = broadcasterKey(station)
        return when {
            key.startsWith("ziggosport") -> "ziggosport"
            key.startsWith("beinsport") -> "beinsport"
            key.startsWith("espn") -> "espn"
            key.startsWith("ssport") -> "ssport"
            key.startsWith("tabii") -> "tabii"
            else -> key
        }
    }
    fun search(snapshot: SportsSnapshot, query: String, broadcaster: Boolean = false): List<SportsChannelResult> {
        val needle = if (broadcaster) quickSearchKey(query) else normalize(query).replace(" ", "")
        if (needle.isEmpty()) return emptyList()
        val exact = if (broadcaster) broadcasterKey(query) else null
        return snapshot.channels.mapNotNull { ch ->
            val keys = channelKeys(snapshot, ch)
            if (keys.none { it.contains(needle) }) null else ch to (exact != null && exact in keys)
        }.sortedBy { if (it.second) 0 else 1 }.map { it.first }
            .flatMap { ch ->
                val lists = snapshot.memberships[ch.id].orEmpty()
                if (lists.isEmpty()) listOf(SportsChannelResult(ch, ChannelEvidence.MANUAL))
                else lists.map { SportsChannelResult(ch, ChannelEvidence.MANUAL, it) }
            }.take(300)
    }
    fun occurrences(snapshot: SportsSnapshot, channels: List<SportsChannelResult>): List<SportsChannelResult> = channels.flatMap { result ->
        snapshot.memberships[result.channel.id].orEmpty().map { result.copy(list = it) }.ifEmpty { listOf(result) }
    }
    fun inRegions(country: String): Boolean = normalize(country) in setOf("netherlands", "the netherlands", "nederland", "turkey", "turkiye")
    fun isReplay(title: String): Boolean = Regex("\\b(replay|repeat|highlights|highlights? summary|samenvatting|herhaling|tekrar|ozet|resumen)\\b")
        .containsMatchIn(normalize(title))
    private val separator = Regex("\\s+(?:vs?\\.?|versus|[-–—])\\s+", RegexOption.IGNORE_CASE)
    fun guideMatch(title: String, startMs: Long, stopMs: Long, channelId: Long, teams: List<SportsTeam>, sportsChannel: Boolean): SportsMatch? {
        if (isReplay(title)) return null
        val pair = separator.split(title, limit = 2)
        if (pair.size != 2 || pair.any { it.isBlank() }) return null
        val followed = teams.any { team -> team.aliases.any { containsName(title, it) } }
        val football = Regex("\\b(football|soccer|voetbal|futbol|champions league|europa league|eredivisie|super lig|la liga|nations league|world cup)\\b").containsMatchIn(normalize(title))
        if (!followed && !football && !sportsChannel) return null
        // The guide's start is a programme time, not necessarily the exact kickoff.
        return SportsMatch("epg:$channelId:$startMs", title, pair[0].trim(), pair[1].trim(), kickoffMs = startMs,
            guideChannels = listOf(channelId), guideTitle = title)
    }
    fun confirms(match: SportsMatch, guide: SportsMatch): Boolean {
        val start = match.kickoffMs ?: return false
        val guideStart = guide.kickoffMs ?: return false
        if (abs(start - guideStart) > 2 * 60 * 60_000L) return false
        val home = match.home ?: return normalize(match.title) == normalize(guide.title)
        val away = match.away ?: return false
        fun present(name: String): Boolean {
            val aliases = SportsTeam.defaults.firstOrNull { it.aliases.any { alias -> normalize(alias) == normalize(name) } }?.aliases ?: listOf(name)
            return aliases.any { containsName(guide.title, it) }
        }
        return present(home) && present(away) && !isReplay(guide.title)
    }
    fun notable(match: SportsMatch): Int {
        val text = normalize(match.league + " " + match.title)
        return when {
            Regex("\\b(final|finale|derby|clasico)\\b").containsMatchIn(text) -> 3
            Regex("\\b(champions league|world cup|nations league|europa league)\\b").containsMatchIn(text) -> 2
            else -> 1
        }
    }
    fun unionIds(selected: Set<String>?, membership: Map<String, Set<Long>>, all: Set<Long>): Set<Long> =
        if (selected == null) all else selected.flatMap { membership[it].orEmpty() }.toSet().intersect(all)
}

object SportsJson {
    private fun JSONObject.text(key: String): String? = optString(key).trim().takeUnless { it.isEmpty() || it == "null" }
    fun events(json: String): List<SportsMatch> {
        val array = JSONObject(json).optJSONArray("events") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val row = array.optJSONObject(i) ?: return@mapNotNull null
            if (row.text("strSport")?.let { it != "Soccer" } == true) return@mapNotNull null
            val id = row.text("idEvent") ?: return@mapNotNull null
            val home = row.text("strHomeTeam") ?: return@mapNotNull null
            val away = row.text("strAwayTeam") ?: return@mapNotNull null
            val date = row.text("dateEvent")
            val time = row.text("strTime")
            val kickoff = row.text("strTimestamp")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: if (date != null && time != null) runCatching {
                    LocalDate.parse(date).atTime(LocalTime.parse(time.removeSuffix("+00:00"))).toInstant(ZoneOffset.UTC).toEpochMilli()
                }.getOrNull() else null
            SportsMatch(id, row.text("strEvent") ?: "$home vs $away", home, away, row.text("idHomeTeam"), row.text("idAwayTeam"), kickoff, date, row.text("strLeague").orEmpty(), homeBadge = row.text("strHomeTeamBadge"), awayBadge = row.text("strAwayTeamBadge"))
        }.distinctBy { it.id }
    }
    fun broadcasts(json: String, eventId: String? = null, eventDate: String? = null, requireIdentity: Boolean = false): List<SportsBroadcast> {
        val root = JSONObject(json)
        val rows = root.optJSONArray("tvevent") ?: root.optJSONArray("tvevents") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            if (requireIdentity && (eventId == null || row.text("idEvent") != eventId || (eventDate != null && row.text("dateEvent") != eventDate))) return@mapNotNull null
            if (eventId != null && row.text("idEvent")?.let { it != eventId } == true) return@mapNotNull null
            val listedDate = row.text("dateEvent")
            if (eventDate != null && listedDate != null) {
                val mismatch = runCatching { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(eventDate), LocalDate.parse(listedDate))) > 1 }.getOrDefault(true)
                if (mismatch) return@mapNotNull null
            }
            val country = row.text("strCountry") ?: return@mapNotNull null
            val name = row.text("strChannel") ?: return@mapNotNull null
            if (SportsMatching.inRegions(country)) SportsBroadcast(name, country) else null
        }.distinct()
    }
    fun teams(json: String): List<SportsTeam> {
        val rows = JSONObject(json).optJSONArray("teams") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            if (row.text("strSport") != "Soccer") return@mapNotNull null
            val id = row.text("idTeam") ?: return@mapNotNull null
            val name = row.text("strTeam") ?: return@mapNotNull null
            (SportsTeam.defaults.find { it.name == name || it.aliases.any { alias -> SportsMatching.normalize(alias) == SportsMatching.normalize(name) } } ?: SportsTeam(id, name)).copy(badge = row.text("strBadge"))
        }
    }
}
