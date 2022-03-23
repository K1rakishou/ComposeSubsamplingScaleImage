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

  private val vCenterStart = PointF(0f, 0f)
  private val vTranslateStart = PointF(0f, 0f)

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
    vCenterStart.set(offset.x, offset.y)
    vTranslateStart.set(
      state.vTranslate.x.toFloat(),
      state.vTranslate.y.toFloat()
    )
  }

  override fun onGestureUpdated(pointerInputChange: PointerInputChange) {
    super.onGestureUpdated(pointerInputChange)

    val offset = pointerInputChange.position
    velocityTracker.addPointerInputChange(pointerInputChange)

    val dx: Float = Math.abs(offset.x - vCenterStart.x)
    val dy: Float = Math.abs(offset.y - vCenterStart.y)

    val minOffset: Float = density.density * 5
    if (dx > minOffset || dy > minOffset || isPanning) {
      state.vTranslate.set(
        x = (vTranslateStart.x + (offset.x - vCenterStart.x)).toInt(),
        y = (vTranslateStart.y + (offset.y - vCenterStart.y)).toInt()
      )

      val lastX: Float = state.vTranslate.x.toFloat()
      val lastY: Float = state.vTranslate.y.toFloat()
      state.fitToBounds(true)
      val atXEdge = lastX != state.vTranslate.x.toFloat()
      val atYEdge = lastY != state.vTranslate.y.toFloat()
      val edgeXSwipe = atXEdge && dx > dy && !isPanning
      val edgeYSwipe = atYEdge && dy > dx && !isPanning
      val yPan = lastY == state.vTranslate.y.toFloat() && dy > minOffset * 3

      if (!edgeXSwipe && !edgeYSwipe && (!atXEdge || !atYEdge || yPan || isPanning)) {
        isPanning = true
        state.refreshRequiredTiles(load = false)
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

    vCenterStart.set(0f, 0f)
    vTranslateStart.set(0f, 0f)
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
      durationMs = state.flingAnimationDurationMs,
      animationUpdateIntervalMs = state.animationUpdateIntervalMs.toLong(),
      animationParams = {
        val currentScale = state.currentScale

        val vTranslateEnd = PointF(
          state.vTranslate.x + (velocityX * 0.25f),
          state.vTranslate.y + (velocityY * 0.25f)
        )
        val sCenterXEnd: Float = (state.viewWidth / 2 - vTranslateEnd.x) / currentScale
        val sCenterYEnd: Float = (state.viewHeight / 2 - vTranslateEnd.y) / currentScale

        val sCenter = PointF(sCenterXEnd, sCenterYEnd)

        val vxCenter = state.viewWidth / 2f
        val vyCenter = state.viewHeight / 2f

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

        state.vTranslate.xState.value -= (state.sourceToViewX(params.sCenterEnd.x) - vFocusNowX).toInt()
        state.vTranslate.yState.value -= (state.sourceToViewY(params.sCenterEnd.y) - vFocusNowY).toInt()

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