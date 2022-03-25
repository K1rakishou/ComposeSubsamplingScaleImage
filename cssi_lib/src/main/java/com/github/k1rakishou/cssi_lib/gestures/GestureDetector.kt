package com.github.k1rakishou.cssi_lib.gestures

import androidx.annotation.CallSuper
import androidx.compose.ui.input.pointer.PointerInputChange
import com.github.k1rakishou.cssi_lib.helpers.logcat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

abstract class GestureDetector(
  val detectorType: DetectorType,
  val debug: Boolean
) {
  protected var currentGestureAnimation: GestureAnimation<*>? = null
  protected var coroutineScope: CoroutineScope? = null

  val animating: Boolean
    get() = currentGestureAnimation?.animating ?: false

  fun cancelAnimation(forced: Boolean = false) {
    val canceled = currentGestureAnimation?.cancel(forced) == true
    if (canceled) {
      currentGestureAnimation = null
    }
  }

  @CallSuper
  open fun onGestureStarted(pointerInputChanges: List<PointerInputChange>) {
    if (debug) {
      logcat(tag = TAG) { "onGestureStarted() detectorType=${detectorType}" }
    }

    coroutineScope?.cancel()
    coroutineScope = CoroutineScope(Dispatchers.Main)
  }

  @CallSuper
  open fun onGestureUpdated(pointerInputChanges: List<PointerInputChange>) {
    if (debug) {
      logcat(tag = TAG) { "onGestureUpdated() detectorType=${detectorType}" }
    }
  }

  @CallSuper
  open fun onGestureEnded(canceled: Boolean, pointerInputChanges: List<PointerInputChange>) {
    if (debug) {
      logcat(tag = TAG) { "onGestureEnded() canceled=$canceled, detectorType=${detectorType}" }
    }

    coroutineScope?.cancel()
    coroutineScope = null
  }

  companion object {
    private const val TAG = "GestureDetector"
  }
}

enum class DetectorType(val index: Int) {
  Zoom(0),
  Pan(1),
  MultiTouch(2)
}