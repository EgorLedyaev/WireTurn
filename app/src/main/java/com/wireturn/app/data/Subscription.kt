package com.wireturn.app.data

import com.google.gson.annotations.SerializedName
import java.util.UUID

/**
 * A remote subscription: a URL that returns WireTurn profiles (a single JSON
 * object, a JSON array, or a ZIP of wt_<name>.json). Profiles it owns are tagged
 * with [Profile.subscriptionId] == this id and matched across refreshes by
 * [Profile.remoteKey]. Stored as a Gson array under SUBSCRIPTIONS_JSON.
 */
data class Subscription(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("name") val name: String = "",
    @SerializedName("url") val url: String = "",
    @SerializedName("intervalHours") val intervalHours: Int = 12,
    @SerializedName("lastUpdated") val lastUpdated: Long = 0L,
    @SerializedName("lastStatus") val lastStatus: String = "",
    @SerializedName("lastProfileCount") val lastProfileCount: Int = 0,
    @SerializedName("enabled") val enabled: Boolean = true,
    @SerializedName("useProxy") val useProxy: Boolean = true,
    @SerializedName("userAgent") val userAgent: String? = null
) {
    // Gson can bypass Kotlin null-safety on deserialization; defend like Profile.
    fun sanitize(): Subscription = copy(
        id = (id as String?)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
        name = (name as String?) ?: "",
        url = (url as String?) ?: "",
        intervalHours = if (intervalHours <= 0) 12 else intervalHours
    )

    val isDue: Boolean
        get() = enabled && url.isNotBlank() &&
            (System.currentTimeMillis() - lastUpdated) >= intervalHours.toLong() * 3_600_000L
}
