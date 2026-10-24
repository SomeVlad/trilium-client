package ru.vlad.triliumnotes.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TriliumApiBranchIdsTest {
    private val server = MockWebServer()
    private lateinit var api: TriliumApi

    @Before fun start() {
        server.start()
        api = TriliumApi(server.url("/").toString(), "test-token")
    }

    @After fun stop() { server.shutdown() }

    @Test fun rootLoadsCompositeBranchWhileHidingSystemNotesAndReportingRealFailure() = runBlocking {
        val noteId = "a".repeat(32)
        val branchId = "root_$noteId"
        server.enqueue(MockResponse().setBody("""{"noteId":"root","childBranchIds":["$branchId","root__hidden","root__system","branchDenied"]}"""))
        server.enqueue(MockResponse().setBody("""{"branchId":"$branchId","noteId":"$noteId","notePosition":10}"""))
        server.enqueue(MockResponse().setBody("""{"noteId":"$noteId","type":"text","mime":"text/html"}"""))
        server.enqueue(MockResponse().setBody("""{"branchId":"root__hidden","noteId":"_hidden"}"""))
        server.enqueue(MockResponse().setBody("""{"branchId":"root__system","noteId":"_system"}"""))
        server.enqueue(MockResponse().setResponseCode(403))

        val result = api.getChildrenResult("root")

        assertEquals(branchId, result.items.single().branchId)
        assertEquals(noteId, result.items.single().note.id)
        assertEquals(listOf("branchDenied"), result.failedBranchIds)
        assertEquals(listOf("/etapi/notes/root", "/etapi/branches/$branchId", "/etapi/notes/$noteId",
            "/etapi/branches/root__hidden", "/etapi/branches/root__system", "/etapi/branches/branchDenied"),
            (0 until server.requestCount).map { server.takeRequest().path })
    }

    @Test fun nestedBranchAcceptsTwoMaximumLengthNoteIds() = runBlocking {
        val parentId = "a".repeat(32)
        val noteId = "b".repeat(32)
        val branchId = "${parentId}_$noteId"
        server.enqueue(MockResponse().setBody("""{"noteId":"$parentId","childBranchIds":["$branchId"]}"""))
        server.enqueue(MockResponse().setBody("""{"branchId":"$branchId","noteId":"$noteId"}"""))
        server.enqueue(MockResponse().setBody("""{"noteId":"$noteId"}"""))

        val result = api.getChildrenResult(parentId)

        assertEquals(branchId, result.items.single().branchId)
        assertTrue(result.failedBranchIds.isEmpty())
        assertEquals(3, server.requestCount)
    }

    @Test fun invalidBranchPathsRemainFailuresAndNoteIdsKeepTheirLimit() = runBlocking {
        val tooLongBranch = "a".repeat(66)
        server.enqueue(MockResponse().setBody("""{"noteId":"root","childBranchIds":["../notes","$tooLongBranch"]}"""))

        val result = api.getChildrenResult("root")

        assertTrue(result.items.isEmpty())
        assertEquals(listOf("../notes", tooLongBranch), result.failedBranchIds)
        try {
            api.getNote("a".repeat(33))
            throw AssertionError("Oversized note ID must be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(1, server.requestCount)
        }
    }
}
