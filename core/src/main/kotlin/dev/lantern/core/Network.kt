package dev.lantern.core

import okhttp3.tls.HeldCertificate
import java.io.*
import java.net.*
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import javax.net.ssl.*
import kotlin.concurrent.thread

private val log = Logger.getLogger("Lantern")

const val PORT = 42781
const val BLOCK = 512 * 1024
private const val SOCKET_BUF = 1024 * 1024
private const val STREAM_BUF = 1024 * 1024
private const val PIPELINE_WINDOW = 8
private const val MAGIC = "Lantern/1"

data class TransferProgress(
    val file: String = "",
    val fileBytes: Long = 0L,
    val fileTotal: Long = 0L,
    val batchBytes: Long = 0L,
    val batchTotal: Long = 0L,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesPerSec: Long = 0L,
    val etaSeconds: Long = -1L,
    val active: Boolean = false
)

data class TransferEvent(
    val timestamp: Long,
    val title: String,
    val detail: String,
    val ok: Boolean = true
)

class TransferMeter {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var lastReportNs = 0L
    @Synchronized fun record(totalBytes: Long, nowNs: Long = System.nanoTime()): Long {
        samples.addLast(nowNs to totalBytes)
        while (samples.size > 2 && nowNs - samples.first().first > 2_000_000_000L) samples.removeFirst()
        val first = samples.first()
        val dtNs = nowNs - first.first
        val db = totalBytes - first.second
        return if (dtNs > 40_000_000L && db >= 0) (db * 1_000_000_000.0 / dtNs).toLong() else 0L
    }
    @Synchronized fun shouldEmit(force: Boolean = false, nowNs: Long = System.nanoTime()): Boolean {
        if (force || nowNs - lastReportNs >= 120_000_000L) {
            lastReportNs = nowNs
            return true
        }
        return false
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
fun formatSpeed(bytesPerSec: Long): String = if (bytesPerSec <= 0L) "—" else formatBytes(bytesPerSec) + "/s"
fun formatEta(seconds: Long): String = when {
    seconds < 0L -> "—"
    seconds < 60L -> "${seconds}s"
    seconds < 3600L -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}
fun secret() = ByteArray(32).also { SecureRandom().nextBytes(it) }.hex()

data class NearbyHub(val pin: String, val name: String, val hosts: List<String>)
data class PairRequest(val id: String, val device: String, val code: String, val address: String)
fun verificationCode(pin: String, token: String): String =
    (digest((pin + "|" + token).byteInputStream()).take(8).toLong(16) % 1_000_000L).toString().padStart(6, '0')

class Identity(val book: Book, val name: String) {
    val cert: HeldCertificate
    val pin: String
    init {
        val pem = book["identity"]
        cert = if (pem.isEmpty()) HeldCertificate.Builder().ecdsa256().commonName("Lantern Hub")
            .duration(3650,TimeUnit.DAYS).build().also { book.put("identity" to it.certificatePem()+it.privateKeyPkcs8Pem()) }
        else HeldCertificate.decode(pem)
        pin = digest(cert.certificate.encoded.inputStream())
    }
    val server: SSLContext by lazy {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null,null); setKeyEntry("hub",cert.keyPair.getPrivate(),CharArray(0),arrayOf(cert.certificate))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks,CharArray(0)) }
        SSLContext.getInstance("TLS").apply { init(keys.keyManagers,null,null) }
    }
    private class PendingEntry(val request: PairRequest, val token: String, val label: String) {
        val latch = CountDownLatch(1)
        @Volatile var approved: Boolean? = null
    }
    private var ticket = ""
    private var pinCode = ""
    private var pinAttempts = 0
    private var expiry = 0L
    private val requests = LinkedHashMap<String, PendingEntry>()
    @Synchronized fun shortInviteCode(): String = if (pinCode.isNotEmpty() && System.nanoTime() < expiry) pinCode else ""
    @Synchronized fun clearInvite() {
        ticket = ""; pinCode = ""; pinAttempts = 0; expiry = 0L
        requests.values.forEach { it.approved = false; it.latch.countDown() }
        requests.clear()
    }
    @Synchronized fun invite(): String {
        ticket = secret()
        pinCode = String.format(Locale.US, "%06d", SecureRandom().nextInt(1_000_000))
        pinAttempts = 5
        expiry = System.nanoTime() + TimeUnit.MINUTES.toNanos(2)
        val hosts = addresses().mapNotNull { it.address.hostAddress?.substringBefore('%' ) }.distinct().joinToString(",")
        require(hosts.isNotEmpty()) { "Connect to Wi-Fi first" }
        return listOf(MAGIC,pin,ticket,hosts).joinToString("|")
    }
    @Synchronized fun claim(code: String, token: String, label: String) {
        require(ticket.isNotEmpty() && ticket == code && System.nanoTime() < expiry) { "Pairing code expired or used" }
        require(token.matches(Regex("[0-9a-f]{64}")))
        book.put("peer."+hash(token) to clean(label).take(48))
        ticket = ""; pinCode = ""; pinAttempts = 0
    }
    @Synchronized fun claimPin(code: String, token: String, label: String) {
        require(pinCode.isNotEmpty() && System.nanoTime() < expiry) { "Pairing code expired or used" }
        require(token.matches(Regex("[0-9a-f]{64}")))
        if (pinCode != code) {
            pinAttempts--
            if (pinAttempts <= 0) { pinCode = ""; ticket = ""; expiry = 0L }
            throw IllegalArgumentException("Incorrect 6-digit pairing code")
        }
        book.put("peer."+hash(token) to clean(label).take(48))
        ticket = ""; pinCode = ""; pinAttempts = 0
    }
    fun peer(token: String): String {
        val key = hash(token)
        return book["peer."+key].takeIf { it.isNotEmpty() }?.let { it+"-"+key.take(8) } ?: error("Pairing revoked. Scan a new QR.")
    }
    @Synchronized fun pendingRequests(): List<PairRequest> = requests.values.map { it.request }
    fun awaitRequest(token: String, label: String, address: String, timeoutMs: Long = 60_000L, onChanged: () -> Unit = {}): Boolean {
        require(token.matches(Regex("[0-9a-f]{64}")))
        val cleanLabel = clean(label).take(48).ifEmpty { "Sender" }
        val reqId = secret().take(16)
        val entry = PendingEntry(PairRequest(reqId, cleanLabel, verificationCode(pin, token), address), token, cleanLabel)
        synchronized(this) {
            require(requests.size < 8) { "Too many pending pairing requests" }
            requests[reqId] = entry
        }
        onChanged()
        return try {
            entry.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            entry.approved == true
        } finally {
            synchronized(this) { requests.remove(reqId) }
            onChanged()
        }
    }
    @Synchronized fun respondRequest(requestId: String, accept: Boolean) {
        val entry = requests[requestId] ?: return
        if (accept) book.put("peer."+hash(entry.token) to entry.label)
        entry.approved = accept
        entry.latch.countDown()
    }
}

