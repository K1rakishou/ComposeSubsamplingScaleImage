package com.github.k1rakishou.cssi_lib.gestures

import android.os.SystemClock
import com.github.k1rakishou.cssi_lib.helpers.errorMessageOrClassName
import com.github.k1rakishou.cssi_lib.helpers.logcat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch


class GestureAnimation<Params>(
  val debug: Boolean,
  val coroutineScope: CoroutineScope,
  val canBeCanceled: Boolean,
  val durationMs: Int,
  val animationUpdateIntervalMs: Long,
  val animationParams: () -> Params,
  val animation: suspend (Params, Float, Long) -> Unit,
  val onAnimationEnd: (Boolean) -> Unit
) {
  private var animationJob: Job? = null

  val animating: Boolean
    get() = animationJob != null

  fun start() {
    if (debug) {
      logcat(tag = TAG) { "Animation start()" }
    }

    animationJob?.cancel()
    animationJob = coroutineScope.launch {
      val job = coroutineContext[Job]!!

      job.invokeOnCompletion { cause ->
        if (debug) {
          logcat(tag = TAG) { "Animation OnCompletion, cause=${cause?.errorMessageOrClassName()}" }
        }

        val canceled = cause is CancellationException
        onAnimationEnd(canceled)
      }

      val startTime = SystemClock.elapsedRealtime()
      val params = animationParams()
      var progress = 0f

      try {
        while (job.isActive) {
          job.ensureActive()

          animation(params, progress, durationMs.toLong())
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
      } finally {
        if (progress < 1f) {
          animation(params, 1f, durationMs.toLong())
        }

        animationJob = null
      }
    }
  }

  fun cancel(): Boolean {
    if (debug) {
      logcat(tag = TAG) { "Animation cancel() canBeCanceled=$canBeCanceled" }
    }

    if (canBeCanceled) {
      animationJob?.cancel()
      animationJob = null

      return true
    }

    return false
  }

  companion object {
    private const val TAG = "GestureAnimation"
  }

}

enum class GestureAnimationEasing {
  EaseOutQuad,
  EaseInOutQuad
}