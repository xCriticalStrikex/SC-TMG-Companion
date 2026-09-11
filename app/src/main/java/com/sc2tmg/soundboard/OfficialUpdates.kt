package com.sc2tmg.soundboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateReport(
    val ok: Boolean,
    val message: String,
    val documents: List<String> = emptyList()
)

object OfficialUpdates {
    const val DOWNLOADS_URL = "https://archon-studio.com/downloads/starcraft-tabletop-miniatures-game"

    private data class OfficialDoc(val label: String, val url: String, val bundledSha256: String)

    private val docs = listOf(
        OfficialDoc("Core Rules", "https://archon-studio.com/files/manuals/sc/StarCraft-TMG_EN.pdf", "27639c562e6db9777dd9ba984d0c9f9b581841ec30166848e021f893cd00ea54"),
        OfficialDoc("FAQ", "https://archon-studio.com/files/manuals/sc/StarCraft-TMG-FAQ_EN.pdf", "eeeffb7a3a11f7616116bcd0e8fd5a437cd50c47c2454a3c865e32f34783e62c"),
        OfficialDoc("Protoss Cards", "https://archon-studio.com/files/manuals/sc/StarCraft-Protoss-P2P-Card-Sheets-A4_EN.pdf", "4e8547b2df8d545df3d0ebb7d7821521a888dc0437d6f4dde21d82145337a212"),
        OfficialDoc("Terran Cards", "https://archon-studio.com/files/manuals/sc/StarCraft-Terran-P2P-Card-Sheets-A4_EN.pdf", "afa3f229db61444d0673dea35e31772530a4c39dadaa0e281ba1bae0d271109c"),
        OfficialDoc("Zerg Cards", "https://archon-studio.com/files/manuals/sc/StarCraft-Zerg-P2P-Card-Sheets-A4_EN.pdf", "6810f46ee422ac5d8f3cc169c3eda3ccb9551f01ab71a1f7e4ac8c266817b364")
    )

    suspend fun check(): UpdateReport = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        var verified = 0
        var changed = 0

        docs.forEach { doc ->
            try {
                val remoteHash = sha256OfUrl(doc.url)
                when {
                    remoteHash == null -> lines += "? ${doc.label}: could not verify"
                    remoteHash.equals(doc.bundledSha256, ignoreCase = true) -> {
                        verified++
                        lines += "✓ ${doc.label}: current"
                    }
                    else -> {
                        changed++
                        lines += "! ${doc.label}: UPDATE AVAILABLE"
                    }
                }
            } catch (_: Exception) {
                lines += "? ${doc.label}: could not verify"
            }
        }

        val extraDownloads = runCatching { discoverExtraDownloads() }.getOrDefault(emptyList())
        val summary = buildString {
            append(lines.joinToString("\n"))
            append("\n\n")
            when {
                changed > 0 -> append("$changed bundled document(s) differ from Archon's current official PDF. Open the official downloads page to retrieve the new copy; this build's offline search remains tied to its bundled text until the app is refreshed.")
                verified == docs.size -> append("All bundled rules/FAQ/card PDFs match the current official files byte-for-byte.")
                else -> append("No confirmed difference was found, but some documents could not be verified. Check again on a stable connection.")
            }
            if (extraDownloads.isNotEmpty()) {
                append("\n\nOther official StarCraft downloads detected: ")
                append(extraDownloads.take(10).joinToString())
            }
        }
        UpdateReport(ok = verified > 0 || changed > 0, message = summary, documents = lines)
    }

    private fun sha256OfUrl(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SC2-TMG-Companion/1.1")
        }
        if (connection.responseCode !in 200..299) return null
        val digest = MessageDigest.getInstance("SHA-256")
        connection.inputStream.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun discoverExtraDownloads(): List<String> {
        val connection = (URL(DOWNLOADS_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "SC2-TMG-Companion/1.1")
        }
        if (connection.responseCode !in 200..299) return emptyList()
        val html = connection.inputStream.bufferedReader().use { it.readText() }
        val known = listOf("StarCraft-TMG", "StarCraft-TMG-FAQ", "StarCraft-Protoss-P2P-Card-Sheets-A4", "StarCraft-Terran-P2P-Card-Sheets-A4", "StarCraft-Zerg-P2P-Card-Sheets-A4")
        return Regex("StarCraft-[A-Za-z0-9() _.-]+")
            .findAll(html)
            .map { it.value.trim().trimEnd('.', '-') }
            .filter { it.length in 10..90 }
            .distinct()
            .filterNot { found -> known.any { expected -> found.startsWith(expected, ignoreCase = true) } }
            .toList()
    }
}
