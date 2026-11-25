package dev.lantern.core

import java.io.Closeable
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class Engine(dir: File, val device: String, val tree: (String)->Tree, val online: ()->Boolean) {
    companion object {
        private val hashPool = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        ) { r -> Thread(r, "lantern-hash").apply { isDaemon = true } }
    }
    val settings = Book(File(dir,"settings.properties"))
    val identity = Identity(settings,device)
    private val catalog = Book(File(dir,"verified.properties"))
    private val journal = Book(File(dir,"partials.properties"))
    private val receipts = Book(File(dir,"receipts.properties"))
    private val hashCache = Book(File(dir,"hashes.properties"))
    private val historyLock = Any()
    private val wakeSignal = java.util.concurrent.Semaphore(0)
    @Volatile var message = "Choose a folder to get started"
    @Volatile var pending = 0
    @Volatile var lastScan = ""
    @Volatile var transfer = TransferProgress()
    @Volatile var history: List<TransferEvent> = emptyList()
    @Volatile var requestTick = 0
    @Volatile private var generation = 0L
    @Volatile private var worker: Thread? = null
    @Volatile private var hub: Hub? = null
    @Volatile private var activeWire: Closeable? = null
    val receiving get() = settings["mode"] == "hub"
    val enabled get() = settings["enabled"] == "yes"
    val paired get() = settings["pin"].isNotEmpty() && settings["token"].isNotEmpty()
    val folder get() = settings[if (receiving) "destination" else "source"]
    val peers get() = settings.entries("peer.")
    val pairRequests get() = identity.pendingRequests()
    val hubName get() = settings["hubName"]
    fun clearHistory() { synchronized(historyLock) { history = emptyList() } }
    private fun recordEvent(event: TransferEvent) {
        synchronized(historyLock) {
            history = (listOf(event) + history).take(50)
        }
    }
    fun mode(hubMode: Boolean) {
        if (receiving == hubMode) return
        stop(); settings.put("mode" to if (hubMode) "hub" else "send","enabled" to "")
        pending = 0; lastScan = ""; message = "Choose a folder or press Start"
    }
    fun choose(key: String) {
        val marker = if (receiving) Destination.select(tree(key)).marker else ""
        stop()
        if (receiving) settings.put("destination" to key,"marker" to marker)
        else settings.put("source" to key)
        message = "Folder selected"
        if (enabled) start()
    }
    fun pairWith(code: String) {
        check(online()) { "Connect to the local Wi-Fi network" }
        val trimmed = code.trim()
        val l = if (trimmed.matches(Regex("\\d{6}(@.+)?"))) pairByCode(trimmed, device) else pair(trimmed, device)
        settings.put("pin" to l.pin,"token" to l.token,"address" to l.host,"hubName" to l.name)
        message = "Paired with "+l.name
        recordEvent(TransferEvent(System.currentTimeMillis(), "Paired with ${l.name}", l.host, true))
        if (enabled) { start(); if (wakeSignal.availablePermits() == 0) wakeSignal.release() }
    }
    fun requestPairWith(hub: NearbyHub, onCode: (String) -> Unit = {}) {
        check(online()) { "Connect to the local Wi-Fi network" }
        val l = requestPair(hub, device, onCode)
        settings.put("pin" to l.pin, "token" to l.token, "address" to l.host, "hubName" to l.name)
        message = "Paired with " + l.name
        recordEvent(TransferEvent(System.currentTimeMillis(), "Paired with ${l.name}", l.host, true))
        if (enabled) { start(); if (wakeSignal.availablePermits() == 0) wakeSignal.release() }
    }
    fun respondPair(id: String, accept: Boolean) {
        identity.respondRequest(id, accept)
        requestTick++
    }
    fun disconnectHub() {
        stop()
        settings.put("pin" to "", "token" to "", "address" to "", "hubName" to "", "lastDestination" to "", "enabled" to "")
        pending = 0; lastScan = ""; message = "Disconnected from Hub"
        recordEvent(TransferEvent(System.currentTimeMillis(), "Disconnected from Hub", "", true))
    }
    fun scanNow() {
        if (!enabled) { enable(true); return }
        if (wakeSignal.availablePermits() == 0) wakeSignal.release()
    }
    fun enable(on: Boolean) {
        settings.put("enabled" to if (on) "yes" else "")
        if (on) start() else { stop(); message = "Paused" }
    }
    @Synchronized fun stop() {
        generation++
        identity.clearInvite()
        worker?.interrupt(); worker = null
        runCatching { activeWire?.close() }; activeWire = null
        hub?.close(); hub = null
        transfer = TransferProgress()
    }
    @Synchronized fun start() {
        if (!enabled) { stop(); return }
        if (worker?.isAlive == true) return
        stop()
        val gen = generation
        worker = thread(isDaemon=true,name="lantern-monitor") {
            wakeSignal.drainPermits()
            var delay = 1500L
            var previous = emptyMap<String,Pair<Long,Long>>()
            var lastError = ""
            while (gen == generation) {
                try {
                    check(folder.isNotEmpty()) { "Choose a folder" }
                    if (receiving) {
                        val dest = Destination(tree(folder),settings["marker"])
                        dest.check()
                        check(online()) { "Waiting for local Wi-Fi" }
                        synchronized(this) {
                            if (gen != generation) return@thread
                            if (hub == null) {
                                hub = Hub(identity,dest,catalog,journal,
                                    onProgress = { if (gen == generation) transfer = it },
                                    onEvent = { if (gen == generation) recordEvent(it) },
                                    onRequests = { if (gen == generation) requestTick++ }
                                ) { if (gen == generation) message = it }
                                message = "Listening for paired devices"
                            }
                        }
                        lastError = ""
                    } else {
                        val source = tree(folder)
                        val files = source.walk().filter { it.node.media() }
                        val stamps = files.associate { it.node.id to (it.node.size to it.node.modified) }
                        val stable = files.filter { previous[it.node.id] == stamps[it.node.id] && it.node.size >= 0 }
                        previous = stamps
                        val batch = if (stable.size > 2) {
                            val tasks = stable.map { item ->
                                Callable {
                                    check(gen == generation)
                                    val token = source.changeToken(item.node)
                                    val sha = if (token != null) {
                                        val ck = "v2."+hash(source.key+"|"+item.node.id+"|"+token)
                                        hashCache[ck].takeIf { h -> h.matches(Regex("[0-9a-f]{64}")) }
                                            ?: digest(source.open(item.node)).also { h -> hashCache.put(ck to h) }
                                    } else {
                                        digest(source.open(item.node))
                                    }
                                    Outgoing(item,Content(item.node.size,sha))
                                }
                            }
                            hashPool.invokeAll(tasks).map {
                                try { it.get() } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause ?: e) }
                            }
                        } else {
                            stable.map {
                                check(gen == generation)
                                val token = source.changeToken(it.node)
                                val sha = if (token != null) {
                                    val ck = "v2."+hash(source.key+"|"+it.node.id+"|"+token)
                                    hashCache[ck].takeIf { h -> h.matches(Regex("[0-9a-f]{64}")) }
                                        ?: digest(source.open(it.node)).also { h -> hashCache.put(ck to h) }
                                } else {
                                    digest(source.open(it.node))
                                }
                                Outgoing(it,Content(it.node.size,sha))
                            }
                        }
                        val pin = settings["pin"]
                        val base = hash(pin+source.key)
                        fun receipt(f: Outgoing) = base+"."+hash(f.item.path)
                        var currentSelection = settings["lastDestination"]
                        val unstableCount = files.size - stable.size
                        val unconfirmed = batch.filterTo(LinkedHashSet()) { receipts[receipt(it)] != currentSelection+it.content.sha }
                        val deliveredInSync = HashSet<Outgoing>()
                        pending = unstableCount + unconfirmed.size
                        check(pin.isNotEmpty()) { "Scan the Hub pairing QR" }
                        check(online()) { "Waiting for local Wi-Fi" }
                        if (stable.isNotEmpty() || files.isEmpty()) {
                            val receiptBatch = mutableListOf<Pair<String,String>>()
                            var savedSelection = currentSelection
                            fun flushReceipts() {
                                if (receiptBatch.isNotEmpty()) {
                                    receipts.put(*receiptBatch.toTypedArray())
                                    receiptBatch.clear()
                                }
                            }
                            val stats = try {
                                sync(source,batch,Link(pin,settings["token"],settings["address"],hubName),
                                    { settings.put("address" to it) },{ if (gen == generation) message = it },
                                    { f,marker ->
                                        val value = marker+f.content.sha
                                        if (receipts[receipt(f)] != value) {
                                            receiptBatch.add(receipt(f) to value)
                                            if (receiptBatch.size >= 25) flushReceipts()
                                        }
                                        if (marker != savedSelection) {
                                            savedSelection = marker
                                            settings.put("lastDestination" to marker)
                                        }
                                        deliveredInSync.add(f)
                                        if (marker != currentSelection) {
                                            currentSelection = marker
                                            unconfirmed.clear()
                                            batch.filterTo(unconfirmed) { it !in deliveredInSync && receipts[receipt(it)] != marker+it.content.sha }
                                        } else {
                                            unconfirmed.remove(f)
                                        }
                                        if (gen == generation) pending = unstableCount + unconfirmed.size
                                    },{ gen == generation },{ activeWire = it },
                                    { if (gen == generation) transfer = it },
                                    { if (gen == generation) recordEvent(it) })
                            } finally {
                                runCatching { flushReceipts() }
                                if (gen == generation) transfer = TransferProgress()
                            }
                            lastScan = "Uploaded "+stats.uploaded+" · "+stats.bytes+" bytes · already present "+stats.present
                            if (stats.uploaded > 0) {
                                recordEvent(TransferEvent(
                                    System.currentTimeMillis(),
                                    "Scan complete · ${stats.uploaded} uploaded",
                                    "${formatBytes(stats.bytes)} sent · ${stats.present} already present",
                                    true
                                ))
                            }
                            message = if (pending > 0) "Waiting for files to finish writing" else "Watching for new media"
                            lastError = ""
                        } else message = "Waiting for files to finish writing"
                    }
                    delay = if (receiving || (previous.isNotEmpty() && pending > 0)) 3000L else 15000L
                } catch (_: InterruptedException) { return@thread }
                catch (e: Exception) {
                    if (gen != generation) return@thread
                    if (receiving) synchronized(this) { if (generation == gen) { hub?.close(); hub = null } }
                    transfer = TransferProgress()
                    val nextMsg = when (e) {
                        is SecurityException -> "Folder permission lost. Select the folder again."
                        is java.net.SocketException, is java.net.SocketTimeoutException -> "Waiting for paired Hub on Wi-Fi"
                        else -> e.message ?: "Transfer paused"
                    }
                    message = nextMsg
                    if (nextMsg != lastError) {
                        lastError = nextMsg
                        recordEvent(TransferEvent(System.currentTimeMillis(), "Paused", nextMsg, false))
                    }
                    delay = (delay*2).coerceIn(3000L,30000L)
                }
                try { wakeSignal.tryAcquire(delay, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { return@thread }
            }
        }
    }
}
