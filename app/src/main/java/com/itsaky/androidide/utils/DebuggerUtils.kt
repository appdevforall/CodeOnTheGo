package com.itsaky.androidide.utils

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import com.itsaky.androidide.services.debug.DebuggerService

object DebuggerUtils {

    fun debuggerServiceConnection(
        onConnected: (DebuggerService) -> Unit,
        onDisconnected: () -> Unit,
    ): ServiceConnection {
        return object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val debugger = (service as DebuggerService.Binder).getService()
                onConnected(debugger)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                onDisconnected()
            }
        }
    }
}
