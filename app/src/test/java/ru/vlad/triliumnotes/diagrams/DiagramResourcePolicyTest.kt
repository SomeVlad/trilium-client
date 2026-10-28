package ru.vlad.triliumnotes.diagrams

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiagramResourcePolicyTest {
    @Test fun onlyLocalBundledViewerAssetsAreAvailable() {
        for (path in listOf("index.html", "viewer.css", "vendor/mermaid.js", "vendor/fonts/Virgil/Virgil-Regular.woff2")) {
            assertEquals("diagrams/$path", DiagramResourcePolicy.assetPath(Uri.parse("https://${DiagramResourcePolicy.HOST}/diagrams/$path"), "GET"))
        }
    }
    @Test fun serverRequestsPrivateFilesAndEncodedTraversalAreBlocked() {
        for (url in listOf("https://server.invalid/etapi/notes", "file:///data/user/0/secret", "content://private/secret",
            "https://${DiagramResourcePolicy.HOST}/diagrams/../session.encrypted",
            "https://${DiagramResourcePolicy.HOST}/diagrams/%2e%2e/editor/editor.js",
            "https://${DiagramResourcePolicy.HOST}/diagrams/vendor/a.js?token=secret",
            "https://${DiagramResourcePolicy.HOST}:443/diagrams/index.html",
            "https://user@${DiagramResourcePolicy.HOST}/diagrams/index.html",
            "https://${DiagramResourcePolicy.HOST}/diagrams/index.html#payload",
            "https://${DiagramResourcePolicy.HOST}/editor/editor.js")) {
            assertNull(url, DiagramResourcePolicy.assetPath(Uri.parse(url), "GET"))
        }
        assertNull(DiagramResourcePolicy.assetPath(Uri.parse(DiagramResourcePolicy.INDEX), "POST"))
    }
}
