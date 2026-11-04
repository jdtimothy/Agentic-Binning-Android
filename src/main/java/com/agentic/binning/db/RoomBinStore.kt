package com.agentic.binning.db

import com.agentic.binning.move.BinItem
import com.agentic.binning.move.BinStore
import com.agentic.binning.move.MoveKind
import com.agentic.binning.move.MoveStore
import com.agentic.binning.move.PlannedStep
import com.agentic.binning.move.RunState
import com.agentic.binning.move.StepState

/**
 * [BinStore] over one project's database. [shared] answers which paths another listed project also
 * holds (P8); AppGraph opens the other projects' databases for it.
 */
class RoomBinStore(
    db: ProjectDatabase,
    private val shared: suspend (List<String>) -> Set<String> = { emptySet() },
) : BinStore, MoveStore by db.moves() {
    private val dao = db.moves()

    override suspend fun binItems(): List<BinItem> = dao.binItems()

    override suspend fun categoryNames(): Map<Long, String> = dao.categoryNames().associate { it.id to it.name }

    override suspend fun removedPaths(): List<String> = dao.removedPaths()

    override suspend fun dropRemoved(paths: List<String>) = paths.chunked(CHUNK).forEach { dao.dropRemoved(it) }

    override suspend fun sharedPaths(paths: List<String>): Set<String> = shared(paths)

    override suspend fun writeRun(
        kind: MoveKind,
        startedAt: Long,
        steps: List<PlannedStep>,
        bytes: Map<Long, Long>,
        inPlace: List<Long>,
        missing: List<Long>,
    ): Long = dao.writeRun(
        MoveRunEntity(kind = kind.name, startedAt = startedAt, finishedAt = null, state = RunState.Running.name),
        steps.map {
            MoveStepEntity(
                runId = 0,
                itemId = it.itemId,
                source = it.source,
                dest = it.dest,
                state = StepState.Planned.name,
                bytes = bytes[it.itemId] ?: 0L,
                keepSource = it.keepSource,
                numbered = it.numbered,
            )
        },
        inPlace,
        missing,
    )

    override suspend fun markItems(inPlace: List<Long>, missing: List<Long>) = dao.markItems(inPlace, missing)

    override suspend fun openRun(): MoveRunEntity? = dao.openRun()

    override suspend fun run(id: Long): MoveRunEntity? = dao.run(id)

    override suspend fun finishRun(runId: Long, state: RunState, finishedAt: Long, message: String?, removedFolders: List<String>) =
        dao.finishRun(runId, state.name, finishedAt, message, removedFolders.joinToString("\n").ifEmpty { null })

    override suspend fun binnedFolders(): List<String> = dao.binnedFolders()

    private companion object {
        /** Under SQLite's 999 bound variables. */
        const val CHUNK = 500
    }
}
