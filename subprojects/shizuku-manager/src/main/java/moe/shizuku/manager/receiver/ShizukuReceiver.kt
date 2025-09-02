package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.shell.ShellBinderRequestHandler

class ShizukuReceiver : BroadcastReceiver() {
	override fun onReceive(
		context: Context,
		intent: Intent,
	) {
		ShellBinderRequestHandler.handleRequest(context, intent)
	}
}
