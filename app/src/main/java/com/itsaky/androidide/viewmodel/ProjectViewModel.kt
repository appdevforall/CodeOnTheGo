package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.slf4j.LoggerFactory

/**
 * Manages the state and logic for project-wide operations like initialization (syncing).
 */
class ProjectViewModel : ViewModel() {

    private val log = LoggerFactory.getLogger(ProjectViewModel::class.java)

    private val _initState = MutableStateFlow<TaskState>(TaskState.Idle)

    val initState: StateFlow<TaskState> = _initState

}