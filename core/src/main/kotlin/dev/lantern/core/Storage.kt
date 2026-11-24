package dev.lantern.core

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

/** A small atomic, app-private store. Interrupted writes leave the last complete snapshot. */
class Book(private val file: File) {
    private val values = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
    @Synchronized operator fun get(key: String): String = values.getProperty(key, "")
    @Synchronized fun entries(prefix: String) = values.stringPropertyNames().filter { it.startsWith(prefix) }.associateWith { get(it) }
    @Synchronized fun put(vararg pairs: Pair<String,String>) {
        if (pairs.isEmpty()) return
        file.parentFile.mkdirs()
        val next = Properties().apply { putAll(this@Book.values) }
        for ((key,value) in pairs) if (value.isEmpty()) next.remove(key) else next.setProperty(key,value)
        if (values == next) return
        val temp = File(file.parentFile, file.name + ".new")
        FileOutputStream(temp).use { next.store(it, null); it.fd.sync() }
        try {
            Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING)
        }
        values.clear()
        values.putAll(next)
    }
}

data class Node(val id: String, val name: String, val size: Long, val modified: Long, val dir: Boolean, val mime: String = "")
interface Tree {
    val key: String
    fun root(): Node
    fun children(dir: Node): List<Node>
    fun open(n: Node): InputStream
    fun create(parent: Node, name: String, directory: Boolean): Node
    fun append(n: Node, offset: Long): OutputStream
    fun rename(n: Node, name: String): Node
    fun remove(n: Node)
    /** Returns an opaque token that changes whenever file content changes, or null if unavailable. */
    fun changeToken(n: Node): String? = null
}

fun digest(input: InputStream): String = input.use {
    val md = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(128 * 1024)
    while (true) { val count = it.read(buf); if (count < 0) break; md.update(buf,0,count) }
    md.digest().hex()
}
fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
fun hash(text: String) = digest(text.byteInputStream())
data class Item(val path: String, val node: Node)
private val extensions = "jpg jpeg png gif webp heic heif avif bmp tif tiff dng raw cr2 cr3 nef arw raf orf rw2 svg jxl mp4 mov m4v mkv avi webm 3gp 3g2 mts m2ts ts mpg mpeg wmv flv ogv vob".split(" ").toSet()
fun Node.media() = mime.startsWith("image/") || mime.startsWith("video/") || name.substringAfterLast('.',"").lowercase() in extensions

fun Tree.walk(): List<Item> {
    val result = mutableListOf<Item>()
    val seen = mutableSetOf<String>()
    val dirs = ArrayDeque<Pair<Node,String>>()
    dirs.add(root() to "")
    while (dirs.isNotEmpty()) {
        val (dir,prefix) = dirs.removeLast()
        if (!seen.add(dir.id)) continue
        for (node in children(dir)) {
            val path = prefix + node.name
            if (node.dir) dirs.add(node to (path + "/")) else result.add(Item(path,node))
        }
    }
    return result
}
fun clean(s: String): String {
    var safe = s.replace(Regex("""[\x00-\x1f<>:"/\\|?*]"""), "_").take(135)
    if (safe.isNotEmpty() && Character.isHighSurrogate(safe.last())) safe = safe.dropLast(1)
    safe = safe.trimEnd(' ','.')
    if (safe.isEmpty() || safe == "." || safe == ".." || safe.substringBefore('.').uppercase() in
        setOf("CON","PRN","AUX","NUL",*(1..9).flatMap { listOf("COM"+it,"LPT"+it) }.toTypedArray())) safe = "_" + safe
    return safe
}
data class Content(val size: Long, val sha: String) {
    init { require(size >= 0 && sha.matches(Regex("[0-9a-f]{64}"))) }
}

