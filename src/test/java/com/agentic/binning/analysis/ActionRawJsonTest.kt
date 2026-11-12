package com.agentic.binning.analysis

import com.agentic.binning.db.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionRawJsonTest {
    @Test fun assembledActions_areAlsoInStoredRawJson() {
        val raw = """{"description":"A coast.","actions":[],"tags":["sea"],"suggested_name":"Coast","shot_type":"wide"}"""
        val actions = listOf(ActionEntry("0:31", "A wave breaks"))
        val updated = AnalysisParser.withActions(raw, actions)
        assertEquals(actions, AnalysisParser.parse(updated, ItemKind.Video)!!.actions)
        assertEquals("A coast.", AnalysisParser.parse(updated, ItemKind.Video)!!.description)
    }
}
