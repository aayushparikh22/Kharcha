package com.spendcalci

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads updates/latest.json from the GitHub repo, downloads the APK it points to,
 * and hands it to Android's package installer. Works without a token (public repo).
 */
object Updater {
    private const val OWNER = "aayushparikh22"
    private const val REPO = "Kharcha"
    private const val BRANCH = "main"
    private const val RAW = "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/"
    private const val API = "https://api.github.com/repos/$OWNER/$REPO"
    const val REPO_URL = "https://github.com/$OWNER/$REPO"
    private const val MANIFEST_PATH = "updates/latest.json"

    data class Manifest(val versionName: String, val notes: String, val apkUrl: String)
    data class Push(val message: String, val date: String)

    fun installedVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /** Compares dotted versions numerically: "1.10" is newer than "1.9", "v2" equals "2.0". */
    fun isNewer(latest: String, current: String): Boolean {
        val a = versionParts(latest)
        val b = versionParts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun versionParts(v: String): List<Int> = v.trim().removePrefix("v").removePrefix("V")
        .split('.', '-').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    suspend fun fetchManifest(): Manifest = withContext(Dispatchers.IO) {
        val json = JSONObject(httpGet("$RAW$MANIFEST_PATH?t=${System.currentTimeMillis()}"))
        Manifest(
            versionName = json.getString("versionName"),
            notes = json.optString("notes"),
            apkUrl = RAW + json.getString("apk"),
        )
    }

    suspend fun fetchLatestPush(): Push? = withContext(Dispatchers.IO) {
        runCatching {
            val commit = JSONArray(httpGet("$API/commits?sha=$BRANCH&per_page=1")).getJSONObject(0)
            val info = commit.getJSONObject("commit")
            Push(
                message = info.getString("message").lineSequence().first(),
                date = info.getJSONObject("committer").getString("date"),
            )
        }.getOrNull()
    }

    /** Streams [url] into [dest], reporting 0..1 progress, and checks the download is a complete APK. */
    suspend fun download(url: String, dest: File, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(read.toFloat() / total)
                    }
                }
            }
            if (total > 0 && dest.length() != total) throw IOException("download incomplete")
            if (dest.length() < 4 || dest.inputStream().use { it.read() } != 'P'.code) {
                throw IOException("downloaded file is not an APK")
            }
        } finally {
            conn.disconnect()
        }
        dest
    }

    fun canInstallPackages(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun launchInstaller(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Kharcha-Updater")
        }

    private fun httpGet(url: String): String {
        val conn = open(url)
        try {
            when (val code = conn.responseCode) {
                in 200..299 -> return conn.inputStream.bufferedReader().use { it.readText() }
                404 -> throw FileNotFoundException(url)
                else -> throw IOException("HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }
}

/** Process-wide update state, shared by the Updates tab, its badge and the launch check. */
object UpdateCenter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var installed by mutableStateOf("")
        private set
    var manifest by mutableStateOf<Updater.Manifest?>(null)
        private set
    var push by mutableStateOf<Updater.Push?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    /** Download progress 0..1, or null when nothing is downloading. */
    val progress = MutableStateFlow<Float?>(null)

    val updateAvailable: Boolean
        get() = manifest?.let { Updater.isNewer(it.versionName, installed) } ?: false

    fun init(ctx: Context) {
        installed = Updater.installedVersion(ctx)
    }

    fun check(silent: Boolean = false) {
        if (checking) return
        checking = true
        scope.launch {
            push = Updater.fetchLatestPush() ?: push
            try {
                manifest = Updater.fetchManifest()
                error = null
                notice = null
            } catch (e: FileNotFoundException) {
                notice = "No update has been published yet."
            } catch (e: Exception) {
                if (!silent) error = "Couldn't reach GitHub. Check your connection and try again."
            } finally {
                checking = false
            }
        }
    }

    fun downloadAndInstall(ctx: Context) {
        val m = manifest ?: return
        if (progress.value != null) return
        if (!Updater.canInstallPackages(ctx)) {
            Updater.openInstallPermission(ctx)
            return
        }
        error = null
        progress.value = 0f
        scope.launch {
            try {
                val apk = Updater.download(m.apkUrl, File(ctx.cacheDir, "updates/Kharcha-update.apk")) {
                    progress.value = it
                }
                Updater.launchInstaller(ctx, apk)
            } catch (e: Exception) {
                error = "Download failed: ${e.message ?: "unknown error"}"
            } finally {
                progress.value = null
            }
        }
    }
}
