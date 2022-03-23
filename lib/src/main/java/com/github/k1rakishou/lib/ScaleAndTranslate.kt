package com.github.k1rakishou.lib

import android.graphics.PointF

internal class ScaleAndTranslate(
  var scale: Float = 0f,
  val vTranslate: PointF = PointF(0f, 0f)
) {
  fun reset() {
    scale = 0f
    vTranslate.set(0f, 0f)
  }
}