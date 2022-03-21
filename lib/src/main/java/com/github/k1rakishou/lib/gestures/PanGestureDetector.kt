package com.github.k1rakishou.lib.gestures

import android.graphics.PointF
import android.os.SystemClock
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.Density
import com.github.k1rakishou.lib.ComposeSubsamplingScaleImageState
import kotlin.math.absoluteValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

class PanGestureDetector(
  private val density: Density,
  private val state: ComposeSubsamplingScaleImageState,
) : GestureDetector(DetectorType.Pan, state.debug) {
  private val velocityTracker = VelocityTracker()

  // vCenterStart
  private val screenCenterStart = PointF(0f, 0f)
  // vTranslateStart
  private val screenTranslateStart = PointF(0f, 0f)

  private val startOffset = PointF(0f, 0f)

  private var coroutineScope: CoroutineScope? = null
  private var isPanning = false
  private var animatingFling = false

  override fun onGestureStarted(pointerInputChange: PointerInputChange) {
    super.onGestureStarted(pointerInputChange)

    coroutineScope?.cancel()
    coroutineScope = CoroutineScope(Dispatchers.Main)

    isPanning = false
    animatingFling = false

    val offset = pointerInputChange.position
    velocityTracker.resetTracking()
    velocityTracker.addPointerInputChange(pointerInputChange)

    startOffset.set(offset.x, offset.y)
    screenCenterStart.set(offset.x, offset.y)
    screenTranslateStart.set(
      state.screenTranslate.x.toFloat(),
      state.screenTranslate.y.toFloat()
    )
  }

  override fun onGestureUpdated(pointerInputChange: PointerInputChange) {
    super.onGestureUpdated(pointerInputChange)

    val offset = pointerInputChange.position
    velocityTracker.addPointerInputChange(pointerInputChange)

    val dx: Float = Math.abs(offset.x - screenCenterStart.x)
    val dy: Float = Math.abs(offset.y - screenCenterStart.y)

    val minOffset: Float = density.density * 5
    if (dx > minOffset || dy > minOffset || isPanning) {
      state.screenTranslate.set(
        x = (screenTranslateStart.x + (offset.x - screenCenterStart.x)).toInt(),
        y = (screenTranslateStart.y + (offset.y - screenCenterStart.y)).toInt()
      )

      val lastX: Float = state.screenTranslate.x.toFloat()
      val lastY: Float = state.screenTranslate.y.toFloat()
      state.fitToBounds(true)
      val atXEdge = lastX != state.screenTranslate.x.toFloat()
      val atYEdge = lastY != state.screenTranslate.y.toFloat()
      val edgeXSwipe = atXEdge && dx > dy && !isPanning
      val edgeYSwipe = atYEdge && dy > dx && !isPanning
      val yPan = lastY == state.screenTranslate.y.toFloat() && dy > minOffset * 3

      if (!edgeXSwipe && !edgeYSwipe && (!atXEdge || !atYEdge || yPan || isPanning)) {
        isPanning = true
      }
    }
  }

  override fun onGestureEnded(canceled: Boolean, pointerInputChange: PointerInputChange) {
    velocityTracker.addPointerInputChange(pointerInputChange)
    val endOffset = pointerInputChange.position
    val minVelocity = state.minFlingVelocityPxPerSecond
    val minDist = state.minFlingMoveDistPx

    if (
      !animatingFling &&
      isPanning &&
      coroutineScope != null &&
      ((endOffset.x - startOffset.x).absoluteValue > minDist || (endOffset.y - startOffset.y).absoluteValue > minDist)
    ) {
      val velocity = velocityTracker.calculateVelocity()

      if (velocity.x.absoluteValue > minVelocity || velocity.y.absoluteValue > minVelocity) {
        if (currentGestureAnimation != null) {
          return
        }

        animatingFling = true

        initAndStartFlingAnimation(
          debug = debug,
          velocityX = velocity.x,
          velocityY = velocity.y,
          pointerInputChange = pointerInputChange
        )

        return
      }

      // fallthrough
    }

    super.onGestureEnded(canceled, pointerInputChange)

    screenCenterStart.set(0f, 0f)
    screenTranslateStart.set(0f, 0f)
    startOffset.set(0f, 0f)
    isPanning = false
    animatingFling = false
    velocityTracker.resetTracking()
    state.refreshRequiredTiles(load = true)

    coroutineScope?.cancel()
    coroutineScope = null
  }

  private fun initAndStartFlingAnimation(
    debug: Boolean,
    velocityX: Float,
    velocityY: Float,
    pointerInputChange: PointerInputChange
  ) {
    currentGestureAnimation = GestureAnimation<PanAnimationParameters>(
      debug = debug,
      coroutineScope = coroutineScope!!,
      canBeCanceled = true,
      durationMs = state.panFlingAnimationDurationMs,
      animationUpdateIntervalMs = state.animationUpdateIntervalMs,
      animationParams = {
        val currentScale = state.currentScale

        val vTranslateEnd = PointF(
          state.screenTranslate.x + (velocityX * 0.25f),
          state.screenTranslate.y + (velocityY * 0.25f)
        )
        val sCenterXEnd: Float = (state.availableWidth / 2 - vTranslateEnd.x) / currentScale
        val sCenterYEnd: Float = (state.availableHeight / 2 - vTranslateEnd.y) / currentScale

        val sCenter = PointF(sCenterXEnd, sCenterYEnd)

        val vxCenter = state.availableWidth / 2f
        val vyCenter = state.availableHeight / 2f

        val vFocusStart = state.sourceToViewCoord(sCenter)
        val vFocusEnd = PointF(vxCenter, vyCenter)

        return@GestureAnimation PanAnimationParameters(
          gestureAnimationEasing = GestureAnimationEasing.EaseOutQuad,
          startTime = SystemClock.elapsedRealtime(),
          startScale = currentScale,
          endScale = currentScale,
          sCenter = sCenter,
          sCenterEnd = sCenter,
          vFocusStart = vFocusStart,
          vFocusEnd = vFocusEnd,
        )
      },
      animation = { params: PanAnimationParameters, _: Float, duration: Long ->
        val startScale = params.startScale
        val endScale = params.endScale

        var timeElapsed = SystemClock.elapsedRealtime() - params.startTime
        val finished = timeElapsed > duration
        timeElapsed = Math.min(timeElapsed, duration)

        val vFocusNowX = state.ease(
          gestureAnimationEasing = params.gestureAnimationEasing,
          time = timeElapsed,
          from = params.vFocusStart.x,
          change = params.vFocusEnd.x - params.vFocusStart.x,
          duration = duration
        )
        val vFocusNowY = state.ease(
          gestureAnimationEasing = params.gestureAnimationEasing,
          time = timeElapsed,
          from = params.vFocusStart.y,
          change = params.vFocusEnd.y - params.vFocusStart.y,
          duration = duration
        )

        state.screenTranslate.xState.value -= (state.sourceToViewX(params.sCenterEnd.x) - vFocusNowX).toInt()
        state.screenTranslate.yState.value -= (state.sourceToViewY(params.sCenterEnd.y) - vFocusNowY).toInt()

        state.fitToBounds(finished || startScale == endScale)
        state.refreshRequiredTiles(finished)
      },
      onAnimationEnd = { canceled ->
        onGestureEnded(
          canceled = canceled,
          pointerInputChange = pointerInputChange
        )

        animatingFling = false
      }
    )

    currentGestureAnimation!!.start()
  }

  private data class PanAnimationParameters(
    val gestureAnimationEasing: GestureAnimationEasing,
    val startTime: Long,
    val startScale: Float,
    val endScale: Float,
    val sCenter: PointF,
    val sCenterEnd: PointF,
    val vFocusStart: PointF,
    val vFocusEnd: PointF,
  )

}