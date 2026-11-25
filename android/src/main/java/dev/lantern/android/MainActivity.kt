package dev.lantern.android

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.lantern.core.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun qrBitmap(value: String): Bitmap {
    val m = QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,512,512)
    val pixels = IntArray(512*512) { if (m[it%512,it/512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels,512,512,Bitmap.Config.ARGB_8888)
}
class MainActivity: ComponentActivity() {
    private val engine by lazy { Session.engine(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(engine.enabled) startForegroundService(Intent(this,LinkService::class.java))
        setContent { MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF086650),secondaryContainer=Color(0xFFD4EDE2))) { Screen() } }
    }
    private fun readQr(uri: Uri): String {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver,uri)) { decoder,info,_ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize((maxOf(info.size.width,info.size.height)/1600).coerceAtLeast(1))
        }
        val pixels = IntArray(bitmap.width*bitmap.height)
        bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,bitmap.height,pixels))),
            mapOf(DecodeHintType.TRY_HARDER to true)).text
    }
    @Composable private fun Screen() {
        val scope = rememberCoroutineScope()
        var tick by remember { mutableIntStateOf(0) }
        var qr by remember { mutableStateOf("") }
        var copied by remember { mutableStateOf(false) }
        var linkInput by remember { mutableStateOf("") }
        var error by remember { mutableStateOf("") }
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
        fun runJob(action: ()->Unit) { scope.launch { try { withContext(Dispatchers.IO) { action() }; error="" } catch(e: Exception) { error=e.message ?: "Could not read QR or folder" }; tick++ } }
        val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if(uri != null) runJob {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if(engine.receiving) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                contentResolver.takePersistableUriPermission(uri,flags); engine.choose(uri.toString())
            }
        }
        val scan = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let { runJob { engine.pairWith(it) } } }
        val image = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { runJob { engine.pairWith(readQr(it)) } } }
        val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) { while(true) { delay(250); tick++ } }
        LaunchedEffect(qr) { if(qr.isNotEmpty()) { delay(120000); qr="" } }
        LaunchedEffect(engine.receiving, engine.paired) {
            if (!engine.receiving && !engine.paired) {
                while (true) {
                    discovering = true
                    nearby = try { withContext(Dispatchers.IO) { discoverNearby() } } catch (ce: CancellationException) { throw ce } catch (_: Exception) { emptyList() }
                    discovering = false
                    delay(5000)
                }
            } else discovering = false
        }
        tick
        engine.requestTick
        val requests = if (engine.receiving) engine.pairRequests else emptyList()
        val progress = engine.transfer
        val history = engine.history
        val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
        Surface(modifier=Modifier.fillMaxSize(),color=Color(0xFFF5F7F2)) {
            Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("Lantern",style=MaterialTheme.typography.headlineLarge)
                Text("Local photo and video transfer",style=MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf(false to "Send",true to "Receive as Hub").forEach { (hub,label) ->
                        FilterChip(selected=engine.receiving==hub,onClick={
                            if (engine.receiving != hub) { engine.mode(hub); stopService(Intent(this@MainActivity,LinkService::class.java)); qr=""; error=""; tick++ }
                        },label={ Text(label) })
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(if(engine.receiving) "Destination folder" else "Watched folder",style=MaterialTheme.typography.titleMedium)
                        Text(if(engine.folder.isEmpty()) "No folder selected" else runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(engine.folder)) }.getOrDefault(engine.folder))
                        OutlinedButton(onClick={ choose.launch(null) }) { Text("Choose folder") }
                    }
                }
                if(!engine.receiving) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Paired Hub · "+engine.hubName.ifEmpty { "None" },style=MaterialTheme.typography.titleMedium)
                                    if (engine.paired) Text("Saved permanently · auto-reconnects when scanning",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (engine.paired) {
                                    OutlinedButton(onClick={
                                        engine.disconnectHub()
                                        stopService(Intent(this@MainActivity,LinkService::class.java))
                                        nearby = emptyList(); pairingStatus = ""; error = ""; tick++
                                    }) { Text("Disconnect") }
                                }
                            }
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick={ scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setOrientationLocked(false).setBeepEnabled(false)) }) { Text("Scan QR") }
                                OutlinedButton(onClick={ image.launch(arrayOf("image/*")) }) { Text("Open QR image") }
                            }
                            if (!engine.paired) {
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value=linkInput,
                                        onValueChange={ linkInput=it },
                                        singleLine=true,
                                        placeholder={ Text("6-digit code, code@IP, or link") },
                                        modifier=Modifier.weight(1f)
                                    )
                                    Button(enabled=linkInput.isNotBlank(),onClick={
                                        val code = linkInput.trim()
                                        runJob {
                                            engine.pairWith(code)
                                            scope.launch { linkInput = "" }
                                        }
                                    }) { Text("Connect") }
                                }
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                    Text("Nearby Hubs",style=MaterialTheme.typography.titleSmall,modifier=Modifier.weight(1f))
                                    TextButton(enabled=!discovering && requestingPin.isEmpty(),onClick={ refreshNearby() }) {
                                        Text(if(discovering) "Searching…" else "Refresh")
                                    }
                                }
                                if (pairingStatus.isNotEmpty()) Text(pairingStatus,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
                                if (nearby.isEmpty()) {
                                    Text(if (discovering) "Searching local Wi-Fi for active Hubs…" else "No active Hub found nearby. Start Receive as Hub on the target device, then tap Refresh.",
                                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    for (hub in nearby) {
                                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                            Column(Modifier.weight(1f)) {
                                                Text(hub.name,style=MaterialTheme.typography.bodyLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                                                Text("PIN ${hub.pin} · ${hub.hosts.firstOrNull().orEmpty()}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Button(enabled=requestingPin.isEmpty(),onClick={
                                                requestingPin = hub.pin
                                                pairingStatus = "Sending request to ${hub.name}…"
                                                error = ""
                                                scope.launch {
                                                    try {
                                                        withContext(Dispatchers.IO) {
                                                            engine.requestPairWith(hub) { code ->
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
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text("Incoming connection request",style=MaterialTheme.typography.titleMedium)
                                Text("${req.device} wants to connect permanently to this Hub")
                                Text("Verification code · ${req.code} · ${req.address}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Button(onClick={ engine.respondPair(req.id,true); tick++ }) { Text("Accept") }
                                    OutlinedButton(onClick={ engine.respondPair(req.id,false); tick++ }) { Text("Decline") }
                                }
                            }
                        }
                    }
                }
                Text(engine.message,style=MaterialTheme.typography.titleMedium)
                if(!engine.receiving) { Text("Pending · "+engine.pending); if(engine.lastScan.isNotEmpty()) Text(engine.lastScan) }
                if(progress.active) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(progress.file,style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                            val frac = if (progress.fileTotal > 0L) (progress.fileBytes.toFloat() / progress.fileTotal.toFloat()).coerceIn(0f, 1f) else 1f
                            LinearProgressIndicator(progress={ frac },modifier=Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                Text("${percent(progress.fileBytes, progress.fileTotal)}% · ${formatBytes(progress.fileBytes)} / ${formatBytes(progress.fileTotal)}",style=MaterialTheme.typography.bodySmall)
                                Text("${formatSpeed(progress.bytesPerSec)} · ETA ${formatEta(progress.etaSeconds)}",style=MaterialTheme.typography.bodySmall)
                            }
                            if (progress.batchTotal > 0L && progress.filesTotal > 1) {
                                val batchFrac = (progress.batchBytes.toFloat() / progress.batchTotal.toFloat()).coerceIn(0f, 1f)
                                LinearProgressIndicator(progress={ batchFrac },modifier=Modifier.fillMaxWidth())
                                Text("Queue ${progress.filesDone}/${progress.filesTotal} · ${formatBytes(progress.batchBytes)} / ${formatBytes(progress.batchTotal)}",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(enabled=engine.folder.isNotEmpty(),onClick={
                        val on = !engine.enabled
                        engine.settings.put("enabled" to if(on) "yes" else "")
                        if(on) {
                            if(Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                            startForegroundService(Intent(this@MainActivity,LinkService::class.java))
                        } else { engine.enable(false); stopService(Intent(this@MainActivity,LinkService::class.java)); qr="" }
                        tick++
                    }) { Text(if(engine.enabled) "Pause" else "Start") }
                    if(!engine.receiving && engine.paired && engine.folder.isNotEmpty()) OutlinedButton(onClick={
                        if(!engine.enabled) {
                            engine.settings.put("enabled" to "yes")
                            if(Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                            startForegroundService(Intent(this@MainActivity,LinkService::class.java))
                        } else engine.scanNow()
                        tick++
                    }) { Text("Scan now") }
                    if(engine.receiving && engine.enabled) OutlinedButton(onClick={
                        try { qr=engine.identity.invite(); copied=false; error="" } catch(e: Exception) { error=e.message.orEmpty() }
                    }) { Text("Pair a device") }
                }
                if(engine.receiving && qr.isNotEmpty()) {
                    val shortCode = engine.identity.shortInviteCode()
                    val lanIp = localLanIps().firstOrNull()
                    if (shortCode.isNotEmpty()) {
                        Text("Pairing code · $shortCode",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.align(Alignment.CenterHorizontally))
                        if (lanIp != null) {
                            Text("Wi-Fi IP · $lanIp · or type $shortCode@$lanIp",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.align(Alignment.CenterHorizontally))
                        }
                    }
                    Image(remember(qr) { qrBitmap(qr).asImageBitmap() },"Pairing QR",Modifier.size(250.dp).align(Alignment.CenterHorizontally))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterHorizontally)) {
                        OutlinedButton(onClick={
                            getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Lantern pairing link", qr))
                            copied=true
                        }) {
                            Text(if(copied) "Link copied" else "Copy pairing link")
                        }
                        OutlinedButton(onClick={
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, qr)
                            startActivity(Intent.createChooser(send, "Share Lantern pairing link"))
                        }) { Text("Share link") }
                    }
                    Text("Enter 6-digit code or scan within two minutes")
                }
                if(engine.receiving) for((key,label) in engine.peers) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(label,Modifier.weight(1f)); TextButton(onClick={ engine.settings.put(key to ""); tick++ }) { Text("Revoke") }
                    }
                }
                if(history.isNotEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Text("Transfer history",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                                TextButton(onClick={ engine.clearHistory(); tick++ }) { Text("Clear") }
                            }
                            for(ev in history.take(15)) {
                                Column(Modifier.padding(vertical=2.dp)) {
                                    Text("${timeFmt.format(Date(ev.timestamp))} · ${ev.title}",
                                        style=MaterialTheme.typography.bodyMedium,
                                        color=if(ev.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                                        maxLines=1,overflow=TextOverflow.Ellipsis)
                                    if(ev.detail.isNotEmpty()) Text(ev.detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
