/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.appdevforall.codeonthego.tooling.impl.progress

import org.appdevforall.codeonthego.tooling.events.StatusEvent
import org.appdevforall.codeonthego.tooling.events.configuration.ProjectConfigurationOperationResult.PluginApplicationResult
import org.appdevforall.codeonthego.tooling.events.download.FileDownloadFinishEvent
import org.appdevforall.codeonthego.tooling.events.download.FileDownloadProgressEvent
import org.appdevforall.codeonthego.tooling.events.download.FileDownloadStartEvent
import org.appdevforall.codeonthego.tooling.events.internal.DefaultFinishEvent
import org.appdevforall.codeonthego.tooling.events.internal.DefaultOperationDescriptor
import org.appdevforall.codeonthego.tooling.events.internal.DefaultOperationResult
import org.appdevforall.codeonthego.tooling.events.internal.DefaultProgressEvent
import org.appdevforall.codeonthego.tooling.events.internal.DefaultStartEvent
import org.appdevforall.codeonthego.tooling.events.task.TaskFinishEvent
import org.appdevforall.codeonthego.tooling.events.task.TaskProgressEvent
import org.appdevforall.codeonthego.tooling.events.task.TaskStartEvent
import org.appdevforall.codeonthego.tooling.events.test.TestFinishEvent
import org.appdevforall.codeonthego.tooling.events.test.TestOperationResult
import org.appdevforall.codeonthego.tooling.events.test.TestProgressEvent
import org.appdevforall.codeonthego.tooling.events.test.TestStartEvent
import org.appdevforall.codeonthego.tooling.events.transform.TransformFinishEvent
import org.appdevforall.codeonthego.tooling.events.transform.TransformOperationDescriptor.SubjectDescriptor
import org.appdevforall.codeonthego.tooling.events.transform.TransformOperationResult
import org.appdevforall.codeonthego.tooling.events.transform.TransformStartEvent
import org.appdevforall.codeonthego.tooling.events.work.WorkItemFinishEvent
import org.appdevforall.codeonthego.tooling.events.work.WorkItemOperationResult
import org.appdevforall.codeonthego.tooling.events.work.WorkItemProgressEvent
import org.appdevforall.codeonthego.tooling.events.work.WorkItemStartEvent
import org.appdevforall.codeonthego.tooling.model.PluginIdentifier
import org.gradle.tooling.events.OperationDescriptor
import org.gradle.tooling.events.ProgressEvent
import org.gradle.tooling.events.configuration.ProjectConfigurationFinishEvent
import org.gradle.tooling.events.configuration.ProjectConfigurationOperationDescriptor
import org.gradle.tooling.events.configuration.ProjectConfigurationOperationResult
import org.gradle.tooling.events.configuration.ProjectConfigurationProgressEvent
import org.gradle.tooling.events.configuration.ProjectConfigurationStartEvent
import org.gradle.tooling.events.configuration.ProjectConfigurationSuccessResult
import org.gradle.tooling.events.download.FileDownloadOperationDescriptor
import org.gradle.tooling.events.download.FileDownloadResult
import org.gradle.tooling.events.task.TaskExecutionResult
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskOperationDescriptor
import org.gradle.tooling.events.task.TaskOperationResult
import org.gradle.tooling.events.task.TaskSkippedResult
import org.gradle.tooling.events.task.TaskSuccessResult
import org.gradle.tooling.events.test.TestOperationDescriptor
import org.gradle.tooling.events.test.TestSuccessResult
import org.gradle.tooling.events.transform.TransformOperationDescriptor
import org.gradle.tooling.events.transform.TransformSuccessResult
import org.gradle.tooling.events.work.WorkItemOperationDescriptor
import org.gradle.tooling.events.work.WorkItemSuccessResult
import org.gradle.tooling.model.ProjectIdentifier

