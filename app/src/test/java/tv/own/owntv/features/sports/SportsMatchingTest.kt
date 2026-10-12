package tv.own.owntv.features.sports

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SportsMatchingTest {
    @Test fun combinedListsAreAUnionWithNoDuplicates() {
        val membership = mapOf("dutch" to setOf(1L, 2L), "sports" to setOf(2L, 3L), "turkish" to setOf(4L))
        assertEquals(setOf(1L, 2L, 3L), SportsMatching.unionIds(setOf("dutch", "sports"), membership, setOf(1L, 2L, 3L, 4L)))
        assertEquals(setOf(2L, 3L, 4L), SportsMatching.unionIds(setOf("turkish", "sports"), membership, setOf(1L, 2L, 3L, 4L)))
    }
    @Test fun emptyOrMissingScopeNeverBecomesAll() {
        val all = setOf(1L, 2L)
        assertEquals(all, SportsMatching.unionIds(null, emptyMap(), all))
        assertTrue(SportsMatching.unionIds(emptySet(), emptyMap(), all).isEmpty())
        assertTrue(SportsMatching.unionIds(setOf("deleted"), emptyMap(), all).isEmpty())
        assertEquals(setOf(1L), SportsMatching.unionIds(setOf("dutch"), mapOf("dutch" to setOf(1L, 999L)), all))
    }
    @Test fun countryAndQualityTagsDoNotConfuseChannelNumbers() {
        assertTrue(SportsMatching.sameBroadcaster("NL | Ziggo Sport 1 FHD", "Ziggo Sport1"))
        assertTrue(SportsMatching.sameBroadcaster("TR: beIN Sports 1 HD", "beIN Sports 1 TR"))
        assertFalse(SportsMatching.sameBroadcaster("beIN Sports 2 HD", "beIN Sports 1"))
        assertFalse(SportsMatching.sameBroadcaster("Ziggo Sport Select", "Ziggo Sport"))
        assertFalse(SportsMatching.sameBroadcaster("Sky Sports 1", ""))
    }
    @Test fun teamsUseAccentsAndWordBoundaries() {
        assertTrue(SportsTeam.defaults[1].let { it.aliases.any { name -> SportsMatching.containsName("Fenerbahce - Real Madrid", name) } })
        assertTrue(SportsMatching.containsName("Turkije - Frankrijk", "Turkije"))
        assertFalse(SportsMatching.containsName("Real Madridista training", "Real Madrid"))
        assertFalse(SportsMatching.containsName("Franceville - Paris", "France"))
    }
    @Test fun guideRequiresMatchStructureAndRejectsReplays() {
        val teams = SportsTeam.defaults
        assertNull(SportsMatching.guideMatch("Real Madrid training", 10, 20, 1, teams, false))
        assertNull(SportsMatching.guideMatch("Herhaling: Real Madrid - Barcelona", 10, 20, 1, teams, true))
        assertNull(SportsMatching.guideMatch("Fenerbahce - Besiktas özet", 10, 20, 1, teams, true))
        assertNotNull(SportsMatching.guideMatch("Turkije - Frankrijk", 10, 20, 1, teams, false))
        assertNull(SportsMatching.guideMatch("Movie A - Movie B", 10, 20, 1, teams, false))
    }
    @Test fun guideConfirmationNeedsBothTeamsAndNearbyTime() {
        val fixture = SportsMatch("123", "Turkey vs France", "Turkey", "France", kickoffMs = 10_000_000)
        val guide = SportsMatch("epg:1", "Turkije - Frankrijk", kickoffMs = 9_000_000)
        assertTrue(SportsMatching.confirms(fixture, guide))
        assertFalse(SportsMatching.confirms(fixture, guide.copy(title = "Turkije - Spanje")))
        assertFalse(SportsMatching.confirms(fixture, guide.copy(kickoffMs = 100_000_000)))
    }
    @Test fun jsonUsesUtcInstantAndPreservesUnknownTime() {
        val matches = SportsJson.events("""{"events":[{"idEvent":"1","strSport":"Soccer","strHomeTeam":"Turkey","strAwayTeam":"France","dateEvent":"2026-10-25","strTimestamp":"2026-10-25T18:30:00+00:00"},{"idEvent":"2","strHomeTeam":"Real Madrid","strAwayTeam":"Barcelona","dateEvent":"2026-10-25","strTime":null}]}""")
        assertEquals(Instant.parse("2026-10-25T18:30:00Z").toEpochMilli(), matches[0].kickoffMs)
        assertNull(matches[1].kickoffMs)
        assertEquals("2026-10-25", matches[1].date)
    }
    @Test fun nullFeedsAreEmptyAndDifferentSportsAreExcluded() {
        assertTrue(SportsJson.events("""{"events":null}""").isEmpty())
        assertTrue(SportsJson.events("""{"events":[{"idEvent":"1","strSport":"Basketball","strHomeTeam":"A","strAwayTeam":"B"}]}""").isEmpty())
    }
    @Test fun broadcastCountriesAreFiltered() {
        val result = SportsJson.broadcasts("""{"tvevent":[{"strCountry":"Netherlands","strChannel":"Ziggo Sport"},{"strCountry":"Turkey","strChannel":"TRT 1"},{"strCountry":"Brazil","strChannel":"HBO"}]}""")
        assertEquals(listOf("Ziggo Sport", "TRT 1"), result.map { it.name })
    }
    @Test fun savedScopeAndTeamsRoundTripWithoutBroadeningEmptyScope() {
        val custom = SportsTeam("42", "Example", listOf("Example", "Alias"))
        val prefs = SportsPreferences(listOf(custom), setOf("custom:dutch", "custom:sports"))
        assertEquals(prefs, SportsPreferencesStore.decode(SportsPreferencesStore.encode(prefs)))
        assertEquals(emptySet<String>(), SportsPreferencesStore.decode(SportsPreferencesStore.encode(prefs.copy(lists = emptySet()))).lists)
        assertNull(SportsPreferencesStore.decode(SportsPreferencesStore.encode(prefs.copy(lists = null))).lists)
        assertTrue(SportsPreferencesStore.decode(SportsPreferencesStore.encode(prefs.copy(teams = emptyList()))).teams.isEmpty())
    }

    @Test fun oldOrMismatchedBroadcastListingsAreIgnored() {
        val json = """{"tvevent":[{"idEvent":"1","dateEvent":"2026-09-08","strCountry":"Netherlands","strChannel":"Wrong date"},{"idEvent":"2","dateEvent":"2026-10-12","strCountry":"Turkey","strChannel":"Wrong event"},{"idEvent":"1","dateEvent":"2026-10-12","strCountry":"Turkey","strChannel":"TRT 1"}]}"""
        assertEquals(listOf("TRT 1"), SportsJson.broadcasts(json, "1", "2026-10-12").map { it.name })
    }
    @Test fun corruptedSavedPreferencesDoNotExposeAllLists() {
        assertEquals(emptySet<String>(), SportsPreferencesStore.decode("broken json").lists)
        assertNull(SportsPreferencesStore.decode(null).lists)
    }
    private fun channel(id: Long, name: String) = tv.own.owntv.core.database.entity.ChannelEntity(id = id, sourceId = 1, name = name, streamUrl = "")
    @Test fun quickSearchFindsBrandVariantsAndRanksExactNumberFirst() {
        val snapshot = SportsSnapshot(channels = listOf(channel(1, "NL ZiggoSport 1 HD"), channel(2, "NL Ziggo Sport 2 RAW"), channel(3, "ZIGGO SPORT SELECT"), channel(4, "Sky Sports 2")))
        assertEquals(listOf(2L, 1L, 3L), SportsMatching.search(snapshot, "Ziggo Sport 2", true).map { it.channel.id })
        assertEquals("atv", SportsMatching.quickSearchKey("ATV"))
        assertEquals("npo1", SportsMatching.quickSearchKey("NPO1"))
    }
    @Test fun quickSearchOnlyUsesSnapshotAndRetainsSelectedListDuplicates() {
        val dutch = SportsList("dutch", "Dutch")
        val sports = SportsList("sports", "Sports")
        val snapshot = SportsSnapshot(channels = listOf(channel(1, "Ziggo Sport 2"), channel(2, "Renamed channel")), providerNames = mapOf(2L to "NL: ZiggoSport 3 UHD", 99L to "Ziggo Sport 9"), memberships = mapOf(1L to listOf(dutch, sports), 2L to listOf(sports)))
        val results = SportsMatching.search(snapshot, "Ziggo Sport 2", true)
        assertEquals(listOf(1L, 1L, 2L), results.map { it.channel.id })
        assertEquals(listOf("dutch", "sports", "sports"), results.map { it.list?.key })
        assertFalse(results.any { it.channel.id == 99L })
        assertTrue(SportsMatching.search(snapshot.copy(channels = emptyList()), "Ziggo Sport 2", true).isEmpty())
    }
    @Test fun manualSearchMatchesCompactNumberedNames() {
        val snapshot = SportsSnapshot(channels = listOf(channel(1, "NPO1 HD"), channel(2, "NPO 2 FHD"), channel(3, "ATV HD")))
        assertEquals(listOf(1L, 2L), SportsMatching.search(snapshot, "npo").map { it.channel.id })
        assertEquals(listOf(1L), SportsMatching.search(snapshot, "NPO1", true).map { it.channel.id })
        assertTrue(SportsMatching.search(snapshot, " ").isEmpty())
    }
    @Test fun countryScheduleRequiresExactIdentityAndAcceptsPluralResponse() {
        val json = """{"tvevents":[{"idEvent":"1","dateEvent":"2026-10-12","strCountry":"Netherlands","strChannel":"Ziggo Sport 2"},{"idEvent":"2","dateEvent":"2026-10-12","strCountry":"Netherlands","strChannel":"Other match"},{"dateEvent":"2026-10-12","strCountry":"Turkey","strChannel":"No identity"},{"idEvent":"1","dateEvent":"2026-10-13","strCountry":"Turkey","strChannel":"Different day"}]}"""
        assertEquals(listOf("Ziggo Sport 2"), SportsJson.broadcasts(json, "1", "2026-10-12", true).map { it.name })
    }
    @Test fun badgesAreParsedAndSavedWithoutLosingTeamAliases() {
        val team = SportsJson.teams("""{"teams":[{"idTeam":"133807","strSport":"Soccer","strTeam":"Fenerbahce","strBadge":"https://example.org/fener.png"}]}""").single()
        assertEquals("https://example.org/fener.png", team.badge)
        assertTrue(team.aliases.contains("Fenerbahçe"))
        val prefs = SportsPreferences(listOf(team), setOf("sports"))
        assertEquals(prefs, SportsPreferencesStore.decode(SportsPreferencesStore.encode(prefs)))
        val match = SportsJson.events("""{"events":[{"idEvent":"1","strHomeTeam":"Fenerbahce","strAwayTeam":"Real Madrid","strHomeTeamBadge":"https://example.org/fener.png","strAwayTeamBadge":"https://example.org/real.png"}]}""").single()
        assertEquals("https://example.org/fener.png", match.homeBadge)
        assertEquals("https://example.org/real.png", match.awayBadge)
    }
    @Test fun guideResultsRetainOneEntryPerSelectedMembership() {
        val snapshot = SportsSnapshot(memberships = mapOf(1L to listOf(SportsList("dutch", "Dutch"), SportsList("sports", "Sports"))))
        val results = SportsMatching.occurrences(snapshot, listOf(SportsChannelResult(channel(1, "NPO1"), ChannelEvidence.GUIDE)))
        assertEquals(2, results.size)
        assertTrue(results.all { it.evidence == ChannelEvidence.GUIDE })
    }


    @Test fun indexedLargeSelectedScopeKeepsExactRankingAndDuplicateLists() {
        val lists = listOf(SportsList("dutch", "Dutch"), SportsList("sports", "Sports"))
        val channels = (1L..50_000L).map { channel(it, if (it == 50_000L) "NL: ZIGGO SPORT 3 RAW" else "Example $it") }
        val snapshot = SportsSnapshot(channels = channels, memberships = mapOf(50_000L to lists))
        val indexed = SportsMatching.indexed(snapshot)
        assertEquals(50_000, indexed.broadcasterKeys.size)
        val result = SportsMatching.search(indexed, "Ziggo Sport 3", broadcaster = true)
        assertEquals(listOf(50_000L, 50_000L), result.map { it.channel.id })
        assertEquals(lists, result.map { it.list })
        assertEquals(SportsMatching.search(snapshot, "Ziggo Sport 3", true), result)
    }
    @Test fun popupKeysAreStringsAndSeparateCountriesAndListOccurrences() {
        val nl = SportsBroadcast("ESPN", "Netherlands")
        val tr = nl.copy(country = "Turkey")
        assertNotEquals(SportsMatching.broadcastRowKey(nl), SportsMatching.broadcastRowKey(tr))
        val first = SportsChannelResult(channel(1, "ESPN"), ChannelEvidence.MANUAL, SportsList("dutch", "Dutch"))
        val second = first.copy(list = SportsList("sports", "Sports"))
        assertNotEquals(SportsMatching.channelRowKey(first), SportsMatching.channelRowKey(second))
        assertNotEquals(SportsMatching.channelRowKey(first), SportsMatching.broadcastRowKey(nl))
    }

    @Test fun connectServiceSearchesRelatedChannelsWithoutClaimingTheirBroadcasts() {
        val service = SportsBroadcast("beIN CONNECT Turkey", "Turkey", streaming = true)
        val query = SportsMatching.relatedTvSearch(service)!!
        val snapshot = SportsMatching.indexed(SportsSnapshot(channels = listOf(channel(1, "TR: beIN SPORTS 1 HD"), channel(2, "TR: beIN SPORTS 2 RAW"), channel(3, "TR: TRT 1 HD")), memberships = mapOf(1L to listOf(SportsList("turkish", "Turkish"), SportsList("sports", "Sports")))))
        val results = SportsMatching.search(snapshot, query, true)
        assertEquals(listOf(1L, 1L, 2L), results.map { it.channel.id })
        assertTrue(results.all { it.evidence == ChannelEvidence.MANUAL })
        assertFalse(SportsMatching.sameBroadcaster("TR: beIN SPORTS 1 HD", service.name))
    }
    @Test fun serviceAliasDoesNotRewriteConfirmedTvChannelsOrUnknownServices() {
        assertNull(SportsMatching.relatedTvSearch(SportsBroadcast("beIN Sports 2", "Turkey")))
        assertNull(SportsMatching.relatedTvSearch(SportsBroadcast("beIN CONNECT Turkey", "Turkey", streaming = false)))
        assertNull(SportsMatching.relatedTvSearch(SportsBroadcast("Unknown", "Turkey", streaming = true)))
    }
}
