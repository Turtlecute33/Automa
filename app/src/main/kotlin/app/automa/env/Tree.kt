package app.automa.env

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

/** File tree work that never follows a symbolic link: a guest link can point to any host path. */
object Tree {
    /** Deletes [dir] and all in it. A symbolic link is deleted, not followed. */
    fun delete(dir: File) {
        val root = dir.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                // A read-only folder from a package: the owner needs write access to delete in it.
                addOwner(d)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(f)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(f: Path, e: IOException): FileVisitResult {
                // A folder with no permissions (chmod 000) cannot be opened, so preVisitDirectory does not come. The
                // owner gets access, then a new walk deletes it (review KM-L-05). No loop: a second failure throws.
                if (!Files.isDirectory(f, LinkOption.NOFOLLOW_LINKS) || !addOwner(f)) throw e
                delete(f.toFile())
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(d: Path, e: IOException?): FileVisitResult {
                if (e != null) throw e
                Files.delete(d)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /**
     * The text of [file] when it is a regular file of at most [max] bytes, else null. A guest program can put a FIFO
     * (its open waits for ever), a link to /dev/zero or a very large file where the app reads a marker or a config
     * (review DS-L-19, DS-L-76). A symbolic link is not followed. The check and the open are two steps: a guest
     * program that changes the file between them can still make the open wait (at the app start no guest runs).
     */
    fun readSmall(file: File, max: Int = SMALL): String? = runCatching {
        val path = file.toPath()
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attrs.isRegularFile || attrs.size() > max) return null
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(max + 1) }
            .takeIf { it.size <= max }?.decodeToString()
    }.getOrNull()

    /** Adds owner read, write and execute to the folder [d]. True when the permissions changed. */
    private fun addOwner(d: Path): Boolean = runCatching {
        val perms = Files.getPosixFilePermissions(d, LinkOption.NOFOLLOW_LINKS)
        if (perms.containsAll(OWNER)) return false
        Files.setPosixFilePermissions(d, perms + OWNER)
        true
    }.getOrDefault(false)

    private const val SMALL = 64 shl 10
    private val OWNER = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
}
