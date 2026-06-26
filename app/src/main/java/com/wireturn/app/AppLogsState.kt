package com.wireturn.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Глобальное состояние логов приложения (прокси, Xray, VPN и др.)
 */
object AppLogsState {
    
    data class LogEntry(val id: Long, val message: String)

    private const val MAX_LOG_LINES = 500
    private var nextId = 0L
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun addLog(msg: String) {
        val cleanMsg = redactSecrets(stripAnsi(msg))
        _logs.update { current ->
            val next = current + LogEntry(nextId++, cleanMsg)
            if (next.size > MAX_LOG_LINES) next.drop(next.size - MAX_LOG_LINES) else next
        }
    }

    fun stripAnsi(msg: String): String {
        return msg.replace("\u001B\\[[;\\d]*[mK]".toRegex(), "")
    }

    // --- Secret redaction -----------------------------------------------------
    // Applied to every stored log line via addLog() (SEC-1-B), so subprocess argv
    // dumps, config-echoing stdout, and the clipboard export all pass one chokepoint.
    private val URL_USERINFO = Regex("([a-zA-Z][a-zA-Z0-9+.\\-]*://)[^/@\\s?]+@")
    private val SOCKS_INLINE = Regex("(-local-socks5\\s+)[^@\\s]+@")
    private val SECRET_FLAG = Regex(
        "(-(?:wg-private-key|wg-public-key|proxy-user|proxy-pass|password|login|socks-user|socks-pass))(\\s+)\\S+"
    )
    private val OLCRTC_KEY = Regex("(olcrtc://\\S*?#)\\S+")
    private val URL_TOKEN = Regex("([?&](?:sub_token|access_token|token)=)[^&\\s#]+")

    fun redactSecrets(msg: String): String {
        var s = msg
        s = URL_USERINFO.replace(s, "$1****@")
        s = SOCKS_INLINE.replace(s, "$1****@")
        s = SECRET_FLAG.replace(s, "$1$2****")
        s = OLCRTC_KEY.replace(s, "$1****")
        s = URL_TOKEN.replace(s, "$1****")
        return s
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
