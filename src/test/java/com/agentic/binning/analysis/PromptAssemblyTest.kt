package com.agentic.binning.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PromptAssemblyTest {
    @Test fun videoMediaText_interleaves() =
        assertEquals(
            "Video:<__media__>[0m0.00s]<__media__><__media__>",
            PromptAssembly.videoMediaText(listOf(0, 500, 1000)),
        )

    @Test fun photoMediaText_isMarker() = assertEquals("<__media__>", PromptAssembly.photoMediaText())

    @Test fun splice_replacesPlaceholderOnce() =
        assertEquals(
            "<|im_start|>user\n<__media__>\nDescribe.<|im_end|>",
            PromptAssembly.splice("<|im_start|>user\n${PromptAssembly.PLACEHOLDER}<|im_end|>", "<__media__>", "Describe."),
        )

    @Test fun splice_missingPlaceholder_throws() {
        assertThrows(IllegalStateException::class.java) {
            PromptAssembly.splice("<|im_start|>user\nno placeholder<|im_end|>", "<__media__>", "Describe.")
        }
    }

    @Test fun splice_duplicatePlaceholder_throws() {
        val twice = "${PromptAssembly.PLACEHOLDER} ${PromptAssembly.PLACEHOLDER}"
        assertThrows(IllegalStateException::class.java) {
            PromptAssembly.splice(twice, "<__media__>", "Describe.")
        }
    }
}
