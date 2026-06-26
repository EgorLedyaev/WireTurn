package com.wireturn.app.domain

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.wireturn.app.AppLogsState
import com.wireturn.app.R
import com.wireturn.app.viewmodel.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AppUpdater(private val context: Context) {

    val state: StateFlow<UpdateState> = _state.asStateFlow()
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private fun getCurrentVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    } catch (_: PackageManager.NameNotFoundException) {
        "0.0.0"
    }

    /**
     * @param silent true — при ошибке сети остаёмся в [UpdateState.Idle] (автопроверка при запуске).
     *               false — показываем [UpdateState.Error] (ручная проверка из UI).
     * @param force  true — игнорировать минутный кулдаун (при смене настроек).
     */
    suspend fun checkForUpdate(silent: Boolean = false, allowUnstable: Boolean = false, force: Boolean = false) {
        if (!force && (_state.value == UpdateState.Checking || _state.value == UpdateState.Downloading)) return
        if (!force && _state.value == UpdateState.ReadyToInstall) return
        
        if (force) cancelOngoingWork()

        val now = System.currentTimeMillis()
        if (!force && silent && now - lastCheckTime < 60_000) return
        
        lastCheckTime = now
        
        withWorker {
            AppLogsState.addLog("Checking for updates...")
            _state.value = UpdateState.Checking
            try {
                var release = withContext(Dispatchers.IO) { 
                    if (allowUnstable) fetchLatestUnstableRelease() else fetchLatestRelease() 
                }
                
                if (release == null) {
                    // Try again with xray if conditions are met
                    val proxy = getXrayIfRunning()
                    if (proxy != null) {
                        release = withContext(Dispatchers.IO) { 
                            if (allowUnstable) fetchLatestUnstableRelease(proxy) else fetchLatestRelease(proxy) 
                        }
                    }
                }

                if (release == null) {
                    AppLogsState.addLog("Failed to fetch release info from GitHub")
                    _state.value = if (silent) UpdateState.Idle
                    else UpdateState.Error(context.getString(R.string.error_release_info_failed))
                    return@withWorker
                }

                val remoteTag = release.getString("tag_name")
                val remoteName = release.optString("name", "")
                
                // Извлекаем версию и хеш из названия: "Unstable Build v6.1.1-a1b2c3d" -> "6.1.1-unstable-a1b2c3d"
                val remoteVersion = if (remoteTag == "unstable-latest" && remoteName.isNotBlank()) {
                    val versionRegex = """v(\d+(?:\.\d+)*)(?:-([a-f0-9]+))?""".toRegex()
                    val match = versionRegex.find(remoteName)
                    if (match != null) {
                        val ver = match.groupValues[1]
                        val hash = match.groupValues[2]
                        if (hash.isNotBlank()) "$ver-unstable-$hash" else "$ver-unstable"
                    } else {
                        "unstable-latest"
                    }
                } else {
                    remoteTag.removePrefix("v")
                }

                val remoteBody = release.optString("body", "")

                if (isNewer(remoteVersion, getCurrentVersion(), remoteBody)) {
                    latestApkUrl = findApkUrl(release)
                    if (latestApkUrl != null) {
                        AppLogsState.addLog("Update available: $remoteVersion")
                        val changelog = release.optString("body", "").trim()
                        _state.value = UpdateState.Available(remoteVersion, changelog)
                    } else {
                        AppLogsState.addLog("Update available ($remoteVersion), but no suitable APK found")
                        _state.value = if (silent) UpdateState.Idle
                        else UpdateState.Error(context.getString(R.string.error_apk_not_found))
                    }
                } else {
                    AppLogsState.addLog("No update found (current: ${getCurrentVersion()}, remote: $remoteVersion)")
                    _state.value = UpdateState.NoUpdate
                }
            } catch (e: Exception) {
                AppLogsState.addLog("Update check failed: ${e.message}")
                _state.value = if (silent) UpdateState.Idle
                else UpdateState.Error(context.getString(R.string.error_no_connection))
            }
        }
    }

    suspend fun downloadUpdate() {
        val url = latestApkUrl ?: run {
            _state.value = UpdateState.Error(context.getString(R.string.error_update_url_not_found))
            return
        }

        cancelOngoingWork()
        
        withWorker {
            AppLogsState.addLog("Downloading update...")
            _state.value = UpdateState.Downloading
            _downloadProgress.value = 0
            try {
                val apkFile = File(context.cacheDir, "update.apk")
                withContext(Dispatchers.IO) {
                    val proxy = getXrayIfRunning()
                    AppLogsState.addLog("Starting download from: $url" + (if (proxy != null) " (via proxy)" else ""))
                    val connection = if (proxy != null) {
                        URL(url).openConnection(proxy)
                    } else {
                        URL(url).openConnection()
                    } as HttpURLConnection
                    connection.instanceFollowRedirects = true
                    connection.connect()

                    val totalSize = connection.contentLength.toLong()
                    var downloaded = 0L

                    connection.inputStream.use { input ->
                        apkFile.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var lastProgress = -1
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                ensureActive()
                                output.write(buffer, 0, bytesRead)
                                downloaded += bytesRead
                                if (totalSize > 0) {
                                    val progress = (downloaded * 100 / totalSize).toInt()
                                    if (progress != lastProgress) {
                                        _downloadProgress.value = progress
                                        lastProgress = progress
                                    }
                                }
                            }
                        }
                    }
                }
                AppLogsState.addLog("Update downloaded successfully")
                val signerOk = withContext(Dispatchers.IO) { verifyApkSignature(apkFile) }
                if (!signerOk) {
                    AppLogsState.addLog("Signature verification FAILED — downloaded APK signer does not match this app. Aborting install.")
                    apkFile.delete()
                    _state.value = UpdateState.Error(context.getString(R.string.error_update_signature_mismatch))
                    return@withWorker
                }
                _state.value = UpdateState.ReadyToInstall
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLogsState.addLog("Download failed: ${e.message}")
                File(context.cacheDir, "update.apk").delete()
                _state.value = UpdateState.Error(context.getString(R.string.error_download_failed, e.message))
            }
        }
    }

    fun installUpdate() {
        AppLogsState.addLog("Launching APK installer")
        val apkFile = File(context.cacheDir, "update.apk")
        if (!apkFile.exists()) {
            _state.value = UpdateState.Error(context.getString(R.string.error_update_file_not_found))
            return
        }

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    // Private

    @Suppress("DEPRECATION")
    private fun verifyApkSignature(apkFile: File): Boolean {
        return try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                PackageManager.GET_SIGNING_CERTIFICATES
            else
                PackageManager.GET_SIGNATURES
            val downloaded = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            if (downloaded == null) {
                AppLogsState.addLog("Signature check: cannot parse downloaded APK")
                return false
            }
            if (downloaded.packageName != context.packageName) {
                AppLogsState.addLog("Signature check: package mismatch (${downloaded.packageName})")
                return false
            }
            val installedSigs = signaturesOf(pm.getPackageInfo(context.packageName, flags))
            val downloadedSigs = signaturesOf(downloaded)
            val ok = installedSigs.isNotEmpty() && installedSigs == downloadedSigs
            if (!ok) AppLogsState.addLog("Signature check: signer certificate mismatch")
            ok
        } catch (e: Exception) {
            AppLogsState.addLog("Signature check failed: ${e.message}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun signaturesOf(info: android.content.pm.PackageInfo): Set<android.content.pm.Signature> {
        val sigs: Array<android.content.pm.Signature>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.let {
                    if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
                }
            } else {
                info.signatures
            }
        return sigs?.toSet() ?: emptySet()
    }

    private fun fetchLatestRelease(proxy: java.net.Proxy? = null): JSONObject? {
        val json = fetchString(RELEASES_URL, proxy) ?: return null
        return try { JSONObject(json) } catch (_: Exception) { null }
    }

    private fun fetchLatestUnstableRelease(proxy: java.net.Proxy? = null): JSONObject? {
        val url = "https://api.github.com/repos/EgorLedyaev/WireTurn/releases"
        val json = fetchString(url, proxy) ?: return null
        
        return try {
            val jsonArray = JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                val release = jsonArray.getJSONObject(i)
                val tagName = release.getString("tag_name")
                if (tagName.contains("unstable", ignoreCase = true) || release.getBoolean("prerelease")) {
                    return release
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchString(url: String, proxy: java.net.Proxy? = null): String? {
        val connection = if (proxy != null) {
            URL(url).openConnection(proxy)
        } else {
            URL(url).openConnection()
        } as HttpURLConnection
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "wireturn-App")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        return try {
            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().readText()
            } else null
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun getXrayIfRunning(): java.net.Proxy? {
        val settingsSnapshot = com.wireturn.app.XrayServiceState.session.value?.settings ?: return null
        val state = com.wireturn.app.XrayServiceState.state.value
        if (state == com.wireturn.app.viewmodel.XrayState.Idle) return null

        return try {
            val socksAddr = settingsSnapshot.connectableAddress
            if (socksAddr.isNotBlank()) {
                val parts = socksAddr.split(":")
                if (parts.size == 2) {
                    val host = parts[0]
                    val port = parts[1].toInt()
                    java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress.createUnresolved(host, port))
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun findApkUrl(release: JSONObject): String? {
        val assets = release.getJSONArray("assets")
        val apkAssets = mutableListOf<JSONObject>()
        
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk", ignoreCase = true)) {
                apkAssets.add(asset)
            }
        }

        if (apkAssets.isEmpty()) return null

        // 1. Поиск под конкретную архитектуру устройства (в порядке приоритета ОС)
        for (abi in Build.SUPPORTED_ABIS) {
            val match = apkAssets.find { 
                it.getString("name").lowercase().contains(abi.lowercase()) 
            }
            if (match != null) return match.getString("browser_download_url")
        }

        // 2. Поиск универсальной сборки
        val universal = apkAssets.find { 
            it.getString("name").lowercase().contains("universal") 
        }
        if (universal != null) return universal.getString("browser_download_url")

        // 3. Фолбэк на любой найденный APK
        return apkAssets.firstOrNull()?.getString("browser_download_url")
    }

    private fun cancelOngoingWork() {
        activeJob?.cancel()
        activeJob = null
    }

    private suspend inline fun withWorker(crossinline block: suspend () -> Unit) {
        val job = currentCoroutineContext()[Job]
        activeJob = job
        try {
            block()
        } finally {
            if (activeJob == job) activeJob = null
        }
    }

    companion object {
        private const val RELEASES_URL =
            "https://api.github.com/repos/EgorLedyaev/WireTurn/releases/latest"

        private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
        private val _downloadProgress = MutableStateFlow(0)
        private var latestApkUrl: String? = null
        private var lastCheckTime = 0L
        private var activeJob: Job? = null

        fun isNewer(remote: String, current: String, remoteBody: String = ""): Boolean {
            // Вспомогательная функция для получения списка чисел из версии (напр. "1.0.2-unstable" -> [1, 0, 2])
            fun String.toVersionList(): List<Int> {
                val basePart = this.split("-").first()
                return basePart.split(".")
                    .map { it.filter { char -> char.isDigit() }.toIntOrNull() ?: 0 }
            }

            val remoteIsUnstable = remote.contains("unstable", ignoreCase = true)
            val currentIsUnstable = current.contains("unstable", ignoreCase = true)

            // Если обе версии нестабильные, сравниваем хеши (если они есть)
            if (remoteIsUnstable && currentIsUnstable) {
                // UPD-1: extract trailing git short-hash (>=7 hex) regardless of '-' count;
                // split("-").last() can yield "unstable".
                val hashRegex = Regex("[0-9a-f]{7,}")
                val rHash = hashRegex.findAll(remote).lastOrNull()?.value
                val cHash = hashRegex.findAll(current).lastOrNull()?.value
                if (rHash != null && cHash != null && rHash == cHash) return false
                val r = remote.toVersionList()
                val c = current.toVersionList()
                if (r == c) return true
            }

            val r = remote.toVersionList()
            val c = current.toVersionList()

            // Сравниваем основные числа версии
            for (i in 0 until maxOf(r.size, c.size)) {
                val rv = r.getOrElse(i) { 0 }
                val cv = c.getOrElse(i) { 0 }
                if (rv != cv) return rv > cv
            }
            
            // Если номера версий идентичны (напр. 1.0 и 1.0-unstable)
            // Стабильная версия всегда считается новее нестабильной
            if (!remoteIsUnstable && currentIsUnstable) return true
            
            // Если на GitHub пришла версия с суффиксом unstable, а у нас стабильная того же номера - это не обнова
            // Во всех остальных случаях (версии равны) - false
            return false
        }
    }
}
