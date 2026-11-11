package fr.caperuisseau.apkpureupdater

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client APKPure, calqué sur apkeep 1.1.0 (EFForg/apkeep, src/download_sources/apkpure.rs + src/consts.rs) :
 *  - GET https://api.pureapk.com/m/v3/cms/app_version?hl=en-US&package_name=<pkg>
 *  - en-têtes x-cv: 3172501, x-sv: 29, x-abis: <abis séparées par des virgules>, x-gp: 1
 *  - la réponse est du binaire (protobuf). apkeep ne la décode pas : il applique des regex dessus.
 *    * URL de téléchargement : (X?APKJ)..(https?://...)  -> "XAPKJ" = fichier .xapk, sinon .apk
 *    * noms de version       : ([[:alnum:]\.-]+):\([[:xdigit:]]{40,}
 *  - apkeep considère la PREMIÈRE URL trouvée comme la dernière version.
 *
 * API non officielle : elle peut changer ou casser du jour au lendemain.
 */
object ApkPureClient {

    private const val VERSIONS_URL = "https://api.pureapk.com/m/v3/cms/app_version?hl=en-US&package_name="

    /** Archi par défaut si on ne connaît pas celle de l'appareil (apkeep : -o arch=arm64-v8a). */
    const val DEFAULT_ABIS = "arm64-v8a"

    // On lit les octets en ISO-8859-1 : 1 octet = 1 caractère, rien n'est perdu.
    // Petite différence avec apkeep : "[\s\S]{2}" au lieu de ".." pour que l'octet de longueur
    // protobuf passe même s'il vaut 0x0A (un "." ne matche pas le saut de ligne).
    private val DOWNLOAD_RE = Regex(
        """(X?APKJ)[\s\S]{2}(https?://(www\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\.[a-zA-Z0-9()]{1,6}\b([-a-zA-Z0-9()@:%_+.~#?&/=]*))"""
    )
    private val VERSION_RE = Regex("""([A-Za-z0-9.\-]+):\([0-9A-Fa-f]{40,}""")

    data class Latest(
        val versionName: String?,
        val downloadUrl: String,
        val isXapk: Boolean,
    )

    class ApkPureException(msg: String) : Exception(msg)

    /** Appel réseau bloquant : à lancer hors du thread UI. */
    fun fetchLatest(packageName: String, abis: String = DEFAULT_ABIS): Latest {
        val url = URL(VERSIONS_URL + URLEncoder.encode(packageName, "UTF-8"))
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("x-cv", "3172501")
            conn.setRequestProperty("x-sv", "29")
            conn.setRequestProperty("x-abis", abis)
            conn.setRequestProperty("x-gp", "1")

            val code = conn.responseCode
            if (code == 404) throw ApkPureException("Appli introuvable sur APKPure")
            if (code != 200) throw ApkPureException("Réponse HTTP $code d'APKPure")
            val body = conn.inputStream.use { it.readBytes() }
            return parse(String(body, Charsets.ISO_8859_1))
                ?: throw ApkPureException("Aucune URL de téléchargement trouvée (appli absente d'APKPure ?)")
        } finally {
            conn.disconnect()
        }
    }

    fun parse(body: String): Latest? {
        val dl = DOWNLOAD_RE.find(body) ?: return null
        // Dans une entrée, le nom de version précède l'URL (cf. regex "version précise" d'apkeep).
        // On prend donc la dernière version vue AVANT la première URL.
        val versions = VERSION_RE.findAll(body).toList()
        val version = versions.lastOrNull { it.range.first < dl.range.first }?.groupValues?.get(1)
            ?: versions.firstOrNull()?.groupValues?.get(1)
        return Latest(
            versionName = version,
            downloadUrl = dl.groupValues[2],
            isXapk = dl.groupValues[1] == "XAPKJ",
        )
    }

    /**
     * Compare deux versionName. >0 si a plus récent que b, 0 si égales, <0 sinon.
     * null si on ne peut pas trancher (formats bizarres).
     */
    fun compareVersions(a: String, b: String): Int? {
        if (a == b) return 0
        val pa = a.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toBigInteger() }
        val pb = b.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toBigInteger() }
        if (pa.isEmpty() || pb.isEmpty()) return null
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { java.math.BigInteger.ZERO }
            val y = pb.getOrElse(i) { java.math.BigInteger.ZERO }
            if (x != y) return x.compareTo(y)
        }
        return null // mêmes chiffres mais textes différents ("1.2-beta" vs "1.2")
    }
}
