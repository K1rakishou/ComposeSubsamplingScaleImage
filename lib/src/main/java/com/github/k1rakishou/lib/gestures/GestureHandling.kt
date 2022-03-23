package com.github.k1rakishou.lib.gestures

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.consumeAllChanges
import androidx.compose.ui.input.pointer.consumePositionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import com.github.k1rakishou.lib.ComposeSubsamplingScaleImageState
import com.github.k1rakishou.lib.helpers.logcat
import kotlin.math.absoluteValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "ComposeSubsamplingScaleImageGestures"

fun Modifier.composeSubsamplingScaleImageGestureDetector(
  state: ComposeSubsamplingScaleImageState,
  zoomGestureDetector: ZoomGestureDetector? = null,
  panGestureDetector: PanGestureDetector? = null
) = composed(
  inspectorInfo = {
    name = "composeSubsamplingScaleImageGestureDetector"
  },
  factory = {
    pointerInput(
      key1 = Unit,
      block = {
        processGestures(
          state = state,
          zoomGestureDetector = zoomGestureDetector,
          panGestureDetector = panGestureDetector
        )
      }
    )
  }
)

private suspend fun PointerInputScope.processGestures(
  state: ComposeSubsamplingScaleImageState,
  zoomGestureDetector: ZoomGestureDetector?,
  panGestureDetector: PanGestureDetector?
) {
  val quickZoomTimeoutMs = state.quickZoomTimeoutMs
  val activeDetectorJobs = arrayOfNulls<Job?>(DetectorType.values().size)
  val allDetectors = arrayOf(zoomGestureDetector, panGestureDetector).filterNotNull()

  fun stopOtherDetectors(excludeDetectorType: DetectorType) {
    logcat(tag = TAG) { "stopOtherDetectors(excludeDetectorType=$excludeDetectorType)" }

    for (index in activeDetectorJobs.indices) {
      if (index == excludeDetectorType.index) {
        continue
      }

      activeDetectorJobs[index]?.cancel()
      activeDetectorJobs[index] = null
    }
  }

  forEachGesture {
    activeDetectorJobs.forEachIndexed { index, job ->
      job?.cancel()
      activeDetectorJobs[index] = null
    }

    coroutineScope {
      activeDetectorJobs[DetectorType.Zoom.index] = launch {
        detectZoomGestures(
          quickZoomTimeoutMs = quickZoomTimeoutMs,
          zoomGestureDetector = zoomGestureDetector,
          coroutineScope = this,
          detectorType = DetectorType.Zoom,
          cancelAnimations = { allDetectors.fastForEach { detector -> detector.cancelAnimation() } },
          stopOtherDetectors = { detectorType -> stopOtherDetectors(detectorType) },
          gesturesLocked = { allDetectors.fastAny { detector -> detector.animating } }
        )
      }

      activeDetectorJobs[DetectorType.Pan.index] = launch {
        detectPanGestures(
          state = state,
          panGestureDetector = panGestureDetector,
          coroutineScope = this,
          detectorType = DetectorType.Pan,
          cancelAnimations = { allDetectors.fastForEach { detector -> detector.cancelAnimation() } },
          stopOtherDetectors = { detectorType -> stopOtherDetectors(detectorType) },
          gesturesLocked = { allDetectors.fastAny { detector -> detector.animating } }
        )
      }
    }
  }
}

private suspend fun PointerInputScope.detectPanGestures(
  state: ComposeSubsamplingScaleImageState,
  panGestureDetector: PanGestureDetector?,
  coroutineScope: CoroutineScope,
  detectorType: DetectorType,
  cancelAnimations: () -> Unit,
  stopOtherDetectors: (DetectorType) -> Unit,
  gesturesLocked: () -> Boolean
) {
  awaitPointerEventScope {
    val firstDown = awaitFirstDownOnPass(
      pass = PointerEventPass.Initial,
      requireUnconsumed = false
    )

    if (panGestureDetector == null) {
      return@awaitPointerEventScope
    }

    cancelAnimations()

    if (gesturesLocked()) {
      consumeChangesUntilAllPointersAreUp(
        gestureDetector = panGestureDetector,
        pointerInputChange = firstDown,
        coroutineScope = coroutineScope,
        gesturesLocked = gesturesLocked
      )

      return@awaitPointerEventScope
    }

    if (panGestureDetector.debug) {
      logcat(tag = TAG) { "Gestures NOT locked, detectorType=${panGestureDetector.detectorType}" }
    }

    val panInfo = state.getPanInfo()
      ?: return@awaitPointerEventScope

    if (panInfo.touchesLeftAndRight()) {
      return@awaitPointerEventScope
    }

    var skipThisGesture = false

    val touchSlop = awaitTouchSlopOrCancellation(
      pointerId = firstDown.id,
      onTouchSlopReached = { change, _ ->
        val delta = firstDown.position.x - change.position.x
        val panInfoNew = state.getPanInfo()

        if (panInfoNew != null) {
          if (delta < 0 && panInfoNew.touchesLeft()) {
            skipThisGesture = true
            return@awaitTouchSlopOrCancellation
          } else if (delta > 0 && panInfoNew.touchesRight()) {
            skipThisGesture = true
            return@awaitTouchSlopOrCancellation
          }
        }

        change.consumePositionChange()
      }
    )

    if (skipThisGesture || touchSlop == null) {
      return@awaitPointerEventScope
    }

    var lastPointerInputChange: PointerInputChange = firstDown
    var canceled = false

    try {
      stopOtherDetectors(detectorType)
      panGestureDetector.onGestureStarted(firstDown)

      while (coroutineScope.isActive) {
        val pointerEvent = awaitPointerEvent(pass = PointerEventPass.Main)
        val pointerInputChange = pointerEvent.changes
          .fastFirstOrNull { it.id == firstDown.id }
          ?: break

        if (pointerInputChange.changedToUpIgnoreConsumed()) {
          break
        }

        if (pointerInputChange.positionChanged()) {
          panGestureDetector.onGestureUpdated(pointerInputChange)
        }

        pointerInputChange.consumeAllChanges()
        lastPointerInputChange = pointerInputChange
      }
    } catch (error: Throwable) {
      canceled = error is CancellationException
      throw error
    } finally {
      panGestureDetector.onGestureEnded(
        canceled = canceled,
        pointerInputChange = lastPointerInputChange
      )
    }
  }
}

