package com.andmx.ui2.product

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.andmx.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal const val PRODUCT_REPO = "https://github.com/shiaho777/andmx"
private const val STAGED_APK_NAME = "update.apk"
private const val APK_LIMIT = 160L * 1024 * 1024
private val previewTag = Regex("""v[0-9]+\.[0-9]+\.[0-9]+-preview\.[0-9]+""")
private val stableTag = Regex("""v[0-9]+\.[0-9]+\.[0-9]+""")
private val digestPattern = Regex("[a-f0-9]{64}")

internal fun matchesGithubReleaseChannel(tag: String, prerelease: Boolean, previewChannel: Boolean): Boolean =
    prerelease == previewChannel && (if (previewChannel) previewTag else stableTag).matches(tag)

private fun releaseApkName(versionName: String): String = "andmx.$versionName.APK"

internal data class PreviewRelease(
    val versionName: String,
    val versionCode: Long,
    val sha256: String,
    val certificateSha256: String,
    val notes: String,
) {
    val apkUrl: String get() = "$PRODUCT_REPO/releases/download/v$versionName/${releaseApkName(versionName)}"
}

internal fun parsePreviewRelease(tag: String, manifest: JSONObject, notes: String): PreviewRelease {
    val versionName = manifest.getString("versionName")
    val versionCode = manifest.getLong("versionCode")
    val sha256 = manifest.getString("sha256")
    require((stableTag.matches(tag) || previewTag.matches(tag)) && tag == "v$versionName")
    require(manifest.getString("packageName") == "com.andmx" && manifest.getString("apk") == releaseApkName(versionName))
    val certificate = manifest.getString("certificateSha256")
    require(versionCode > 1 && digestPattern.matches(sha256) && digestPattern.matches(certificate))
    return PreviewRelease(versionName, versionCode, sha256, certificate, notes.take(6000))
}

internal class PreviewUpdates(private val app: Context) {
    private val manager = app.packageManager
    private val directory = File(app.cacheDir, "product-update")

    suspend fun check(): PreviewRelease? = withContext(Dispatchers.IO) {
        val installed = PackageInfoCompat.getLongVersionCode(installedInfo())
        val releases = JSONArray(readText("https://api.github.com/repos/shiaho777/andmx/releases?per_page=10"))
        val candidates = buildList {
            for (index in 0 until releases.length()) {
                val item = releases.getJSONObject(index)
                val tag = item.optString("tag_name")
                if (item.optBoolean("draft") ||
                    !matchesGithubReleaseChannel(tag, item.optBoolean("prerelease"), BuildConfig.GITHUB_PREVIEW)
                ) continue
                val assets = item.getJSONArray("assets")
                val names = (0 until assets.length()).map { assets.getJSONObject(it).optString("name") }
                val apkName = releaseApkName(tag.removePrefix("v"))
                if ("release-manifest.json" !in names || apkName !in names) continue
                val manifest = JSONObject(readText("$PRODUCT_REPO/releases/download/$tag/release-manifest.json"))
                add(parsePreviewRelease(tag, manifest, item.optString("body")))
            }
        }
        candidates.filter { it.versionCode > installed }.maxByOrNull { it.versionCode }
    }

    suspend fun download(release: PreviewRelease, progress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val apk = File(directory, STAGED_APK_NAME)
        val temporary = File.createTempFile("download-", ".apk", directory)
        val coroutine = currentCoroutineContext()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            connect(release.apkUrl) { connection ->
                val total = connection.contentLengthLong
                require(total in -1..APK_LIMIT)
                connection.inputStream.use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var received = 0L
                        var nextProgress = 0L
                        while (true) {
                            coroutine.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            require(received <= APK_LIMIT)
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            if (received >= nextProgress) {
                                progress(received, total)
                                nextProgress = received + 1024 * 1024
                            }
                        }
                        require(total < 0 || received == total)
                    }
                }
            }
            require(digest.digest().asHex() == release.sha256)
            requireMatchingPackage(temporary, release)
            if (!temporary.renameTo(apk)) throw IOException("Could not stage update")
            apk
        } finally {
            temporary.delete()
        }
    }

    suspend fun installer(file: File, release: PreviewRelease): Intent = withContext(Dispatchers.IO) {
        require(file.canonicalFile == File(directory, STAGED_APK_NAME).canonicalFile)
        val digest = MessageDigest.getInstance("SHA-256")
        val coroutine = currentCoroutineContext()
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                coroutine.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().asHex() == release.sha256)
        requireMatchingPackage(file, release)
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun requireMatchingPackage(file: File, release: PreviewRelease) {
        val installed = installedInfo()
        val archive = requireNotNull(manager.getPackageArchiveInfo(file.path, signingFlags()))
        require(archive.packageName == app.packageName && archive.versionName == release.versionName)
        require(PackageInfoCompat.getLongVersionCode(archive) == release.versionCode)
        require(release.versionCode > PackageInfoCompat.getLongVersionCode(installed))
        val signers = installed.signerFingerprints()
        require(signers == setOf(release.certificateSha256) && signers == archive.signerFingerprints())
    }

    private fun installedInfo(): PackageInfo = manager.getPackageInfo(app.packageName, signingFlags())

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= 28) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.signerFingerprints(): Set<String> {
        val certificates = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures
        return certificates.orEmpty().map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).asHex()
        }.toSet()
    }

    private fun readText(url: String): String = connect(url) { connection ->
        connection.inputStream.use { input -> input.readPreviewText() }
    }

    private fun <T> connect(url: String, read: (HttpsURLConnection) -> T): T {
        var target = URL(url)
        repeat(6) {
            val host = target.host.lowercase()
            require(target.protocol == "https" && target.userInfo == null && target.port in listOf(-1, 443))
            require(host == "github.com" || host == "api.github.com" || host.endsWith(".githubusercontent.com"))
            val connection = target.openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 8000
                connection.readTimeout = 12000
                connection.setRequestProperty("User-Agent", "AndMX")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    target = URL(target, requireNotNull(connection.getHeaderField("Location")))
                } else {
                    if (status != 200) throw IOException("GitHub HTTP $status")
                    return read(connection)
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }
}

private fun InputStream.readPreviewText(): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= 1024 * 1024)
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}

private fun ByteArray.asHex(): String = joinToString("") { "%02x".format(it) }
