package com.agentic.binning.project

import java.io.File

/** Layout of a project folder: user media lives in the folder, app data under `.binning/`. */
object ProjectFolders {
    private const val MAX_NAME_LENGTH = 80

    /** The hidden folder that holds the project's database and thumbnails. */
    const val BINNING_DIR = ".binning"
    private val illegal = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    /** Turns a user-typed project name into a safe folder name. */
    fun folderNameFor(projectName: String): String {
        val replaced = projectName.map { if (it in illegal) '_' else it }.joinToString("")
        return replaced.trim { it == ' ' || it == '.' }.take(MAX_NAME_LENGTH)
            .trimEnd(' ', '.')
    }

    fun binningDir(projectDir: File): File = File(projectDir, BINNING_DIR)

    fun databaseFile(projectDir: File): File = File(binningDir(projectDir), "project.db")

    fun thumbsDir(projectDir: File): File = File(binningDir(projectDir), "thumbs")
}
