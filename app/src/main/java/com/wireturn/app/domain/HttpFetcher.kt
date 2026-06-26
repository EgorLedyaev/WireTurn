package com.wireturn.app.domain

import com.wireturn.app.viewmodel.XrayState
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Shared HTTP fetch with the same direct-then-Xray-proxy fallback AppUpdater
 * uses: the subscription URL itself may sit on a blocked host that is only
 * reachable through the running tunnel.
 */
object HttpFetcher {

    fun xrayProxyIfRunning(): Proxy? {
        val settings = com.wireturn.app.XrayServiceState.session.value?.settings ?: return null
        if (com.wireturn.app.XrayServiceState.state.value == XrayState.Idle) return null
        return try {
            val addr = settings.connectableAddress
            if (addr.isBlank()) return null
            val parts = addr.split(":")
            if (parts.size != 2) return null
            Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(parts[0], parts[1].toInt()))
        } catch (_: Exception) {
            null
        }
    }

    /** Fetch raw bytes; tries direct, then (if useProxy) via the running Xray SOCKS proxy. */
    fun fetchBytes(url: String, userAgent: String? = null, useProxy: Boolean = true): ByteArray? {
        fetchOnce(url, null, userAgent)?.let { return it }
        if (useProxy) {
            xrayProxyIfRunning()?.let { p -> fetchOnce(url, p, userAgent)?.let { return it } }
        }
        return null
    }

    private fun fetchOnce(url: String, proxy: Proxy?, userAgent: String?): ByteArray? {
        val conn = (if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection())
            as HttpURLConnection
        conn.setRequestProperty("User-Agent", userAgent ?: "wireturn-App")
        conn.setRequestProperty("Accept", "*/*")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        return try {
            if (conn.responseCode == 200) conn.inputStream.readBytes() else null
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