private suspend fun PointerInputScope.detectZoomGestures(
  quickZoomTimeoutMs: Int,
  zoomGestureDetector: ZoomGestureDetector?,
  coroutineScope: CoroutineScope,
  detectorType: DetectorType,
  cancelAnimations: () -> Unit,
  stopOtherDetectors: (DetectorType) -> Unit,
  gesturesLocked: () -> Boolean
) {
  awaitPointerEventScope {
    val firstDown = awaitFirstDownOnPass(
      pass = PointerEventPass.Initial,
      requireUnconsumed = false
    )

    if (zoomGestureDetector == null) {
      return@awaitPointerEventScope
    }

    cancelAnimations()

    if (gesturesLocked()) {
      consumeChangesUntilAllPointersAreUp(
        gestureDetector = zoomGestureDetector,
        pointerInputChange = firstDown,
        coroutineScope = coroutineScope,
        gesturesLocked = gesturesLocked
      )

      return@awaitPointerEventScope
    }

    if (zoomGestureDetector.debug) {
      logcat(tag = TAG) { "Gestures NOT locked, detectorType=${zoomGestureDetector.detectorType}" }
    }

    val firstUpOrCancel = waitForUpOrCancellation()
      ?: return@awaitPointerEventScope
    val secondDown = awaitSecondDown(firstUpOrCancel)
      ?: return@awaitPointerEventScope

    if ((secondDown.uptimeMillis - firstDown.uptimeMillis).absoluteValue > quickZoomTimeoutMs) {
      // Too much time has passed between the first and the second touch events so we can't use this
      // gesture as quick zoom anymore
      return@awaitPointerEventScope
    }

    secondDown.consumeAllChanges()

    var lastPointerInputChange = secondDown
    var canceled = false

    try {
      stopOtherDetectors(detectorType)
      zoomGestureDetector.onGestureStarted(secondDown)

      while (coroutineScope.isActive) {
        val pointerEvent = awaitPointerEvent(pass = PointerEventPass.Main)
        val pointerInputChange = pointerEvent.changes
          .fastFirstOrNull { it.id == secondDown.id }
          ?: break

        if (pointerInputChange.changedToUpIgnoreConsumed()) {
          break
        }

        if (pointerInputChange.positionChanged()) {
          zoomGestureDetector.onGestureUpdated(pointerInputChange)
        }

        pointerInputChange.consumeAllChanges()
        lastPointerInputChange = pointerInputChange
      }
    } catch (error: Throwable) {
      canceled = error is CancellationException
      throw error
    } finally {
      zoomGestureDetector.onGestureEnded(
        canceled = canceled,
        pointerInputChange = lastPointerInputChange
      )
    }
  }
}

private suspend fun AwaitPointerEventScope.consumeChangesUntilAllPointersAreUp(
  gestureDetector: GestureDetector,
  pointerInputChange: PointerInputChange,
  coroutineScope: CoroutineScope,
  gesturesLocked: () -> Boolean
) {
  if (gestureDetector.debug) {
    logcat(tag = TAG) { "Gestures locked detectorType=${gestureDetector.detectorType}" }
  }

  pointerInputChange.consumeAllChanges()

  while (coroutineScope.isActive && gesturesLocked()) {
    val event = awaitPointerEvent(pass = PointerEventPass.Main)

    if (event.changes.fastAll { it.changedToUpIgnoreConsumed() }) {
      break
    }

    event.changes.fastForEach { it.consumeAllChanges() }
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