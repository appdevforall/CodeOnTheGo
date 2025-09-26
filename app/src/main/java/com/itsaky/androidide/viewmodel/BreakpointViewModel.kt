package com.itsaky.androidide.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.repositories.BreakpointRepository
import com.itsaky.androidide.roomData.breakpoint.Breakpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BreakpointViewModel(application: Application) : AndroidViewModel(application) {

    private val _breakpoints = MutableLiveData<List<Breakpoint>>()
    val breakpoints: LiveData<List<Breakpoint>> = _breakpoints

    /**
     * Carga todos los breakpoints asociados a un proyecto específico.
     * @param projectLocation La ruta única del proyecto.
     */
    suspend fun loadBreakpointsForProject(projectLocation: String): List<Breakpoint> {
        return withContext(Dispatchers.IO) {
            BreakpointRepository.loadBreakpoints(projectLocation)
        }
    }

    /**
     * Añade un nuevo breakpoint.
     */
    fun insertBreakpoint(breakpoint: Breakpoint) = viewModelScope.launch(Dispatchers.IO) {
        val currentBreakpoints = BreakpointRepository.loadBreakpoints(breakpoint.projectLocation)
        currentBreakpoints.add(breakpoint)
//        BreakpointRepository.saveBreakpoints(breakpoint.projectLocation, currentBreakpoints)

        _breakpoints.postValue(currentBreakpoints)
    }

    /**
     * Un método de ayuda para crear y añadir un breakpoint.
     */
    fun addBreakpoint(projectLocation: String, filePath: String, isEnabled: Boolean, lineNumber: Int) {
        val newBreakpoint = Breakpoint(
            projectLocation = projectLocation,
            filePath = filePath,
            lineNumber = lineNumber,
            isEnabled = isEnabled
        )
        insertBreakpoint(newBreakpoint)
    }

    /**
     * Elimina un breakpoint específico.
     */
    fun deleteBreakpoint(breakpoint: Breakpoint) = viewModelScope.launch(Dispatchers.IO) {
        val currentBreakpoints = BreakpointRepository.loadBreakpoints(breakpoint.projectLocation)
        currentBreakpoints.removeAll {
            it.filePath == breakpoint.filePath && it.lineNumber == breakpoint.lineNumber
        }
//        BreakpointRepository.saveBreakpoints(breakpoint.projectLocation, currentBreakpoints)

        _breakpoints.postValue(currentBreakpoints)
    }
}