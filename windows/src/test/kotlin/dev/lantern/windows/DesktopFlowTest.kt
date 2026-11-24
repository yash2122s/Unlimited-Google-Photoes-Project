package dev.lantern.windows

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import dev.lantern.core.*
import org.junit.*
import org.junit.rules.TemporaryFolder
import java.awt.Container
import java.awt.Window
import java.io.File
import javax.swing.*

class DesktopFlowTest {
    @get:Rule val ui=createComposeRule()
    @get:Rule val temp=TemporaryFolder()
    /** Supply a screenshot of a fresh physical Hub QR for the hardware variant. */
    @Test fun folderChooserQrImageAndAutomaticTransfer() {
        val home=temp.newFolder("private")
        val source=temp.newFolder("Camera")
        val sourceFile=File(source,"Windows-Lantern-QA.png")
        val bitmap=java.awt.image.BufferedImage(32,32,java.awt.image.BufferedImage.TYPE_INT_RGB)
        bitmap.setRGB(3,3,0x008849)
        javax.imageio.ImageIO.write(bitmap,"png",sourceFile)
        val e=Engine(home,"Windows UI test",{ DiskTree(it) },{ true })
        val remote=System.getenv("LANTERN_TEST_QR")?.let(::File)
        val owner=JFrame()
        var hub: Hub?=null
        try {
            val code=remote ?: run {
                val dest=Destination.select(DiskTree(temp.newFolder("destination").path))
                val id=Identity(Book(File(home,"hub")),"UI test Hub")
                hub=Hub(id,dest,Book(File(home,"cache")),Book(File(home,"parts"))) {}
                val qr=id.invite().substringBeforeLast('|')+"|127.0.0.1"
                File(home,"code.png").also {
                    MatrixToImageWriter.writeToPath(QRCodeWriter().encode(qr,BarcodeFormat.QR_CODE,480,480),"PNG",it.toPath())
                }
            }
            ui.setContent { MaterialTheme { Desktop(e,owner) } }
            select("Choose folder",source)
            ui.waitUntil(15000) { e.folder==source.canonicalPath }
            select("Open QR image",code)
            ui.waitUntil(30000) { e.hubName.isNotEmpty() }
            ui.onNodeWithText("Start").performClick()
            ui.waitUntil(120000) { e.lastScan.contains("Uploaded 1") }
            val first=e.lastScan
            ui.waitUntil(90000) { e.lastScan.contains("Uploaded 0 · 0 bytes · already present 1") }
            Assert.assertEquals(0,e.pending)
            ui.onNodeWithText("Transfer history").assertIsDisplayed()
            // Reopening/calling start() while running must not spawn duplicate monitor workers.
            e.start()
            Assert.assertEquals(1, Thread.getAllStackTraces().keys.count { it.name == "lantern-monitor" && it.isAlive })
            println("Desktop UI first scan: "+first+"; repeat: "+e.lastScan)
            System.getenv("LANTERN_TEST_REPORT")?.let { File(it).writeText("Hub "+e.hubName+"\n"+first+"\n"+e.lastScan+"\nSHA-256 "+digest(sourceFile.inputStream())) }
        } finally {
            e.enable(false); hub?.close(); javax.swing.SwingUtilities.invokeLater { owner.dispose() }
        }
    }
    private fun select(label: String,file: File) {
        // Let the production UI create its modal Swing picker and approve the chosen entry there.
        // A Swing timer still runs inside the modal event loop.
        val timer=Timer(100,null)
        timer.addActionListener {
            val picker=Window.getWindows().filter { it.isShowing }.firstNotNullOfOrNull { finder(it) }
            if(picker!=null) { timer.stop(); picker.selectedFile=file; picker.approveSelection() }
        }
        timer.start()
        try { ui.onNodeWithText(label).performClick() } finally { if(!timer.isRunning) timer.stop() }
    }
    private fun finder(c: Container): JFileChooser? {
        if(c is JFileChooser) return c
        return c.components.filterIsInstance<Container>().firstNotNullOfOrNull(::finder)
    }
}
