package dev.lantern.windows

import dev.lantern.core.*
import org.junit.*
import org.junit.rules.TemporaryFolder
import java.io.*
import kotlin.random.Random

class TransferTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var src: File
    private lateinit var dst: File
    private lateinit var source: Tree
    private lateinit var destination: Destination
    private lateinit var id: Identity
    private lateinit var link: Link
    private lateinit var hub: Hub
    private lateinit var state: File
    @Before fun setUp() {
        src=temp.newFolder("source"); dst=temp.newFolder("hub"); state=temp.newFolder("private")
        source=DiskTree(src.path); destination=Destination.select(DiskTree(dst.path))
        startHub()
        val ticket=id.invite()
        link=pair(local(ticket),"test sender")
        Assert.assertTrue(runCatching { pair(local(ticket),"second") }.isFailure)
        link=link.copy(host="127.0.0.1")
    }
    private fun local(ticket: String)=ticket.substringBeforeLast('|')+"|127.0.0.1"
    private fun startHub() {
        id=Identity(Book(File(state,"settings")),"Test Hub")
        hub=Hub(id,destination,Book(File(state,"cache")),Book(File(state,"partials"))) {}
    }
    @After fun close() { hub.close() }
    private fun batch()=source.walk().filter { it.node.media() }.map { Outgoing(it,Content(it.node.size,digest(source.open(it.node)))) }
    private fun send()=sync(source,batch(),link)
    private fun localFile(root: File,name: String,data: ByteArray): File = File(root,name).also { it.parentFile.mkdirs(); it.writeBytes(data) }
    private fun copies()=dst.walk().filter { it.isFile && !it.name.startsWith(".lantern-") }.toList()
    @Test fun hundredPhotosAndDestinationSwitch() {
        for(i in 1..100) {
            val bytes=Random(i).nextBytes(140)
            localFile(src,"DCIM/nested/photo-"+i+".jpg",bytes)
            if(i<=80) localFile(dst,"manual/archive/renamed-"+i+".dat",bytes)
        }
        Assert.assertEquals(Stats(20,2800,80),send())
        Assert.assertEquals(Stats(0,0,100),send())
        Assert.assertEquals(100,copies().size)
        hub.close(); startHub()
        Assert.assertEquals(Stats(0,0,100),send())
        hub.close()
        dst=temp.newFolder("ssd")
        destination=Destination.select(DiskTree(dst.path))
        startHub()
        Assert.assertEquals(Stats(100,14000,0),send())
        Assert.assertEquals(100,copies().size)
    }
    @Test fun sameNameAndManualReplacementWithSameTimestamp() {
        val file=localFile(src,"Album/photo.jpg",byteArrayOf(1,2))
        Assert.assertEquals(1,send().uploaded)
        val old=copies().single()
        val stamp=old.lastModified()
        old.writeBytes(byteArrayOf(3,4)); old.setLastModified(stamp)
        Assert.assertEquals(1,send().uploaded)
        Assert.assertEquals(2,copies().size)
        Assert.assertTrue(copies().any { it.name=="photo (1).jpg" && it.readBytes().contentEquals(file.readBytes()) })
        file.delete()
        Assert.assertEquals(Stats(0,0,0),send())
        Assert.assertEquals(2,copies().size)
    }
    @Test fun interruptedVideoRestartsAtConfirmedOffset() {
        val video=localFile(src,"Camera/long.mp4",Random(42).nextBytes(BLOCK*6+17))
        Assert.assertTrue(runCatching { sync(source,batch(),link,progress={ throw IOException("Simulated Wi-Fi loss") }) }.isFailure)
        hub.close()
        val part=dst.walk().single { it.name.endsWith(".part") }
        Assert.assertEquals(BLOCK.toLong(),part.length())
        Assert.assertTrue(copies().isEmpty())
        startHub()
        val result=send()
        Assert.assertEquals(video.length()-BLOCK,result.bytes)
        Assert.assertEquals(digest(video.inputStream()),digest(copies().single().inputStream()))
        Assert.assertEquals(Stats(0,0,1),send())
    }
    @Test fun revokedCredentialAndWrongHubPin() {
        id.book.put(id.book.entries("peer.").keys.single() to "")
        Assert.assertTrue(runCatching { send() }.exceptionOrNull()?.message.orEmpty().contains("revoked"))
        val code=local(id.invite()).replace(id.pin,"0".repeat(64))
        Assert.assertTrue(runCatching { pair(code,"wrong") }.isFailure)
        Assert.assertTrue(id.book.entries("peer.").isEmpty())
    }
    @Test fun fullStorageThenVolumeRemovalDuringWrite() {
        val video=localFile(src,"new.mp4",Random(9).nextBytes(BLOCK*4))
        hub.close()
        val normal=destination
        val limited=object: Tree by normal.tree {
            override fun append(n: Node,offset: Long): OutputStream = object: FilterOutputStream(normal.tree.append(n,offset)) {
                var used=offset
                override fun write(data: ByteArray,off: Int,len: Int) {
                    if(used+len>BLOCK) throw IOException("No space left on device")
                    out.write(data,off,len); used+=len
                }
            }
        }
        destination=Destination(limited,normal.marker); startHub()
        Assert.assertTrue(runCatching { send() }.isFailure)
        Assert.assertTrue(copies().isEmpty())
        hub.close()
        destination=normal; startHub()
        val marker=File(dst,normal.marker)
        Assert.assertTrue(runCatching { sync(source,batch(),link,progress={ marker.delete() }) }.isFailure)
        Assert.assertTrue(copies().isEmpty())
        Assert.assertTrue(runCatching { send() }.exceptionOrNull()?.message.orEmpty().contains("Waiting for selected SSD"))
        marker.createNewFile()
        Assert.assertEquals(1,send().uploaded)
        Assert.assertEquals(digest(video.inputStream()),digest(copies().single().inputStream()))
    }
    @Test fun permissionFailureKeepsSourceAndRecovers() {
        localFile(src,"f.jpg",byteArrayOf(7))
        hub.close()
        val normal=destination
        destination=Destination(object: Tree by normal.tree {
            override fun root(): Node=throw SecurityException("Folder permission lost")
        },normal.marker)
        startHub()
        Assert.assertTrue(runCatching { send() }.isFailure)
        Assert.assertTrue(File(src,"f.jpg").exists())
        hub.close(); destination=normal; startHub()
        Assert.assertEquals(1,send().uploaded)
    }
}
