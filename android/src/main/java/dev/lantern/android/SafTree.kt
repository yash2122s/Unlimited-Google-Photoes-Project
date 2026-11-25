package dev.lantern.android

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract as DC
import android.provider.DocumentsContract.Document as D
import dev.lantern.core.*
import java.io.*

class SafTree(private val context: Context, private val resolver: ContentResolver, override val key: String): Tree {
    private val base = Uri.parse(key)
    private fun uri(id: String) = DC.buildDocumentUriUsingTree(base,id)
    private val columns = arrayOf(D.COLUMN_DOCUMENT_ID,D.COLUMN_DISPLAY_NAME,D.COLUMN_SIZE,D.COLUMN_LAST_MODIFIED,D.COLUMN_MIME_TYPE)
    private fun resolveDiskPath(docId: String): String? {
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = docId.substring(0, colon)
        val rel = docId.substring(colon + 1).trimStart('/')
        val candidate = if (volume.equals("primary", true)) {
            @Suppress("DEPRECATION")
            File(Environment.getExternalStorageDirectory(), rel)
        } else {
            File("/storage/$volume", rel)
        }
        return candidate.takeIf { it.exists() }?.absolutePath
    }
    private fun notifyMediaStore(node: Node) {
        if (node.dir || node.name.startsWith(".")) return
        val path = runCatching { resolveDiskPath(node.id) }.getOrNull() ?: return
        val mime = node.mime.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
        runCatching {
            MediaScannerConnection.scanFile(context.applicationContext, arrayOf(path), mime?.let { arrayOf(it) }, null)
        }
    }
    private fun row(c: Cursor) = Node(c.getString(0) ?: "",c.getString(1) ?: "",if(c.isNull(2)) -1 else c.getLong(2),
        if(c.isNull(3)) 0 else c.getLong(3),c.getString(4) == D.MIME_TYPE_DIR,c.getString(4) ?: "")
    private fun query(at: Uri): List<Node> {
        try {
            return resolver.query(at,columns,null,null,null)?.use { c ->
                val extras = c.extras
                check(extras?.getBoolean(DC.EXTRA_LOADING,false) != true) { "Storage provider is loading" }
                check(extras?.getString(DC.EXTRA_ERROR) == null) { "Storage provider reported an error" }
                buildList { while (c.moveToNext()) add(row(c)) }
            } ?: error("Waiting for selected SSD.")
        } catch (e: FileNotFoundException) { throw IOException("Waiting for selected SSD.",e) }
        catch (e: IllegalArgumentException) { throw IOException("Waiting for selected SSD.",e) }
    }
    override fun root() = query(uri(DC.getTreeDocumentId(base))).singleOrNull() ?: error("Waiting for selected SSD.")
    override fun children(dir: Node) = query(DC.buildChildDocumentsUriUsingTree(base,dir.id))
    override fun open(n: Node) = resolver.openInputStream(uri(n.id)) ?: error("File unavailable")
    override fun create(parent: Node,name: String,directory: Boolean): Node {
        val item = DC.createDocument(resolver,uri(parent.id),if(directory) D.MIME_TYPE_DIR else "application/octet-stream",name)
            ?: error("Cannot create file. Check space and folder permission.")
        return query(item).single()
    }
    override fun append(n: Node,offset: Long): OutputStream {
        val fd = resolver.openFileDescriptor(uri(n.id),"rw") ?: error("Cannot open destination")
        val stream = ParcelFileDescriptor.AutoCloseOutputStream(fd)
        try {
            check(stream.channel.size() == offset) { "Destination changed. Retry." }
            stream.channel.position(offset)
            return object: FilterOutputStream(stream) {
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                override fun close() { runCatching { fd.fileDescriptor.sync() }; super.close() }
            }
        } catch(e: Exception) { stream.close(); throw e }
    }
    override fun rename(n: Node,name: String): Node {
        val saved = DC.renameDocument(resolver,uri(n.id),name) ?: error("Folder provider cannot finalize the temporary file")
        return query(saved).single().also { notifyMediaStore(it) }
    }
    override fun remove(n: Node) { check(DC.deleteDocument(resolver,uri(n.id))) }
}
