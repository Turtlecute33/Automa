package app.automa.files

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import app.automa.R
import app.automa.app
import app.automa.data.Prefs
import app.automa.env.Tree
import java.io.File
import java.io.FileNotFoundException

/**
 * The guest home folder `/root` in the system Files app and in document pickers (DESIGN §5), so that files go in and
 * out with no other app. Only the system (MANAGE_DOCUMENTS) can bind. Paths never leave the home folder, also not
 * through a symbolic link. With the app lock on, there is no root.
 */
class HomeProvider : DocumentsProvider() {
    private val home: File get() = requireContext().app.env.home.canonicalFile

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        if (Prefs.lockOn(requireContext()) || !home.isDirectory) return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT)
            add(Root.COLUMN_DOCUMENT_ID, ROOT)
            add(Root.COLUMN_TITLE, "Automa")
            add(Root.COLUMN_SUMMARY, "Home folder of the environment")
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).also { row(it, documentId, file(documentId)) }

    override fun queryChildDocuments(parentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val root = home
        file(parentId).listFiles().orEmpty()
            .filter { inside(root, it.canonicalFile) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .forEach { row(cursor, "$parentId/${it.name}", it) }
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentId: String, mimeType: String, displayName: String): String {
        val parent = file(parentId)
        val base = displayName.replace('/', '_').trim().take(200)
        if (base.isEmpty() || base == "." || base == "..") throw FileNotFoundException("Bad name")
        var name = base
        var n = 1
        while (File(parent, name).exists()) name = "$base (${n++})"
        val created = File(parent, name)
        val ok = if (mimeType == Document.MIME_TYPE_DIR) created.mkdir() else created.createNewFile()
        if (!ok) throw FileNotFoundException("Cannot create $name")
        return "$parentId/$name"
    }

    override fun deleteDocument(documentId: String) {
        if (documentId == ROOT) throw FileNotFoundException("Cannot delete the home folder")
        file(documentId) // checks the ID, the lock and the place
        // The canonical path of a link is its target: delete the link itself, not the target.
        Tree.delete(File(file(documentId.substringBeforeLast('/')), documentId.substringAfterLast('/')))
    }

    /** On the real files (review SEC-1): a tree grant must not reach a parent through `..` or a link. */
    override fun isChildDocument(parentDocumentId: String, documentId: String) = runCatching {
        val parent = file(parentDocumentId)
        val child = file(documentId)
        child != parent && inside(parent, child)
    }.getOrDefault(false)

    /**
     * The file for [id] ("home" or "home/<relative path>"), only inside the home folder. No empty, `.` or `..`
     * parts (review SEC-1). Nothing while the app lock is on, also for grants from before the lock (SEC-2).
     */
    private fun file(id: String): File {
        if (Prefs.lockOn(requireContext())) throw FileNotFoundException("Automa is locked")
        if (id != ROOT && !id.startsWith("$ROOT/")) throw FileNotFoundException("Unknown document")
        if (id != ROOT && id.removePrefix("$ROOT/").split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw FileNotFoundException("Unknown document")
        }
        val root = home
        val file = File(root, id.removePrefix(ROOT).trimStart('/')).canonicalFile
        if (!inside(root, file) || !file.exists()) throw FileNotFoundException("Unknown document")
        return file
    }

    private fun inside(root: File, file: File) = file == root || file.path.startsWith(root.path + File.separator)

    private fun row(cursor: MatrixCursor, id: String, file: File) {
        val dir = file.isDirectory
        val flags = when {
            dir -> Document.FLAG_DIR_SUPPORTS_CREATE or (if (id == ROOT) 0 else Document.FLAG_SUPPORTS_DELETE)
            else -> Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id)
            add(Document.COLUMN_DISPLAY_NAME, if (id == ROOT) "Home" else file.name)
            add(Document.COLUMN_MIME_TYPE, if (dir) Document.MIME_TYPE_DIR else mime(file.name))
            add(Document.COLUMN_SIZE, if (dir) null else file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun mime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (ext in TEXT) "text/plain" else "application/octet-stream"
    }

    private companion object {
        const val ROOT = "home"
        val TEXT = setOf("md", "kt", "kts", "ts", "tsx", "js", "json", "yml", "yaml", "toml", "sh", "py", "rs", "go", "log", "diff", "patch")
        val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS,
            Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES,
        )
        val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
