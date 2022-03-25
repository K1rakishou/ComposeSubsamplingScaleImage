package com.github.k1rakishou.cssi_lib.gestures

import android.os.SystemClock
import com.github.k1rakishou.cssi_lib.ComposeSubsamplingScaleImageState
import com.github.k1rakishou.cssi_lib.helpers.logcat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch


class GestureAnimation<Params>(
  val debug: Boolean,
  val detectorType: DetectorType,
  val state: ComposeSubsamplingScaleImageState,
  val coroutineScope: CoroutineScope,
  val durationMs: Int,
  val animationUpdateIntervalMs: Long,
  val animationParams: () -> Params,
  val animationFunc: suspend (Params, Float, Long) -> Unit,
  val onAnimationEnd: (Boolean) -> Unit
) {
  @Volatile private var animationJob: Job? = null

  val animating: Boolean
    get() = animationJob != null

  fun start() {
    if (debug) {
      logcat(tag = TAG) { "Animation start($detectorType)" }
    }

    animationJob?.cancel()
    animationJob = coroutineScope.launch {
      val job = coroutineContext[Job]!!

      val startTime = SystemClock.elapsedRealtime()
      val params = animationParams()
      var progress = 0f
      var endedNormally = false

      try {
        while (job.isActive) {
          job.ensureActive()

          if (!state.isReadyForGestures || animationJob == null) {
            break
          }

          animationFunc(params, progress, durationMs.toLong())
          delay(animationUpdateIntervalMs)

          if (progress >= 1f) {
            break
          }

          val timePassed = SystemClock.elapsedRealtime() - startTime
          progress = timePassed.toFloat() / durationMs.toFloat()

          if (progress > 1f) {
            progress = 1f
          }
        }

        endedNormally = true
      } finally {
        if (debug) {
          logcat(tag = TAG) { "Animation end($detectorType) endedNormally=$endedNormally" }
        }

        animationJob = null
      }
    }
  }

  fun cancel() {
    if (debug) {
      logcat(tag = TAG) {
        "Animation cancel($detectorType) jobActive=${animationJob?.isActive == true}"
      }
    }

    animationJob?.cancel()
    animationJob = null

    onAnimationEnd(true)
  }

  companion object {
    private const val TAG = "GestureAnimation"
  }

}

enum class GestureAnimationEasing {
  EaseOutQuad,
  EaseInOutQuad
}