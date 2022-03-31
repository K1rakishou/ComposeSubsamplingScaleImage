package com.github.k1rakishou.cssi_lib.gestures

import androidx.annotation.CallSuper
import androidx.compose.ui.input.pointer.PointerInputChange
import com.github.k1rakishou.cssi_lib.gestures.GestureAction.End
import com.github.k1rakishou.cssi_lib.gestures.GestureAction.Start
import com.github.k1rakishou.cssi_lib.gestures.GestureAction.Update
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

  private var prevGestureAction: GestureAction? = null

  val animating: Boolean
    get() {
      if (currentGestureAnimation?.animating == true) {
        return true
      }

      return when (prevGestureAction) {
        Start -> false
        Update -> true
        End -> false
        null -> false
      }
    }

  fun cancelAnimation() {
    currentGestureAnimation?.cancel()
    currentGestureAnimation = null
  }

  @CallSuper
  open fun onGestureStarted(pointerInputChanges: List<PointerInputChange>) {
    if (debug) {
      logcat(tag = TAG) { "onGestureStarted() detectorType=${detectorType}" }
    }

    validateGestureStart()

    coroutineScope?.cancel()
    coroutineScope = CoroutineScope(Dispatchers.Main)
  }

  @CallSuper
  open fun onGestureUpdated(pointerInputChanges: List<PointerInputChange>) {
    if (debug) {
      logcat(tag = TAG) { "onGestureUpdated() detectorType=${detectorType}" }
    }

    validateGestureUpdate()
  }

  @CallSuper
  open fun onGestureEnded(canceled: Boolean, pointerInputChanges: List<PointerInputChange>) {
    if (debug) {
      logcat(tag = TAG) { "onGestureEnded() canceled=$canceled, detectorType=${detectorType}" }
    }

    validateGestureEnd()

    coroutineScope?.cancel()
    coroutineScope = null
  }

  private fun validateGestureStart() {
    check(prevGestureAction == null || prevGestureAction == End) {
      "Unexpected prevGestureAction: $prevGestureAction, expected null or End"
    }
    prevGestureAction = Start
  }

  private fun validateGestureUpdate() {
    check(prevGestureAction == Start || prevGestureAction == Update) {
      "Unexpected prevGestureAction: $prevGestureAction, expected Start or Update"
    }
    prevGestureAction = Update
  }

  private fun validateGestureEnd() {
    check(prevGestureAction == Start || prevGestureAction == Update) {
      "Unexpected prevGestureAction: $prevGestureAction, expected Start or Update"
    }
    prevGestureAction = End
  }

  companion object {
    private const val TAG = "GestureDetector"
  }
}

internal enum class GestureAction {
  Start,
  Update,
  End
}

enum class DetectorType(val index: Int) {
  Zoom(0),
  Pan(1),
  MultiTouch(2),
  Tap(3)
}