package com.github.k1rakishou.cssi_lib.helpers

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
internal fun isAndroid11(): Boolean {
  return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
}
