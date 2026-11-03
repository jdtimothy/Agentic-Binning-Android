package com.agentic.binning.ui.media

import android.util.Log
import com.agentic.binning.db.CategoryEntity
import com.agentic.binning.project.OpenProject
import com.agentic.binning.project.ProjectFolders
import com.agentic.binning.projectmedia.MediaFilter
import com.agentic.binning.projectmedia.MediaRow
import com.agentic.binning.projectmedia.ProjectMediaRepository
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

private const val TAG = "ProjectMedia"

/** [project]'s media through its [ProjectMediaRepository], for [ProjectMediaViewModel]. */
class RepositoryMediaSource(private val project: OpenProject) : MediaSource {
    private val repository = ProjectMediaRepository(project.db)
    private val thumbs = ProjectFolders.thumbsDir(File(project.ref.path))

    override val projectName: String get() = project.ref.name

    // The database closes on a project switch, possibly before a collector moves on.
    override fun rows(ftsQuery: String?): Flow<List<MediaRow>> =
        repository.observeRows(MediaFilter(), ftsQuery).catch { e -> Log.w(TAG, "Rows of ${project.ref.name} stopped", e) }

    override fun categories(): Flow<List<CategoryEntity>> =
        repository.observeCategories().catch { e -> Log.w(TAG, "Categories of ${project.ref.name} stopped", e) }

    override fun image(row: MediaRow): File = File(thumbs, "${row.id}.jpg").takeIf(File::isFile) ?: File(row.currentPath)
}
