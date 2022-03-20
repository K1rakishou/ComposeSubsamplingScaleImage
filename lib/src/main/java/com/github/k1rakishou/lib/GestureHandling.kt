package com.github.k1rakishou.lib

import android.graphics.PointF
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.consumeAllChanges
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import com.github.k1rakishou.lib.helpers.logcat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "SubsamplingScaleImageGestureHandling"

fun Modifier.composeSubsamplingScaleImageGestureDetector(
  zoomGesture: ZoomGesture? = null
) = composed(
  inspectorInfo = {
    name = "composeSubsamplingScaleImageGestureDetector"
  },
  factory = {
    pointerInput(
      key1 = Unit,
      block = {
        processGestures(zoomGesture)
      }
    )
  }
)

private suspend fun PointerInputScope.processGestures(
  zoomGesture: ZoomGesture? = null
) {
  val activeDetectors = arrayOfNulls<Job?>(Detector.values().size)

  forEachGesture {
    activeDetectors.forEachIndexed { index, job ->
      job?.cancel()
      activeDetectors[index] = null
    }

    coroutineScope {
      // TODO(KurobaEx): consume horizontal/vertical scroll in Horizontal/Vertical Pager
      // TODO(KurobaEx): detect horizontal/vertical touch slop
      // TODO(KurobaEx): continue with the gesture or stop consuming horizontal/vertical scroll in Horizontal/Vertical Pager

      activeDetectors[Detector.Zoom.index] = launch {
        detectZoomGestures(zoomGesture, this)
      }
    }
  }
}

private suspend fun PointerInputScope.detectZoomGestures(
  zoomGesture: ZoomGesture?,
  coroutineScope: CoroutineScope
) {
  awaitPointerEventScope {
    val firstDown = awaitFirstDownOnPass(
      pass = PointerEventPass.Initial,
      requireUnconsumed = false
    )

    if (zoomGesture == null) {
      return@awaitPointerEventScope
    }

    val firstUpOrCancel = waitForUpOrCancellation()
      ?: return@awaitPointerEventScope
    val secondDown = awaitSecondDown(firstUpOrCancel)
      ?: return@awaitPointerEventScope

    secondDown.consumeAllChanges()

    var lastPosition: Offset = secondDown.position

    try {
      zoomGesture.onZoomStarted(secondDown.position)

      while (coroutineScope.isActive) {
        val pointerEvent = awaitPointerEvent(pass = PointerEventPass.Main)
        val pointerInputChange = pointerEvent.changes
          .firstOrNull { it.id == secondDown.id }
          ?: break

        if (pointerInputChange.changedToUpIgnoreConsumed()) {
          break
        }

        zoomGesture.onZooming(pointerInputChange.position)
        pointerInputChange.consumeAllChanges()
        lastPosition = pointerInputChange.position
      }
    } finally {
      zoomGesture.onZoomEnded(lastPosition)
    }
  }
}

private suspend fun AwaitPointerEventScope.awaitFirstDownOnPass(
  pass: PointerEventPass,
  requireUnconsumed: Boolean
): PointerInputChange {
  var event: PointerEvent
  do {
    event = awaitPointerEvent(pass)
  } while (
    !event.changes.fastAll {
      if (requireUnconsumed) it.changedToDown() else it.changedToDownIgnoreConsumed()
    }
  )

  return event.changes[0]
}

private suspend fun AwaitPointerEventScope.awaitSecondDown(
  firstUp: PointerInputChange
): PointerInputChange? = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
  val minUptime = firstUp.uptimeMillis + viewConfiguration.doubleTapMinTimeMillis
  var change: PointerInputChange

  // The second tap doesn't count if it happens before DoubleTapMinTime of the first tap
  do {
    change = awaitFirstDown()
  } while (change.uptimeMillis < minUptime)

  return@withTimeoutOrNull change
}

enum class Detector(val index: Int) {
  Zoom(0)
}

class ZoomGesture(
  private val density: Density,
  private val state: ComposeSubsamplingScaleImageState
) {
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

  fun onZoomStarted(offset: Offset) {
    val currentScale = state.scaleState.value
    val screenTranslateX = state.screenTranslate.x.toFloat()
    val screenTranslateY = state.screenTranslate.y.toFloat()

    // Store quick scale params. This will become either a double tap zoom or a
    // quick scale depending on whether the user swipes.
    screenCenterStart.set(offset.x, offset.y)
    screenTranslateStart.set(screenTranslateX, screenTranslateY)
    scaleStart = currentScale
    isQuickScaling = true
    isZooming = true
    quickScaleLastDistance = -1f
    quickScaleSourceCenter.set(state.viewToSourceCoord(screenCenterStart))
    quickScaleScreenStart.set(offset.x, offset.y)
    quickScaleScreenLastPoint.set(quickScaleSourceCenter.x, quickScaleSourceCenter.y)
    quickScaleMoved = false
  }

  fun onZoomEnded(offset: Offset) {
    screenCenterStart.set(0f, 0f)
    screenTranslateStart.set(0f, 0f)
    quickScaleSourceCenter.set(0f, 0f)
    quickScaleScreenStart.set(0f, 0f)
    quickScaleScreenLastPoint.set(0f, 0f)
    scaleStart = 0f
    isQuickScaling = false
    isZooming = false
    quickScaleMoved = false
    quickScaleLastDistance = 0f
  }

  fun onZooming(offset: Offset) {
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
        logcat(tag = TAG) { "quickScaleSourceCenter=$quickScaleSourceCenter, vCenterStart=$screenCenterStart" }

        screenTranslateStart.set(state.screenTranslate.x.toFloat(), state.screenTranslate.y.toFloat())
        scaleStart = newScale
        dist = 0f
      }
    }

    quickScaleLastDistance = dist

    state.fitToBounds(true)

    // TODO(KurobaEx): Use debouncing here? Since onZooming will be called on each finger move.
    state.refreshRequiredTiles()
  }

  fun debugDraw(drawScope: DrawScope) {
    val style = Stroke(width = 4f)

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

}