package com.agentic.binning.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ItemKind { Photo, Video }

enum class ItemStatus { Pending, Analyzing, Analyzed, Failed, Sorted, Binned, Missing }

@Entity(
    tableName = "item",
    indices = [
        Index(value = ["contentHash"], unique = true),
        Index(value = ["currentPath"], unique = true),
    ],
)
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contentHash: String,
    val originalPath: String,
    val originalName: String,
    val currentPath: String,
    val kind: ItemKind,
    val takenAt: Long?,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val status: ItemStatus,
    val categoryId: Long? = null,
    val friendlyName: String? = null,
    val nameSetByUser: Boolean = false,
    val categorySetByUser: Boolean = false,
    val addedAt: Long,
    val failureReason: String? = null,
    /**
     * Analysis starts since the item last left Analyzing cleanly; an item a dead process left
     * Analyzing with 2 or more is failed on the next start instead of retried (schema v2).
     */
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
)

@Entity(
    tableName = "analysis",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AnalysisEntity(
    @PrimaryKey val itemId: Long,
    val description: String,
    val actionsJson: String,
    val suggestedName: String,
    val shotType: String,
    val rawJson: String,
    val modelId: String,
    val promptVersion: String,
    val stopReason: String,
    val ttftMs: Double,
    val generatedTokens: Int,
    val analyzedAt: Long,
)

@Entity(tableName = "tag", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(
    tableName = "item_tag",
    primaryKeys = ["itemId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tagId"])],
)
data class ItemTagEntity(
    val itemId: Long,
    val tagId: Long,
)

@Entity(tableName = "category")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val createdBy: String,
    val folderName: String?,
)

/** One Bin Files or Save run (Plan 3). [kind] is a `MoveKind` name, [state] a `RunState` name. */
@Entity(tableName = "move_run")
data class MoveRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val state: String,
    /** Why the run stopped or failed, for the report (schema v4). */
    val message: String? = null,
    /** Empty folders the run removed, one per line, for the report (schema v4). */
    val removedFolders: String? = null,
    /** Items the plan found already in place and only marked binned, for the report (schema v5). */
    @ColumnInfo(defaultValue = "0") val inPlace: Int = 0,
    /** The folder check after the run: null when it didn't run (schema v5). */
    val checkOk: Boolean? = null,
    /** The user closed this run's report; until then it shows again when they come back (B8, schema v5). */
    @ColumnInfo(defaultValue = "0") val reportClosed: Boolean = false,
)

/** One file of a run (Plan 3). [state] is a `StepState` name. */
@Entity(tableName = "move_step", indices = [Index(value = ["runId"])])
data class MoveStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val itemId: Long,
    val source: String,
    val dest: String,
    val state: String,
    /** The source's size when planned; a resume compares it to tell a finished rename (schema v4). */
    @ColumnInfo(defaultValue = "0") val bytes: Long = 0,
    /** Starts of this step; a third start skips it (B3, schema v4). */
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    /** Another project also holds the source: copy it and leave it (P8, schema v4). */
    @ColumnInfo(defaultValue = "0") val keepSource: Boolean = false,
    /** The destination got a " 2"-style number (schema v4). */
    @ColumnInfo(defaultValue = "0") val numbered: Boolean = false,
    /** Why the step was skipped or failed, a `StepErrors` value (schema v4). */
    val error: String? = null,
)

/**
 * A file in a category folder that belongs to no item on purpose, so the folder check skips it
 * (schema v4): the file of an item removed from the project after it was binned (Q2, reason
 * `removed`), or a file the user chose to leave there (B5, reason `left`).
 */
@Entity(tableName = "removed_file")
data class RemovedFileEntity(
    @PrimaryKey val path: String,
    val removedAt: Long,
    val reason: String,
)

/** One run of the sorting model over the uncategorized items (schema v3). */
@Entity(tableName = "sort_run")
data class SortRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val finishedAt: Long?,
    val modelId: String,
    val promptVersion: String,
    val items: Int,
    val sorted: Int,
    val newCategories: Int,
    /** done | partial | failed | unavailable */
    val outcome: String,
    val message: String?,
)

@Entity(tableName = "project_meta")
data class ProjectMetaEntity(
    @PrimaryKey val id: Int = 1,
    val name: String,
    val createdAt: Long,
    val schemaVersion: Int,
    /** The user pressed Stop; analysis does not start on its own until Resume or Add media (schema v2). */
    @ColumnInfo(defaultValue = "0") val stoppedByUser: Boolean = false,
)
