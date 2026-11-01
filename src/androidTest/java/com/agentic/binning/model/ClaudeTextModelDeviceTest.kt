package com.agentic.binning.model

import android.util.Log
import com.agentic.binning.sorting.SortPrompt
import com.agentic.binning.sorting.SortSchema
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Claude's error mapping on the phone with an invalid key: the 401 costs nothing. The real call
 * with the user's key is checked in Task 10. Agents never put a real key here.
 */
class ClaudeTextModelDeviceTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    @Test fun claude_invalidKey_isBadApiKey() = runBlocking {
        val claude = ClaudeTextModel(apiKey = { "sk-ant-invalid" }, log = { Log.i(GenieXTextModelTest.TAG, it) })
        val format = ReplyFormat(SortSchema.assignJsonSchema(), "")
        val e = try {
            claude.complete(SortPrompt.system(), SortPrompt.assignUser(GenieXTextModelTest.items.take(2), emptyList(), emptyList()), format, 900)
            null
        } catch (e: SortingUnavailableException) {
            e
        }
        assertNotNull("expected SortingUnavailableException", e)
        Log.i(GenieXTextModelTest.TAG, "invalid key -> ${e!!.reason}: ${e.message}")
        assertEquals(SortUnavailable.BadApiKey, e.reason)
        assertTrue(e.message.orEmpty(), !e.message.orEmpty().contains("sk-ant-invalid"))
    }
}
