package com.agentic.binning.media

import com.agentic.binning.db.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentPathsTest {
    @Test fun primaryVolume_mapsToInternalStorage() {
        assertEquals("/storage/emulated/0/DCIM/Camera", DocumentPaths.treeDocIdToPath("primary:DCIM/Camera"))
    }

    @Test fun secondaryVolume_mapsToStorageVolume() {
        assertEquals("/storage/1A2B-3C4D/Movies", DocumentPaths.treeDocIdToPath("1A2B-3C4D:Movies"))
    }

    @Test fun volumeRoot_hasNoTrailingSlash() {
        assertEquals("/storage/emulated/0", DocumentPaths.treeDocIdToPath("primary:"))
    }

    @Test fun malformedTreeDocIds_areNull() {
        assertNull(DocumentPaths.treeDocIdToPath("nocolon"))
        assertNull(DocumentPaths.treeDocIdToPath(":DCIM"))
    }

    @Test fun mediaDocIds_mapToKindAndId() {
        assertEquals(ItemKind.Video to 1234L, DocumentPaths.mediaDocId("video:1234"))
        assertEquals(ItemKind.Photo to 77L, DocumentPaths.mediaDocId("image:77"))
    }

    @Test fun unsupportedMediaDocIds_areNull() {
        assertNull(DocumentPaths.mediaDocId("audio:5"))
        assertNull(DocumentPaths.mediaDocId("video:abc"))
        assertNull(DocumentPaths.mediaDocId("video"))
    }
}
