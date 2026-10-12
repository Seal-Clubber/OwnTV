package tv.own.owntv.features.sports

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class FotMobJsonTest {
    private val now = Instant.parse("2026-10-12T17:00:00Z").toEpochMilli()
    private fun sample(name: String): String = javaClass.getResourceAsStream("/fotmob/$name.json")!!.bufferedReader().use { it.readText() }
    private fun fixtures(): List<SportsMatch> = listOf("8695", "8633", "6723", "6595", "6708").flatMap { FotMobJson.fixtures(sample("team-$it"), now) }.distinctBy { it.id }.sortedBy { it.sortTimeMs }

    @Test fun realFiveTeamResponsesOnlyIncludeUpcomingAndDeduplicateFranceTurkey() {
        val matches = fixtures()
        assertEquals(77, matches.size)
        assertEquals(1, matches.count { it.id == "5181799" })
        assertTrue(matches.any { it.homeId == "6708" || it.awayId == "6708" })
        assertEquals("6106284", matches.first().id)
        assertEquals("Champions League", matches.first().league)
        assertTrue(matches.zipWithNext().all { (a, b) -> a.sortTimeMs <= b.sortTimeMs })
        assertTrue(matches.all { it.isLive || it.sortTimeMs >= now })
    }
    @Test fun realCountryFeedFindsNumberedBroadcastersByMatchId() {
        val matches = fixtures()
        val stations = FotMobJson.broadcasts(sample("tv-NL"), "NL", matches)
        assertEquals(listOf("Ziggo Sport 5"), stations["6106284"]!!.map { it.name })
        assertEquals(listOf("Ziggo Sport 3"), stations["6106274"]!!.map { it.name })
        assertTrue(stations["5181797"]!!.isEmpty())
        assertTrue(stations["6106284"]!!.all { it.country == "Netherlands" })
    }
    @Test fun officialTrtConfirmsFenerbahceOnTheDatedSchedule() {
        val matches = fixtures()
        val stations = FotMobJson.trtBroadcasts(sample("trt-schedule"), matches)
        assertEquals(listOf("TRT 1"), stations["6106284"]!!.map { it.name })
        assertTrue(stations["6106284"]!!.all { it.source == SportsBroadcastSource.OFFICIAL && it.country == "Turkey" })
        assertTrue(stations["6106274"]!!.isEmpty())
        assertTrue(FotMobJson.trtBroadcasts(sample("trt-schedule"), listOf(matches.first().copy(kickoffMs = matches.first().kickoffMs!! + 86_400_000))) .values.flatten().isEmpty())
    }
    @Test fun streamingListingsAreKeptSeparateFromTelevisionClaims() {
        val stations = FotMobJson.broadcasts(sample("tv-TR"), "TR", fixtures())["5904773"].orEmpty()
        assertFalse(stations.isEmpty())
        assertTrue(stations.any { it.streaming })
        assertFalse(stations.any { it.name == "beIN Sports 1" })
    }
    @Test fun wrongMatchIdAndRescheduledTimeNeverConfirmAStation() {
        val match = fixtures().first()
        val root = JSONObject(sample("tv-NL"))
        root.getJSONArray(match.id).getJSONObject(0).put("matchId", 999)
        assertTrue(FotMobJson.broadcasts(root.toString(), "NL", listOf(match))[match.id]!!.isEmpty())
        assertTrue(FotMobJson.broadcasts(sample("tv-NL"), "NL", listOf(match.copy(kickoffMs = match.kickoffMs!! + 86_400_000)))[match.id]!!.isEmpty())
    }
    @Test fun replayRowsAreNeverBroadcastEvidence() {
        val match = fixtures().first()
        val root = JSONObject(sample("tv-NL"))
        root.getJSONArray(match.id).getJSONObject(0).put("tags", org.json.JSONArray(listOf("replay")))
        assertTrue(FotMobJson.broadcasts(root.toString(), "NL", listOf(match))[match.id]!!.isEmpty())
    }
    @Test fun dateAndTimePlaceholdersRemainUnconfirmed() {
        val matches = FotMobJson.fixtures(sample("team-8695"), now)
        val provisional = matches.first { it.dateUnconfirmed }
        assertNull(provisional.kickoffMs)
        assertNotNull(provisional.date)
        assertTrue(FotMobJson.broadcasts(sample("tv-NL"), "NL", listOf(provisional))[provisional.id]!!.isEmpty())
    }
    @Test fun kickoffUsesAmsterdamAcrossDaylightSavingChange() {
        val before = SportsClock.amsterdam(Instant.parse("2026-10-14T19:00:00Z").toEpochMilli())
        val after = SportsClock.amsterdam(Instant.parse("2026-11-12T19:45:00Z").toEpochMilli())
        assertEquals(21, before.hour)
        assertEquals(20, after.hour)
        assertEquals(45, after.minute)
        assertEquals("Europe/Amsterdam", after.zone.id)
    }
    @Test fun emptyCountryFeedLeavesBothRegionsUnconfirmed() {
        val matches = fixtures()
        val result = FotMobJson.attach(matches, listOf(FotMobJson.broadcasts("{}", "NL", matches), FotMobJson.broadcasts("{}", "TR", matches)))
        assertTrue(result.all { it.broadcasts.isEmpty() })
        assertEquals(matches.size, result.size)
    }
    @Test fun changedResponseShapesAreReportedRatherThanInterpretedAsFixtures() {
        assertThrows(IllegalArgumentException::class.java) { FotMobJson.fixtures("{}", now) }
        assertThrows(IllegalArgumentException::class.java) { FotMobJson.broadcasts("{\"error\":\"changed\"}", "NL", fixtures()) }
        assertThrows(IllegalArgumentException::class.java) { FotMobJson.trtBroadcasts("{}", fixtures()) }
        assertThrows(IllegalArgumentException::class.java) { FotMobJson.embeddedJson("<html>unavailable</html>") }
    }
    @Test fun oldPreferencesMigrateIdsAndAddNetherlandsWithoutChangingLists() {
        val old = """{"allLists":false,"lists":["dutch","turkish","sports"],"teams":[{"id":"135985","name":"Türkiye"},{"id":"133807","name":"Fenerbahçe"},{"id":"133738","name":"Real Madrid"},{"id":"133913","name":"France"}]}"""
        val preferences = SportsPreferencesStore.decode(old)
        assertEquals(setOf("6595", "8695", "8633", "6723", "6708"), preferences.teams.map { it.id }.toSet())
        assertEquals(setOf("dutch", "turkish", "sports"), preferences.lists)
        assertEquals(preferences, SportsPreferencesStore.decode(SportsPreferencesStore.encode(preferences)))
    }
    @Test fun migrationDoesNotReAddTeamsRemovedFromANewSelection() {
        val preferences = SportsPreferences(SportsTeam.defaults.filterNot { it.id == "6708" }, emptySet())
        assertEquals(preferences, SportsPreferencesStore.decode(SportsPreferencesStore.encode(preferences)))
        val oldUnknown = """{"allLists":false,"lists":[],"teams":[{"id":"99999","name":"Example"}]}"""
        assertEquals("legacy:99999", SportsPreferencesStore.decode(oldUnknown).teams.single().id)
        assertEquals(emptySet<String>(), SportsPreferencesStore.decode(oldUnknown).lists)
    }
    @Test fun realSearchSuggestionsUseFotMobIdsAndLogos() {
        val teams = FotMobJson.teams(sample("search"))
        val fener = teams.first { it.id == "8695" }
        assertTrue(fener.aliases.contains("Fenerbahçe"))
        assertEquals("https://images.fotmob.com/image_resources/logo/teamlogo/8695.png", fener.badge)
        assertTrue(teams.none { it.id == "6106284" })
    }
    @Test fun favouritesUseIdsSoWomensOrYouthTeamsDoNotBecomeTheMensTeam() {
        val team = SportsTeam.defaults.first { it.id == "8633" }
        assertFalse(SportsMatch("1", "Real Madrid Women vs Barcelona", "Real Madrid Women", "Barcelona", "other", "away").follows(team))
        assertTrue(SportsMatch("1", "Real Madrid vs Barcelona", "Real Madrid", "Barcelona", "8633", "away").follows(team))
    }
    @Test fun dailyMatchFeedContainsLiveAndFutureFixtures() {
        val matches = FotMobJson.dailyMatches(sample("matches"), now)
        assertFalse(matches.isEmpty())
        assertTrue(matches.all { it.isLive || it.sortTimeMs >= now })
        assertEquals(matches.size, matches.map { it.id }.toSet().size)
    }

    @Test fun capturedLiveScoresIncludeBothSidesAndMatchMinute() {
        val time = Instant.parse("2026-10-12T18:42:00Z").toEpochMilli()
        val live = FotMobJson.dailyMatches(sample("live-matches"), time).first { it.id == "6050077" }
        assertTrue(live.isLive)
        assertEquals(0, live.homeScore)
        assertEquals(3, live.awayScore)
        assertEquals("26’", live.liveTime)
        assertTrue(live.kickoffMs!! < time)
        assertFalse(live.scoreStale)
    }
    @Test fun unavailableScoresAreNeverInventedAndEndedMatchesLeaveLiveSchedule() {
        val root = JSONObject(sample("live-matches"))
        val leagues = root.getJSONArray("leagues")
        val row = (0 until leagues.length()).flatMap { index ->
            val rows = leagues.getJSONObject(index).getJSONArray("matches")
            (0 until rows.length()).map(rows::getJSONObject)
        }.first { it.getInt("id") == 6050077 }
        val id = row.getInt("id").toString()
        row.getJSONObject("home").remove("score")
        row.getJSONObject("away").put("score", -1)
        val time = Instant.parse("2026-10-12T18:42:00Z").toEpochMilli()
        val live = FotMobJson.dailyMatches(root.toString(), time).first { it.id == id }
        assertNull(live.homeScore)
        assertNull(live.awayScore)
        row.getJSONObject("status").put("finished", true)
        assertTrue(id in FotMobJson.closedDailyIds(root.toString()))
        assertFalse(FotMobJson.dailyMatches(root.toString(), time).any { it.id == id })
        assertFalse(FotMobJson.dailyMatches(sample("live-matches"), time + 24 * 60 * 60_000L).any { it.isLive })
    }
    @Test fun freshDailyScoresOverrideCachedTeamFixturesAndFinishedIdsAreRemoved() {
        val cached = SportsMatch("1", "A vs B", isLive = true, homeScore = 0, awayScore = 0)
        val fresh = cached.copy(homeScore = 2, awayScore = 1)
        assertEquals(listOf(fresh), SportsSchedule.merge(listOf(cached), listOf(fresh), emptySet()))
        assertTrue(SportsSchedule.merge(listOf(cached), emptyList(), setOf("1")).isEmpty())
    }
}
