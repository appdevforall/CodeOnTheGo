package com.itsvks.layouteditor

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import com.itsvks.layouteditor.activities.CrashActivity
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ref.WeakReference
import java.util.Calendar
import kotlin.system.exitProcess

class CrashHandler(myContext: WeakReference<Context?>) : Thread.UncaughtExceptionHandler {
  private val errorMessage = StringBuilder()
  private val softwareInfo = StringBuilder()
  private val dateInfo = StringBuilder()
  private val context: Context?

  init {
    this.context = myContext.get()
  }

  override fun uncaughtException(thread: Thread, exception: Throwable) {
    // Create a StringWriter to write stack trace to
    val stackTrace = StringWriter()

    // Print the stack trace to the StringWriter
    exception.printStackTrace(PrintWriter(stackTrace))

    // Append the stack trace to the error message
    errorMessage.append(stackTrace.toString())

    // Append software information to the software info
    // Declare variables
    val newLine = "\n"
    softwareInfo
      .append("SDK: ")
      .append(Build.VERSION.SDK_INT)
      .append(newLine)
      .append("Android: ")
      .append(Build.VERSION.RELEASE)
      .append(newLine)
      .append("Model: ")
      .append(Build.VERSION.INCREMENTAL)
      .append(newLine)

    // Append the date information to the date info
    dateInfo.append(Calendar.getInstance().time).append(newLine)

    // Log the error message, software info, and date info
    Log.d("Error", errorMessage.toString())
    Log.d("Software", softwareInfo.toString())
    Log.d("Date", dateInfo.toString())

    // Create an intent for the crash activity
    val intent = Intent(context, CrashActivity::class.java)

    // Add the error message, software info, and date info as extras
    intent.putExtra("Error", errorMessage.toString())
    intent.putExtra("Software", softwareInfo.toString())
    intent.putExtra("Date", dateInfo.toString())

    // Start the crash activity
    context!!.startActivity(intent)

    // Kill the process
    Process.killProcess(Process.myPid())

    // Exit with a code of 2
    exitProcess(2)
  }
}
