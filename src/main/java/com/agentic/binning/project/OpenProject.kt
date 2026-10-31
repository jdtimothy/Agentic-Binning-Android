package com.agentic.binning.project

import com.agentic.binning.db.ProjectDatabase

/** A project whose database is currently open. */
class OpenProject(val ref: ProjectRef, val db: ProjectDatabase)
