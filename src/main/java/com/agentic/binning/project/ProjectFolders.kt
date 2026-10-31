package com.agentic.binning.project

import java.io.File

/** Layout of a project folder: user media lives in the folder, app data under `.binning/`. */
object ProjectFolders {
    private const val MAX_NAME_LENGTH = 80
    private val illegal = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    /** Turns a user-typed project name into a safe folder name. */
    fun folderNameFor(projectName: String): String {
        val replaced = projectName.map { if (it in illegal) '_' else it }.joinToString("")
        return replaced.trim { it == ' ' || it == '.' }.take(MAX_NAME_LENGTH)
            .trimEnd(' ', '.')
    }

    fun binningDir(projectDir: File): File = File(projectDir, ".binning")

    fun databaseFile(projectDir: File): File = File(binningDir(projectDir), "project.db")

    fun thumbsDir(projectDir: File): File = File(binningDir(projectDir), "thumbs")
}
