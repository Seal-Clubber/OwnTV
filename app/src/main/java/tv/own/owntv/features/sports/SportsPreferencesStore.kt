package tv.own.owntv.features.sports

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.sportsStore by preferencesDataStore(name = "owntv_tv_sports")

class SportsPreferencesStore(context: Context) {
    private val store = context.applicationContext.sportsStore
    fun observe(profileId: Long): Flow<SportsPreferences> = store.data.map { decode(it[stringPreferencesKey("profile_$profileId")]) }
    suspend fun save(profileId: Long, preferences: SportsPreferences) {
        if (profileId < 0) return
        store.edit { it[stringPreferencesKey("profile_$profileId")] = encode(preferences) }
    }
    companion object {
        fun encode(preferences: SportsPreferences): String = JSONObject().apply {
            put("teamProvider", "fotmob")
            put("allLists", preferences.lists == null)
            put("lists", JSONArray(preferences.lists?.toList().orEmpty()))
            put("teams", JSONArray(preferences.teams.map { team -> JSONObject().apply {
                put("id", team.id); put("name", team.name); put("aliases", JSONArray(team.aliases)); put("badge", team.badge)
            } }))
        }.toString()
        fun decode(raw: String?): SportsPreferences {
            if (raw == null) return SportsPreferences()
            return runCatching {
                val obj = JSONObject(raw)
                val rows = obj.getJSONArray("teams")
                val legacy = obj.optString("teamProvider") != "fotmob"
                val oldIds = mapOf("135985" to "6595", "133807" to "8695", "133738" to "8633", "133913" to "6723")
                val teams = (0 until rows.length()).map { i ->
                    val row = rows.getJSONObject(i)
                    val aliases = row.optJSONArray("aliases") ?: JSONArray()
                    val oldId = row.getString("id")
                    val id = if (legacy) oldIds[oldId] ?: "legacy:$oldId" else oldId
                    SportsTeam(id, row.getString("name"), (0 until aliases.length()).map { aliases.getString(it) }.ifEmpty { listOf(row.getString("name")) }, row.optString("badge").takeIf { it.startsWith("https://") })
                }.distinctBy { it.id }.let { teams ->
                    if (legacy && teams.map { it.id }.containsAll(oldIds.values)) teams + SportsTeam.defaults.first { it.id == "6708" } else teams
                }
                val lists = obj.optJSONArray("lists") ?: JSONArray()
                SportsPreferences(teams, if (obj.optBoolean("allLists", true)) null else (0 until lists.length()).map { lists.getString(it) }.toSet())
            }.getOrDefault(SportsPreferences(lists = emptySet()))
        }
    }
}
