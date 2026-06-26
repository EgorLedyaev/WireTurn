package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelConfigAdapter
import com.wireturn.app.data.Profile
import com.wireturn.app.data.SafeEnumTypeAdapterFactory

/**
 * Single place that turns a WireTurn profile JSON string (a single object OR a
 * top-level array) into sanitized [Profile]s. Shared by one-shot import and
 * subscription sync so the two paths can't drift. Uses the SAME Gson config as
 * AppPreferences/ProfileManager (KernelConfig + SafeEnum adapters).
 */
object ProfileParser {
    val gson = GsonBuilder()
        .registerTypeAdapterFactory(SafeEnumTypeAdapterFactory())
        .registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter())
        .create()

    fun parseJson(json: String, defaultName: String): List<Profile> {
        return try {
            val el = JsonParser.parseString(json)
            when {
                el.isJsonArray -> el.asJsonArray.mapNotNull { e ->
                    try { gson.fromJson(e, Profile::class.java)?.sanitize(defaultName) } catch (_: Exception) { null }
                }
                el.isJsonObject ->
                    try { gson.fromJson(el, Profile::class.java)?.sanitize(defaultName)?.let { listOf(it) } ?: emptyList() }
                    catch (_: Exception) { emptyList() }
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
