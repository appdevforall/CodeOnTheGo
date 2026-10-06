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
package org.appdevforall.codeonthego.tooling.api.util

import com.google.gson.GsonBuilder
import org.appdevforall.codeonthego.builder.model.DefaultJavaCompileOptions
import org.appdevforall.codeonthego.builder.model.IJavaCompilerSettings
import org.appdevforall.codeonthego.tooling.api.IToolingApiClient
import org.appdevforall.codeonthego.tooling.api.IToolingApiServer
import org.appdevforall.codeonthego.tooling.api.messages.result.InitializeResult
import org.appdevforall.codeonthego.tooling.api.models.AndroidProjectMetadata
import org.appdevforall.codeonthego.tooling.api.models.AndroidVariantMetadata
import org.appdevforall.codeonthego.tooling.api.models.BasicAndroidVariantMetadata
import org.appdevforall.codeonthego.tooling.api.models.BasicProjectMetadata
import org.appdevforall.codeonthego.tooling.api.models.GradleTask
import org.appdevforall.codeonthego.tooling.api.models.JavaModuleCompilerSettings
import org.appdevforall.codeonthego.tooling.api.models.JavaModuleDependency
import org.appdevforall.codeonthego.tooling.api.models.JavaModuleExternalDependency
import org.appdevforall.codeonthego.tooling.api.models.JavaModuleProjectDependency
import org.appdevforall.codeonthego.tooling.api.models.JavaProjectMetadata
import org.appdevforall.codeonthego.tooling.api.models.Launchable
import org.appdevforall.codeonthego.tooling.api.models.ProjectMetadata
import org.appdevforall.codeonthego.tooling.events.OperationDescriptor
import org.appdevforall.codeonthego.tooling.events.OperationResult
import org.appdevforall.codeonthego.tooling.events.ProgressEvent
import org.appdevforall.codeonthego.tooling.events.StatusEvent
import org.appdevforall.codeonthego.tooling.events.configuration.ProjectConfigurationFinishEvent
import org.appdevforall.codeonthego.tooling.events.configuration.ProjectConfigurationOperationDescriptor
import org.appdevforall.codeonthego.tooling.events.configuration.ProjectConfigurationOperationResult
import org.appdevforall.codeonthego.tooling.events.configuration.ProjectConfigurationProgressEvent
import org.appdevforall.codeonthego.tooling.events.configuration.ProjectConfigurationStartEvent
import org.appdevforall.codeonthego.tooling.events.download.FileDownloadFinishEvent
import org.appdevforall.codeonthego.tooling.events.download.FileDownloadOperationDescriptor
import org.appdevforall.codeonthego.tooling.events.download.FileDownloadProgressEvent
import org.appdevforall.codeonthego.tooling.events.download.FileDownloadResult
import org.appdevforall.codeonthego.tooling.events.download.FileDownloadStartEvent
import org.appdevforall.codeonthego.tooling.events.internal.DefaultFinishEvent
import org.appdevforall.codeonthego.tooling.events.internal.DefaultOperationDescriptor
import org.appdevforall.codeonthego.tooling.events.internal.DefaultOperationResult
import org.appdevforall.codeonthego.tooling.events.internal.DefaultProgressEvent
import org.appdevforall.codeonthego.tooling.events.internal.DefaultStartEvent
import org.appdevforall.codeonthego.tooling.events.task.TaskExecutionResult
import org.appdevforall.codeonthego.tooling.events.task.TaskFailureResult
import org.appdevforall.codeonthego.tooling.events.task.TaskFinishEvent
import org.appdevforall.codeonthego.tooling.events.task.TaskOperationDescriptor
import org.appdevforall.codeonthego.tooling.events.task.TaskOperationResult
import org.appdevforall.codeonthego.tooling.events.task.TaskProgressEvent
import org.appdevforall.codeonthego.tooling.events.task.TaskSkippedResult
import org.appdevforall.codeonthego.tooling.events.task.TaskStartEvent
import org.appdevforall.codeonthego.tooling.events.task.TaskSuccessResult
import org.appdevforall.codeonthego.tooling.events.test.TestFinishEvent
import org.appdevforall.codeonthego.tooling.events.test.TestOperationDescriptor
import org.appdevforall.codeonthego.tooling.events.test.TestOperationResult
import org.appdevforall.codeonthego.tooling.events.test.TestProgressEvent
import org.appdevforall.codeonthego.tooling.events.test.TestStartEvent
import org.appdevforall.codeonthego.tooling.events.transform.TransformFinishEvent
import org.appdevforall.codeonthego.tooling.events.transform.TransformOperationDescriptor
import org.appdevforall.codeonthego.tooling.events.transform.TransformProgressEvent
import org.appdevforall.codeonthego.tooling.events.transform.TransformStartEvent
import org.appdevforall.codeonthego.tooling.events.work.WorkItemFinishEvent
import org.appdevforall.codeonthego.tooling.events.work.WorkItemOperationDescriptor
import org.appdevforall.codeonthego.tooling.events.work.WorkItemOperationResult
import org.appdevforall.codeonthego.tooling.events.work.WorkItemProgressEvent
import org.appdevforall.codeonthego.tooling.events.work.WorkItemStartEvent
import org.eclipse.lsp4j.jsonrpc.Launcher
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Utility class for launching [IToolingApiClient] and [IToolingApiServer].
 *
 * @author Akash Yadav
 */
