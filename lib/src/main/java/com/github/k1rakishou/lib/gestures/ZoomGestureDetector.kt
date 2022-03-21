package com.github.k1rakishou.lib.gestures

import android.graphics.PointF
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.k1rakishou.lib.ComposeSubsamplingScaleImageState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

class ZoomGestureDetector(
  private val density: Density,
  private val state: ComposeSubsamplingScaleImageState,
) : GestureDetector(DetectorType.Zoom, state.debug) {
  private val quickScaleThreshold = with(density) { 20.dp.toPx() }

  // vCenterStart
  private val screenCenterStart = PointF(0f, 0f)
  // vTranslateStart
  private val screenTranslateStart = PointF(0f, 0f)

  // quickScaleSCenter
  private val quickScaleSourceCenter = PointF(0f, 0f)
  // quickScaleVStart
  private val quickScaleScreenStart = PointF(0f, 0f)
  // quickScaleVLastPoint
  private val quickScaleScreenLastPoint = PointF(0f, 0f)

  private var scaleStart = 0f
  private var isQuickScaling = false
  private var isZooming = false
  private var quickScaleMoved = false
  private var quickScaleLastDistance = 0f
  private var animatingQuickZoom = false

  private var coroutineScope: CoroutineScope? = null

  override fun onGestureStarted(pointerInputChange: PointerInputChange) {
    super.onGestureStarted(pointerInputChange)

    coroutineScope?.cancel()
    coroutineScope = CoroutineScope(Dispatchers.Main)

    val offset = pointerInputChange.position
    val currentScale = state.scaleState.value
    val screenTranslateX = state.screenTranslate.x.toFloat()
    val screenTranslateY = state.screenTranslate.y.toFloat()

    screenCenterStart.set(offset.x, offset.y)
    screenTranslateStart.set(screenTranslateX, screenTranslateY)
    scaleStart = currentScale
    isQuickScaling = true
    isZooming = true
    animatingQuickZoom = false
    quickScaleLastDistance = -1f
    quickScaleSourceCenter.set(state.viewToSourceCoord(screenCenterStart))
    quickScaleScreenStart.set(offset.x, offset.y)
    quickScaleScreenLastPoint.set(quickScaleSourceCenter.x, quickScaleSourceCenter.y)
    quickScaleMoved = false
  }

  override fun onGestureEnded(canceled: Boolean, pointerInputChange: PointerInputChange) {
    if (!animatingQuickZoom && !quickScaleMoved && coroutineScope != null) {
      if (currentGestureAnimation != null) {
        return
      }

      animatingQuickZoom = true
      initAndStartQuickZoomAnimation(debug, pointerInputChange)

      return
    }

    super.onGestureEnded(canceled, pointerInputChange)

    screenCenterStart.set(0f, 0f)
    screenTranslateStart.set(0f, 0f)
    quickScaleSourceCenter.set(0f, 0f)
    quickScaleScreenStart.set(0f, 0f)
    quickScaleScreenLastPoint.set(0f, 0f)
    scaleStart = 0f
    isQuickScaling = false
    isZooming = false
    animatingQuickZoom = false
    quickScaleMoved = false
    quickScaleLastDistance = 0f
    currentGestureAnimation = null
    state.refreshRequiredTiles(load = true)

    coroutineScope?.cancel()
    coroutineScope = null
  }

  override fun onGestureUpdated(pointerInputChange: PointerInputChange) {
    super.onGestureUpdated(pointerInputChange)

    val offset = pointerInputChange.position
    var dist = Math.abs(quickScaleScreenStart.y - offset.y) * 2 + quickScaleThreshold

    if (quickScaleLastDistance == -1f) {
      quickScaleLastDistance = dist
    }

    val isUpwards: Boolean = offset.y > quickScaleScreenLastPoint.y
    quickScaleScreenLastPoint.set(0f, offset.y)
    val spanDiff = Math.abs(1 - (dist / quickScaleLastDistance)) * 0.5f

    if (spanDiff > 0.03f || quickScaleMoved) {
      quickScaleMoved = true

      var multiplier = 1f
      if (quickScaleLastDistance > 0) {
        multiplier = if (isUpwards) (1 + spanDiff) else (1 - spanDiff)
      }

      val previousScale = state.currentScale.toDouble()
      val newScale = Math.max(
        state.calculateMinScale(),
        Math.min(state.maxScale, previousScale.toFloat() * multiplier)
      )
      state.scaleState.value = newScale

      val vLeftStart: Float = screenCenterStart.x - screenTranslateStart.x
      val vTopStart: Float = screenCenterStart.y - screenTranslateStart.y
      val vLeftNow: Float = vLeftStart * (newScale / scaleStart)
      val vTopNow: Float = vTopStart * (newScale / scaleStart)

      state.screenTranslate.set(
        x = (screenCenterStart.x - vLeftNow).toInt(),
        y = (screenCenterStart.y - vTopNow).toInt()
      )

      if (
        (previousScale * state.sourceHeight < state.availableHeight && newScale * state.sourceHeight >= state.availableHeight) ||
        (previousScale * state.sourceWidth < state.availableWidth && newScale * state.sourceWidth >= state.availableWidth)
      ) {
        state.fitToBounds(true)
        screenCenterStart.set(state.sourceToViewCoord(quickScaleSourceCenter))

        screenTranslateStart.set(state.screenTranslate.x.toFloat(), state.screenTranslate.y.toFloat())
        scaleStart = newScale
        dist = 0f
      }
    }

    quickScaleLastDistance = dist
    state.fitToBounds(true)
    state.refreshRequiredTiles(load = true)
  }

  fun debugDraw(drawScope: DrawScope) {
    val style = Stroke(width = 8f)

    with(drawScope) {
      if (screenCenterStart.x > 0f || screenCenterStart.y > 0f) {
        drawCircle(
          color = Color.Red,
          radius = 20.dp.toPx(),
          style = style,
          center = Offset(screenCenterStart.x, screenCenterStart.y)
        )
      }

      if (quickScaleSourceCenter.x > 0 || quickScaleSourceCenter.y > 0) {
        drawCircle(
          color = Color.Blue,
          radius = 35.dp.toPx(),
          style = style,
          center = Offset(
            state.sourceToViewX(quickScaleSourceCenter.x),
            state.sourceToViewY(quickScaleSourceCenter.y),
          )
        )
      }

      if ((quickScaleScreenStart.x > 0 || quickScaleScreenStart.y > 0) && isQuickScaling) {
        drawCircle(
          color = Color.Cyan,
          radius = 30.dp.toPx(),
          style = style,
          center = Offset(quickScaleScreenStart.x, quickScaleScreenStart.y)
        )
      }
    }
  }

  private fun initAndStartQuickZoomAnimation(debug: Boolean, pointerInputChange: PointerInputChange) {
    val vTranslateBefore = PointF(0f, 0f)

    currentGestureAnimation = GestureAnimation<GestureAnimationParameters>(
      debug = debug,
      coroutineScope = coroutineScope!!,
      canBeCanceled = true,
      durationMs = state.zoomAnimationDurationMs,
      animationUpdateIntervalMs = state.animationUpdateIntervalMs,
      animationParams = {
        val currentScale = state.currentScale
        val minScale = state.minScale
        val doubleTapZoomScale = Math.min(state.maxScale, state.doubleTapZoomScale)

        val zoomIn = currentScale <= doubleTapZoomScale * 0.9 || currentScale == minScale
        val endScale = if (zoomIn) doubleTapZoomScale else minScale

        val targetSCenter = state.limitedSCenter(
          sCenterX = this.quickScaleSourceCenter.x,
          sCenterY = this.quickScaleSourceCenter.y,
          scale = endScale,
          sTarget = PointF()
        )

        val vxCenter = state.availableWidth / 2f
        val vyCenter = state.availableHeight / 2f

        val vFocusStart = state.sourceToViewCoord(targetSCenter)
        val vFocusEnd = PointF(vxCenter, vyCenter)

        return@GestureAnimation GestureAnimationParameters(
          gestureAnimationEasing = GestureAnimationEasing.EaseInOutQuad,
          startTime = SystemClock.elapsedRealtime(),
          startScale = currentScale,
          endScale = endScale,
          targetSourceCenter = quickScaleSourceCenter,
          screenFocus = quickScaleScreenStart,
          vFocusStart = vFocusStart,
          vFocusEnd = vFocusEnd,
          sCenterEnd = targetSCenter
        )
      },
      animation = { params: GestureAnimationParameters, _: Float, duration: Long ->
        val startScale = params.startScale
        val endScale = params.endScale
        vTranslateBefore.set(
          state.screenTranslate.x.toFloat(),
          state.screenTranslate.y.toFloat()
        )

        var timeElapsed = SystemClock.elapsedRealtime() - params.startTime
        val finished = timeElapsed > duration
        timeElapsed = Math.min(timeElapsed, duration)

        val newScale = state.ease(
          gestureAnimationEasing = params.gestureAnimationEasing,
          time = timeElapsed,
          from = startScale,
          change = endScale - startScale,
          duration = duration
        )

        state.scaleState.value = newScale

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

        animatingQuickZoom = false
      }
    )

    currentGestureAnimation!!.start()
  }

  data class GestureAnimationParameters(
    val gestureAnimationEasing: GestureAnimationEasing,
    val startTime: Long,
    val startScale: Float,
    val endScale: Float,
    // sCenter
    val targetSourceCenter: PointF,
    // vFocus
    val screenFocus: PointF,

    val vFocusStart: PointF,
    val vFocusEnd: PointF,

    val sCenterEnd: PointF
  )


}