fun addresses(interfaceAllowed: (NetworkInterface)->Boolean = { true }): List<InterfaceAddress> = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    .filter { runCatching { interfaceAllowed(it) && it.isUp && !it.isLoopback && !it.isPointToPoint && !it.name.startsWith("rmnet") && !it.name.startsWith("tun") }.getOrDefault(false) }
    .sortedWith(compareBy<NetworkInterface> { isVirtualInterface(it) }
        .thenByDescending { it.name.startsWith("wlan") || it.displayName.contains("Wi-Fi", true) || it.displayName.contains("Wireless", true) })
    .flatMap { it.interfaceAddresses }
    .filter { it.address.isLinkLocalAddress || (it.address is Inet4Address && it.address.isSiteLocalAddress) }
    .sortedByDescending { it.address is Inet6Address }
fun localLanIps(): List<String> = addresses { !isVirtualInterface(it) }
    .mapNotNull { it.address as? Inet4Address }
    .mapNotNull { it.hostAddress }
    .distinct()
private fun localPeer(ip: InetAddress): Boolean {
    if (ip.isLoopbackAddress) return true
    if (ip is Inet6Address) return ip.isLinkLocalAddress
    if (ip !is Inet4Address) return false
    return addresses().any { nic ->
        val bits = nic.networkPrefixLength.toInt()
        nic.address is Inet4Address && bits in 1..32 && (0 until bits).all { bit ->
            val mask = 128 shr (bit%8)
            (ip.address[bit/8].toInt() and mask) == (nic.address.address[bit/8].toInt() and mask)
        }
    }
}
private fun isVirtualInterface(nic: NetworkInterface): Boolean {
    val d = nic.displayName
    return nic.isVirtual || d.contains("Virtual", true) || d.contains("VMware", true) ||
        d.contains("VirtualBox", true) || d.contains("Hyper-V", true) || d.contains("WSL", true) || d.contains("vEthernet", true)
}
private fun localTargets(host: String): List<InetAddress> {
    // A scope ID belongs to the sender's OS. Never reuse the Hub's zone number.
    val literal = host.substringBefore('%')
    if (':' in literal) {
        require(literal.matches(Regex("[0-9a-fA-F:]+")))
        val raw = InetAddress.getByName(literal)
        require(raw is Inet6Address && raw.isLinkLocalAddress)
        return addresses().map { it.address }.filterIsInstance<Inet6Address>().mapNotNull { it.scopedInterface ?: NetworkInterface.getByInetAddress(it) }
            .distinctBy { it.index }
            .sortedWith(compareByDescending<NetworkInterface> { it.name.startsWith("wlan") || it.displayName.contains("Wi-Fi",true) }.thenBy { isVirtualInterface(it) })
            .map { Inet6Address.getByAddress(null,raw.address,it.index) }
    }
    require(literal.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")))
    return listOf(InetAddress.getByName(literal).also { require(it.isSiteLocalAddress || it.isLinkLocalAddress || it.isLoopbackAddress) })
}
data class Link(val pin: String, val token: String, val host: String, val name: String)
class Wire(val socket: SSLSocket): Closeable {
    val input = DataInputStream(BufferedInputStream(socket.inputStream, STREAM_BUF))
    val out = DataOutputStream(BufferedOutputStream(socket.outputStream, STREAM_BUF))
    fun say(s: String) { out.writeUTF(s); out.flush() }
    fun reply(): String {
        val s = input.readUTF()
        if (s.startsWith("ERR ")) throw IOException(s.drop(4))
        return s
    }
    override fun close() = socket.close()
}
private fun connect(host: String,pin: String): Wire {
    val trust = object: X509TrustManager {
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) = throw java.security.cert.CertificateException()
        override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) {
            if (digest(chain.first().encoded.inputStream()) != pin) throw java.security.cert.CertificateException("Hub identity mismatch")
        }
    }
    val ctx = SSLContext.getInstance("TLS").apply { init(null,arrayOf(trust),null) }
    var failure: Exception = IOException("No local interface for Hub address")
    for (target in localTargets(host)) {
    val socket = ctx.socketFactory.createSocket() as SSLSocket
    try {
        socket.enabledProtocols = socket.supportedProtocols.filter { it in setOf("TLSv1.2","TLSv1.3") }.toTypedArray()
        runCatching { socket.receiveBufferSize = SOCKET_BUF; socket.sendBufferSize = SOCKET_BUF }
        socket.connect(InetSocketAddress(target,PORT),4000)
        socket.soTimeout = 15000
        socket.tcpNoDelay = true
        socket.startHandshake()
        socket.soTimeout = 180000
        return Wire(socket)
    } catch (e: Exception) { socket.close(); failure=e }
    }
    throw failure
}
fun pair(qr: String, myName: String): Link {
    val fields = qr.trim().split('|')
    require(fields.size == 4 && fields[0] == MAGIC && fields.slice(1..2).all { it.matches(Regex("[0-9a-f]{64}")) }) { "Choose a Lantern pairing QR" }
    val token = secret()
    var failure: Exception = IOException("Hub is unreachable")
    for (host in fields[3].split(',').take(16)) {
        val w = try { connect(host,fields[1]) } catch (e: Exception) { failure = e; continue }
        return w.use {
            it.out.writeUTF(MAGIC); it.out.writeUTF("PAIR"); it.out.writeUTF(fields[2]); it.out.writeUTF(token); it.say(myName)
            check(it.reply() == "OK")
            Link(fields[1],token,host,it.input.readUTF())
        }
    }
    throw IOException("Cannot reach the Hub over local Wi-Fi. Check that the Hub is listening and that this Wi-Fi allows devices to communicate.",failure)
}
private fun subnetTargets(): List<InetAddress> {
    val nonVirtual = addresses { !isVirtualInterface(it) }
    val pool = if (nonVirtual.isNotEmpty()) nonVirtual else addresses()
    val prefixes = pool.mapNotNull { it.address as? Inet4Address }.mapNotNull { addr ->
        val raw = addr.address
        if (raw.size == 4) "${raw[0].toInt() and 255}.${raw[1].toInt() and 255}.${raw[2].toInt() and 255}" else null
    }.distinct()
    val targets = ArrayList<InetAddress>(prefixes.size * 254 + 1)
    runCatching { targets.add(InetAddress.getByName("127.0.0.1")) }
    for (p in prefixes.take(4)) {
        for (i in 1..254) runCatching { targets.add(InetAddress.getByName("$p.$i")) }
    }
    return targets
}
private fun probeHubTcp(target: InetAddress): Pair<String, String>? {
    val trustAny = object: X509TrustManager {
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) = throw java.security.cert.CertificateException()
        override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) {}
    }
    val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAny), null) }
    val socket = ctx.socketFactory.createSocket() as SSLSocket
    return try {
        socket.enabledProtocols = socket.supportedProtocols.filter { it in setOf("TLSv1.2","TLSv1.3") }.toTypedArray()
        socket.connect(InetSocketAddress(target, PORT), 250)
        socket.soTimeout = 1000
        socket.startHandshake()
        val certPin = digest(socket.session.peerCertificates.first().encoded.inputStream())
        Wire(socket).use { w ->
            w.out.writeUTF(MAGIC); w.out.writeUTF("INFO"); w.out.flush()
            check(w.reply() == "OK")
            val reportedPin = w.input.readUTF()
            val name = w.input.readUTF().ifEmpty { "Lantern Hub" }
            if (reportedPin == certPin && reportedPin.matches(Regex("[0-9a-f]{64}"))) reportedPin to name else null
        }
    } catch (_: Exception) {
        runCatching { socket.close() }
        null
    }
}
private fun discoveryUdpTargets(): List<InetAddress> {
    val interfaces = addresses()
    val group = InetAddress.getByName("ff02::1").address
    val v6 = interfaces.map { it.address }.filterIsInstance<Inet6Address>().mapNotNull {
        val nic = it.scopedInterface ?: NetworkInterface.getByInetAddress(it) ?: return@mapNotNull null
        Inet6Address.getByAddress(null,group,nic.index)
    }
    return (interfaces.mapNotNull { it.broadcast } + v6 + subnetTargets()).distinctBy { it.hostAddress }
}
private fun probeHubsByTcp(): Map<String, Pair<String, LinkedHashSet<String>>> {
    val found = LinkedHashMap<String, Pair<String, LinkedHashSet<String>>>()
    val probePool = java.util.concurrent.Executors.newFixedThreadPool(64)
    try {
        val futures = subnetTargets().filter { localPeer(it) }.map { target ->
            target to probePool.submit<Pair<String, String>?> { probeHubTcp(target) }
        }
        for ((target, future) in futures) {
            val res = runCatching { future.get() }.getOrNull() ?: continue
            val host = target.hostAddress ?: continue
            val entry = found.getOrPut(res.first) { res.second to LinkedHashSet() }
            entry.second.add(host)
        }
    } finally {
        probePool.shutdownNow()
    }
    return found
}
fun discoverNearby(): List<NearbyHub> = DatagramSocket().use { sock ->
    sock.broadcast = true
    val msg = (MAGIC+" DISCOVER").toByteArray()
    for (target in discoveryUdpTargets()) runCatching {
        sock.send(DatagramPacket(msg,msg.size,target,PORT))
    }
    val found = LinkedHashMap<String, Pair<String, LinkedHashSet<String>>>()
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1200)
    var graceDeadline = deadline
    while (true) {
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(minOf(deadline, graceDeadline) - System.nanoTime()).toInt()
        if (remainingMs <= 0) break
        sock.soTimeout = remainingMs
        val response = DatagramPacket(ByteArray(300),300)
        try { sock.receive(response) } catch (_: SocketTimeoutException) { break }
        if (!localPeer(response.address)) continue
        val text = String(response.data,0,response.length)
        if (!text.startsWith("HUB ")) continue
        val payload = text.drop(4)
        val pin = payload.substringBefore('|')
        val name = payload.substringAfter('|', "").ifEmpty { "Lantern Hub" }
        if (!pin.matches(Regex("[0-9a-f]{64}"))) continue
        val host = response.address.hostAddress ?: continue
        if (found.isEmpty()) graceDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400)
        val entry = found.getOrPut(pin) { name to LinkedHashSet() }
        entry.second.add(host)
    }
    if (found.isEmpty()) found.putAll(probeHubsByTcp())
    found.map { (pin, pair) -> NearbyHub(pin, pair.first, pair.second.sortedByDescending { ':' in it }) }
        .sortedBy { it.name.lowercase() }
}
fun pairByCode(code: String, myName: String): Link {
    val trimmed = code.trim()
    val hasHost = '@' in trimmed
    val cleanCode = trimmed.substringBefore('@').trim()
    val explicitHost = if (hasHost) trimmed.substringAfter('@').trim() else ""
    require(cleanCode.matches(Regex("\\d{6}"))) { "Enter the 6-digit pairing code shown on the Hub" }
    val hubs = if (explicitHost.isNotEmpty()) {
        val found = LinkedHashMap<String, Pair<String, LinkedHashSet<String>>>()
        for (target in localTargets(explicitHost)) {
            val info = probeHubTcp(target) ?: continue
            val host = target.hostAddress ?: continue
            found.getOrPut(info.first) { info.second to LinkedHashSet() }.second.add(host)
        }
        found.map { (pin, pair) -> NearbyHub(pin, pair.first, pair.second.sortedByDescending { ':' in it }) }
    } else {
        discoverNearby()
    }
    require(hubs.isNotEmpty()) { "No active Hub found on local Wi-Fi. Check that Receive as Hub is started on your phone." }
    val token = secret()
    var failure: Exception = IOException("Pairing code was not accepted")
    for (hub in hubs) {
        for (host in hub.hosts.take(16)) {
            val w = try { connect(host, hub.pin) } catch (e: Exception) { failure = e; continue }
            try {
                return w.use {
                    it.out.writeUTF(MAGIC); it.out.writeUTF("PAIR_PIN"); it.out.writeUTF(cleanCode); it.out.writeUTF(token); it.say(myName)
                    check(it.reply() == "OK")
                    Link(hub.pin, token, host, it.input.readUTF())
                }
            } catch (e: Exception) {
                failure = e
                if (e.message?.contains("Incorrect", true) == true || e.message?.contains("expired", true) == true) throw e
            }
        }
    }
    throw failure
}
fun requestPair(hub: NearbyHub, myName: String, onCode: (String)->Unit = {}): Link {
    val token = secret()
    onCode(verificationCode(hub.pin, token))
    var failure: Exception = IOException("Hub is unreachable")
    for (host in hub.hosts.take(16)) {
        val w = try { connect(host,hub.pin) } catch (e: Exception) { failure = e; continue }
        return w.use {
            it.socket.soTimeout = 70000
            it.out.writeUTF(MAGIC); it.out.writeUTF("REQUEST"); it.out.writeUTF(token); it.say(myName)
            check(it.reply() == "OK")
            Link(hub.pin,token,host,it.input.readUTF())
        }
    }
    throw IOException("Cannot reach "+hub.name+" over local Wi-Fi.", failure)
}
private fun discover(pin: String): List<String> = DatagramSocket().use { sock ->
    sock.broadcast = true
    val msg = (MAGIC+" "+pin).toByteArray()
    for (target in discoveryUdpTargets()) runCatching {
        sock.send(DatagramPacket(msg,msg.size,target,PORT))
    }
    val found = linkedSetOf<String>()
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1300)
    var graceDeadline = deadline
    while (true) {
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(minOf(deadline, graceDeadline) - System.nanoTime()).toInt()
        if (remainingMs <= 0) break
        sock.soTimeout = remainingMs
        val response = DatagramPacket(ByteArray(128),128)
        try {
            sock.receive(response)
        } catch (_: SocketTimeoutException) {
            break
        }
        if (String(response.data,0,response.length) == "HERE "+pin && localPeer(response.address)) {
            val host = response.address.hostAddress ?: continue
            if (found.isEmpty()) graceDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250)
            found.add(host)
        }
    }
    if (found.isEmpty()) probeHubsByTcp()[pin]?.second?.let { found.addAll(it) }
    if (found.isEmpty()) throw SocketTimeoutException("Hub discovery timed out")
    found.sortedByDescending { ':' in it }
}

