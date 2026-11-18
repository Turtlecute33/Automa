package app.automa.env

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Extracts a tar stream (ustar, pax and GNU long names) into a folder (DESIGN §3). It refuses absolute names,
 * `..`, and a write through a symbolic link, so that an archive cannot write outside the folder. The owner
 * always gets read and write access, because the guest package manager writes as the app user. It does not keep
 * the setuid, setgid and sticky bits (Setup sets the sticky bit of /tmp).
 */
object Tar {
    private const val BLOCK = 512

    /** Extracts [input] into [dest]. With [only], extracts only the entries with these names. */
    fun extract(input: InputStream, dest: File, only: Set<String>? = null): Int {
        dest.mkdirs()
        val root = dest.canonicalFile
        val header = ByteArray(BLOCK)
        var longName: String? = null
        var longLink: String? = null
        var count = 0
        while (true) {
            if (!readBlock(input, header)) break
            if (header.all { it == 0.toByte() }) break
            if (octal(header, 148, 8) != checksum(header).toLong()) throw IOException("Damaged archive")
            val type = header[156].toInt().toChar()
            val size = size(header)
            var name = longName ?: entryName(header)
            var link = longLink ?: text(header, 157, 100)
            longName = null
            longLink = null
            when (type) {
                'L' -> { longName = text(readData(input, size)); continue }
                'K' -> { longLink = text(readData(input, size)); continue }
                'x' -> {
                    val pax = pax(readData(input, size))
                    longName = pax["path"]
                    longLink = pax["linkpath"]
                    continue
                }
                'g' -> { skip(input, size); continue }
            }
            name = normalize(name) ?: run {
                skip(input, size) // the archive root, "./"
                continue
            }
            if (only != null && name !in only) { skip(input, size); continue }
            val target = File(root, name)
            val parent = target.parentFile ?: root
            // Before mkdirs: a symbolic link in the parent path must not lead outside.
            if (!inside(root, parent.canonicalFile)) throw IOException("Archive entry outside the folder")
            parent.mkdirs()
            val path = target.toPath()
            when (type) {
                '5' -> {
                    if (target.exists() && !inside(root, target.canonicalFile)) throw IOException("Archive entry outside the folder")
                    target.mkdirs()
                    setMode(target, octal(header, 100, 8).toInt() or 0b111_000_000)
                }
                '2' -> {
                    Files.deleteIfExists(path)
                    Files.createSymbolicLink(path, Paths.get(link))
                }
                '1' -> {
                    val source = File(root, normalize(link) ?: throw IOException("Bad link in archive"))
                    if (!inside(root, (source.parentFile ?: root).canonicalFile)) throw IOException("Archive entry outside the folder")
                    Files.copy(source.toPath(), path, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING)
                }
                '0', '\u0000', '7' -> {
                    Files.deleteIfExists(path)
                    target.outputStream().use { out -> copy(input, out, size) }
                    padding(input, size)
                    setMode(target, octal(header, 100, 8).toInt() or 0b110_000_000)
                }
                else -> { skip(input, size); continue }
            }
            count++
        }
        return count
    }

    /** A relative name with no `.` or `..` parts, or null for the archive root. Throws for an unsafe name. */
    fun normalize(raw: String): String? {
        if (raw.startsWith("/")) throw IOException("Absolute name in archive")
        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw IOException("Name with .. in archive")
        return parts.joinToString("/").ifEmpty { null }
    }

    private fun inside(root: File, file: File) = file == root || file.path.startsWith(root.path + File.separator)

    private fun entryName(h: ByteArray): String {
        val name = text(h, 0, 100)
        val ustar = String(h, 257, 5, Charsets.US_ASCII) == "ustar"
        val prefix = if (ustar) text(h, 345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun size(h: ByteArray): Long {
        // GNU base-256 for sizes of 8 GiB and more.
        if (h[124].toInt() and 0x80 != 0) return (125 until 136).fold(0L) { acc, i -> (acc shl 8) or (h[i].toLong() and 0xff) }
        return octal(h, 124, 12)
    }

    /** A Long: a size of 2 GiB and more does not fit an Int (review DS-L-33). */
    private fun octal(h: ByteArray, off: Int, len: Int): Long = text(h, off, len).trim().ifEmpty { "0" }.toLong(8)

    private fun checksum(h: ByteArray): Int =
        h.indices.sumOf { if (it in 148 until 156) ' '.code else h[it].toInt() and 0xff }

    private fun text(h: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && h[end] != 0.toByte()) end++
        return String(h, off, end - off, Charsets.UTF_8)
    }

    private fun text(data: ByteArray) = text(data, 0, data.size)

    /** pax records: "<length> <key>=<value>\n". */
    private fun pax(data: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < data.size) {
            val space = (i until data.size).firstOrNull { data[it] == ' '.code.toByte() } ?: break
            val length = String(data, i, space - i, Charsets.US_ASCII).toIntOrNull() ?: break
            if (length <= 0 || i + length > data.size) break
            val record = String(data, space + 1, i + length - space - 2, Charsets.UTF_8)
            val eq = record.indexOf('=')
            if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
            i += length
        }
        return out
    }

    private fun readData(input: InputStream, size: Long): ByteArray {
        if (size > 1 shl 20) throw IOException("Archive header too large")
        val data = input.readNBytes(size.toInt())
        if (data.size.toLong() != size) throw EOFException("Archive ends too early")
        padding(input, size)
        return data
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(64 shl 10)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("Archive ends too early")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, size: Long) {
        input.skipNBytes(size)
        padding(input, size)
    }

    private fun padding(input: InputStream, size: Long) {
        val rest = (BLOCK - size % BLOCK) % BLOCK
        if (rest > 0) input.skipNBytes(rest)
    }

    private fun readBlock(input: InputStream, block: ByteArray): Boolean {
        val n = input.readNBytes(block, 0, BLOCK)
        if (n == 0) return false
        if (n < BLOCK) throw EOFException("Archive ends too early")
        return true
    }

    private fun setMode(file: File, mode: Int) {
        val perms = HashSet<PosixFilePermission>()
        PosixFilePermission.entries.forEachIndexed { i, p -> if (mode and (1 shl (8 - i)) != 0) perms += p }
        Files.setPosixFilePermissions(file.toPath(), perms)
    }
}
