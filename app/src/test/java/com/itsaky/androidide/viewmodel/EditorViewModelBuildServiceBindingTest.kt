package com.itsaky.androidide.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.services.builder.GradleBuildService
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class EditorViewModelBuildServiceBindingTest {
	private lateinit var app: Application
	private lateinit var store: ViewModelStore
	private lateinit var viewModel: EditorViewModel

	@Before
	fun setUp() {
		app = ApplicationProvider.getApplicationContext()
		shadowOf(app).declareComponentUnbindable(ComponentName(app, GradleBuildService::class.java))
		store = ViewModelStore()
		viewModel = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[EditorViewModel::class.java]
	}

	@Test
	fun bindsThroughTheApplicationContext() {
		val activityContext = RecordingContext(app)

		viewModel.bindBuildService(activityContext) {}

		assertThat(activityContext.bindCalls).isEqualTo(0)
		assertThat(shadowOf(app).boundServiceConnections).hasSize(1)
	}

	@Test
	fun clearingTheViewModelUnbindsTheBuildService() {
		viewModel.bindBuildService(app) {}

		store.clear()

		assertThat(shadowOf(app).unboundServiceConnections).hasSize(1)
		assertThat(shadowOf(app).boundServiceConnections).isEmpty()
	}

	@Test
	fun unbindingBeforeClearDoesNotUnbindTwice() {
		viewModel.bindBuildService(app) {}

		viewModel.unbindBuildService()
		store.clear()

		assertThat(shadowOf(app).unboundServiceConnections).hasSize(1)
	}

	@Test
	fun clearingWithoutBindingDoesNotUnbind() {
		store.clear()

		assertThat(shadowOf(app).unboundServiceConnections).isEmpty()
	}

	@Test
	fun unbindingResetsTheBoundFlag() {
		viewModel.bindBuildService(app) {}
		viewModel.isBoundToBuildSerice = true

		viewModel.unbindBuildService()

		assertThat(viewModel.isBoundToBuildSerice).isFalse()
	}

	private class RecordingContext(
		private val app: Application,
	) : ContextWrapper(app) {
		var bindCalls = 0

		override fun getApplicationContext(): Context = app

		override fun bindService(
			service: Intent,
			conn: ServiceConnection,
			flags: Int,
		): Boolean {
			bindCalls++
			return super.bindService(service, conn, flags)
		}
	}
}
