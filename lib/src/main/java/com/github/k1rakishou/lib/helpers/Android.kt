package com.github.k1rakishou.lib.helpers

import android.os.Build

fun isAndroid11(): Boolean {
  return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
}
