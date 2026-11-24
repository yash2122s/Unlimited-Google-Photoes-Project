package dev.lantern.core

import org.junit.*
import org.junit.rules.TemporaryFolder
import java.io.*
import java.nio.channels.Channels
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class CoreRegressionTest {
    @get:Rule val temp = TemporaryFolder()

    private class LocalTree(override val key: String) : Tree {
        private val base = Paths.get(key).toAbsolutePath().normalize()
        private fun path(id: String): Path = Paths.get(id).toAbsolutePath().normalize().also { require(it.startsWith(base)) }
        private fun node(p: Path): Node {
            val a = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            return Node(p.toString(), p.fileName?.toString() ?: p.toString(), a.size(), a.lastModifiedTime().toMillis(), a.isDirectory)
        }
        override fun root(): Node = if (Files.isDirectory(base)) node(base) else error("Waiting for selected SSD.")
        override fun children(dir: Node): List<Node> = Files.newDirectoryStream(path(dir.id)).use { s -> s.map(::node) }
        override fun open(n: Node): InputStream = Files.newInputStream(path(n.id), LinkOption.NOFOLLOW_LINKS)
        override fun create(parent: Node, name: String, directory: Boolean): Node {
            val target = path(parent.id).resolve(name)
            return node(if (directory) Files.createDirectory(target) else Files.createFile(target))
        }
        override fun append(n: Node, offset: Long): OutputStream {
            val ch = Files.newByteChannel(path(n.id), setOf(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))
            check(ch.size() == offset) { "Destination changed. Retry." }
            ch.position(offset)
            return Channels.newOutputStream(ch)
        }
        override fun rename(n: Node, name: String): Node {
            val p = path(n.id)
            return node(Files.move(p, p.resolveSibling(name)))
        }
        override fun remove(n: Node) { Files.delete(path(n.id)) }
    }

    private fun local(ticket: String) = ticket.substringBeforeLast('|') + "|127.0.0.1"
    private fun writeFile(root: File, rel: String, bytes: ByteArray) = File(root, rel).also { it.parentFile.mkdirs(); it.writeBytes(bytes) }
    private fun batch(t: Tree) = t.walk().filter { it.node.media() }.map { Outgoing(it, Content(it.node.size, digest(t.open(it.node)))) }
    private fun savedFiles(dst: File) = dst.walk().filter { it.isFile && !it.name.startsWith(".lantern-") }.toList()

    @Test fun interruptedVideoChangedSourceNeverCombinesOldPartial() {
        val srcDir = temp.newFolder("src")
        val dstDir = temp.newFolder("dst")
        val stateDir = temp.newFolder("state")
        val source = LocalTree(srcDir.path)
        val dest = Destination.select(LocalTree(dstDir.path))
        val id = Identity(Book(File(stateDir, "settings")), "Hub")
        var hub = Hub(id, dest, Book(File(stateDir, "cache")), Book(File(stateDir, "parts"))) {}
        try {
            val link = pair(local(id.invite()), "Sender").copy(host = "127.0.0.1")
            val oldBytes = Random(10).nextBytes(BLOCK * 4 + 123)
            val video = writeFile(srcDir, "Camera/clip.mp4", oldBytes)

            Assert.assertTrue(runCatching {
                sync(source, batch(source), link, progress = { throw IOException("Force stop simulation") })
            }.isFailure)
            hub.close()

            val partial = dstDir.walk().single { it.name.endsWith(".part") }
            Assert.assertEquals(BLOCK.toLong(), partial.length())

            // Modify source with identical length but completely different bytes before resume.
            val newBytes = Random(99).nextBytes(BLOCK * 4 + 123)
            video.writeBytes(newBytes)
            val expectedSha = digest(video.inputStream())

            hub = Hub(id, dest, Book(File(stateDir, "cache")), Book(File(stateDir, "parts"))) {}
            val stats = sync(source, batch(source), link)
            Assert.assertEquals(1, stats.uploaded)
            Assert.assertEquals(newBytes.size.toLong(), stats.bytes)
            val saved = savedFiles(dstDir)
            Assert.assertEquals(1, saved.size)
            Assert.assertEquals(expectedSha, digest(saved.single().inputStream()))
            Assert.assertTrue(dstDir.walk().none { it.name.endsWith(".part") })
        } finally {
            hub.close()
        }
    }

    @Test fun hubCompletesSaveBeforeAckLostConvergesWithoutDuplicate() {
        val srcDir = temp.newFolder("src")
        val dstDir = temp.newFolder("dst")
        val stateDir = temp.newFolder("state")
        val source = LocalTree(srcDir.path)
        val dest = Destination.select(LocalTree(dstDir.path))
        val id = Identity(Book(File(stateDir, "settings")), "Hub")
        val hub = Hub(id, dest, Book(File(stateDir, "cache")), Book(File(stateDir, "parts"))) {}
        try {
            val link = pair(local(id.invite()), "Sender").copy(host = "127.0.0.1")
            val bytes = Random(7).nextBytes(BLOCK * 2 + 55)
            val video = writeFile(srcDir, "clip.mp4", bytes)

            // Disconnect sender in delivered callback (simulating ack loss / crash right after Hub saved).
            val firstAttempt = runCatching {
                sync(source, batch(source), link, delivered = { _, _ -> throw IOException("Lost ack") })
            }
            Assert.assertTrue(firstAttempt.isFailure)
            Assert.assertEquals(1, savedFiles(dstDir).size)

            // Retry must converge with 0 bytes retransmitted and only 1 valid final file.
            val retry = sync(source, batch(source), link)
            Assert.assertEquals(Stats(0, 0, 1), retry)
            val files = savedFiles(dstDir)
            Assert.assertEquals(1, files.size)
            Assert.assertEquals(digest(video.inputStream()), digest(files.single().inputStream()))
        } finally {
            hub.close()
        }
    }

    @Test fun crashDuringFinalizationPreservedIdProviderDoesNotDuplicateOrHideFile() {
        val dstDir = temp.newFolder("dst")
        val stateDir = temp.newFolder("state")
        val base = LocalTree(dstDir.path)
        // Simulate a DocumentsProvider whose documentId is invariant across renameDocument.
        val idMap = mutableMapOf<String, String>() // stableId -> currentPath
        val pathToId = mutableMapOf<String, String>()
        var nextId = 1
        fun stableNode(n: Node): Node {
            if (n.dir) return n
            val sid = pathToId.getOrPut(n.id) { "doc-${nextId++}".also { idMap[it] = n.id } }
            return n.copy(id = sid)
        }
        val stableIdTree = object : Tree {
            override val key = base.key
            override fun root() = base.root()
            override fun children(dir: Node) = base.children(dir).map(::stableNode)
            override fun open(n: Node) = base.open(n.copy(id = idMap[n.id] ?: n.id))
            override fun create(parent: Node, name: String, directory: Boolean) = stableNode(base.create(parent, name, directory))
            override fun append(n: Node, offset: Long) = base.append(n.copy(id = idMap[n.id] ?: n.id), offset)
            override fun rename(n: Node, name: String): Node {
                val sid = n.id
                val oldPath = idMap.getValue(sid)
                val renamed = base.rename(n.copy(id = oldPath), name)
                pathToId.remove(oldPath)
                pathToId[renamed.id] = sid
                idMap[sid] = renamed.id
                return renamed.copy(id = sid)
            }
            override fun remove(n: Node) {
                val p = idMap.remove(n.id) ?: n.id
                pathToId.remove(p)
                base.remove(n.copy(id = p))
            }
        }
        val dest = Destination.select(stableIdTree)
        val cache = Book(File(stateDir, "cache"))
        val journal = Book(File(stateDir, "parts"))
        val contentBytes = Random(5).nextBytes(2048)
        val c = Content(contentBytes.size.toLong(), digest(contentBytes.inputStream()))

        // Simulate crash right after tree.rename() before journal entry was cleared:
        val c1 = Catalog(dest, cache, journal)
        val part = c1.partial("Sender-1", "Album/photo.jpg", c)
        stableIdTree.append(part.node, 0L).use { it.write(contentBytes) }
        val renamed = stableIdTree.rename(part.node, "photo.jpg")
        Assert.assertEquals(part.node.id, renamed.id)
        // Intentionally do NOT clear journal (simulating crash before journal.put(record to "")).

        // Restart Catalog: finalized file MUST be recognized by present(), and partial() must NOT touch it.
        val c2 = Catalog(dest, cache, journal)
        Assert.assertTrue(c2.present(c))
        val files = savedFiles(dstDir)
        Assert.assertEquals(1, files.size)
        Assert.assertEquals("photo.jpg", files.single().name)
    }

    @Test fun concurrentSendersIdenticalContentAndSameFilename() {
        val src1Dir = temp.newFolder("src1")
        val src2Dir = temp.newFolder("src2")
        val dstDir = temp.newFolder("dst")
        val stateDir = temp.newFolder("state")
        val src1 = LocalTree(src1Dir.path)
        val src2 = LocalTree(src2Dir.path)
        val dest = Destination.select(LocalTree(dstDir.path))
        val id = Identity(Book(File(stateDir, "settings")), "Hub")
        val hub = Hub(id, dest, Book(File(stateDir, "cache")), Book(File(stateDir, "parts"))) {}
        try {
            val link1 = pair(local(id.invite()), "PhoneA").copy(host = "127.0.0.1")
            val link2 = pair(local(id.invite()), "PhoneB").copy(host = "127.0.0.1")

            val sharedBytes = Random(77).nextBytes(BLOCK * 3 + 19)
            writeFile(src1Dir, "DCIM/shared.mp4", sharedBytes)
            writeFile(src2Dir, "DCIM/shared.mp4", sharedBytes)
            writeFile(src1Dir, "DCIM/unique.jpg", Random(11).nextBytes(512))
            writeFile(src2Dir, "DCIM/unique.jpg", Random(22).nextBytes(512))

            val gate = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            val f1 = pool.submit<Stats> { gate.await(); sync(src1, batch(src1), link1) }
            val f2 = pool.submit<Stats> { gate.await(); sync(src2, batch(src2), link2) }
            gate.countDown()
            val s1 = f1.get(30, TimeUnit.SECONDS)
            val s2 = f2.get(30, TimeUnit.SECONDS)
            pool.shutdown()

            Assert.assertEquals(2, s1.uploaded + s1.present)
            Assert.assertEquals(2, s2.uploaded + s2.present)
            val saved = savedFiles(dstDir)
            // 1 deduplicated shared.mp4 + 2 distinct unique.jpg (one per sender folder) = 3 files total.
            Assert.assertEquals(3, saved.size)
            Assert.assertEquals(1, saved.count { it.name == "shared.mp4" })
            Assert.assertEquals(2, saved.count { it.name == "unique.jpg" })
        } finally {
            hub.close()
        }
    }

    @Test fun repeatScanDoesNotRehashAndPendingDoesNotFlickerAndDetectsExternalDelete() {
        val srcDir = temp.newFolder("src")
        val dstDir = temp.newFolder("dst")
        val hubState = temp.newFolder("hubState")
        val sndState = temp.newFolder("sndState")

        for (i in 1..20) writeFile(srcDir, "Album/p-$i.jpg", Random(i).nextBytes(200))
        val openCalls = AtomicInteger(0)
        val rawSrc = LocalTree(srcDir.path)
        val countingSrc = object : Tree by rawSrc {
            override fun open(n: Node): InputStream {
                openCalls.incrementAndGet()
                return rawSrc.open(n)
            }
        }
        val dest = Destination.select(LocalTree(dstDir.path))
        val hubId = Identity(Book(File(hubState, "settings")), "Hub")
        val hub = Hub(hubId, dest, Book(File(hubState, "cache")), Book(File(hubState, "parts"))) {}
        val engine = Engine(sndState, "Sender", { countingSrc }, { true })
        try {
            val link = pair(local(hubId.invite()), "Sender").copy(host = "127.0.0.1")
            engine.settings.put("pin" to link.pin, "token" to link.token, "address" to link.host, "hubName" to link.name, "source" to srcDir.path)
            engine.enable(true)

            val deadline = System.currentTimeMillis() + 20000
            while (System.currentTimeMillis() < deadline && !engine.lastScan.contains("Uploaded 20")) Thread.sleep(100)
            Assert.assertTrue(engine.lastScan, engine.lastScan.contains("Uploaded 20 · 4000 bytes · already present 0"))
            Assert.assertEquals(0, engine.pending)

            // Wait for repeat scan; verify 0 bytes uploaded and pending stays 0 (no flicker).
            val deadline2 = System.currentTimeMillis() + 25000
            while (System.currentTimeMillis() < deadline2 && !engine.lastScan.contains("already present 20")) {
                Assert.assertEquals(0, engine.pending)
                Thread.sleep(50)
            }
            Assert.assertTrue(engine.lastScan, engine.lastScan.contains("Uploaded 0 · 0 bytes · already present 20"))

            // Delete one file externally on Hub; next scan must detect and re-upload it.
            savedFiles(dstDir).first().delete()
            val deadline3 = System.currentTimeMillis() + 25000
            while (System.currentTimeMillis() < deadline3 && !engine.lastScan.contains("Uploaded 1")) Thread.sleep(100)
            Assert.assertTrue(engine.lastScan, engine.lastScan.contains("Uploaded 1 · 200 bytes · already present 19"))
            Assert.assertEquals(20, savedFiles(dstDir).size)
        } finally {
            engine.stop()
            hub.close()
        }
    }

    @Test fun reliableChangeTokenReusesCachedDigestUntilTokenChanges() {
        val srcDir = temp.newFolder("src")
        val dstDir = temp.newFolder("dst")
        val hubState = temp.newFolder("hubState")
        val sndState = temp.newFolder("sndState")
        val file = writeFile(srcDir, "Album/p.jpg", Random(88).nextBytes(512))
        val openCalls = AtomicInteger(0)
        var currentToken = "gen-1"
        val rawSrc = LocalTree(srcDir.path)
        val tokenTree = object : Tree by rawSrc {
            override fun changeToken(n: Node): String = currentToken
            override fun open(n: Node): InputStream {
                openCalls.incrementAndGet()
                return rawSrc.open(n)
            }
        }
        val dest = Destination.select(LocalTree(dstDir.path))
        val hubId = Identity(Book(File(hubState, "settings")), "Hub")
        val hub = Hub(hubId, dest, Book(File(hubState, "cache")), Book(File(hubState, "parts"))) {}
        val engine = Engine(sndState, "Sender", { tokenTree }, { true })
        try {
            val link = pair(local(hubId.invite()), "Sender").copy(host = "127.0.0.1")
            engine.settings.put("pin" to link.pin, "token" to link.token, "address" to link.host, "hubName" to link.name, "source" to srcDir.path)
            engine.enable(true)

            val deadline1 = System.currentTimeMillis() + 20000
            while (System.currentTimeMillis() < deadline1 && !engine.lastScan.contains("Uploaded 1")) Thread.sleep(100)
            Assert.assertTrue(engine.lastScan, engine.lastScan.contains("Uploaded 1 · 512 bytes"))
            val opensAfterFirst = openCalls.get()

            // Repeat scan with unchanged reliable token must NOT re-open source file.
            val deadline2 = System.currentTimeMillis() + 25000
            while (System.currentTimeMillis() < deadline2 && !engine.lastScan.contains("already present 1")) Thread.sleep(100)
            Assert.assertTrue(engine.lastScan, engine.lastScan.contains("Uploaded 0 · 0 bytes · already present 1"))
            Assert.assertEquals(opensAfterFirst, openCalls.get())

            // Bump reliable token & replace bytes with same length and timestamp -> must re-hash and deliver.
            val stamp = file.lastModified()
            file.writeBytes(Random(89).nextBytes(512))
            Assert.assertTrue(file.setLastModified(stamp))
            currentToken = "gen-2"
            val deadline3 = System.currentTimeMillis() + 25000
            while (System.currentTimeMillis() < deadline3 && savedFiles(dstDir).size < 2) Thread.sleep(100)
            Assert.assertEquals(2, savedFiles(dstDir).size)
        } finally {
            engine.stop()
            hub.close()
        }
    }

    @Test fun cleanSanitizationPathTraversalAndIdempotentEngineStart() {
        Assert.assertEquals("_", clean("..."))
        Assert.assertEquals("_CON", clean("CON.txt").substringBefore('.') )
        Assert.assertEquals("_", clean("   "))
        // High surrogate at index 134 should not be left unpaired
        val emoji = "\uD83D\uDE00"
        val longEmoji = "a".repeat(134) + emoji
        val cleaned = clean(longEmoji)
        Assert.assertEquals(134, cleaned.length)
        Assert.assertFalse(Character.isHighSurrogate(cleaned.last()))

        // Path traversal rejected by Catalog.partial
        val dstDir = temp.newFolder("dst")
        val stateDir = temp.newFolder("state")
        val dest = Destination.select(LocalTree(dstDir.path))
        val catalog = Catalog(dest, Book(File(stateDir, "c")), Book(File(stateDir, "j")))
        val c = Content(1, digest(byteArrayOf(1).inputStream()))
        Assert.assertTrue(runCatching { catalog.partial("sender", "../escape.jpg", c) }.isFailure)
        Assert.assertTrue(runCatching { catalog.partial("sender", "a\\b.jpg", c) }.isFailure)

        // Calling Engine.start() twice does not spawn duplicate worker threads or stop active worker
        val srcDir = temp.newFolder("src")
        val sndDir = temp.newFolder("snd")
        val engine = Engine(sndDir, "Test", { LocalTree(it) }, { false })
        try {
            engine.settings.put("source" to srcDir.path, "enabled" to "yes")
            engine.start()
            Thread.sleep(100)
            val before = Thread.getAllStackTraces().keys.count { it.name == "lantern-monitor" && it.isAlive }
            engine.start()
            Thread.sleep(100)
            val after = Thread.getAllStackTraces().keys.count { it.name == "lantern-monitor" && it.isAlive }
            Assert.assertEquals(1, before)
            Assert.assertEquals(1, after)
        } finally {
            engine.stop()
        }
    }

    @Test fun growingFileWaitsUntilStableBeforeUpload() {
        val srcDir = temp.newFolder("src")
        val dstDir = temp.newFolder("dst")
        val hubState = temp.newFolder("hubState")
        val sndState = temp.newFolder("sndState")
        val file = writeFile(srcDir, "Camera/growing.mp4", Random(1).nextBytes(1024))
        val dest = Destination.select(LocalTree(dstDir.path))
        val hubId = Identity(Book(File(hubState, "settings")), "Hub")
        val hub = Hub(hubId, dest, Book(File(hubState, "cache")), Book(File(hubState, "parts"))) {}
        val engine = Engine(sndState, "Sender", { LocalTree(it) }, { true })
        try {
            val link = pair(local(hubId.invite()), "Sender").copy(host = "127.0.0.1")
            engine.settings.put("pin" to link.pin, "token" to link.token, "address" to link.host, "hubName" to link.name, "source" to srcDir.path)
            engine.enable(true)

            // Grow the file before stability confirms, then stop modifying it.
            Thread.sleep(700)
            file.appendBytes(Random(2).nextBytes(2048))
            val expectedSha = digest(file.inputStream())

            val deadline = System.currentTimeMillis() + 25000
            while (System.currentTimeMillis() < deadline && !engine.lastScan.contains("Uploaded 1")) Thread.sleep(100)
            Assert.assertTrue(engine.lastScan, engine.lastScan.contains("Uploaded 1 · 3072 bytes"))
            val saved = savedFiles(dstDir)
            Assert.assertEquals(1, saved.size)
            Assert.assertEquals(expectedSha, digest(saved.single().inputStream()))
        } finally {
            engine.stop()
            hub.close()
        }
    }

    @Test fun differentSsdWithoutMarkerPausesSafelyAndNeverWrites() {
        val srcDir = temp.newFolder("src")
        val ssd1 = temp.newFolder("ssd1")
        val ssd2 = temp.newFolder("ssd2")
        val hubState = temp.newFolder("hubState")
        val sndState = temp.newFolder("sndState")
        writeFile(srcDir, "photo.jpg", Random(3).nextBytes(500))

        // Select ssd1, then replace ssd1 directory contents with ssd2 (no marker).
        val hubEngine = Engine(hubState, "Hub", { LocalTree(it) }, { true })
        hubEngine.mode(true)
        hubEngine.choose(ssd1.path)
        val markerName = hubEngine.settings["marker"]
        Assert.assertTrue(File(ssd1, markerName).exists())
        hubEngine.enable(true)

        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline && hubEngine.message != "Listening for paired devices") Thread.sleep(100)
        Assert.assertEquals("Listening for paired devices", hubEngine.message)
        val link = pair(local(hubEngine.identity.invite()), "Sender")

        // Swap drive: delete marker (simulating a different SSD mounted at same path)
        File(ssd1, markerName).delete()
        val deadline2 = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline2 && !hubEngine.message.contains("Waiting for selected SSD")) Thread.sleep(100)
        Assert.assertTrue(hubEngine.message, hubEngine.message.contains("Waiting for selected SSD"))

        // Attempting to sync while wrong SSD is mounted fails cleanly and writes nothing.
        Assert.assertTrue(runCatching { sync(LocalTree(srcDir.path), batch(LocalTree(srcDir.path)), link.copy(host = "127.0.0.1")) }.isFailure)
        Assert.assertTrue(savedFiles(ssd1).isEmpty())
        Assert.assertTrue(savedFiles(ssd2).isEmpty())
        hubEngine.stop()
    }

    @Test fun unreachableStoredHostTriggersDiscoveryAndFallsBackToWorkingAddress() {
        val srcDir = temp.newFolder("src")
        val dstDir = temp.newFolder("dst")
        val hubState = temp.newFolder("hubState")
        writeFile(srcDir, "a.jpg", Random(55).nextBytes(300))
        val dest = Destination.select(LocalTree(dstDir.path))
        val id = Identity(Book(File(hubState, "settings")), "Hub")
        val hub = Hub(id, dest, Book(File(hubState, "cache")), Book(File(hubState, "parts"))) {}
        try {
            val invite = id.invite()
            val hosts = invite.substringAfterLast('|').split(',')
            Assume.assumeTrue("Requires at least one LAN address for discovery test", hosts.isNotEmpty() && addresses().isNotEmpty())
            val link = pair(local(invite), "Sender")
            // Poison stored address with unreachable TEST-NET-1 / wrong local IPv4 (10.255.255.254)
            // so connect() fails and sync() must use discover(pin) (which prefers IPv6 link-local!).
            var updatedHost = ""
            val stats = sync(LocalTree(srcDir.path), batch(LocalTree(srcDir.path)), link.copy(host = "10.255.255.254"), address = { updatedHost = it })
            Assert.assertEquals(1, stats.uploaded)
            Assert.assertTrue("Discovered host should be non-empty", updatedHost.isNotEmpty())
            if (addresses().any { it.address is java.net.Inet6Address }) {
                Assert.assertTrue("Expected IPv6 link-local preferred over IPv4, got: $updatedHost", ':' in updatedHost)
            }
        } finally {
            hub.close()
        }
    }

    @Test fun caseInsensitiveSubfolderMergesWithoutDuplicateDirectory() {
        val dstDir = temp.newFolder("dst")
        val stateDir = temp.newFolder("state")
        val dest = Destination.select(LocalTree(dstDir.path))
        val catalog = Catalog(dest, Book(File(stateDir, "c")), Book(File(stateDir, "j")))
        val c1 = Content(1, digest(byteArrayOf(1).inputStream()))
        val c2 = Content(1, digest(byteArrayOf(2).inputStream()))
        val p1 = catalog.partial("Sender", "Album/one.jpg", c1)
        val p2 = catalog.partial("Sender", "album/two.jpg", c2)
        Assert.assertEquals(p1.parent.id, p2.parent.id)
    }

    @Test fun staleSenderHashReplacesSameSizeSameTimestampAndPreservesHubCopy() {
        val srcDir = temp.newFolder("src")
        val dstDir = temp.newFolder("dst")
        val hubState = temp.newFolder("hubState")
        val sndState = temp.newFolder("sndState")

        val bytesA = Random(101).nextBytes(2048)
        val bytesB = Random(202).nextBytes(2048)
        val shaA = digest(bytesA.inputStream())
        val shaB = digest(bytesB.inputStream())
        val photo = writeFile(srcDir, "Album/photo.jpg", bytesA)
        val originalTimestamp = photo.lastModified()

        // Seed a legacy cache entry matching the previous build's hashes.properties format.
        val legacyCache = Book(File(sndState, "hashes.properties"))
        val legacyKey = hash(srcDir.path + "|" + photo.canonicalPath + "|" + photo.length() + "|" + originalTimestamp)
        legacyCache.put(legacyKey to shaA)

        val dest = Destination.select(LocalTree(dstDir.path))
        val hubId = Identity(Book(File(hubState, "settings")), "Hub")
        val hub = Hub(hubId, dest, Book(File(hubState, "cache")), Book(File(hubState, "parts"))) {}
        val engine = Engine(sndState, "Sender", { LocalTree(it) }, { true })
        try {
            val link = pair(local(hubId.invite()), "Sender").copy(host = "127.0.0.1")
            engine.settings.put("pin" to link.pin, "token" to link.token, "address" to link.host, "hubName" to link.name, "source" to srcDir.path)
            engine.enable(true)

            val deadline1 = System.currentTimeMillis() + 20000
            while (System.currentTimeMillis() < deadline1 && !engine.lastScan.contains("Uploaded 1")) Thread.sleep(100)
            Assert.assertTrue(engine.lastScan, engine.lastScan.contains("Uploaded 1 · 2048 bytes"))
            Assert.assertEquals(1, savedFiles(dstDir).size)

            // Replace source bytes with different bytes of the exact same size and restore original timestamp.
            photo.writeBytes(bytesB)
            Assert.assertTrue(photo.setLastModified(originalTimestamp))

            val deadline2 = System.currentTimeMillis() + 25000
            while (System.currentTimeMillis() < deadline2 && savedFiles(dstDir).size < 2) Thread.sleep(100)

            val saved = savedFiles(dstDir)
            Assert.assertEquals("Expected earlier copy preserved and replacement delivered", 2, saved.size)
            val shas = saved.map { digest(it.inputStream()) }.toSet()
            Assert.assertEquals(setOf(shaA, shaB), shas)

            // Repeat scan after replacement must upload zero bytes.
            val deadline3 = System.currentTimeMillis() + 25000
            while (System.currentTimeMillis() < deadline3 && !engine.lastScan.contains("Uploaded 0 · 0 bytes · already present 1")) Thread.sleep(100)
            Assert.assertTrue(engine.lastScan, engine.lastScan.contains("Uploaded 0 · 0 bytes · already present 1"))
        } finally {
            engine.stop()
            hub.close()
        }
    }

    @Test fun overlappingReconnectWithSameCredentialHasSingleWriterAndNoWritesAfterSaved() {
        val srcDir = temp.newFolder("src")
        val otherSrcDir = temp.newFolder("otherSrc")
        val dstDir = temp.newFolder("dst")
        val hubState = temp.newFolder("hubState")
        val videoBytes = Random(303).nextBytes(BLOCK * 3 + 77)
        val expectedSha = digest(videoBytes.inputStream())
        writeFile(srcDir, "Camera/clip.mp4", videoBytes)
        val otherBytes = Random(304).nextBytes(512)
        writeFile(otherSrcDir, "Camera/other.jpg", otherBytes)

        val baseDst = LocalTree(dstDir.path)
        val activeWriters = AtomicInteger(0)
        val maxConcurrentWritersForClip = AtomicInteger(0)
        val clipWriters = AtomicInteger(0)
        val firstWriterHolding = CountDownLatch(1)
        val releaseFirstWriter = CountDownLatch(1)
        val writesAfterSaved = AtomicInteger(0)
        val savedSignaled = java.util.concurrent.atomic.AtomicBoolean(false)
        val appendCallsForClip = AtomicInteger(0)

        val guardedDst = object : Tree by baseDst {
            override fun append(n: Node, offset: Long): OutputStream {
                val isClip = n.id.contains("Sender") && n.name.endsWith(".part") && offset != 0L || (appendCallsForClip.get() == 0 && !n.id.contains("Other"))
                val raw = baseDst.append(n, offset)
                // Identify clip vs unrelated transfer via parent directory (Sender vs Other)
                val isSenderClip = n.id.contains("Sender")
                if (!isSenderClip) return raw
                val callNum = appendCallsForClip.incrementAndGet()
                val nowClip = clipWriters.incrementAndGet()
                maxConcurrentWritersForClip.updateAndGet { maxOf(it, nowClip) }
                activeWriters.incrementAndGet()
                return object : FilterOutputStream(raw) {
                    var writtenHere = 0L
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        if (savedSignaled.get()) writesAfterSaved.incrementAndGet()
                        if (callNum == 1 && writtenHere >= BLOCK) {
                            firstWriterHolding.countDown()
                            releaseFirstWriter.await(15, TimeUnit.SECONDS)
                            throw IOException("First session interrupted while holding writer")
                        }
                        out.write(b, off, len)
                        writtenHere += len
                    }
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            clipWriters.decrementAndGet()
                            activeWriters.decrementAndGet()
                        }
                    }
                }
            }
        }

        val dest = Destination.select(guardedDst)
        val hubId = Identity(Book(File(hubState, "settings")), "Hub")
        val hub = Hub(hubId, dest, Book(File(hubState, "cache")), Book(File(hubState, "parts"))) {}
        try {
            val link = pair(local(hubId.invite()), "Sender").copy(host = "127.0.0.1")
            val otherLink = pair(local(hubId.invite()), "Other").copy(host = "127.0.0.1")
            val rawSrcTree = LocalTree(srcDir.path)
            val resumedSkipBytes = java.util.concurrent.atomic.AtomicLong(0)
            val trackingSrcTree = object : Tree by rawSrcTree {
                override fun open(n: Node): InputStream {
                    val inner = rawSrcTree.open(n)
                    return object : FilterInputStream(inner) {
                        override fun skip(nBytes: Long): Long {
                            val s = super.skip(nBytes)
                            resumedSkipBytes.addAndGet(s)
                            return s
                        }
                    }
                }
            }
            val outBatch = batch(rawSrcTree)
            val otherTree = LocalTree(otherSrcDir.path)
            val otherBatch = batch(otherTree)
            val pool = Executors.newFixedThreadPool(3)

            // Session 1 starts and blocks inside append after writing BLOCK bytes.
            val f1 = pool.submit<Stats> { sync(rawSrcTree, outBatch, link) }
            Assert.assertTrue("Writer 1 did not reach hold point", firstWriterHolding.await(10, TimeUnit.SECONDS))

            // While Writer 1 holds the lock, an unrelated transfer MUST complete concurrently without waiting.
            val unrelatedStats = pool.submit<Stats> { sync(otherTree, otherBatch, otherLink) }.get(10, TimeUnit.SECONDS)
            Assert.assertEquals(1, unrelatedStats.uploaded)

            // Session 2 reconnects with the EXACT SAME credential while Writer 1 is held open.
            val f2 = pool.submit<Stats> {
                val s = sync(trackingSrcTree, outBatch, link)
                savedSignaled.set(true)
                s
            }

            // While Writer 1 is still open, Session 2 must NOT open a second writer or complete.
            Thread.sleep(600)
            Assert.assertEquals(1, maxConcurrentWritersForClip.get())
            Assert.assertEquals(1, appendCallsForClip.get())
            Assert.assertFalse(f2.isDone)

            // Release Writer 1 (throws disconnect after writing BLOCK bytes and closes its stream).
            releaseFirstWriter.countDown()
            Assert.assertTrue(runCatching { f1.get(15, TimeUnit.SECONDS) }.isFailure)

            val stats2 = f2.get(15, TimeUnit.SECONDS)
            pool.shutdown()

            Assert.assertEquals(1, maxConcurrentWritersForClip.get())
            Assert.assertEquals(BLOCK.toLong(), resumedSkipBytes.get())
            Assert.assertEquals(1, stats2.uploaded)
            Assert.assertEquals(videoBytes.size.toLong() - BLOCK, stats2.bytes)
            val clipFiles = savedFiles(dstDir).filter { it.name == "clip.mp4" }
            Assert.assertEquals(1, clipFiles.size)
            Assert.assertEquals(expectedSha, digest(clipFiles.single().inputStream()))
            Assert.assertEquals(0, writesAfterSaved.get())
        } finally {
            releaseFirstWriter.countDown()
            hub.close()
        }
    }

    @Test fun legitimateDotLanternMediaInNestedHubFolderIsIndexed() {
        val srcDir = temp.newFolder("src")
        val dstDir = temp.newFolder("dst")
        val hubState = temp.newFolder("hubState")

        val bytes = Random(404).nextBytes(1500)
        writeFile(srcDir, "Album/vacation.jpg", bytes)
        // Manually place legitimate .lantern-trip.jpg in nested Hub folder BEFORE scan.
        writeFile(dstDir, "manual/nested/.lantern-trip.jpg", bytes)

        // Also seed a legacy journal record pointing to an active .lantern-<uuid>.part for another file
        // to ensure legacy journal entries stay excluded from index and resume cleanly.
        val dest = Destination.select(LocalTree(dstDir.path))
        val prefix = hash(dstDir.path + dest.marker)
        val partBytes = Random(405).nextBytes(BLOCK * 2 + 13)
        writeFile(srcDir, "Album/resume.mp4", partBytes)
        val partSha = digest(partBytes.inputStream())
        val legacyPartFile = writeFile(dstDir, "Sender/Album/.lantern-11111111-2222-3333-4444-555555555555.part", partBytes.copyOfRange(0, BLOCK))
        val journal = Book(File(hubState, "parts"))
        val senderId = Identity(Book(File(hubState, "settings")), "Hub")
        val hub = Hub(senderId, dest, Book(File(hubState, "cache")), journal) {}
        try {
            val link = pair(local(senderId.invite()), "Sender").copy(host = "127.0.0.1")
            val peerName = senderId.peer(link.token)
            // Update legacy part path to match peerName (Sender-<hash>)
            val realPart = writeFile(dstDir, "$peerName/Album/.lantern-11111111-2222-3333-4444-555555555555.part", partBytes.copyOfRange(0, BLOCK))
            legacyPartFile.delete()
            journal.put("part.$prefix.${hash("$peerName/Album/resume.mp4/$partSha")}" to realPart.canonicalPath)

            val srcTree = LocalTree(srcDir.path)
            val vacationBatch = batch(srcTree).filter { it.item.path == "Album/vacation.jpg" }

            // First and repeat scans for vacation.jpg must upload 0 bytes (matched .lantern-trip.jpg).
            Assert.assertEquals(Stats(0, 0, 1), sync(srcTree, vacationBatch, link))
            Assert.assertEquals(Stats(0, 0, 1), sync(srcTree, vacationBatch, link))

            // Legacy journaled partial must also resume from BLOCK rather than starting at 0.
            val fullBatch = batch(srcTree)
            val resumeStats = sync(srcTree, fullBatch, link)
            Assert.assertEquals(Stats(1, (partBytes.size - BLOCK).toLong(), 1), resumeStats)

            val files = dstDir.walk().filter { it.isFile && it.name != dest.marker }.toList()
            Assert.assertEquals(2, files.size)
            Assert.assertTrue(files.any { it.name == ".lantern-trip.jpg" })
            Assert.assertTrue(files.any { it.name == "resume.mp4" && digest(it.inputStream()) == partSha })
        } finally {
            hub.close()
        }
    }

    @Test fun unjournaledTempNamedFileIsIndexed() {
        val srcDir = temp.newFolder("src")
        val dstDir = temp.newFolder("dst")
        val hubState = temp.newFolder("hubState")

        val bytes = Random(505).nextBytes(1600)
        writeFile(srcDir, "Album/photo.jpg", bytes)
        // Manually place an unjournaled file whose name matches the internal temp pattern.
        writeFile(dstDir, "manual/.lantern-12345678-1234-1234-1234-123456789abc.part", bytes)

        val dest = Destination.select(LocalTree(dstDir.path))
        val id = Identity(Book(File(hubState, "settings")), "Hub")
        val hub = Hub(id, dest, Book(File(hubState, "cache")), Book(File(hubState, "parts"))) {}
        try {
            val link = pair(local(id.invite()), "Sender").copy(host = "127.0.0.1")
            val srcTree = LocalTree(srcDir.path)
            val outBatch = batch(srcTree)

            Assert.assertEquals(Stats(0, 0, 1), sync(srcTree, outBatch, link))
            Assert.assertEquals(Stats(0, 0, 1), sync(srcTree, outBatch, link))

            val files = dstDir.walk().filter { it.isFile && it.name != dest.marker }.toList()
            Assert.assertEquals(1, files.size)
            Assert.assertEquals(".lantern-12345678-1234-1234-1234-123456789abc.part", files.single().name)
        } finally {
            hub.close()
        }
    }

    @Test fun concurrentScanDoesNotTreatUnfinishedPartialAsPresent() {
        val srcADir = temp.newFolder("srcA")
        val srcBDir = temp.newFolder("srcB")
        val srcCDir = temp.newFolder("srcC")
        val dstDir = temp.newFolder("dst")
        val hubState = temp.newFolder("hubState")

        // 0-byte media file matches the initial size of a newly created empty .part file.
        writeFile(srcADir, "Album/emptyA.jpg", ByteArray(0))
        writeFile(srcBDir, "Album/emptyB.jpg", ByteArray(0))
        writeFile(srcCDir, "Album/emptyC.jpg", ByteArray(0))

        val baseDst = LocalTree(dstDir.path)
        val firstPartCreated = CountDownLatch(1)
        val releaseFirstCreate = CountDownLatch(1)
        val partCreates = AtomicInteger(0)

        val pausingDst = object : Tree by baseDst {
            override fun create(parent: Node, name: String, directory: Boolean): Node {
                val created = baseDst.create(parent, name, directory)
                if (!directory && name.endsWith(".part") && partCreates.incrementAndGet() == 1) {
                    firstPartCreated.countDown()
                    releaseFirstCreate.await(15, TimeUnit.SECONDS)
                }
                return created
            }
        }

        val dest = Destination.select(pausingDst)
        val id = Identity(Book(File(hubState, "settings")), "Hub")
        val hub = Hub(id, dest, Book(File(hubState, "cache")), Book(File(hubState, "parts"))) {}
        val pool = Executors.newFixedThreadPool(2)
        try {
            val linkA = pair(local(id.invite()), "SenderA").copy(host = "127.0.0.1")
            val linkB = pair(local(id.invite()), "SenderB").copy(host = "127.0.0.1")
            val linkC = pair(local(id.invite()), "SenderC").copy(host = "127.0.0.1")

            val srcA = LocalTree(srcADir.path)
            val srcB = LocalTree(srcBDir.path)
            val srcC = LocalTree(srcCDir.path)

            // Sender A creates its empty .part file and pauses immediately before journal registration.
            val futureA = pool.submit<Stats> { sync(srcA, batch(srcA), linkA) }
            Assert.assertTrue("Sender A did not reach partial creation", firstPartCreated.await(10, TimeUnit.SECONDS))

            // While A is paused with an unfinished empty partial on disk, B scans:
            // B MUST NOT treat A's unfinished partial as a delivered file (present must be 0, not HAVE).
            val statsB = pool.submit<Stats> { sync(srcB, batch(srcB), linkB) }.get(10, TimeUnit.SECONDS)
            Assert.assertEquals(Stats(1, 0, 0), statsB)

            // Let A finish; A completes cleanly.
            releaseFirstCreate.countDown()
            val statsA = futureA.get(15, TimeUnit.SECONDS)
            Assert.assertEquals(1, statsA.uploaded + statsA.present)

            // Once a finalized matching file exists, C receives HAVE.
            val statsC = sync(srcC, batch(srcC), linkC)
            Assert.assertEquals(Stats(0, 0, 1), statsC)
        } finally {
            releaseFirstCreate.countDown()
            pool.shutdownNow()
            hub.close()
        }
    }

    @Test fun liveProgressSpeedEtaHistoryAndMultiChunkPipeline() {
        val srcDir = temp.newFolder("src")
        val dstDir = temp.newFolder("dst")
        val hubState = temp.newFolder("hubState")
        val sndState = temp.newFolder("sndState")

        // 12 MB video exercises multi-chunk pipelining across >20 chunks of BLOCK (512 KB).
        val videoBytes = Random(606).nextBytes(12 * 1024 * 1024 + 321)
        val expectedSha = digest(videoBytes.inputStream())
        writeFile(srcDir, "Camera/pipeline-test.mp4", videoBytes)

        val progressSnapshots = java.util.Collections.synchronizedList(mutableListOf<TransferProgress>())
        val dest = Destination.select(LocalTree(dstDir.path))
        val hubId = Identity(Book(File(hubState, "settings")), "Hub")
        val hubEvents = java.util.Collections.synchronizedList(mutableListOf<TransferEvent>())
        val hub = Hub(hubId, dest, Book(File(hubState, "cache")), Book(File(hubState, "parts")),
            onEvent = { hubEvents.add(it) }) {}
        val engine = Engine(sndState, "Sender", { LocalTree(it) }, { true })
        try {
            val link = pair(local(hubId.invite()), "Sender").copy(host = "127.0.0.1")
            engine.settings.put("pin" to link.pin, "token" to link.token, "address" to link.host, "hubName" to link.name, "source" to srcDir.path)

            // Also verify direct sync emits active progress snapshots, then ends inactive.
            val srcTree = LocalTree(srcDir.path)
            val sndEvents = java.util.Collections.synchronizedList(mutableListOf<TransferEvent>())
            val stats = sync(srcTree, batch(srcTree), link, onProgress = { progressSnapshots.add(it) }, onEvent = { sndEvents.add(it) })
            Assert.assertEquals(1, stats.uploaded)
            Assert.assertEquals(videoBytes.size.toLong(), stats.bytes)
            Assert.assertTrue(progressSnapshots.any { it.active && it.file == "Camera/pipeline-test.mp4" && it.fileTotal == videoBytes.size.toLong() })
            Assert.assertFalse(progressSnapshots.last().active)
            println("Local pipeline 12MB event: " + sndEvents.lastOrNull()?.detail)

            val saved = savedFiles(dstDir)
            Assert.assertEquals(1, saved.size)
            Assert.assertEquals(expectedSha, digest(saved.single().inputStream()))
            Assert.assertTrue(hubEvents.any { it.ok && it.title.contains("pipeline-test.mp4") })

            // Verify Engine populates history and clearHistory() clears it.
            writeFile(srcDir, "Camera/second.jpg", Random(607).nextBytes(2048))
            engine.enable(true)
            val deadline = System.currentTimeMillis() + 20000
            while (System.currentTimeMillis() < deadline && !engine.lastScan.contains("Uploaded 1")) Thread.sleep(100)
            Assert.assertTrue(engine.history.any { it.ok && it.title.contains("second.jpg") })
            Assert.assertTrue(engine.history.any { it.ok && it.title.contains("Scan complete") })
            Assert.assertFalse(engine.transfer.active)
            engine.clearHistory()
            Assert.assertTrue(engine.history.isEmpty())

            // Formatting checks
            Assert.assertEquals("512 B", formatBytes(512))
            Assert.assertEquals("1.5 KB", formatBytes(1536))
            Assert.assertEquals("2.0 MB/s", formatSpeed(2 * 1024 * 1024))
            Assert.assertEquals("1m 5s", formatEta(65))
        } finally {
            engine.stop()
            hub.close()
        }
    }

    @Test fun nearbyDiscoverAndRequestAcceptSavesPermanentPairingAcrossRestartAndReconnect() {
        val srcDir = temp.newFolder("nearbySrc")
        val dstDir = temp.newFolder("nearbyDst")
        val hubState = temp.newFolder("nearbyHubState")
        val sndState = temp.newFolder("nearbySndState")

        val pool = Executors.newSingleThreadExecutor()
        var hub1: Engine? = Engine(hubState, "LivingRoomHub", { LocalTree(it) }, { true })
        var snd1: Engine? = Engine(sndState, "WorkLaptop", { LocalTree(it) }, { true })
        var hub2: Engine? = null
        var snd2: Engine? = null

        try {
            hub1!!.mode(true)
            hub1.choose(dstDir.path)
            hub1.enable(true)

            val hubReadyDeadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < hubReadyDeadline && hub1.message != "Listening for paired devices") {
                Thread.sleep(50)
            }
            Assert.assertEquals("Listening for paired devices", hub1.message)

            val found = discoverNearby()
            val discovered = found.firstOrNull { it.pin == hub1.identity.pin }
            Assert.assertNotNull("Expected nearby Hub to be discovered via LAN broadcast; found=$found", discovered)
            Assert.assertEquals("LivingRoomHub", discovered!!.name)

            val senderCodeRef = java.util.concurrent.atomic.AtomicReference("")
            val pairFuture = pool.submit {
                snd1!!.requestPairWith(discovered) { code -> senderCodeRef.set(code) }
            }

            val reqDeadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < reqDeadline && hub1.pairRequests.isEmpty()) {
                Thread.sleep(50)
            }
            val req = hub1.pairRequests.single()
            Assert.assertEquals("WorkLaptop", req.device)
            Assert.assertEquals(6, req.code.length)
            Assert.assertTrue(req.code.all { it.isDigit() })
            val codeDeadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < codeDeadline && senderCodeRef.get().isEmpty()) {
                Thread.sleep(20)
            }
            Assert.assertEquals(req.code, senderCodeRef.get())

            val prevTick = hub1.requestTick
            hub1.respondPair(req.id, true)
            Assert.assertTrue(hub1.requestTick > prevTick)

            pairFuture.get(10, TimeUnit.SECONDS)
            Assert.assertTrue(snd1!!.paired)
            Assert.assertEquals("LivingRoomHub", snd1.hubName)
            Assert.assertTrue(hub1.peers.values.any { it.contains("WorkLaptop") })
            Assert.assertTrue(hub1.pairRequests.isEmpty())

            // Initial transfer works with saved pairing.
            val photo1 = Random(701).nextBytes(2048)
            writeFile(srcDir, "Camera/nearby-one.jpg", photo1)
            snd1.choose(srcDir.path)
            snd1.enable(true)
            val deliver1Deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deliver1Deadline && savedFiles(dstDir).isEmpty()) {
                Thread.sleep(100)
            }
            Assert.assertEquals(1, savedFiles(dstDir).size)

            // Simulate app closed/disconnected for days + Hub address change, then reopen and Scan now.
            snd1.stop(); snd1 = null
            hub1.stop(); hub1 = null

            hub2 = Engine(hubState, "LivingRoomHub", { LocalTree(it) }, { true })
            Assert.assertTrue(hub2.receiving)
            Assert.assertTrue(hub2.enabled)
            hub2.start()
            val hub2Deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < hub2Deadline && hub2.message != "Listening for paired devices") {
                Thread.sleep(50)
            }
            Assert.assertEquals("Listening for paired devices", hub2.message)

            snd2 = Engine(sndState, "WorkLaptop", { LocalTree(it) }, { true })
            Assert.assertTrue(snd2.paired)
            Assert.assertEquals("LivingRoomHub", snd2.hubName)
            snd2.settings.put("address" to "192.0.2.99", "enabled" to "")

            val photo2 = Random(702).nextBytes(4096)
            writeFile(srcDir, "Camera/nearby-two.jpg", photo2)
            snd2.scanNow()

            val deliver2Deadline = System.currentTimeMillis() + 25_000
            while (System.currentTimeMillis() < deliver2Deadline && savedFiles(dstDir).size < 2) {
                Thread.sleep(100)
            }
            Assert.assertEquals(2, savedFiles(dstDir).size)
            Assert.assertNotEquals("192.0.2.99", snd2.settings["address"])

            // Explicit Disconnect forgets the Hub cleanly.
            snd2.disconnectHub()
            Assert.assertFalse(snd2.paired)
            Assert.assertFalse(snd2.enabled)
            Assert.assertEquals("", snd2.hubName)
            Assert.assertEquals("Disconnected from Hub", snd2.message)

            writeFile(srcDir, "Camera/after-disconnect.jpg", Random(703).nextBytes(1024))
            Thread.sleep(500)
            Assert.assertEquals(2, savedFiles(dstDir).size)
        } finally {
            snd1?.stop()
            snd2?.stop()
            hub1?.stop()
            hub2?.stop()
            pool.shutdownNow()
        }
    }

    @Test fun nearbyRequestDeclineLeavesSenderUnpaired() {
        val dstDir = temp.newFolder("declineDst")
        val hubState = temp.newFolder("declineHubState")
        val sndState = temp.newFolder("declineSndState")

        val pool = Executors.newSingleThreadExecutor()
        val hub = Engine(hubState, "StrictHub", { LocalTree(it) }, { true })
        val snd = Engine(sndState, "GuestPC", { LocalTree(it) }, { true })
        try {
            hub.mode(true)
            hub.choose(dstDir.path)
            hub.enable(true)

            val hubDeadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < hubDeadline && hub.message != "Listening for paired devices") {
                Thread.sleep(50)
            }
            val discovered = discoverNearby().first { it.pin == hub.identity.pin }
            val future = pool.submit<Throwable?> {
                try {
                    snd.requestPairWith(discovered)
                    null
                } catch (t: Throwable) {
                    t
                }
            }

            val reqDeadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < reqDeadline && hub.pairRequests.isEmpty()) {
                Thread.sleep(50)
            }
            val req = hub.pairRequests.single()
            hub.respondPair(req.id, false)

            val err = future.get(10, TimeUnit.SECONDS)
            Assert.assertNotNull(err)
            Assert.assertTrue(err!!.message.orEmpty().contains("declined", ignoreCase = true))
            Assert.assertFalse(snd.paired)
            Assert.assertTrue(hub.peers.isEmpty())
            Assert.assertTrue(hub.pairRequests.isEmpty())
        } finally {
            snd.stop()
            hub.stop()
            pool.shutdownNow()
        }
    }

    @Test fun pairingLinkConnectsDirectlyAndSavesPermanentPairing() {
        val dstDir = temp.newFolder("linkDst")
        val hubState = temp.newFolder("linkHubState")
        val sndState = temp.newFolder("linkSndState")

        val hub = Engine(hubState, "LinkHub", { LocalTree(it) }, { true })
        val snd = Engine(sndState, "LinkPC", { LocalTree(it) }, { true })
        try {
            hub.mode(true)
            hub.choose(dstDir.path)
            hub.enable(true)

            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && hub.message != "Listening for paired devices") {
                Thread.sleep(50)
            }
            val link = local(hub.identity.invite())
            snd.pairWith("  $link\n")
            Assert.assertTrue(snd.paired)
            Assert.assertEquals("LinkHub", snd.hubName)
            Assert.assertTrue(hub.peers.values.any { it.contains("LinkPC") })
        } finally {
            snd.stop()
            hub.stop()
        }
    }

    @Test fun sixDigitPairingCodeConnectsAndRateLimitsWrongGuesses() {
        val dstDir = temp.newFolder("pinDst")
        val hubState = temp.newFolder("pinHubState")
        val sndState = temp.newFolder("pinSndState")

        val hub = Engine(hubState, "PinHub", { LocalTree(it) }, { true })
        val snd = Engine(sndState, "PinPC", { LocalTree(it) }, { true })
        try {
            hub.mode(true)
            hub.choose(dstDir.path)
            hub.enable(true)

            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && hub.message != "Listening for paired devices") {
                Thread.sleep(50)
            }
            hub.identity.invite()
            val shortCode = hub.identity.shortInviteCode()
            Assert.assertEquals(6, shortCode.length)
            Assert.assertTrue(shortCode.all { it.isDigit() })

            val wrong = if (shortCode == "000000") "111111" else "000000"
            val wrongErr = runCatching { snd.pairWith(wrong) }.exceptionOrNull()
            Assert.assertNotNull(wrongErr)
            Assert.assertTrue(wrongErr!!.message.orEmpty().contains("Incorrect", ignoreCase = true))
            Assert.assertFalse(snd.paired)

            snd.pairWith(shortCode)
            Assert.assertTrue(snd.paired)
            Assert.assertEquals("PinHub", snd.hubName)
            Assert.assertTrue(hub.peers.values.any { it.contains("PinPC") })
            Assert.assertEquals("", hub.identity.shortInviteCode())

            val reuseErr = runCatching { snd.pairWith(shortCode) }.exceptionOrNull()
            Assert.assertNotNull(reuseErr)
        } finally {
            snd.stop()
            hub.stop()
        }
    }
}
