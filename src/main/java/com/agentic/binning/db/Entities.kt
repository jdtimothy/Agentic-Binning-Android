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

@Entity(tableName = "move_run")
data class MoveRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val state: String,
)

@Entity(tableName = "move_step", indices = [Index(value = ["runId"])])
data class MoveStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val itemId: Long,
    val source: String,
    val dest: String,
    val state: String,
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