/** A local marker binds the selection to that folder/volume, even if a drive letter is reused. */
class Destination(val tree: Tree, val marker: String) {
    companion object {
        fun select(tree: Tree): Destination {
            val marker = ".lantern-volume-" + UUID.randomUUID()
            val node = tree.create(tree.root(),marker,false)
            require(node.name == marker) { "Folder provider changed the volume marker name" }
            return Destination(tree,marker)
        }
    }
    fun check() {
        val found = tree.children(tree.root()).any { it.name == marker && !it.dir }
        check(found) { "Waiting for selected SSD." }
    }
}

private val appTempPattern = Regex("""^\.lantern-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\.part$""")

class Catalog(private val dest: Destination, private val cache: Book, private val journal: Book,
              private val lock: Any = Any(), private val recentlySaved: MutableMap<Content,Node>? = null) {
    private data class HeldLock(val lock: ReentrantLock = ReentrantLock(), var refs: Int = 0)
    private class DestState {
        val activeTempNames = HashSet<String>()
        var finishEpoch = 0L
    }
    companion object {
        private val partialLocks = HashMap<String, HeldLock>()
        private val stateLock = Any()
        private val destStates = HashMap<String, DestState>()
        private fun destState(prefix: String): DestState = synchronized(stateLock) {
            destStates.getOrPut(prefix) { DestState() }
        }
        fun acquirePartial(dest: Destination, sender: String, path: String): () -> Unit {
            val key = hash(dest.tree.key + "|" + dest.marker + "|" + sender + "|" + path)
            val entry = synchronized(partialLocks) {
                partialLocks.getOrPut(key) { HeldLock() }.also { it.refs++ }
            }
            try {
                entry.lock.lockInterruptibly()
            } catch (t: Throwable) {
                synchronized(partialLocks) { if (--entry.refs == 0) partialLocks.remove(key, entry) }
                throw t
            }
            return {
                try {
                    entry.lock.unlock()
                } finally {
                    synchronized(partialLocks) { if (--entry.refs == 0) partialLocks.remove(key, entry) }
                }
            }
        }
    }
    private val tree = dest.tree
    private val files = mutableMapOf<Long,MutableList<Node>>()
    private val verified = mutableMapOf<Content,Node>()
    private val checked = mutableSetOf<String>()
    private val prefix = hash(tree.key + dest.marker)
    private val state = destState(prefix)
    private val pendingCache = mutableListOf<Pair<String,String>>()
    init {
        dest.check()
        var scanned = emptyList<Item>()
        var excludedIds = emptySet<String>()
        var excludedNames = emptySet<String>()
        for (attempt in 0..5) {
            val (epochBefore, namesBefore, idsBefore) = synchronized(stateLock) {
                Triple(
                    state.finishEpoch,
                    state.activeTempNames.toSet(),
                    journal.entries("part."+prefix+".").values.map { it.substringAfter('|') }.toSet()
                )
            }
            val walkResult = if (attempt == 5) synchronized(lock) { tree.walk() } else tree.walk()
            val (epochAfter, namesAfter, idsAfter) = synchronized(stateLock) {
                Triple(
                    state.finishEpoch,
                    state.activeTempNames.toSet(),
                    journal.entries("part."+prefix+".").values.map { it.substringAfter('|') }.toSet()
                )
            }
            scanned = walkResult
            excludedIds = idsBefore + idsAfter
            excludedNames = namesBefore + namesAfter
            if (epochBefore == epochAfter || attempt == 5) break
        }
        for ((itemPath,n) in scanned) {
            if (itemPath == dest.marker) continue
            if (appTempPattern.matches(n.name) && (n.id in excludedIds || n.name in excludedNames)) continue
            files.getOrPut(n.size) { mutableListOf() }.add(n)
        }
    }
    fun acquirePartial(sender: String, path: String): () -> Unit {
        validatePath(path)
        return acquirePartial(dest, sender, path)
    }
    fun present(c: Content): Boolean {
        // Cached fingerprints order candidates. Re-read before acknowledging a match, even
        // when a provider reuses timestamps, or a file was replaced since the last scan.
        synchronized(lock) { if (verified.containsKey(c)) return true }
        val recent = synchronized(lock) { recentlySaved?.get(c) }
        if (recent != null && runCatching { recent.size == c.size && digest(tree.open(recent)) == c.sha }.getOrDefault(false)) {
            synchronized(lock) { verified[c] = recent }
            return true
        }
        val candidates = synchronized(lock) { files[c.size]?.toList() } ?: return false
        val ordered = candidates.sortedByDescending { cached(it) == c.sha }
        for (n in ordered) {
            synchronized(lock) { if (!checked.add(n.id)) continue }
            val sha = try {
                digest(tree.open(n))
            } catch (_: IOException) {
                dest.check()
                continue
            }
            if (cached(n) != sha) {
                var flushNow: List<Pair<String,String>>? = null
                synchronized(lock) {
                    pendingCache.add(cacheKey(n) to sha)
                    if (pendingCache.size >= 64) { flushNow = pendingCache.toList(); pendingCache.clear() }
                }
                flushNow?.let { cache.put(*it.toTypedArray()) }
            }
            synchronized(lock) { verified[Content(n.size,sha)] = n }
            if (sha == c.sha) return true
        }
        return false
    }
    fun flushCache() {
        val batch = synchronized(lock) { pendingCache.toList().also { pendingCache.clear() } }
        if (batch.isNotEmpty()) cache.put(*batch.toTypedArray())
    }
    private fun cacheKey(n: Node) = prefix+"."+hash(n.id+"|"+n.size+"|"+n.modified)
    private fun cached(n: Node) = cache[cacheKey(n)]

    inner class Partial(val parent: Node, val node: Node, val desired: String, val record: String, val content: Content) {
        fun finish(): Node {
            dest.check()
            val actual = tree.children(parent).firstOrNull { it.id == node.id } ?: error("Temporary file unavailable")
            if (actual.size != content.size || digest(tree.open(actual)) != content.sha) {
                runCatching { tree.remove(actual) }
                synchronized(stateLock) {
                    journal.put(record to "")
                    state.finishEpoch++
                }
                error("Content changed or verification failed. Retrying from the beginning.")
            }
            synchronized(lock) {
                recentlySaved?.get(content)?.let { existing ->
                    val stillValid = runCatching { existing.size == content.size && digest(tree.open(existing)) == content.sha }.getOrDefault(false)
                    if (stillValid) {
                        synchronized(stateLock) { state.activeTempNames.add(actual.name) }
                        try {
                            runCatching { tree.remove(actual) }
                            synchronized(stateLock) {
                                journal.put(record to "")
                                state.activeTempNames.remove(actual.name)
                                state.finishEpoch++
                            }
                        } catch (t: Throwable) {
                            synchronized(stateLock) {
                                state.activeTempNames.remove(actual.name)
                                state.finishEpoch++
                            }
                            throw t
                        }
                        verified[content] = existing
                        return existing
                    }
                }
                val names = tree.children(parent).map { it.name.lowercase() }.toSet()
                var final = desired
                var i = 1
                val dot = desired.lastIndexOf('.').takeIf { it > 0 } ?: desired.length
                while (final.lowercase() in names) final = desired.take(dot)+" ("+(i++)+")"+desired.substring(dot)
                synchronized(stateLock) { state.activeTempNames.add(actual.name) }
                try {
                    val saved = tree.rename(actual,final)
                    val ok = runCatching { saved.size == content.size && digest(tree.open(saved)) == content.sha }.getOrDefault(false)
                    if (!ok) {
                        runCatching { tree.remove(saved) }
                        synchronized(stateLock) {
                            journal.put(record to "")
                            state.activeTempNames.remove(actual.name)
                            state.finishEpoch++
                        }
                        error("Saved copy failed verification")
                    }
                    files.getOrPut(saved.size) { mutableListOf() }.add(saved)
                    verified[content] = saved
                    recentlySaved?.put(content, saved)
                    pendingCache.add(cacheKey(saved) to content.sha)
                    var flushNow: List<Pair<String,String>>? = null
                    if (pendingCache.size >= 64) { flushNow = pendingCache.toList(); pendingCache.clear() }
                    synchronized(stateLock) {
                        journal.put(record to "")
                        state.activeTempNames.remove(actual.name)
                        state.finishEpoch++
                    }
                    flushNow?.let { cache.put(*it.toTypedArray()) }
                    return saved
                } catch (t: Throwable) {
                    synchronized(stateLock) {
                        state.activeTempNames.remove(actual.name)
                        state.finishEpoch++
                    }
                    throw t
                }
            }
        }
    }
    private fun validatePath(path: String): List<String> {
        require(path.length in 1..8192)
        val parts = path.split('/')
        require(parts.size <= 128 && parts.none { it.isBlank() || it == "." || it == ".." || '\\' in it })
        return parts
    }
    fun partial(sender: String, path: String, c: Content): Partial {
        val parts = validatePath(path)
        val parent = synchronized(lock) {
            var cur = tree.root()
            for (dir in listOf(clean(sender))+parts.dropLast(1).map(::clean)) {
                cur = tree.children(cur).firstOrNull { it.name.equals(dir, ignoreCase = true) } ?: tree.create(cur,dir,true)
                check(cur.dir) { "A file blocks the destination subfolder" }
            }
            cur
        }
        val record = "part."+prefix+"."+hash(sender+"/"+path)
        val legacyRecord = "part."+prefix+"."+hash(sender+"/"+path+"/"+c.sha)
        val (prevSha, prevId, hadLegacy) = synchronized(stateLock) {
            val current = journal[record]
            val legacy = if (current.isEmpty()) journal[legacyRecord] else ""
            when {
                current.isNotEmpty() -> Triple(current.substringBefore('|', ""), current.substringAfter('|'), false)
                legacy.isNotEmpty() -> Triple(c.sha, legacy, true)
                else -> Triple("", "", false)
            }
        }
        var node = if (prevId.isNotEmpty()) tree.children(parent).firstOrNull { it.id == prevId } else null
        if (node != null && !appTempPattern.matches(node.name)) {
            node = null
            synchronized(stateLock) { journal.put(record to "", legacyRecord to "") }
        } else if (node != null && (prevSha != c.sha || node.size < 0 || node.size > c.size)) {
            val stale = node
            synchronized(stateLock) { state.activeTempNames.add(stale.name) }
            try {
                runCatching { tree.remove(stale) }
                synchronized(stateLock) {
                    journal.put(record to "", legacyRecord to "")
                    state.activeTempNames.remove(stale.name)
                    state.finishEpoch++
                }
            } catch (t: Throwable) {
                synchronized(stateLock) {
                    state.activeTempNames.remove(stale.name)
                    state.finishEpoch++
                }
                throw t
            }
            node = null
        } else if (node != null && hadLegacy) {
            synchronized(stateLock) { journal.put(record to c.sha+"|"+node.id, legacyRecord to "") }
        }
        if (node == null) {
            val tempName = ".lantern-"+UUID.randomUUID()+".part"
            synchronized(stateLock) { state.activeTempNames.add(tempName) }
            var created: Node? = null
            try {
                created = tree.create(parent,tempName,false)
                synchronized(stateLock) {
                    journal.put(record to c.sha+"|"+created.id, legacyRecord to "")
                    state.activeTempNames.remove(tempName)
                }
                node = created
            } catch (t: Throwable) {
                synchronized(stateLock) { state.activeTempNames.remove(tempName) }
                if (created != null) runCatching { tree.remove(created) }
                throw t
            }
        }
        return Partial(parent,node,clean(parts.last()),record,c)
    }
}