object ToolingApiLauncher {
	fun <T> createIOLauncher(
		local: Any?,
		remote: Class<T>?,
		`in`: InputStream?,
		out: OutputStream?,
	): Launcher<T> =
		Launcher
			.Builder<T>()
			.setInput(`in`)
			.setOutput(out)
			.setLocalService(local)
			.setRemoteInterface(remote)
			.configureGson { configureGson(it) }
			.create()

	@JvmStatic
	fun configureGson(builder: GsonBuilder) {
		builder.registerTypeAdapter(File::class.java, FileTypeAdapter())

		builder.runtimeTypeAdapter(
			InitializeResult::class.java,
			InitializeResult.Success::class.java,
			InitializeResult.Failure::class.java,
		)

		// some methods return BasicProjectMetadata while some return ProjectMetadata
		// so we need to register type adapter for both of them
		builder.runtimeTypeAdapter(
			BasicProjectMetadata::class.java,
			ProjectMetadata::class.java,
			AndroidProjectMetadata::class.java,
			JavaProjectMetadata::class.java,
		)
		builder.runtimeTypeAdapter(
			ProjectMetadata::class.java,
			AndroidProjectMetadata::class.java,
			JavaProjectMetadata::class.java,
		)
		builder.runtimeTypeAdapter(
			BasicAndroidVariantMetadata::class.java,
			AndroidVariantMetadata::class.java,
		)
		builder.runtimeTypeAdapter(
			JavaModuleDependency::class.java,
			JavaModuleExternalDependency::class.java,
			JavaModuleProjectDependency::class.java,
		)
		builder.runtimeTypeAdapter(
			IJavaCompilerSettings::class.java,
			DefaultJavaCompileOptions::class.java,
			JavaModuleCompilerSettings::class.java,
		)
		builder.runtimeTypeAdapter(
			Launchable::class.java,
			GradleTask::class.java,
		)
		builder.runtimeTypeAdapter(
			ProgressEvent::class.java,
			ProjectConfigurationProgressEvent::class.java,
			ProjectConfigurationStartEvent::class.java,
			ProjectConfigurationFinishEvent::class.java,
			FileDownloadProgressEvent::class.java,
			FileDownloadStartEvent::class.java,
			FileDownloadFinishEvent::class.java,
			TaskProgressEvent::class.java,
			TaskStartEvent::class.java,
			TaskFinishEvent::class.java,
			TestProgressEvent::class.java,
			TestStartEvent::class.java,
			TestFinishEvent::class.java,
			TransformProgressEvent::class.java,
			TransformStartEvent::class.java,
			TransformFinishEvent::class.java,
			WorkItemProgressEvent::class.java,
			WorkItemStartEvent::class.java,
			WorkItemFinishEvent::class.java,
			DefaultProgressEvent::class.java,
			DefaultStartEvent::class.java,
			DefaultFinishEvent::class.java,
			StatusEvent::class.java,
		)
		builder.runtimeTypeAdapter(
			OperationDescriptor::class.java,
			ProjectConfigurationOperationDescriptor::class.java,
			FileDownloadOperationDescriptor::class.java,
			TaskOperationDescriptor::class.java,
			TestOperationDescriptor::class.java,
			TransformOperationDescriptor::class.java,
			WorkItemOperationDescriptor::class.java,
			DefaultOperationDescriptor::class.java,
		)
		builder.runtimeTypeAdapter(
			OperationResult::class.java,
			ProjectConfigurationOperationResult::class.java,
			FileDownloadResult::class.java,
			TaskOperationResult::class.java,
			TestOperationResult::class.java,
			WorkItemOperationResult::class.java,
			DefaultOperationResult::class.java,
		)
		builder.runtimeTypeAdapter(
			TaskOperationResult::class.java,
			TaskFailureResult::class.java,
			TaskSkippedResult::class.java,
			TaskExecutionResult::class.java,
			TaskSuccessResult::class.java,
		)
	}

	private fun <T> GsonBuilder.runtimeTypeAdapter(
		baseClass: Class<T>,
		vararg subtypes: Class<out T>,
	) {
		registerTypeAdapterFactory(
			RuntimeTypeAdapterFactory
				.of(baseClass, "gsonType", true)
				.registerSubtype(baseClass, baseClass.name)
				.also { factory ->
					subtypes.forEach { subtype ->
						factory.registerSubtype(subtype, subtype.name)
					}
				},
		)
	}

	fun newClientLauncher(
		client: IToolingApiClient,
		`in`: InputStream?,
		out: OutputStream?,
		executorService: ExecutorService = Executors.newCachedThreadPool(),
	): Launcher<Any> = newIoLauncher(arrayOf(client), arrayOf(IToolingApiServer::class.java), `in`, out, executorService)

	fun newIoLauncher(
		locals: Array<Any>,
		remotes: Array<Class<*>?>,
		`in`: InputStream?,
		out: OutputStream?,
		executorService: ExecutorService = Executors.newCachedThreadPool(),
	): Launcher<Any> =
		Launcher
			.Builder<Any>()
			.setInput(`in`)
			.setOutput(out)
			.setExecutorService(executorService)
			.setLocalServices(listOf(*locals))
			.setRemoteInterfaces(listOf(*remotes))
			.configureGson { configureGson(it) }
			.setClassLoader(locals[0].javaClass.classLoader)
			.create()

	@JvmStatic
	fun newServerLauncher(
		server: IToolingApiServer,
		`in`: InputStream?,
		out: OutputStream?,
		executorService: ExecutorService = Executors.newCachedThreadPool(),
	): Launcher<Any> =
		newIoLauncher(
			arrayOf(server),
			arrayOf(
				IToolingApiClient::class.java,
			),
			`in`,
			out,
			executorService,
		)
}
