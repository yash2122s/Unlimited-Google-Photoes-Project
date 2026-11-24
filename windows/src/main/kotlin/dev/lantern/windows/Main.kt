package dev.lantern.windows

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.github.sarxos.webcam.Webcam
import com.google.zxing.*
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import dev.lantern.core.*
import kotlinx.coroutines.*
import java.awt.*
import java.awt.image.BufferedImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

private fun readCode(image: BufferedImage): String = MultiFormatReader().decode(
    BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image))), mapOf(DecodeHintType.TRY_HARDER to true)).text

private fun choose(owner: java.awt.Window, folder: Boolean): File? {
    val picker = JFileChooser().apply {
        if(folder) fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        else fileFilter = FileNameExtensionFilter("QR image","png","jpg","jpeg","bmp","gif")
    }
    return if(picker.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) picker.selectedFile else null
}
private fun webcamCode(): String {
    val cam = Webcam.getDefault() ?: error("No webcam found. Select a saved QR image.")
    try {
        cam.viewSize = cam.viewSizes.maxBy { it.width*it.height }
        check(cam.open()) { "Unable to open webcam" }
        val end = System.nanoTime()+60_000_000_000L
        while(System.nanoTime() < end) {
            cam.image?.let { try { return readCode(it) } catch (_: NotFoundException) {} }
            Thread.sleep(130)
        }
        error("No QR detected within one minute")
    } finally { cam.close() }
}
private fun icon(): BufferedImage = BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB).apply {
    createGraphics().also { g ->
        g.color = java.awt.Color(12,88,65); g.fillRoundRect(0,0,32,32,9,9)
        g.color = java.awt.Color.WHITE; g.fillRect(8,7,5,18); g.fillRect(8,21,16,4); g.dispose()
    }
}
fun main() {
    val home = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),"Lantern")
    home.mkdirs()
    val lockFile = java.io.RandomAccessFile(File(home,"running.lock"),"rw")
    val lock = lockFile.channel.tryLock() ?: return
    val e = Engine(home,System.getenv("COMPUTERNAME") ?: "Windows",{ DiskTree(it) },{ addresses().isNotEmpty() })
    if(e.enabled) e.start()
    try { application {
        var visible by remember { mutableStateOf(true) }
        val state = rememberWindowState(width=560.dp,height=740.dp)
        var windowRef by remember { mutableStateOf<java.awt.Window?>(null) }
        fun reopen() { visible=true; state.isMinimized=false; windowRef?.toFront() }
        val supported = SystemTray.isSupported()
        val launcher = remember { icon() }
        DisposableEffect(Unit) {
            val menu = PopupMenu()
            val open = MenuItem("Open Lantern").apply { addActionListener { reopen() } }
            val exit = MenuItem("Exit").apply { addActionListener { e.stop(); exitApplication() } }
            menu.add(open); menu.add(exit)
            val tray = if(supported) TrayIcon(launcher,"Lantern",menu).apply {
                isImageAutoSize=true; addActionListener { reopen() }; SystemTray.getSystemTray().add(this)
            } else null
            onDispose { if(tray!=null) SystemTray.getSystemTray().remove(tray) }
        }
        Window(visible=visible,onCloseRequest={ if(supported) visible=false else { e.stop(); exitApplication() } },
            title="Lantern",state=state) {
            windowRef = window
            MaterialTheme(colors=lightColors(primary=Color(0xFF086650))) { Desktop(e,window) }
        }
    } } finally { e.stop(); lock.release(); lockFile.close() }
}
@Composable internal fun Desktop(e: Engine, owner: java.awt.Window) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var tick by remember { mutableIntStateOf(0) }
    var qr by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var linkInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var nearby by remember { mutableStateOf<List<NearbyHub>>(emptyList()) }
    var discovering by remember { mutableStateOf(false) }
    var requestingPin by remember { mutableStateOf("") }
    var pairingStatus by remember { mutableStateOf("") }
    fun refreshNearby() {
        if (discovering) return
        scope.launch {
            discovering = true
            nearby = try { withContext(Dispatchers.IO) { discoverNearby() } } catch (ce: CancellationException) { throw ce } catch (_: Exception) { emptyList() }
            discovering = false
        }
    }
    fun work(task: ()->Unit) { scope.launch { try { withContext(Dispatchers.IO) { task() }; error="" } catch(ex: Exception) { error=ex.message ?: "Unable to complete operation" }; tick++ } }
    LaunchedEffect(Unit) { while(true) { delay(250); tick++ } }
    LaunchedEffect(qr) { if(qr.isNotEmpty()) { delay(120000); qr="" } }
    LaunchedEffect(e.receiving, e.paired) {
        if (!e.receiving && !e.paired) {
            while (true) {
                discovering = true
                nearby = try { withContext(Dispatchers.IO) { discoverNearby() } } catch (ce: CancellationException) { throw ce } catch (_: Exception) { emptyList() }
                discovering = false
                delay(5000)
            }
        } else discovering = false
    }
    tick
    e.requestTick
    val requests = if (e.receiving) e.pairRequests else emptyList()
    val progress = e.transfer
    val history = e.history
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Surface(Modifier.fillMaxSize(),color=Color(0xFFF5F7F2)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Lantern",style=MaterialTheme.typography.h4)
            Text("Local photo and video transfer",style=MaterialTheme.typography.body2)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(false to "Send",true to "Receive as Hub").forEach { (hub,label) ->
                    OutlinedButton(onClick={ e.mode(hub); qr=""; error=""; nearby=emptyList(); pairingStatus=""; requestingPin=""; tick++ },enabled=e.receiving!=hub) { Text(label) }
                }
            }
            Card(Modifier.fillMaxWidth(),elevation=1.dp) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(if(e.receiving) "Destination folder" else "Watched folder",style=MaterialTheme.typography.subtitle1)
                    Text(e.folder.ifEmpty { "No folder selected" },style=MaterialTheme.typography.body2)
                    OutlinedButton(onClick={ choose(owner,true)?.let { file -> work { e.choose(file.canonicalPath) } } }) { Text("Choose folder") }
                }
            }
            Text(e.message,style=MaterialTheme.typography.subtitle1)
            if(!e.receiving) { Text("Pending · "+e.pending); if(e.lastScan.isNotEmpty()) Text(e.lastScan) }
            if(progress.active) {
                Card(Modifier.fillMaxWidth(),elevation=1.dp) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(progress.file,style=MaterialTheme.typography.subtitle2,maxLines=1,overflow=TextOverflow.Ellipsis)
                        val frac = if (progress.fileTotal > 0L) (progress.fileBytes.toFloat() / progress.fileTotal.toFloat()).coerceIn(0f, 1f) else 1f
                        LinearProgressIndicator(progress=frac,modifier=Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("${percent(progress.fileBytes, progress.fileTotal)}% · ${formatBytes(progress.fileBytes)} / ${formatBytes(progress.fileTotal)}",style=MaterialTheme.typography.caption)
                            Text("${formatSpeed(progress.bytesPerSec)} · ETA ${formatEta(progress.etaSeconds)}",style=MaterialTheme.typography.caption)
                        }
                        if (progress.batchTotal > 0L && progress.filesTotal > 1) {
                            val batchFrac = (progress.batchBytes.toFloat() / progress.batchTotal.toFloat()).coerceIn(0f, 1f)
                            LinearProgressIndicator(progress=batchFrac,modifier=Modifier.fillMaxWidth())
                            Text("Queue ${progress.filesDone}/${progress.filesTotal} · ${formatBytes(progress.batchBytes)} / ${formatBytes(progress.batchTotal)}",style=MaterialTheme.typography.caption)
                        }
                    }
                }
            }
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colors.error)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(enabled=e.folder.isNotEmpty(),onClick={ e.enable(!e.enabled); qr=""; tick++ }) { Text(if(e.enabled) "Pause" else "Start") }
                if(!e.receiving && e.paired && e.folder.isNotEmpty()) OutlinedButton(onClick={ e.scanNow(); tick++ }) { Text("Scan now") }
                if(e.receiving && e.enabled) OutlinedButton(onClick={ try { qr=e.identity.invite() } catch(ex: Exception) { error=ex.message.orEmpty() } }) { Text("Pair a device") }
            }
            if(!e.receiving) {
                Card(Modifier.fillMaxWidth(),elevation=1.dp) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text("Paired Hub · " + if (e.paired) "${e.hubName} (saved)" else "None",style=MaterialTheme.typography.subtitle1,modifier=Modifier.weight(1f))
                            if (e.paired) {
                                OutlinedButton(onClick={
                                    e.disconnectHub()
                                    nearby = emptyList(); pairingStatus = ""; error = ""; tick++
                                }) { Text("Disconnect") }
                            }
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled=!scanning,onClick={
                                scanning=true
                                scope.launch { try {
                                    withContext(Dispatchers.IO) { e.pairWith(webcamCode()) }; error=""
                                } catch(ex: Exception) { error=ex.message ?: "Camera unavailable" } finally { scanning=false } }
                            }) { Text(if(scanning) "Point webcam at QR…" else "Scan with webcam") }
                            OutlinedButton(onClick={ choose(owner,false)?.let { file -> work { e.pairWith(readCode(ImageIO.read(file) ?: error("Unsupported image"))) } } }) { Text("Open QR image") }
                        }
                        if (!e.paired) {
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value=linkInput,
                                    onValueChange={ linkInput=it },
                                    singleLine=true,
                                    placeholder={ Text("6-digit code or pairing link") },
                                    modifier=Modifier.weight(1f)
                                )
                                Button(enabled=linkInput.isNotBlank(),onClick={
                                    val code = linkInput.trim()
                                    work {
                                        e.pairWith(code)
                                        scope.launch { linkInput = "" }
                                    }
                                }) { Text("Connect") }
                            }
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Text("Nearby Hubs",style=MaterialTheme.typography.subtitle2,modifier=Modifier.weight(1f))
                                TextButton(enabled=!discovering && requestingPin.isEmpty(),onClick={ refreshNearby() }) {
                                    Text(if(discovering) "Searching…" else "Refresh")
                                }
                            }
                            if (pairingStatus.isNotEmpty()) Text(pairingStatus,style=MaterialTheme.typography.body2,color=MaterialTheme.colors.primary)
                            if (nearby.isEmpty()) {
                                Text(if (discovering) "Searching local Wi-Fi for active Hubs…" else "No active Hub found nearby. Start Receive as Hub on Android, then click Refresh.",
                                    style=MaterialTheme.typography.caption,color=Color.DarkGray)
                            } else {
                                for (hub in nearby) {
                                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                        Column(Modifier.weight(1f)) {
                                            Text(hub.name,style=MaterialTheme.typography.body1,maxLines=1,overflow=TextOverflow.Ellipsis)
                                            Text("PIN ${hub.pin} · ${hub.hosts.firstOrNull().orEmpty()}",style=MaterialTheme.typography.caption,color=Color.DarkGray)
                                        }
                                        Button(enabled=requestingPin.isEmpty(),onClick={
                                            requestingPin = hub.pin
                                            pairingStatus = "Sending request to ${hub.name}…"
                                            error = ""
                                            scope.launch {
                                                try {
                                                    withContext(Dispatchers.IO) {
                                                        e.requestPairWith(hub) { code ->
                                                            scope.launch { pairingStatus = "Accept on ${hub.name} · Code $code" }
                                                        }
                                                    }
                                                    pairingStatus = ""
                                                } catch (ce: CancellationException) {
                                                    throw ce
                                                } catch (ex: Exception) {
                                                    pairingStatus = ""
                                                    error = ex.message ?: "Connection failed"
                                                } finally {
                                                    requestingPin = ""
                                                    tick++
                                                }
                                            }
                                        }) { Text(if(requestingPin == hub.pin) "Waiting…" else "Connect") }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                for (req in requests) {
                    Card(Modifier.fillMaxWidth(),elevation=1.dp) {
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text("Incoming connection request",style=MaterialTheme.typography.subtitle1)
                            Text("${req.device} wants to connect permanently to this Hub")
                            Text("Verification code · ${req.code} · ${req.address}",style=MaterialTheme.typography.caption,color=Color.DarkGray)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Button(onClick={ e.respondPair(req.id,true); tick++ }) { Text("Accept") }
                                OutlinedButton(onClick={ e.respondPair(req.id,false); tick++ }) { Text("Decline") }
                            }
                        }
                    }
                }
            }
            if(e.receiving && qr.isNotEmpty()) {
                val shortCode = e.identity.shortInviteCode()
                if (shortCode.isNotEmpty()) {
                    Text("Pairing code · $shortCode",style=MaterialTheme.typography.h5,modifier=Modifier.align(Alignment.CenterHorizontally))
                }
                val image=remember(qr) { MatrixToImageWriter.toBufferedImage(QRCodeWriter().encode(qr,BarcodeFormat.QR_CODE,480,480)).toComposeImageBitmap() }
                Image(image,"Pairing QR",Modifier.size(230.dp).align(Alignment.CenterHorizontally))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) {
                    OutlinedButton(onClick={ clipboard.setText(AnnotatedString(qr)); copied=true }) {
                        Text(if(copied) "Link copied" else "Copy pairing link")
                    }
                }
            }
            if(e.receiving) for((key,label) in e.peers) Row(verticalAlignment=Alignment.CenterVertically) {
                Text(label,Modifier.weight(1f)); TextButton(onClick={ e.settings.put(key to ""); tick++ }) { Text("Revoke") }
            }
            if(history.isNotEmpty()) {
                Card(Modifier.fillMaxWidth(),elevation=1.dp) {
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text("Transfer history",style=MaterialTheme.typography.subtitle1,modifier=Modifier.weight(1f))
                            TextButton(onClick={ e.clearHistory(); tick++ }) { Text("Clear") }
                        }
                        for(ev in history.take(15)) {
                            Column(Modifier.padding(vertical=2.dp)) {
                                Text("${timeFmt.format(Date(ev.timestamp))} · ${ev.title}",
                                    style=MaterialTheme.typography.body2,
                                    color=if(ev.ok) Color.Unspecified else MaterialTheme.colors.error,
                                    maxLines=1,overflow=TextOverflow.Ellipsis)
                                if(ev.detail.isNotEmpty()) Text(ev.detail,style=MaterialTheme.typography.caption,color=Color.DarkGray)
                            }
                        }
                    }
                }
            }
        }
    }
}


