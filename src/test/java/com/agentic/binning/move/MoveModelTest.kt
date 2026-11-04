package com.agentic.binning.move

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveModelTest {
    @Test fun states_roundTripTheirNames() {
        StepState.entries.forEach { assertEquals(it, StepState.of(it.name)) }
        RunState.entries.forEach { assertEquals(it, RunState.of(it.name)) }
        MoveKind.entries.forEach { assertEquals(it, MoveKind.of(it.name)) }
    }

    /** A value written by a newer build must never crash a resume; it is treated as finished. */
    @Test fun unknownState_isFailedNotACrash() {
        assertEquals(StepState.Failed, StepState.of("Teleported"))
        assertEquals(RunState.Failed, RunState.of(""))
        assertEquals(MoveKind.Bin, MoveKind.of("bin"))
        assertEquals(MoveKind.Bin, MoveKind.of(null))
    }

    @Test fun openRuns_areRunningAndPaused() {
        assertEquals(setOf(RunState.Running, RunState.Paused), RunState.entries.filter { it.isOpen }.toSet())
    }

    @Test fun finalSteps_areNeverResumed() {
        assertEquals(
            setOf(StepState.Done, StepState.Skipped, StepState.Missing, StepState.Failed),
            StepState.entries.filter { it.isFinal }.toSet(),
        )
        assertFalse(StepState.Verified.isFinal)
        assertTrue(StepState.Planned.needsWork)
    }
}
