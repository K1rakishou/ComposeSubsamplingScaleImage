package com.github.k1rakishou.cssi_lib.gestures

import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.consumeAllChanges
import androidx.compose.ui.input.pointer.consumePositionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import com.github.k1rakishou.cssi_lib.ComposeSubsamplingScaleImageState
import com.github.k1rakishou.cssi_lib.ScrollableContainerDirection
import com.github.k1rakishou.cssi_lib.helpers.logcat
import kotlin.math.absoluteValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "ComposeSubsamplingScaleImageGestures"

internal suspend fun PointerInputScope.processGestures(
  state: ComposeSubsamplingScaleImageState,
  zoomGestureDetector: ZoomGestureDetector?,
  panGestureDetector: PanGestureDetector?,
  multiTouchGestureDetector: MultiTouchGestureDetector?
) {
  val quickZoomTimeoutMs = state.quickZoomTimeoutMs
  val activeDetectorJobs = arrayOfNulls<Job?>(DetectorType.values().size)
  val allDetectors = arrayOf(zoomGestureDetector, panGestureDetector).filterNotNull()

  fun stopOtherDetectors(excludeDetectorType: DetectorType) {
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

      activeDetectorJobs[DetectorType.MultiTouch.index] = launch {
        detectMultiTouchGestures(
          multiTouchGestureDetector = multiTouchGestureDetector,
          coroutineScope = this,
          detectorType = DetectorType.MultiTouch,
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
      logcat(tag = TAG) { "pan() Gestures NOT locked, detectorType=${detectorType}" }
    }

    val panInfo = state.getPanInfo()
    if (panInfo == null) {
      if (panGestureDetector.debug) {
        logcat(tag = TAG) { "pan() panInfo == null, detectorType=${detectorType}" }
      }
      return@awaitPointerEventScope
    }

    when (state.scrollableContainerDirection) {
      ScrollableContainerDirection.Horizontal -> {
        // If we are inside of a horizontally scrollable container (LazyRow/HorizontalPager) and
        // we are touching both left and right sides of the screen then cancel this gesture and
        // allow parent container scrolling
        if (panInfo.touchesLeftAndRight()) {
          if (panGestureDetector.debug) {
            logcat(tag = TAG) { "pan() touchesLeftAndRight == true, detectorType=${detectorType}" }
          }
          return@awaitPointerEventScope
        }
      }
      ScrollableContainerDirection.Vertical -> {
        // Same as Horizontal but for vertically scrollable container (LazyColumn/VerticalPager)
        if (panInfo.touchesTopAndBottom()) {
          if (panGestureDetector.debug) {
            logcat(tag = TAG) { "pan() touchesTopAndBottom == true, detectorType=${detectorType}" }
          }
          return@awaitPointerEventScope
        }
      }
      null -> {
        // We are not inside of a scrollable container so continue with the gesture
      }
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
      if (panGestureDetector.debug) {
        logcat(tag = TAG) {
          "pan() awaitTouchSlopOrCancellation() failed " +
            "(skipThisGesture=$skipThisGesture, touchSlop==null=${touchSlop == null}), " +
            "detectorType=${detectorType}"
        }
      }
      return@awaitPointerEventScope
    }

    var lastPointerInputChange: PointerInputChange = firstDown
    var canceled = false

    try {
      stopOtherDetectors(detectorType)
      panGestureDetector.onGestureStarted(listOf(firstDown))

      while (coroutineScope.isActive) {
        val pointerEvent = awaitPointerEvent(pass = PointerEventPass.Main)

        val pointerInputChange = pointerEvent.changes
          .fastFirstOrNull { it.id == firstDown.id }
          ?: break

        if (pointerInputChange.changedToUpIgnoreConsumed()) {
          break
        }

        if (pointerInputChange.positionChanged()) {
          panGestureDetector.onGestureUpdated(listOf(pointerInputChange))
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
        pointerInputChanges = listOf(lastPointerInputChange)
      )
    }
  }
}

private suspend fun PointerInputScope.detectMultiTouchGestures(
  multiTouchGestureDetector: MultiTouchGestureDetector?,
  coroutineScope: CoroutineScope,
  detectorType: DetectorType,
  cancelAnimations: () -> Unit,
  stopOtherDetectors: (DetectorType) -> Unit,
  gesturesLocked: () -> Boolean
) {
  while (coroutineScope.isActive) {
    val initialPointerEvent = awaitPointerEventScope { awaitPointerEvent(pass = PointerEventPass.Initial) }

    if (multiTouchGestureDetector == null) {
      return
    }

    if (initialPointerEvent.changes.fastAll { it.changedToUpIgnoreConsumed() }) {
      if (multiTouchGestureDetector.debug) {
        logcat(tag = TAG) { "multi() initialPointerEvent.changes are all up, detectorType=${detectorType}" }
      }
      return
    }

    val pointersCount = initialPointerEvent.changes.count { it.pressed }
    if (pointersCount <= 0) {
      if (multiTouchGestureDetector.debug) {
        logcat(tag = TAG) { "multi() pointersCount <= 0 (pointersCount=$pointersCount), detectorType=${detectorType}" }
      }
      return
    }

    cancelAnimations()

    if (gesturesLocked()) {
      initialPointerEvent.changes.fastForEach { it.consumeAllChanges() }

      awaitPointerEventScope {
        consumeChangesUntilAllPointersAreUp(
          gestureDetector = multiTouchGestureDetector,
          pointerInputChange = null,
          coroutineScope = coroutineScope,
          gesturesLocked = gesturesLocked
        )
      }

      return
    }

    if (multiTouchGestureDetector.debug) {
      logcat(tag = TAG) { "multi() Gestures NOT locked, detectorType=${detectorType}" }
    }

    if (pointersCount < 2) {
      if (multiTouchGestureDetector.debug) {
        logcat(tag = TAG) { "multi() pointersCount < 2 (pointersCount=$pointersCount), detectorType=${detectorType}" }
      }
      continue
    }

    val twoMostRecentEvents = initialPointerEvent.changes
      .filter { it.pressed }
      .sortedByDescending { it.uptimeMillis }
      .take(2)

    if (twoMostRecentEvents.isEmpty()) {
      if (multiTouchGestureDetector.debug) {
        logcat(tag = TAG) { "multi() twoMostRecentEvents is empty, detectorType=${detectorType}" }
      }
      return
    }

    if (twoMostRecentEvents.size != 2) {
      if (multiTouchGestureDetector.debug) {
        logcat(tag = TAG) { "multi() twoMostRecentEvents.size != 2 (size=${twoMostRecentEvents.size}), detectorType=${detectorType}" }
      }
      continue
    }

    var lastPointerInputChanges = twoMostRecentEvents
    var canceled = false

    try {
      multiTouchGestureDetector.onGestureStarted(twoMostRecentEvents)

      stopOtherDetectors(detectorType)
      initialPointerEvent.changes.fastForEach { it.consumeAllChanges() }

      var firstPointerChange = twoMostRecentEvents[0]
      var secondPointerChange = twoMostRecentEvents[1]

      val firstEventId = firstPointerChange.id
      val secondEventId = secondPointerChange.id

      awaitPointerEventScope {
        while (coroutineScope.isActive) {
          val pointerEvent = awaitPointerEvent(pass = PointerEventPass.Main)
          if (pointerEvent.type != PointerEventType.Move) {
            break
          }

          pointerEvent.changes.fastForEach { it.consumeAllChanges() }

          firstPointerChange = pointerEvent.changes.fastFirstOrNull { it.id == firstEventId } ?: break
          secondPointerChange = pointerEvent.changes.fastFirstOrNull { it.id == secondEventId } ?: break

          val twoInputChanges = listOf(firstPointerChange, secondPointerChange)
          lastPointerInputChanges = twoInputChanges

          multiTouchGestureDetector.onGestureUpdated(twoInputChanges)
        }
      }

      return
    } catch (error: Throwable) {
      canceled = error is CancellationException
      throw error
    } finally {
      multiTouchGestureDetector.onGestureEnded(
        canceled = canceled,
        pointerInputChanges = lastPointerInputChanges
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
      logcat(tag = TAG) { "zoom() Gestures NOT locked, detectorType=${detectorType}" }
    }

    val firstUpOrCancel = waitForUpOrCancellation()
    if (firstUpOrCancel == null) {
      if (zoomGestureDetector.debug) {
        logcat(tag = TAG) { "zoom() waitForUpOrCancellation() failed, detectorType=${detectorType}" }
      }

      return@awaitPointerEventScope
    }

    val secondDown = awaitSecondDown(firstUpOrCancel)
    if (secondDown == null) {
      if (zoomGestureDetector.debug) {
        logcat(tag = TAG) { "zoom() awaitSecondDown() failed, detectorType=${detectorType}" }
      }

      return@awaitPointerEventScope
    }

    val timeDelta = (secondDown.uptimeMillis - firstDown.uptimeMillis).absoluteValue
    if (timeDelta > quickZoomTimeoutMs) {
      if (zoomGestureDetector.debug) {
        logcat(tag = TAG) { "zoom() timeDelta failed (timeDelta=$timeDelta), detectorType=${detectorType}" }
      }

      // Too much time has passed between the first and the second touch events so we can't use this
      // gesture as quick zoom anymore
      return@awaitPointerEventScope
    }

    secondDown.consumeAllChanges()

    var lastPointerInputChange = secondDown
    var canceled = false

    try {
      stopOtherDetectors(detectorType)
      zoomGestureDetector.onGestureStarted(listOf(secondDown))

      while (coroutineScope.isActive) {
        val pointerEvent = awaitPointerEvent(pass = PointerEventPass.Main)

        val pointerInputChange = pointerEvent.changes
          .fastFirstOrNull { it.id == secondDown.id }
          ?: break

        if (pointerInputChange.changedToUpIgnoreConsumed()) {
          break
        }

        if (pointerInputChange.positionChanged()) {
          zoomGestureDetector.onGestureUpdated(listOf(pointerInputChange))
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
        pointerInputChanges = listOf(lastPointerInputChange)
      )
    }
  }
}

private suspend fun AwaitPointerEventScope.consumeChangesUntilAllPointersAreUp(
  gestureDetector: GestureDetector,
  pointerInputChange: PointerInputChange?,
  coroutineScope: CoroutineScope,
  gesturesLocked: () -> Boolean
) {
  if (gestureDetector.debug) {
    logcat(tag = TAG) { "Gestures locked detectorType=${gestureDetector.detectorType}" }
  }

  pointerInputChange?.consumeAllChanges()

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
): PointerInputChange? {
  return withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
    val minUptime = firstUp.uptimeMillis + viewConfiguration.doubleTapMinTimeMillis
    var change: PointerInputChange? = null

    // The second tap doesn't count if it happens before DoubleTapMinTime of the first tap
    do {
      val pointerEvent = awaitPointerEvent(pass = PointerEventPass.Initial)

      val ourChange = pointerEvent.changes.fastFirstOrNull { it.id != firstUp.id } ?: break
      change = ourChange
    } while (ourChange.uptimeMillis < minUptime)

    return@withTimeoutOrNull change
  }
}