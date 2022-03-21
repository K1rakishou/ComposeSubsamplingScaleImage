package com.github.k1rakishou.lib.gestures

import androidx.annotation.CallSuper
import androidx.compose.ui.input.pointer.PointerInputChange
import com.github.k1rakishou.lib.helpers.logcat

abstract class GestureDetector(
  val detectorType: DetectorType,
  val debug: Boolean
) {
  protected var currentGestureAnimation: GestureAnimation<*>? = null

  val animating: Boolean
    get() = currentGestureAnimation?.animating ?: false

  fun cancelAnimation() {
    val canceled = currentGestureAnimation?.cancel() == true
    if (canceled) {
      currentGestureAnimation = null
    }
  }

  @CallSuper
  open fun onGestureStarted(pointerInputChange: PointerInputChange) {
    if (debug) {
      logcat(tag = TAG) { "onGestureStarted() detectorType=${detectorType}" }
    }
  }

  @CallSuper
  open fun onGestureUpdated(pointerInputChange: PointerInputChange) {
    if (debug) {
      logcat(tag = TAG) { "onGestureUpdated() detectorType=${detectorType}" }
    }
  }

  @CallSuper
  open fun onGestureEnded(canceled: Boolean, pointerInputChange: PointerInputChange) {
    if (debug) {
      logcat(tag = TAG) { "onGestureEnded() canceled=$canceled, detectorType=${detectorType}" }
    }
  }

  companion object {
    private const val TAG = "GestureDetector"
  }
}

enum class DetectorType(val index: Int) {
  Zoom(0),
  Pan(1)
}