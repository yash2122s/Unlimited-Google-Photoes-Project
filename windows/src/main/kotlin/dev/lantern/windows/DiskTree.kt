package dev.lantern.windows

import dev.lantern.core.*
import java.io.*
import java.nio.channels.Channels
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

class DiskTree(override val key: String): Tree {
    private val base = Paths.get(key).toAbsolutePath().normalize()
    private fun path(id: String): Path {
        val p = Paths.get(id).toAbsolutePath().normalize()
        require(p.startsWith(base))
        // Re-check each component before opening. Never traverse a junction or symlink
        // from the selected folder into an unrelated directory.
        var cursor: Path? = p
        while(cursor != null && cursor.startsWith(base)) {
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                val a = Files.readAttributes(cursor,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
                require(!a.isSymbolicLink && !a.isOther) { "Linked folders are excluded" }
            }
            cursor = cursor.parent
        }
        return p
    }
    private fun node(p: Path): Node {
        val a = Files.readAttributes(p,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        return Node(p.toString(),p.fileName?.toString() ?: p.toString(),a.size(),a.lastModifiedTime().toMillis(),a.isDirectory)
    }
    override fun root(): Node {
        if (!Files.isDirectory(base)) error("Waiting for selected SSD.")
        return node(path(base.toString()))
    }
    override fun children(dir: Node): List<Node> = Files.newDirectoryStream(path(dir.id)).use { stream ->
        stream.mapNotNull { p ->
            val a = Files.readAttributes(p,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
            if(a.isRegularFile || a.isDirectory) node(p) else null
        }
    }
    override fun open(n: Node): InputStream = Files.newInputStream(path(n.id),LinkOption.NOFOLLOW_LINKS)
    override fun create(parent: Node,name: String,directory: Boolean): Node {
        require(name == clean(name))
        val target = path(parent.id).resolve(name)
        return node(if(directory) Files.createDirectory(target) else Files.createFile(target))
    }
    override fun append(n: Node,offset: Long): OutputStream {
        val channel = Files.newByteChannel(path(n.id),setOf(StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS))
        try {
            check(channel.size() == offset) { "Destination changed. Retry." }
            channel.position(offset)
            val stream = Channels.newOutputStream(channel)
            return object: FilterOutputStream(stream) {
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                override fun close() {
                    if (channel is java.nio.channels.FileChannel) runCatching { channel.force(true) }
                    super.close()
                }
            }
        } catch(e: Exception) { channel.close(); throw e }
    }
    override fun rename(n: Node,name: String): Node {
        require(name == clean(name))
        val p = path(n.id)
        return node(Files.move(p,p.resolveSibling(name)))
    }
    override fun remove(n: Node) { Files.delete(path(n.id)) }
}
