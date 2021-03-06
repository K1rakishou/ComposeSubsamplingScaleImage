package com.github.k1rakishou.cssi_lib.helpers

import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter

internal inline fun logcat(
  tag: String,
  message: () -> String
) {
  Log.d(tag, message())
}

internal inline fun logcatError(
  tag: String,
  message: () -> String
) {
  Log.e(tag, message())
}

internal fun Throwable.asLog(): String {
  val stringWriter = StringWriter(256)
  val printWriter = PrintWriter(stringWriter, false)
  printStackTrace(printWriter)
  printWriter.flush()
  return stringWriter.toString()
}
