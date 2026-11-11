package fr.caperuisseau.apkpureupdater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkPureClientTest {

    private val sha = "0123456789abcdef0123456789abcdef01234567"

    /** Imite une entrée de la réponse binaire : "<version>:(<sha1>" … "<type>" + 2 octets + URL. */
    private fun entry(version: String, type: String, url: String, sep: String = "\u0012@") =
        "\u0001\u0002$version:($sha)\u0003\u0004$type$sep$url\u0000\u0005"

    @Test
    fun parse_takesFirstUrlAndItsVersion() {
        val body = entry("2.0.1", "XAPKJ", "https://download.pureapk.com/b/XAPK/abc?k=1&_fn=x") +
            entry("1.9.0", "APKJ", "https://download.pureapk.com/b/APK/def?k=2")
        val latest = ApkPureClient.parse(body)!!
        assertEquals("2.0.1", latest.versionName)
        assertEquals("https://download.pureapk.com/b/XAPK/abc?k=1&_fn=x", latest.downloadUrl)
        assertTrue(latest.isXapk)
    }

    @Test
    fun parse_plainApk() {
        val latest = ApkPureClient.parse(entry("133.0", "APKJ", "https://download.pureapk.com/b/APK/def"))!!
        assertEquals("133.0", latest.versionName)
        assertFalse(latest.isXapk)
    }

    @Test
    fun parse_lengthByteMayBeNewline() {
        val latest = ApkPureClient.parse(entry("1.0", "APKJ", "https://d.pureapk.com/x", sep = "\n\u0001"))
        assertEquals("https://d.pureapk.com/x", latest?.downloadUrl)
    }

    @Test
    fun parse_noUrl() {
        assertNull(ApkPureClient.parse("rien d'utile ici 1.0:($sha)"))
        assertNull(ApkPureClient.parse(""))
    }

    @Test
    fun parse_urlWithoutVersion() {
        val latest = ApkPureClient.parse("APKJ\u0001\u0002https://d.pureapk.com/x")!!
        assertNull(latest.versionName)
    }

    @Test
    fun compareVersions_numeric() {
        assertEquals(0, ApkPureClient.compareVersions("1.2.3", "1.2.3"))
        assertTrue(ApkPureClient.compareVersions("1.10", "1.9")!! > 0)
        assertTrue(ApkPureClient.compareVersions("1.9", "1.10")!! < 0)
        assertTrue(ApkPureClient.compareVersions("2.0", "1.99.99")!! > 0)
        assertTrue(ApkPureClient.compareVersions("1.2.1", "1.2")!! > 0)
    }

    @Test
    fun compareVersions_hugeBuildNumbers() {
        assertTrue(ApkPureClient.compareVersions("1.0.99999999999999999999", "1.0.99999999999999999998")!! > 0)
    }

    @Test
    fun compareVersions_undecidable() {
        assertNull(ApkPureClient.compareVersions("1.2-beta", "1.2"))
        assertNull(ApkPureClient.compareVersions("abc", "1.0"))
        assertNull(ApkPureClient.compareVersions("1.2", "1.2.0"))
    }
}
