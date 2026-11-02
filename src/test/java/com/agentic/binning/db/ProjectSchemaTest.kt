package com.agentic.binning.db

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectSchemaTest {
    private fun exported(version: Int): Pair<Int, String> {
        val file = File("schemas/com.agentic.binning.db.ProjectDatabase/$version.json")
        val database = Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
        return database.getValue("version").jsonPrimitive.content.toInt() to database.getValue("identityHash").jsonPrimitive.content
    }

    @Test fun everyVersionHasItsExportedIdentityHash() {
        assertEquals((1..PROJECT_DB_VERSION).toSet(), PROJECT_DB_IDENTITY_HASHES.keys)
        for (version in 1..PROJECT_DB_VERSION) {
            val (exportedVersion, hash) = exported(version)
            assertEquals(version, exportedVersion)
            assertEquals("schema v$version", hash, PROJECT_DB_IDENTITY_HASHES.getValue(version))
        }
    }
}