class Hub(private val id: Identity, private val dest: Destination, private val cache: Book,
          private val journal: Book,
          private val onProgress: (TransferProgress)->Unit = {},
          private val onEvent: (TransferEvent)->Unit = {},
          private val onRequests: ()->Unit = {},
          private val status: (String)->Unit): Closeable {
    private val listener = id.server.serverSocketFactory.createServerSocket() as SSLServerSocket
    private val udp = MulticastSocket(null)
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val destLock = Any()
    private val recentlySaved = mutableMapOf<Content,Node>()
    @Volatile private var stopped = false
    init {
        try {
            listener.reuseAddress = true
            runCatching { listener.receiveBufferSize = SOCKET_BUF }
            listener.bind(InetSocketAddress(PORT))
            listener.enabledProtocols = listener.supportedProtocols.filter { it in setOf("TLSv1.2","TLSv1.3") }.toTypedArray()
            udp.reuseAddress = true; udp.bind(InetSocketAddress(PORT))
            val mcast = InetSocketAddress(InetAddress.getByName("ff02::1"), PORT)
            addresses().map { it.address }.filterIsInstance<Inet6Address>()
                .mapNotNull { it.scopedInterface ?: NetworkInterface.getByInetAddress(it) }
                .distinctBy { it.index }
                .forEach { nic -> runCatching { udp.joinGroup(mcast, nic) } }
        } catch (e: Exception) { close(); throw e }
        thread(isDaemon=true,name="lantern-discovery") {
            val buf = ByteArray(160)
            while (!stopped) try {
                val p = DatagramPacket(buf,buf.size); udp.receive(p)
                if (!localPeer(p.address)) continue
                val msg = String(buf,0,p.length)
                when (msg) {
                    MAGIC+" "+id.pin -> {
                        val data = ("HERE "+id.pin).toByteArray()
                        udp.send(DatagramPacket(data,data.size,p.address,p.port))
                    }
                    MAGIC+" DISCOVER" -> {
                        val data = ("HUB "+id.pin+"|"+id.name).toByteArray()
                        udp.send(DatagramPacket(data,data.size,p.address,p.port))
                    }
                }
            } catch (_: IOException) { if (stopped) break }
        }
        thread(isDaemon=true,name="lantern-hub") {
            while (!stopped) try {
                val sock = listener.accept() as SSLSocket
                if (stopped || !localPeer(sock.inetAddress)) { runCatching { sock.close() }; continue }
                clients.add(sock)
                thread(isDaemon=true,name="lantern-peer") {
                    try {
                        sock.use { s ->
                            s.soTimeout = 15000; s.tcpNoDelay = true
                            runCatching { s.receiveBufferSize = SOCKET_BUF; s.sendBufferSize = SOCKET_BUF }
                            val w = Wire(s)
                            try { serve(w) } catch (e: Exception) {
                                if (!stopped) {
                                    val msg = e.message ?: "Connection interrupted"
                                    status(msg)
                                    onEvent(TransferEvent(System.currentTimeMillis(), "Hub transfer interrupted", msg, false))
                                }
                                runCatching { w.say("ERR "+(e.message ?: "Interrupted").take(200)) }
                            } finally {
                                onProgress(TransferProgress(active = false))
                            }
                        }
                    } finally { clients.remove(sock) }
                }
            } catch (e: Exception) { if (!stopped) status(e.message ?: "Network unavailable") }
        }
    }
    private fun serve(w: Wire) {
        val i = w.input
        require(i.readUTF() == MAGIC)
        val operation = i.readUTF()
        require(operation in setOf("PAIR","PAIR_PIN","REQUEST","INFO","SYNC"))
        if (operation == "INFO") {
            w.out.writeUTF("OK"); w.out.writeUTF(id.pin); w.say(id.name)
            return
        }
        if (operation == "PAIR") {
            id.claim(i.readUTF(),i.readUTF(),i.readUTF())
            w.out.writeUTF("OK"); w.say(id.name); status("Paired · listening")
            onEvent(TransferEvent(System.currentTimeMillis(), "Device paired", "Added sender to Hub", true))
            return
        }
        if (operation == "PAIR_PIN") {
            id.claimPin(i.readUTF(),i.readUTF(),i.readUTF())
            w.out.writeUTF("OK"); w.say(id.name); status("Paired · listening")
            onEvent(TransferEvent(System.currentTimeMillis(), "Device paired", "Added sender via 6-digit code", true))
            return
        }
        if (operation == "REQUEST") {
            val token = i.readUTF()
            val label = i.readUTF()
            val addr = w.socket.inetAddress.hostAddress?.substringBefore('%') ?: ""
            w.socket.soTimeout = 70000
            status("Connection request from "+clean(label).take(48))
            val accepted = id.awaitRequest(token, label, addr, onChanged = onRequests)
            if (accepted) {
                w.out.writeUTF("OK"); w.say(id.name); status("Paired · listening")
                onEvent(TransferEvent(System.currentTimeMillis(), "Device paired", "${clean(label).take(48)} accepted", true))
            } else {
                status("Listening for paired devices")
                w.say("ERR Connection request declined")
            }
            return
        }
        val token = i.readUTF()
        val sender = id.peer(token)
        status("Checking destination for "+sender)
        val catalog = Catalog(dest,cache,journal,destLock,recentlySaved)
        try {
            w.socket.soTimeout = 180000
            w.out.writeUTF("READY"); w.say(dest.marker)
            val buffer = ByteArray(BLOCK)
            val meter = TransferMeter()
            var receivedSessionBytes = 0L
            var sessionFiles = 0
            while (true) {
                val op = i.readUTF()
                if (op == "DONE") { status("Listening · scan complete"); return }
                require(op == "FILE")
                id.peer(token)
                val path = i.readUTF()
                val c = Content(i.readLong(),i.readUTF())
                val release = catalog.acquirePartial(sender,path)
                try {
                    if (catalog.present(c)) { log.info("HUB HAVE "+path+" ("+c.size+")"); w.say("HAVE"); continue }
                    val part = catalog.partial(sender,path,c)
                    var offset = part.node.size
                    log.info("HUB OFFSET "+offset+"/"+c.size+" "+path)
                    w.out.writeUTF("OFFSET"); w.out.writeLong(offset); w.out.flush()
                    val remaining = c.size - offset
                    if (remaining <= BLOCK.toLong()) {
                        dest.tree.append(part.node,offset).use { output ->
                            while (offset < c.size) {
                                val count = i.readInt()
                                require(count in 1..BLOCK && count.toLong() <= c.size-offset)
                                i.readFully(buffer,0,count)
                                id.peer(token)
                                output.write(buffer,0,count); output.flush()
                                offset += count
                                receivedSessionBytes += count
                                w.out.writeLong(offset); w.out.flush()
                                val rate = meter.record(receivedSessionBytes)
                                val eta = if (rate > 0) (c.size - offset).coerceAtLeast(0L) / rate else -1L
                                if (meter.shouldEmit(offset == c.size)) {
                                    status("Receiving "+path+" · "+percent(offset,c.size)+"%")
                                    onProgress(TransferProgress("Receiving $path", offset, c.size, receivedSessionBytes, 0L, sessionFiles, 0, rate, eta, true))
                                }
                            }
                        }
                    } else {
                        dest.tree.append(part.node,offset).use { output ->
                            val queue = ArrayBlockingQueue<Pair<ByteArray, Int>>(4)
                            val pool = ArrayBlockingQueue<ByteArray>(6)
                            val writeError = AtomicReference<Throwable?>(null)
                            var writtenOffset = offset
                            val writer = thread(isDaemon = true, name = "lantern-hub-writer") {
                                try {
                                    while (true) {
                                        val (chunk, len) = queue.take()
                                        if (len < 0) break
                                        id.peer(token)
                                        output.write(chunk, 0, len)
                                        pool.offer(chunk)
                                        writtenOffset += len
                                        receivedSessionBytes += len
                                        w.out.writeLong(writtenOffset); w.out.flush()
                                        val rate = meter.record(receivedSessionBytes)
                                        val eta = if (rate >= 32 * 1024 && receivedSessionBytes >= 256 * 1024) (c.size - writtenOffset).coerceAtLeast(0L) / rate else -1L
                                        if (meter.shouldEmit(writtenOffset == c.size)) {
                                            status("Receiving "+path+" · "+percent(writtenOffset,c.size)+"%")
                                            onProgress(TransferProgress("Receiving $path", writtenOffset, c.size, receivedSessionBytes, 0L, sessionFiles, 0, rate, eta, true))
                                        }
                                    }
                                    output.flush()
                                } catch (t: Throwable) {
                                    writeError.compareAndSet(null, t)
                                    queue.clear()
                                }
                            }
                            try {
                                var readOffset = offset
                                while (readOffset < c.size) {
                                    writeError.get()?.let { throw it }
                                    val count = i.readInt()
                                    require(count in 1..BLOCK && count.toLong() <= c.size - readOffset)
                                    val chunk = pool.poll() ?: ByteArray(BLOCK)
                                    i.readFully(chunk, 0, count)
                                    readOffset += count
                                    while (!queue.offer(chunk to count, 50, TimeUnit.MILLISECONDS)) {
                                        writeError.get()?.let { throw it }
                                    }
                                }
                                while (!queue.offer(ByteArray(0) to -1, 50, TimeUnit.MILLISECONDS)) {
                                    writeError.get()?.let { throw it }
                                }
                            } catch (t: Throwable) {
                                queue.clear()
                                queue.offer(ByteArray(0) to -1)
                                writer.join()
                                throw writeError.get() ?: t
                            }
                            writer.join()
                            writeError.get()?.let { throw it }
                        }
                    }
                    val saved = part.finish()
                    sessionFiles++
                    log.info("HUB SAVED "+path+" ("+c.size+")")
                    onEvent(TransferEvent(System.currentTimeMillis(), "Received ${saved.name}", "$sender · ${formatBytes(c.size)}", true))
                    w.say("SAVED")
                } finally {
                    release()
                }
            }
        } finally {
            runCatching { catalog.flushCache() }
        }
    }
    override fun close() {
        stopped = true
        runCatching { listener.close() }
        runCatching { udp.close() }
        synchronized(clients) { clients.toList() }.forEach { runCatching { it.close() } }
    }
}

