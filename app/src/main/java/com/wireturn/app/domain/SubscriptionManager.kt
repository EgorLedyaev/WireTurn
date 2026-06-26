package com.wireturn.app.domain

import com.wireturn.app.AppLogsState
import com.wireturn.app.R
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.Profile
import com.wireturn.app.data.Subscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Fetches remote subscriptions and merges their profiles into the local list.
 *
 * Merge is an UPSERT keyed by [Profile.remoteKey] (the server-supplied uid, or a
 * filename/index fallback): a profile already owned by this subscription is
 * REPLACED in place keeping its local [Profile.id] (so CURRENT_PROFILE_ID and the
 * list order survive), one that vanished upstream is dropped, and a new one is
 * appended with a fresh id. All merges are serialized behind a [Mutex] reading
 * the DataStore directly (not the StateFlow) to avoid lost updates.
 */
class SubscriptionManager(
    private val prefs: AppPreferences,
    private val scope: CoroutineScope
) {
    val subscriptions: StateFlow<List<Subscription>> = prefs.subscriptionsFlow
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val mutex = Mutex()

    fun addSubscription(name: String, url: String, intervalHours: Int = 12) {
        scope.launch {
            val trimmed = url.trim()
            if (trimmed.isBlank()) return@launch
            // Read+save under the lock so concurrent adds can't clobber each other,
            // then refresh AFTER releasing it (the Mutex is non-reentrant).
            val id = mutex.withLock {
                val subs = prefs.subscriptionsFlow.first()
                val existing = subs.firstOrNull { it.url == trimmed }
                if (existing != null) {
                    existing.id
                } else {
                    val sub = Subscription(
                        name = name.ifBlank { hostOf(trimmed) },
                        url = trimmed,
                        intervalHours = if (intervalHours <= 0) 12 else intervalHours
                    )
                    prefs.saveSubscriptions(subs + sub)
                    sub.id
                }
            }
            refreshInternal(id)
        }
    }

    fun removeSubscription(id: String, deleteProfiles: Boolean) {
        scope.launch {
            mutex.withLock {
                prefs.saveSubscriptions(prefs.subscriptionsFlow.first().filter { it.id != id })
                if (deleteProfiles) {
                    prefs.saveProfiles(prefs.profilesFlow.first().filter { it.subscriptionId != id })
                }
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        scope.launch { updateMeta(id) { it.copy(enabled = enabled) } }
    }

    fun refresh(id: String) { scope.launch { refreshInternal(id) } }

    fun refreshAll() {
        scope.launch {
            for (s in prefs.subscriptionsFlow.first().filter { it.enabled }) refreshInternal(s.id)
        }
    }

    /** Refresh only subscriptions past their interval (called on app open). */
    suspend fun refreshDue() {
        for (s in prefs.subscriptionsFlow.first()) if (s.isDue) refreshInternal(s.id)
    }

    private suspend fun refreshInternal(id: String) = mutex.withLock {
        val sub = prefs.subscriptionsFlow.first().find { it.id == id } ?: return@withLock
        val bytes = withContext(Dispatchers.IO) {
            HttpFetcher.fetchBytes(sub.url, sub.userAgent, sub.useProxy)
        }
        val now = System.currentTimeMillis()
        if (bytes == null) {
            updateMetaLocked(id) { it.copy(lastStatus = "fetch failed", lastUpdated = now) }
            AppLogsState.addLog("Subscription '${sub.name}': fetch failed")
            return@withLock
        }
        val defaultName = prefs.context.getString(R.string.profile_default_name)
        val incoming = parseBytes(bytes, defaultName)
        if (incoming.isEmpty()) {
            updateMetaLocked(id) { it.copy(lastStatus = "empty / parse error", lastUpdated = now) }
            AppLogsState.addLog("Subscription '${sub.name}': no profiles parsed")
            return@withLock
        }
        mergeLocked(id, incoming)
        updateMetaLocked(id) {
            it.copy(lastStatus = "ok (${incoming.size})", lastUpdated = now, lastProfileCount = incoming.size)
        }
        AppLogsState.addLog("Subscription '${sub.name}': synced ${incoming.size} profile(s)")
    }

    private fun parseBytes(bytes: ByteArray, defaultName: String): List<Profile> {
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
        ) {
            val out = mutableListOf<Profile>()
            var idx = 0
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory && e.name.lowercase().endsWith(".json")) {
                            val json = zis.readBytes().toString(Charsets.UTF_8)
                            val fname = e.name.substringAfterLast('/').removeSuffix(".json").removePrefix("wt_")
                            for (p in ProfileParser.parseJson(json, defaultName)) {
                                out.add(ensureKey(p, "f:$fname", idx)); idx++
                            }
                        }
                        e = zis.nextEntry
                    }
                }
            } catch (_: Exception) {}
            return out
        }
        val text = bytes.toString(Charsets.UTF_8)
        return ProfileParser.parseJson(text, defaultName).mapIndexed { i, p -> ensureKey(p, null, i) }
    }

    // Every incoming profile needs a remoteKey for matching: prefer the server
    // uid (already promoted into remoteKey by sanitize()), else filename/index.
    private fun ensureKey(p: Profile, fileKey: String?, index: Int): Profile =
        if (!p.remoteKey.isNullOrBlank()) p
        else p.copy(remoteKey = fileKey?.let { "$it:$index" } ?: "i:$index")

    private suspend fun mergeLocked(subId: String, incoming: List<Profile>) {
        val current = prefs.profilesFlow.first()
        val incomingByKey = incoming.associateBy { it.remoteKey }
        val consumed = HashSet<String?>()
        val result = ArrayList<Profile>(current.size + incoming.size)
        for (p in current) {
            if (p.subscriptionId != subId) { result.add(p); continue }
            val match = incomingByKey[p.remoteKey]
            if (match != null) {
                result.add(match.copy(id = p.id, subscriptionId = subId, remoteKey = p.remoteKey))
                consumed.add(p.remoteKey)
            }
            // owned but no longer present upstream -> dropped
        }
        for (p in incoming) {
            if (consumed.contains(p.remoteKey)) continue
            result.add(p.copy(id = UUID.randomUUID().toString(), subscriptionId = subId))
        }
        prefs.saveProfiles(result)
    }

    private suspend fun updateMeta(id: String, f: (Subscription) -> Subscription) =
        mutex.withLock { updateMetaLocked(id, f) }

    private suspend fun updateMetaLocked(id: String, f: (Subscription) -> Subscription) {
        prefs.saveSubscriptions(prefs.subscriptionsFlow.first().map { if (it.id == id) f(it) else it })
    }

    private fun hostOf(url: String): String =
        try { java.net.URI(url).host ?: "subscription" } catch (_: Exception) { "subscription" }
}
