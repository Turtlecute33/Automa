package app.automa.env

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

/** File tree work that never follows a symbolic link: a guest link can point to any host path. */
object Tree {
    /** Deletes [dir] and all in it. A symbolic link is deleted, not followed. */
    fun delete(dir: File) {
        val root = dir.toPath()
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                // A read-only folder from a package: the owner needs write access to delete in it.
                runCatching { Files.setPosixFilePermissions(d, Files.getPosixFilePermissions(d) + OWNER) }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(f)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(d: Path, e: IOException?): FileVisitResult {
                if (e != null) throw e
                Files.delete(d)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private val OWNER = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
}
