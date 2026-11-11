package fr.caperuisseau.apkpureupdater

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Petit stockage dans les SharedPreferences : applis surveillées, réglages, téléchargements. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("store", Context.MODE_PRIVATE)

    // Gardé en mémoire : lu à chaque ligne de la liste, inutile de recopier le Set à chaque fois.
    @Volatile
    private var watchedCache: Set<String> = prefs.getStringSet("watched", emptySet())!!.toSet()

    var watched: Set<String>
        get() = watchedCache
        set(v) {
            watchedCache = v.toSet()
            prefs.edit().putStringSet("watched", watchedCache).apply()
        }

    fun setWatched(pkg: String, on: Boolean) {
        watched = if (on) watched + pkg else watched - pkg
    }

    var autoCheck: Boolean
        get() = prefs.getBoolean("auto", false)
        set(v) { prefs.edit().putBoolean("auto", v).apply() }

    var showSystem: Boolean
        get() = prefs.getBoolean("show_system", false)
        set(v) { prefs.edit().putBoolean("show_system", v).apply() }

    /** L'avertissement "APKPure n'est pas une source officielle" a été lu et fermé. */
    var warningSeen: Boolean
        get() = prefs.getBoolean("warning_seen", false)
        set(v) { prefs.edit().putBoolean("warning_seen", v).apply() }

    /** Dernière version pour laquelle on a déjà notifié (évite de spammer). */
    fun lastNotified(pkg: String): String? = prefs.getString("notified_$pkg", null)
    fun setLastNotified(pkg: String, v: String) { prefs.edit().putString("notified_$pkg", v).apply() }

    /** id DownloadManager -> "pkg|apk" ou "pkg|xapk" */
    fun rememberDownload(id: Long, pkg: String, isXapk: Boolean) {
        prefs.edit().putString("dl_$id", "$pkg|${if (isXapk) "xapk" else "apk"}").apply()
    }
    fun downloadInfo(id: Long): Pair<String, Boolean>? =
        prefs.getString("dl_$id", null)?.split("|")?.let { it[0] to (it.getOrNull(1) == "xapk") }
    fun forgetDownload(id: Long) { prefs.edit().remove("dl_$id").apply() }
}

/** ABI de l'appareil, par ordre de préférence ("arm64-v8a,armeabi-v7a,armeabi" sur un téléphone récent). */
val deviceAbis: List<String>
    get() = Build.SUPPORTED_ABIS?.toList().orEmpty().ifEmpty { listOf(ApkPureClient.DEFAULT_ABIS) }

/** Version installée (versionName), ou null si l'appli n'est pas installée. */
fun installedVersion(context: Context, pkg: String): String? = try {
    context.packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
} catch (_: PackageManager.NameNotFoundException) {
    null
}

enum class Status { UPDATE, UP_TO_DATE, DIFFERENT, ERROR }

data class CheckResult(
    val pkg: String,
    val installed: String?,
    val latest: ApkPureClient.Latest?,
    val status: Status,
    val error: String? = null,
)

/** Vérifie un package (bloquant, réseau). */
fun checkPackage(context: Context, pkg: String): CheckResult {
    val installed = installedVersion(context, pkg)
    return try {
        val latest = ApkPureClient.fetchLatest(pkg, deviceAbis.joinToString(","))
        val status = when {
            installed == null -> Status.UPDATE
            latest.versionName == null -> Status.DIFFERENT
            else -> when (val c = ApkPureClient.compareVersions(latest.versionName, installed)) {
                null -> Status.DIFFERENT
                else -> if (c > 0) Status.UPDATE else Status.UP_TO_DATE
            }
        }
        CheckResult(pkg, installed, latest, status)
    } catch (e: Exception) {
        CheckResult(pkg, installed, null, Status.ERROR, e.message ?: e.javaClass.simpleName)
    }
}
