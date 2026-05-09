package com.itsaky.androidide.lsp.kotlin.compiler.services

import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationTrackerByEventFactoryBase
import org.jetbrains.kotlin.com.intellij.openapi.project.Project

class ModificationTrackerFactory(project: Project): KotlinModificationTrackerByEventFactoryBase(project)