package com.github.k1rakishou.cssi_lib

internal class ScaleAndTranslate(
  var scale: Float = 0f,
  val vTranslate: PointfMut = PointfMut()
) {
  fun reset() {
    scale = 0f
    vTranslate.set(0f, 0f)
  }
}