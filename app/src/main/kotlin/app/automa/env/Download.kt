package app.automa.env

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * The only network code of the app (DESIGN §8): HTTPS GETs. A file stays only if its SHA-256 is correct; a text
 * (the release data for the update) has a size limit. HttpsURLConnection follows redirects only from https to https.
 */
object Download {
    suspend fun fetch(url: String, to: File, sha256: String, maxBytes: Long, progress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val part = File(to.path + ".part")
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = open(url)
            try {
                val total = connection.contentLengthLong
                if (total > maxBytes) throw IOException("File too large")
                var done = 0L
                connection.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(256 shl 10)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            if (done > maxBytes) throw IOException("File too large")
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                            if (total > 0) progress(done.toFloat() / total)
                        }
                    }
                }
                if (total >= 0 && done != total) throw IOException("Download incomplete")
                if (hex(digest.digest()) != sha256) throw IOException("The file is not the expected file (SHA-256)")
                if (!part.renameTo(to)) throw IOException("Cannot save the file")
            } finally {
                connection.disconnect()
                part.delete()
            }
        }

    /** A small UTF-8 text, at most [maxBytes]. With [headers] (a key), no redirects, so the key goes to no other host. */
    suspend fun text(url: String, maxBytes: Int, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val connection = open(url, headers)
        try {
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buf = ByteArray(16 shl 10)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) throw IOException("File too large")
                    out.write(buf, 0, n)
                }
            }
            out.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    /** Connects and checks the answer: HTTPS also after redirects, and HTTP 200. */
    private fun open(url: String, headers: Map<String, String> = emptyMap()): HttpsURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 45_000
            connection.setRequestProperty("User-Agent", "Automa")
            if (headers.isNotEmpty()) connection.instanceFollowRedirects = false
            headers.forEach(connection::setRequestProperty)
            val code = connection.responseCode
            if (connection.url.protocol != "https") throw IOException("Refused a redirect to an insecure address")
            if (code != 200) throw IOException("Server answered HTTP $code")
            return connection
        } catch (e: Exception) {
            connection.disconnect()
            throw e
        }
    }

    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