fun percent(n: Long,total: Long) = if (total == 0L) 100 else (100.0*n/total).toInt()
data class Outgoing(val item: Item,val content: Content)
data class Stats(val uploaded: Int,val bytes: Long,val present: Int)
fun sync(tree: Tree, batch: List<Outgoing>, link: Link, address: (String)->Unit = {},
         progress: (String)->Unit = {}, delivered: (Outgoing,String)->Unit = {_,_->},
         active: ()->Boolean = {true}, onWire: (Closeable?)->Unit = {},
         onProgress: (TransferProgress)->Unit = {},
         onEvent: (TransferEvent)->Unit = {}): Stats {
    val wire = try { connect(link.host,link.pin) } catch (_: Exception) {
        var connected: Wire? = null
        var failure: Exception = IOException("Hub is unreachable")
        for (host in discover(link.pin)) {
            try {
                connected = connect(host,link.pin).also { address(host) }
                break
            } catch (e: Exception) { failure = e }
        }
        connected ?: throw failure
    }
    onWire(wire)
    return try { wire.use { w ->
        w.out.writeUTF(MAGIC); w.out.writeUTF("SYNC"); w.say(link.token)
        check(w.reply() == "READY")
        val selection = w.input.readUTF()
        var sent = 0
        val bytes = java.util.concurrent.atomic.AtomicLong(0L)
        var present = 0
        var filesDone = 0
        var batchTotal = batch.sumOf { it.content.size }
        val batchConfirmed = java.util.concurrent.atomic.AtomicLong(0L)
        val meter = TransferMeter()
        val buf = ByteArray(BLOCK)
        for (file in batch) {
            check(active()) { "Paused" }
            val (item,c) = file
            w.out.writeUTF("FILE"); w.out.writeUTF(item.path); w.out.writeLong(c.size); w.say(c.sha)
            when (w.reply()) {
                "HAVE" -> {
                    present++
                    filesDone++
                    batchTotal = (batchTotal - c.size).coerceAtLeast(batchConfirmed.get())
                }
                "OFFSET" -> {
                    var pos = w.input.readLong()
                    require(pos in 0..c.size)
                    log.info("SYNC OFFSET "+pos+"/"+c.size+" "+item.path)
                    batchTotal = (batchTotal - pos).coerceAtLeast(batchConfirmed.get())
                    val fileStartBytes = bytes.get()
                    val fileStartNs = System.nanoTime()
                    if (pos < c.size) {
                        tree.open(item.node).use { src ->
                            var skip = pos
                            while (skip > 0) {
                                val n = src.skip(skip)
                                if (n <= 0L) { check(src.read() >= 0); skip-- } else skip -= n
                            }
                            // Chunk 1 stays synchronous so progress-throw tests stop cleanly at <= 1 chunk.
                            check(active()) { "Paused" }
                            val firstN = src.read(buf, 0, minOf(BLOCK.toLong(), c.size - pos).toInt())
                            check(firstN > 0) { "Source changed during transfer" }
                            w.out.writeInt(firstN); w.out.write(buf, 0, firstN); w.out.flush()
                            pos += firstN
                            check(w.input.readLong() == pos)
                            val totalBytes1 = bytes.addAndGet(firstN.toLong())
                            val batchConfirmed1 = batchConfirmed.addAndGet(firstN.toLong())
                            val rate1 = meter.record(totalBytes1)
                            val eta1 = if (rate1 >= 32 * 1024 && batchConfirmed1 >= 256 * 1024) (batchTotal - batchConfirmed1).coerceAtLeast(0L) / rate1 else -1L
                            progress("Sending "+item.path+" · "+percent(pos,c.size)+"%")
                            onProgress(TransferProgress(item.path, pos, c.size, batchConfirmed1, batchTotal, filesDone, batch.size, rate1, eta1, true))

                            if (pos < c.size) {
                                val window = Semaphore(PIPELINE_WINDOW)
                                val expectedAcks = LinkedBlockingQueue<Long>()
                                val ackError = AtomicReference<Throwable?>(null)
                                val aborting = java.util.concurrent.atomic.AtomicBoolean(false)
                                var confirmedFilePos = pos
                                val ackThread = thread(isDaemon = true, name = "lantern-ack") {
                                    try {
                                        while (true) {
                                            val expected = expectedAcks.take()
                                            if (expected < 0L) break
                                            val ack = w.input.readLong()
                                            check(ack == expected) { "Hub ack mismatch: expected $expected got $ack" }
                                            val delta = ack - confirmedFilePos
                                            confirmedFilePos = ack
                                            val totalNow = bytes.addAndGet(delta)
                                            val batchNow = batchConfirmed.addAndGet(delta)
                                            val rate = meter.record(totalNow)
                                            if (meter.shouldEmit(ack == c.size)) {
                                                val eta = if (rate >= 32 * 1024 && batchNow >= 256 * 1024) (batchTotal - batchNow).coerceAtLeast(0L) / rate else -1L
                                                progress("Sending "+item.path+" · "+percent(ack,c.size)+"%")
                                                onProgress(TransferProgress(item.path, ack, c.size, batchNow, batchTotal, filesDone, batch.size, rate, eta, true))
                                            }
                                            window.release()
                                        }
                                    } catch (t: Throwable) {
                                        if (!aborting.get()) ackError.compareAndSet(null, t)
                                        window.release(PIPELINE_WINDOW * 2)
                                    }
                                }
                                try {
                                    while (pos < c.size) {
                                        ackError.get()?.let { throw it }
                                        check(active()) { "Paused" }
                                        val n = src.read(buf, 0, minOf(BLOCK.toLong(), c.size - pos).toInt())
                                        check(n > 0) { "Source changed during transfer" }
                                        window.acquire()
                                        ackError.get()?.let { throw it }
                                        check(active()) { "Paused" }
                                        val nextPos = pos + n
                                        expectedAcks.put(nextPos)
                                        w.out.writeInt(n); w.out.write(buf, 0, n); w.out.flush()
                                        pos = nextPos
                                    }
                                    expectedAcks.put(-1L)
                                    ackThread.join()
                                    ackError.get()?.let { throw it }
                                } catch (t: Throwable) {
                                    aborting.set(true)
                                    expectedAcks.offer(-1L)
                                    runCatching { w.close() }
                                    ackThread.join()
                                    throw ackError.get() ?: t
                                }
                            }
                        }
                    }
                    check(w.reply() == "SAVED")
                    sent++
                    filesDone++
                    val sentForFile = bytes.get() - fileStartBytes
                    val elapsedNs = (System.nanoTime() - fileStartNs).coerceAtLeast(1L)
                    val fileRate = if (sentForFile > 0) (sentForFile * 1_000_000_000.0 / elapsedNs).toLong() else 0L
                    val detail = buildString {
                        append(formatBytes(sentForFile))
                        if (sentForFile < c.size) append(" (resumed of ${formatBytes(c.size)})")
                        if (fileRate > 0) append(" · ${formatSpeed(fileRate)}")
                    }
                    onEvent(TransferEvent(System.currentTimeMillis(), "Uploaded ${item.path}", detail, true))
                }
                else -> error("Unsupported Hub response")
            }
            delivered(file,selection)
        }
        w.say("DONE")
        val finalBytes = bytes.get()
        log.info("SYNC DONE uploaded="+sent+" bytes="+finalBytes+" present="+present)
        Stats(sent,finalBytes,present)
    } } finally {
        onWire(null)
        onProgress(TransferProgress(active = false))
    }
}



