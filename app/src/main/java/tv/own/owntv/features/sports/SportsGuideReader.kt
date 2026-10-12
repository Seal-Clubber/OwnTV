package tv.own.owntv.features.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ChannelListIndex
import tv.own.owntv.core.database.dao.CustomCategoryDao
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.EpgDedupe
import tv.own.owntv.core.epg.EpgShift
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.ActiveProfileSources
import tv.own.owntv.core.settings.SettingsRepository

class SportsGuideReader(
    private val categoryDao: CategoryDao,
    private val customDao: CustomCategoryDao,
    private val profileDao: ProfileDao,
    private val epgDao: EpgDao,
    private val settings: SettingsRepository,
) {
    suspend fun read(ctx: ActiveProfileSources, prefs: SportsPreferences, cust: SectionCustomizations, index: List<ChannelListIndex>): SportsSnapshot = withContext(Dispatchers.Default) {
        if (ctx.profileId < 0 || ctx.liveSourceIds.isEmpty()) return@withContext SportsSnapshot()
        val profile = profileDao.getById(ctx.profileId) ?: return@withContext SportsSnapshot()
        val categories = categoryDao.observe(ctx.liveSourceIds, MediaType.LIVE).first()
        val hidden = AdultCategoryClassifier.hiddenCategoryIds(categories, cust.hiddenCategories, profile.isKids)
        val visibleCats = categories.filter { it.id !in hidden }
        val sourceNames = ctx.sources.associate { it.id to it.name }
        val options = cust.customCategories.filter { it.id !in cust.hiddenCategories && it.id !in cust.deletedCustomCategories }
            .map { SportsList(it.id, cust.categoryNames[it.id] ?: it.name) } + visibleCats.map {
                val key = CustomizeKeys.category(it)
                SportsList(key, (cust.categoryNames[key] ?: it.name) + " · " + sourceNames[it.sourceId].orEmpty())
            }
        val byCategory = index.groupBy { it.categoryId }
        val membership = mutableMapOf<String, Set<Long>>()
        prefs.lists?.forEach { key ->
            if (options.none { it.key == key }) return@forEach
            val category = visibleCats.find { CustomizeKeys.category(it) == key }
            membership[key] = if (category != null) byCategory[category.id].orEmpty()
                .filter { cust.movedFromOrigin["${it.sourceId}:${it.remoteId ?: it.name}"] != key }
                .mapTo(hashSetOf()) { it.id }
            else customDao.liveSelectionKeys(ctx.profileId, key, ctx.liveSourceIds, null).mapTo(hashSetOf()) { it.itemId }
        }
        val selected = SportsMatching.unionIds(prefs.lists, membership, index.mapTo(hashSetOf()) { it.id })
        val channels = index.asSequence().filter { it.id in selected && it.categoryId !in hidden }.map {
            // Metadata only. Playback always reloads the real entity by id in LiveViewModel.
            ChannelEntity(id = it.id, sourceId = it.sourceId, categoryId = it.categoryId, name = it.name,
                streamUrl = "", epgChannelId = it.epgChannelId, remoteId = it.remoteId)
        }.filter { CustomizeKeys.channel(it) !in cust.hiddenItems }.toList()
        val globalShift = settings.epgOffsetMinutes.first()
        val guideChannels = channels.mapNotNull { ch ->
            val key = (cust.epgMatchResolver.epgIdFor(ch) ?: ch.epgChannelId)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            Triple(ch, key, EpgShift.minutesFor(cust, ch, globalShift))
        }
        val now = System.currentTimeMillis()
        val end = now + 7 * 86_400_000L
        val minShift = guideChannels.minOfOrNull { it.third } ?: 0
        val maxShift = guideChannels.maxOfOrNull { it.third } ?: 0
        val catNames = categories.associate { it.id to it.name }
        val channelsByGuide = guideChannels.groupBy { it.second }
        val guides = mutableListOf<SportsMatch>()
        for (keys in channelsByGuide.keys.chunked(150)) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val rows = epgDao.sportsMatchSummaries(keys, EpgShift.toStored(now, maxShift), EpgShift.toStored(end, minShift))
                .groupBy { it.epgChannelId }
            for (key in keys) {
                for ((ch, _, shift) in channelsByGuide[key].orEmpty()) {
                    val isSports = Regex("\\b(sport|sports|spor|football|voetbal|futbol)\\b").containsMatchIn(SportsMatching.normalize(catNames[ch.categoryId].orEmpty()))
                    for (raw in EpgDedupe.collapse(rows[key].orEmpty())) {
                        val programme = EpgShift.apply(raw, shift)
                        if (programme.stopMs <= now || programme.startMs >= end) continue
                        SportsMatching.guideMatch(programme.title, programme.startMs, programme.stopMs, ch.id, prefs.teams, isSports)?.let(guides::add)
                    }
                }
            }
        }
        val distinctGuides = guides.groupBy { SportsMatching.normalize(it.title) to it.kickoffMs }.values.map { matches ->
            matches.first().copy(guideChannels = matches.flatMap { it.guideChannels }.distinct())
        }.sortedBy { it.kickoffMs }
        SportsMatching.indexed(SportsSnapshot(options, channels.map { ch -> cust.itemNames[CustomizeKeys.channel(ch)]?.let { ch.copy(name = it) } ?: ch }, distinctGuides, channels.associate { it.id to it.name },
            channels.associate { ch -> ch.id to options.filter { option -> ch.id in membership[option.key].orEmpty() } }))
    }
}
