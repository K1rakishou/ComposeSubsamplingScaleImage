package com.github.k1rakishou.lib

import android.graphics.PointF
import androidx.compose.runtime.mutableStateOf

class PointState {
  val xState = mutableStateOf(0)
  val yState = mutableStateOf(0)

  val x: Int
    get() = xState.value
  val y: Int
    get() = yState.value
  val isEmpty: Boolean
    get() = xState.value <= 0 && yState.value <= 0

  fun set(x: Int, y: Int) {
    this.xState.value = x
    this.yState.value = y
  }

  fun set(pointF: PointF) {
    this.xState.value = pointF.x.toInt()
    this.yState.value = pointF.y.toInt()
  }

  fun reset() {
    xState.value = 0
    yState.value = 0
  }
}