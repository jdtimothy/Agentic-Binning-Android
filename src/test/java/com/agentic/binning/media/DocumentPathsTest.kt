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

    @Test fun localPickerSegments_giveTheMediaStoreId() {
        assertEquals(58064L, DocumentPaths.pickerMediaId(listOf("picker", "0", "com.android.providers.media.photopicker", "media", "58064")))
        assertEquals(7L, DocumentPaths.pickerMediaId(listOf("picker_get_content", "0", "com.google.android.providers.media.photopicker", "media", "7")))
    }

    @Test fun cloudOrMalformedPickerSegments_areNull() {
        assertNull(DocumentPaths.pickerMediaId(listOf("picker", "0", "com.google.android.apps.photos.cloudpicker", "media", "58064")))
        assertNull(DocumentPaths.pickerMediaId(listOf("picker", "0", "com.android.providers.media.photopicker", "media", "abc")))
        assertNull(DocumentPaths.pickerMediaId(listOf("picker")))
    }

    @Test fun unsupportedMediaDocIds_areNull() {
        assertNull(DocumentPaths.mediaDocId("audio:5"))
        assertNull(DocumentPaths.mediaDocId("video:abc"))
        assertNull(DocumentPaths.mediaDocId("video"))
    }
}
