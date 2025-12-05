package com.itsaky.androidide.ui

data class ProjectDetails(
    val sizeFormatted: String,
    val numberOfFiles: Int,
    val gradleVersion: String,
    val kotlinVersion: String,
    val javaVersion: String
)