/** @author Akash Yadav */
class EventTransformer {
  companion object {

    // ------------------------ COMMON -------------------------
    private fun operationDescriptor(
      descriptor: OperationDescriptor?
    ): org.appdevforall.codeonthego.tooling.events.OperationDescriptor? =
      when (descriptor) {
        null -> null
        is ProjectConfigurationOperationDescriptor -> projectConfigurationDescriptor(descriptor)
        is FileDownloadOperationDescriptor -> fileDownloadDescriptor(descriptor)
        is TaskOperationDescriptor -> taskDescriptor(descriptor)
        is TransformOperationDescriptor -> transformDescriptor(descriptor)
        is WorkItemOperationDescriptor -> workDescriptor(descriptor)
        else ->
          DefaultOperationDescriptor(name = descriptor.name, displayName = descriptor.displayName)
      }

    // ----------------- PROJECT CONFIGURATION --------------------
    @JvmStatic
    fun projectConfigurationStart(
      event: ProjectConfigurationStartEvent
    ): org.appdevforall.codeonthego.tooling.events.configuration.ProjectConfigurationProgressEvent =
      org.appdevforall.codeonthego.tooling.events.configuration.ProjectConfigurationStartEvent(
        displayName = event.displayName,
        eventTime = event.eventTime,
        descriptor = projectConfigurationDescriptor(event.descriptor)
      )

    @JvmStatic
    fun projectConfigurationProgress(
      event: ProjectConfigurationProgressEvent
    ): org.appdevforall.codeonthego.tooling.events.configuration.ProjectConfigurationProgressEvent =
      org.appdevforall.codeonthego.tooling.events.configuration.ProjectConfigurationProgressEvent(
        displayName = event.displayName,
        eventTime = event.eventTime,
        descriptor = projectConfigurationDescriptor(event.descriptor)
      )

    @JvmStatic
    fun projectConfigurationFinish(
      event: ProjectConfigurationFinishEvent
    ): org.appdevforall.codeonthego.tooling.events.configuration.ProjectConfigurationProgressEvent =
      org.appdevforall.codeonthego.tooling.events.configuration.ProjectConfigurationFinishEvent(
        displayName = event.displayName,
        eventTime = event.eventTime,
        descriptor = projectConfigurationDescriptor(event.descriptor),
        result = projectConfigurationResult(event.result)
      )

    private fun projectConfigurationResult(
      result: ProjectConfigurationOperationResult
    ): org.appdevforall.codeonthego.tooling.events.configuration.ProjectConfigurationOperationResult =
      org.appdevforall.codeonthego.tooling.events.configuration.ProjectConfigurationOperationResult(
        pluginApplicationResults =
          result.pluginApplicationResults.map {
            PluginApplicationResult(
              plugin = PluginIdentifier(it.plugin?.displayName ?: "Unknown plugin"),
              it.totalConfigurationTime.toMillis()
            )
          },
        startTime = result.startTime,
        endTime = result.endTime,
        success = result is ProjectConfigurationSuccessResult
      )

    private fun projectConfigurationDescriptor(
      descriptor: ProjectConfigurationOperationDescriptor
    ): org.appdevforall.codeonthego.tooling.events.configuration.ProjectConfigurationOperationDescriptor =
      org.appdevforall.codeonthego.tooling.events.configuration.ProjectConfigurationOperationDescriptor(
        project = projectIdentifier(descriptor.project),
        name = descriptor.name,
        displayName = descriptor.displayName
      )

    private fun projectIdentifier(
      project: ProjectIdentifier
    ): org.appdevforall.codeonthego.tooling.model.ProjectIdentifier =
      org.appdevforall.codeonthego.tooling.model.ProjectIdentifier(
        projectPath = project.projectPath,
        buildIdentifier =
          org.appdevforall.codeonthego.tooling.model.BuildIdentifier(project.buildIdentifier.rootDir)
      )

    // ---------------------- FILE DOWNLOAD ---------------------------------
    @JvmStatic
    fun fileDownloadStart(
      event: org.gradle.tooling.events.download.FileDownloadStartEvent
    ): FileDownloadStartEvent =
      FileDownloadStartEvent(
        eventTime = event.eventTime,
        displayName = event.displayName,
        descriptor = fileDownloadDescriptor(event.descriptor)
      )

    @JvmStatic
    fun fileDownloadProgress(
      event: org.gradle.tooling.events.download.FileDownloadProgressEvent
    ): FileDownloadProgressEvent =
      FileDownloadProgressEvent(
        eventTime = event.eventTime,
        displayName = event.displayName,
        descriptor = fileDownloadDescriptor(event.descriptor)
      )

    @JvmStatic
    fun fileDownloadFinish(
      event: org.gradle.tooling.events.download.FileDownloadFinishEvent
    ): FileDownloadFinishEvent =
      FileDownloadFinishEvent(
        eventTime = event.eventTime,
        displayName = event.displayName,
        descriptor = fileDownloadDescriptor(event.descriptor),
        result = fileDownloadResult(event.result)
      )

    private fun fileDownloadResult(
      result: FileDownloadResult
    ): org.appdevforall.codeonthego.tooling.events.download.FileDownloadResult =
      org.appdevforall.codeonthego.tooling.events.download.FileDownloadResult(
        bytesDownloaded = result.bytesDownloaded,
        startTime = result.startTime,
        endTime = result.endTime
      )

    private fun fileDownloadDescriptor(
      descriptor: FileDownloadOperationDescriptor
    ): org.appdevforall.codeonthego.tooling.events.download.FileDownloadOperationDescriptor =
      org.appdevforall.codeonthego.tooling.events.download.FileDownloadOperationDescriptor(
        descriptor.uri,
        descriptor.name,
        descriptor.displayName
      )

    // -------------------- TASK -------------------------------
    @JvmStatic
    fun taskStart(event: org.gradle.tooling.events.task.TaskStartEvent): TaskStartEvent =
      TaskStartEvent(
        eventTime = event.eventTime,
        displayName = event.displayName,
        descriptor = taskDescriptor(event.descriptor)
      )

    @JvmStatic
    fun taskProgress(event: org.gradle.tooling.events.task.TaskProgressEvent): TaskProgressEvent =
      TaskProgressEvent(
        eventTime = event.eventTime,
        displayName = event.displayName,
        descriptor = taskDescriptor(event.descriptor)
      )

    @JvmStatic
    fun taskFinish(event: org.gradle.tooling.events.task.TaskFinishEvent): TaskFinishEvent =
      TaskFinishEvent(
        eventTime = event.eventTime,
        displayName = event.displayName,
        descriptor = taskDescriptor(event.descriptor),
        result = taskResult(event.result)
      )

    private fun taskResult(
      result: TaskOperationResult
    ): org.appdevforall.codeonthego.tooling.events.task.TaskOperationResult {

      // The order of conditions must not change here.

      if (result is TaskSuccessResult) {
        return org.appdevforall.codeonthego.tooling.events.task.TaskSuccessResult(
          result.isUpToDate,
          result.isFromCache,
          result.startTime,
          result.endTime,
          result.isIncremental,
          result.executionReasons
        )
      }

      if (result is TaskFailureResult) {
        return org.appdevforall.codeonthego.tooling.events.task.TaskFailureResult(
          result.startTime,
          result.endTime
        )
      }

      if (result is TaskExecutionResult) {
        return org.appdevforall.codeonthego.tooling.events.task.TaskExecutionResult(
          result.startTime,
          result.endTime,
          result.isIncremental,
          result.executionReasons
        )
      }

      if (result is TaskSkippedResult) {
        return org.appdevforall.codeonthego.tooling.events.task.TaskSkippedResult(
          result.skipMessage,
          result.startTime,
          result.endTime
        )
      }

      return org.appdevforall.codeonthego.tooling.events.task.TaskOperationResult(
        startTime = result.startTime,
        endTime = result.endTime
      )
    }

    private fun taskDescriptor(
      descriptor: TaskOperationDescriptor
    ): org.appdevforall.codeonthego.tooling.events.task.TaskOperationDescriptor =
      org.appdevforall.codeonthego.tooling.events.task.TaskOperationDescriptor(
        dependencies =
          descriptor.dependencies.filterNotNull().mapNotNull { operationDescriptor(it) }.toSet(),
        originPlugin = PluginIdentifier(descriptor.originPlugin?.displayName ?: "Unknown plugin"),
        taskPath = descriptor.taskPath,
        name = descriptor.name,
        displayName = descriptor.displayName
      )

    // ----------------------- TEST -------------------------
    @JvmStatic
    fun testStart(event: org.gradle.tooling.events.test.TestStartEvent): TestStartEvent =
      TestStartEvent(
        eventTime = event.eventTime,
        displayName = event.displayName,
        operationDescriptor = testDescriptor(event.descriptor)
      )

    @JvmStatic
    fun testProgress(event: org.gradle.tooling.events.test.TestProgressEvent): TestProgressEvent =
      TestProgressEvent(
        eventTime = event.eventTime,
        displayName = event.displayName,
        descriptor = testDescriptor(event.descriptor)
      )

    @JvmStatic
    fun testFinish(event: org.gradle.tooling.events.test.TestFinishEvent): TestFinishEvent =
      TestFinishEvent(
        eventTime = event.eventTime,
        displayName = event.displayName,
        operationDescriptor = testDescriptor(event.descriptor),
        result = testResult(event.result)
      )

    private fun testResult(
      result: org.gradle.tooling.events.test.TestOperationResult
    ): TestOperationResult =
      TestOperationResult(
        startTime = result.startTime,
        endTime = result.endTime,
        success = result is TestSuccessResult
      )

    private fun testDescriptor(
      descriptor: TestOperationDescriptor
    ): org.appdevforall.codeonthego.tooling.events.test.TestOperationDescriptor =
      org.appdevforall.codeonthego.tooling.events.test.TestOperationDescriptor(
        name = descriptor.name,
        displayName = descriptor.displayName
      )

    // ----------------------- TRANSFORM -------------------------
    @JvmStatic
    fun transformStart(
      event: org.gradle.tooling.events.transform.TransformStartEvent
    ): TransformStartEvent =
      TransformStartEvent(
        eventTime = event.eventTime,
        displayName = event.displayName,
        descriptor = transformDescriptor(event.descriptor)
      )

    @JvmStatic
    fun transformProgress(
      event: org.gradle.tooling.events.transform.TransformProgressEvent
    ): TransformStartEvent =
      TransformStartEvent(
        eventTime = event.eventTime,
        displayName = event.displayName,
        descriptor = transformDescriptor(event.descriptor)
      )

    @JvmStatic
    fun transformFinish(
      event: org.gradle.tooling.events.transform.TransformFinishEvent
    ): TransformFinishEvent =
      TransformFinishEvent(
        eventTime = event.eventTime,
        displayName = event.displayName,
        operationDescriptor = transformDescriptor(event.descriptor),
        result = transformResult(event.result)
      )

    private fun transformResult(
      result: org.gradle.tooling.events.transform.TransformOperationResult
    ): TransformOperationResult =
      TransformOperationResult(
        success = result is TransformSuccessResult,
        startTime = result.startTime,
        endTime = result.endTime
      )

    private fun transformDescriptor(
      descriptor: TransformOperationDescriptor
    ): org.appdevforall.codeonthego.tooling.events.transform.TransformOperationDescriptor =
      org.appdevforall.codeonthego.tooling.events.transform.TransformOperationDescriptor(
        name = descriptor.name,
        displayName = descriptor.displayName,
        subject = SubjectDescriptor(descriptor.subject.displayName),
        transformer =
          org.appdevforall.codeonthego.tooling.events.transform.TransformOperationDescriptor
            .TransformerDescriptor(descriptor.transformer.displayName),
        dependencies = descriptor.dependencies.mapNotNull { operationDescriptor(it) }.toSet()
      )

    // ----------------------- WORK ITEM -------------------------
    @JvmStatic
    fun workStart(event: org.gradle.tooling.events.work.WorkItemStartEvent): WorkItemStartEvent =
      WorkItemStartEvent(
        eventTime = event.eventTime,
        displayName = event.displayName,
        descriptor = workDescriptor(event.descriptor)
      )

    @JvmStatic
    fun workProgress(
      event: org.gradle.tooling.events.work.WorkItemProgressEvent
    ): WorkItemProgressEvent =
      WorkItemProgressEvent(
        eventTime = event.eventTime,
        displayName = event.displayName,
        descriptor = workDescriptor(event.descriptor)
      )

    @JvmStatic
    fun workFinish(event: org.gradle.tooling.events.work.WorkItemFinishEvent): WorkItemFinishEvent =
      WorkItemFinishEvent(
        eventTime = event.eventTime,
        displayName = event.displayName,
        operationDescriptor = workDescriptor(event.descriptor),
        result = workResult(event.result)
      )

    private fun workResult(
      result: org.gradle.tooling.events.work.WorkItemOperationResult
    ): WorkItemOperationResult =
      WorkItemOperationResult(
        success = result is WorkItemSuccessResult,
        startTime = result.startTime,
        endTime = result.endTime
      )

    private fun workDescriptor(
      descriptor: WorkItemOperationDescriptor
    ): org.appdevforall.codeonthego.tooling.events.work.WorkItemOperationDescriptor =
      org.appdevforall.codeonthego.tooling.events.work.WorkItemOperationDescriptor(
        name = descriptor.name,
        displayName = descriptor.displayName,
        className = descriptor.className
      )

    // ---------------------------- STATUS ---------------------------------
    fun statusEvent(event: org.gradle.tooling.events.StatusEvent): StatusEvent =
      StatusEvent(
        total = event.total,
        progress = event.progress,
        unit = event.unit,
        displayName = event.displayName,
        eventTime = event.eventTime,
        descriptor = operationDescriptor(event.descriptor)!!
      )

    // ----------------------- DEFAULT ----------------------------------
    fun progress(event: ProgressEvent): org.appdevforall.codeonthego.tooling.events.ProgressEvent =
      DefaultProgressEvent(
        eventTime = event.eventTime,
        displayName = event.displayName,
        descriptor = operationDescriptor(event.descriptor)!!
      )

    fun start(
      event: org.gradle.tooling.events.StartEvent
    ): org.appdevforall.codeonthego.tooling.events.ProgressEvent =
      DefaultStartEvent(
        eventTime = event.eventTime,
        displayName = event.displayName,
        descriptor = operationDescriptor(event.descriptor)!!
      )

    fun finish(
      event: org.gradle.tooling.events.FinishEvent
    ): org.appdevforall.codeonthego.tooling.events.ProgressEvent =
      DefaultFinishEvent(
        eventTime = event.eventTime,
        displayName = event.displayName,
        descriptor = operationDescriptor(event.descriptor)!!,
        result = DefaultOperationResult(event.result.startTime, event.result.endTime)
      )
  }
}
