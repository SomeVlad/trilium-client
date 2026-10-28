package ru.vlad.triliumnotes.capture

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class CaptureInboxTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var source: File
    private var opens = 0
    private var failSecondStream = false
    private val uri = Uri.parse("content://synthetic.capture.fixture/document/1")
    @Before fun setUp() {
        directory = Files.createTempDirectory("capture-inbox-test").toFile()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = directory
            override fun getApplicationContext(): Context = this
        }
        source = File(directory, "fixture.png").apply { writeBytes(byteArrayOf(0, 1, 13, 10, -1)) }
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("synthetic.png")) }
            override fun getType(uri: Uri) = "image/png"
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                opens++
                if (failSecondStream && uri.lastPathSegment == "2") throw java.io.FileNotFoundException("Synthetic provider failure")
                return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        // Match Android provider startup before exercising the real ContentResolver transport.
        // Registering an unattached provider leaves mContext null on modern API levels.
        provider.attachInfo(context, ProviderInfo().apply {
            authority = uri.authority
            exported = true
            grantUriPermissions = true
        })
        assertSame(context, provider.context)
        ShadowContentResolver.registerProviderInternal(uri.authority, provider)
    }
    @After fun tearDown() { directory.deleteRecursively() }
    private fun id() = UUID.randomUUID().toString()
    private fun share() = Intent(Intent.ACTION_SEND).setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    @Test fun binaryAndTextSurviveSourceLossAndInboxRecreation() {
        val token = id()
        val inbox = CaptureInbox(context)
        val capture = inbox.receive(share().putExtra(Intent.EXTRA_TEXT, "Synthetic caption https://example.org/")
            .putExtra(Intent.EXTRA_SUBJECT, "Fixture title"), token)!!
        source.delete()
        val restored = CaptureInbox(context)
        assertEquals(capture, restored.entries.value.single())
        assertEquals("Synthetic caption https://example.org/", capture.text)
        assertEquals("synthetic.png", capture.attachments.single().title)
        assertEquals("image/png", capture.attachments.single().mime)
        assertArrayEquals(byteArrayOf(0, 1, 13, 10, -1), restored.attachmentFile(token, "0").readBytes())
        // The restored activity can replay its delivery even after the transient provider disappears.
        assertEquals(capture, restored.receive(share(), token))
        assertEquals(1, opens)
    }
    @Test fun clipAndExtraStreamDeduplicateButSeparateSharesRemainSeparate() {
        val intent = share().apply { clipData = ClipData.newUri(context.contentResolver, "fixture", uri) }
        val inbox = CaptureInbox(context)
        val token = id()
        assertEquals(1, inbox.receive(intent, token)!!.attachments.size)
        inbox.receive(intent, token)
        assertEquals(1, opens)
        inbox.receive(intent, id())
        assertEquals(2, opens)
        assertEquals(2, inbox.entries.value.size)
    }
    @Test fun multipleStreamsCopiedBeforeProviderAccessEnds() {
        val second = Uri.parse("content://synthetic.capture.fixture/document/2")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri, second))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val inbox = CaptureInbox(context)
        val capture = inbox.receive(intent, id())!!
        source.delete()
        assertEquals(2, capture.attachments.size)
        capture.attachments.forEach { assertEquals(5L, inbox.attachmentFile(capture.id, it.id).length()) }
    }
    @Test fun accountAndDeliveryCheckpointsAreDurableAndCannotSwitchAccount() {
        val inbox = CaptureInbox(context)
        val token = id()
        inbox.receive(share(), token)
        inbox.bindTarget(token, "account-one", "note1234")
        inbox.markTextApplied(token)
        val restored = CaptureInbox(context)
        assertEquals("account-one", restored.entries.value.single().accountId)
        assertTrue(restored.entries.value.single().textApplied)
        assertThrows(IllegalArgumentException::class.java) { restored.bindTarget(token, "account-two", "note1234") }
        assertThrows(IllegalArgumentException::class.java) { restored.bindTarget(token, "account-one", "note5678") }
        assertThrows(IllegalArgumentException::class.java) { restored.complete(token) }
        restored.markAttachmentApplied(token, "0")
        restored.complete(token)
        assertTrue(restored.entries.value.isEmpty())
        assertNull(CaptureInbox(context).receive(share(), token))
        assertEquals(1, opens)
    }
    @Test fun newNoteDestinationSurvivesCrashBeforeNoteCreationIncludingEmptyChosenTitle() {
        val token = id()
        val inbox = CaptureInbox(context)
        inbox.receive(share(), token)
        inbox.bindTarget(token, "account-one", "newNote1", "folder01", "")
        val restored = CaptureInbox(context)
        val pending = restored.entries.value.single()
        assertTrue(pending.createNew)
        assertEquals("folder01", pending.targetParentId)
        assertEquals("", pending.targetTitle)
        restored.bindTarget(token, "account-one", "newNote1", "folder01", "")
        assertThrows(IllegalArgumentException::class.java) {
            restored.bindTarget(token, "account-one", "newNote1", "root", "Another title")
        }
    }
    @Test fun externalFileAndOwnPrivateProviderAreRejectedBeforeOpening() {
        val inbox = CaptureInbox(context)
        assertThrows(IllegalArgumentException::class.java) { inbox.receive(share().putExtra(Intent.EXTRA_STREAM, Uri.fromFile(source)), id()) }
        val private = Uri.parse("content://${context.packageName}.files/capture_inbox/secret")
        assertThrows(IllegalArgumentException::class.java) { inbox.receive(share().putExtra(Intent.EXTRA_STREAM, private), id()) }
        assertThrows(IllegalArgumentException::class.java) { inbox.receive(share().setFlags(0), id()) }
        assertEquals(0, opens)
        assertTrue(inbox.entries.value.isEmpty())
    }
    @Test fun textOnlyNeedsNoGrantAndDiscardPreventsRecreationReplay() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Synthetic text")
        val inbox = CaptureInbox(context)
        val token = id()
        assertEquals("Synthetic text", inbox.receive(intent, token)!!.text)
        assertNull(inbox.entries.value.single().accountId)
        assertThrows(IllegalArgumentException::class.java) { inbox.markTextApplied(token) }
        inbox.discard(token)
        assertNull(CaptureInbox(context).receive(intent, token))
    }
    @Test fun explicitAbortAfterSecondStreamFailureRemovesPrivatePartialBytesAndPreservesSource() {
        failSecondStream = true
        val token = id()
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri, Uri.parse("content://synthetic.capture.fixture/document/2")))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val inbox = CaptureInbox(context)
        assertThrows(java.io.IOException::class.java) { inbox.receive(intent, token) }
        assertTrue(File(directory, "capture-inbox/$token/part-0").isFile)
        assertTrue(inbox.entries.value.isEmpty())
        // The error screen's explicit Close action calls discard before finishing.
        inbox.discard(token)
        assertFalse(File(directory, "capture-inbox/$token").exists())
        assertTrue(source.isFile)
        assertArrayEquals(byteArrayOf(0, 1, 13, 10, -1), source.readBytes())
        assertNull(CaptureInbox(context).receive(intent, token))
    }
    @Test fun pathAndMimeValidationRejectUntrustedForms() {
        assertFalse(CaptureInbox.validId("../outside"))
        assertFalse(CaptureInbox.validMime("text/plain\n"))
        assertFalse(CaptureInbox.validMime(null))
        assertEquals("fixture.txt", CaptureInbox.safeTitle("../../fixture.txt\n"))
        assertThrows(IllegalArgumentException::class.java) { CaptureInbox(context).attachmentFile("../outside", "0") }
    }
}
