package com.github.k1rakishou.lib

import android.graphics.PointF
import android.os.SystemClock
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
import androidx.compose.ui.util.fastForEach
import com.github.k1rakishou.lib.helpers.errorMessageOrClassName
import com.github.k1rakishou.lib.helpers.logcat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "ComposeSubsamplingScaleImageGestures"

fun Modifier.composeSubsamplingScaleImageGestureDetector(
  zoomGesture: ZoomGestureDetector? = null
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
  zoomGesture: ZoomGestureDetector? = null
) {
  val activeDetectors = arrayOfNulls<Job?>(DetectorType.values().size)

  forEachGesture {
    activeDetectors.forEachIndexed { index, job ->
      job?.cancel()
      activeDetectors[index] = null
    }

    coroutineScope {
      // TODO(KurobaEx): consume horizontal/vertical scroll in Horizontal/Vertical Pager
      // TODO(KurobaEx): detect horizontal/vertical touch slop
      // TODO(KurobaEx): continue with the gesture or stop consuming horizontal/vertical scroll in Horizontal/Vertical Pager

      activeDetectors[DetectorType.Zoom.index] = launch {
        detectZoomGestures(
          zoomGesture = zoomGesture,
          coroutineScope = this,
          gesturesLocked = { zoomGesture?.animating ?: false }
        )
      }
    }
  }
}

private suspend fun PointerInputScope.detectZoomGestures(
  zoomGesture: ZoomGestureDetector?,
  coroutineScope: CoroutineScope,
  gesturesLocked: () -> Boolean
) {
  awaitPointerEventScope {
    val firstDown = awaitFirstDownOnPass(
      pass = PointerEventPass.Initial,
      requireUnconsumed = false
    )

    if (zoomGesture == null) {
      return@awaitPointerEventScope
    }

    zoomGesture.cancelAnimation()

    if (gesturesLocked()) {
      if (zoomGesture.debug) {
        logcat(tag = TAG) { "Gestures locked" }
      }

      firstDown.consumeAllChanges()

      while (coroutineScope.isActive && gesturesLocked()) {
        val event = awaitPointerEvent(pass = PointerEventPass.Main)

        if (event.changes.fastAll { it.changedToUpIgnoreConsumed() }) {
          break
        }

        event.changes.fastForEach { it.consumeAllChanges() }
      }

      return@awaitPointerEventScope
    }

    if (zoomGesture.debug) {
      logcat(tag = TAG) { "Gestures NOT locked" }
    }

    val firstUpOrCancel = waitForUpOrCancellation()
      ?: return@awaitPointerEventScope
    val secondDown = awaitSecondDown(firstUpOrCancel)
      ?: return@awaitPointerEventScope

    secondDown.consumeAllChanges()

    var lastPosition: Offset = secondDown.position

    try {
      zoomGesture.onGestureStarted(secondDown.position)

      while (coroutineScope.isActive) {
        val pointerEvent = awaitPointerEvent(pass = PointerEventPass.Main)
        val pointerInputChange = pointerEvent.changes
          .firstOrNull { it.id == secondDown.id }
          ?: break

        if (pointerInputChange.changedToUpIgnoreConsumed()) {
          break
        }

        zoomGesture.onGestureUpdated(pointerInputChange.position)
        pointerInputChange.consumeAllChanges()
        lastPosition = pointerInputChange.position
      }
    } finally {
      zoomGesture.onGestureEnded(lastPosition)
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

enum class DetectorType(val index: Int) {
  Zoom(0)
}

abstract class GestureDetector(
  val detectorType: DetectorType
) {
  protected var currentAnimation: Animation<*>? = null

  val animating: Boolean
    get() = currentAnimation?.animating ?: false

  fun cancelAnimation() {
    val canceled = currentAnimation?.cancel() == true
    if (canceled) {
      currentAnimation = null
    }
  }

  abstract fun onGestureStarted(offset: Offset)
  abstract fun onGestureUpdated(offset: Offset)
  abstract fun onGestureEnded(offset: Offset)
}

class Animation<Params>(
  val debug: Boolean,
  val coroutineScope: CoroutineScope,
  val canBeCanceled: Boolean,
  val durationMs: Int,
  val animationParams: () -> Params,
  val animation: suspend (Params, Float, Long) -> Unit,
  val onAnimationEnd: () -> Unit
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

        onAnimationEnd()
      }

      val startTime = SystemClock.elapsedRealtime()
      val params = animationParams()
      var progress = 0f

      try {
        while (job.isActive) {
          job.ensureActive()

          animation(params, progress, durationMs.toLong())
          delay(16L)

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
}

class ZoomGestureDetector(
  private val density: Density,
  private val state: ComposeSubsamplingScaleImageState,
) : GestureDetector(DetectorType.Zoom) {
  val debug = state.debug

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

  enum class Easing {
    EaseOutQuad,
    EaseInOutQuad
  }

  data class QuickZoomAnimationParameters(
    val easing: Easing,
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

  override fun onGestureStarted(offset: Offset) {
    coroutineScope?.cancel()
    coroutineScope = CoroutineScope(Dispatchers.Main)

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
    animatingQuickZoom = false
    quickScaleLastDistance = -1f
    quickScaleSourceCenter.set(state.viewToSourceCoord(screenCenterStart))
    quickScaleScreenStart.set(offset.x, offset.y)
    quickScaleScreenLastPoint.set(quickScaleSourceCenter.x, quickScaleSourceCenter.y)
    quickScaleMoved = false
  }

  override fun onGestureEnded(offset: Offset) {
    if (!animatingQuickZoom && !quickScaleMoved && coroutineScope != null) {
      if (currentAnimation != null) {
        return
      }

      animatingQuickZoom = true
      initAndStartQuickZoomAnimation(debug, offset)

      return
    }

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
    currentAnimation = null

    coroutineScope?.cancel()
    coroutineScope = null
  }

  override fun onGestureUpdated(offset: Offset) {
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

  private fun initAndStartQuickZoomAnimation(debug: Boolean, offset: Offset) {
    val vTranslateBefore = PointF(0f, 0f)

    currentAnimation = Animation<QuickZoomAnimationParameters>(
      debug = debug,
      coroutineScope = coroutineScope!!,
      canBeCanceled = true,
      durationMs = 250,
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

        return@Animation QuickZoomAnimationParameters(
          easing = Easing.EaseInOutQuad,
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
      animation = { params: QuickZoomAnimationParameters, _: Float, duration: Long ->
        val startScale = params.startScale
        val endScale = params.endScale
        vTranslateBefore.set(state.screenTranslate.x.toFloat(), state.screenTranslate.y.toFloat())

        var scaleElapsed = SystemClock.elapsedRealtime() - params.startTime
        val finished = scaleElapsed > duration
        scaleElapsed = Math.min(scaleElapsed, duration)

        val newScale = state.ease(
          easing = params.easing,
          time = scaleElapsed,
          from = startScale,
          change = endScale - startScale,
          duration = duration
        )

        state.scaleState.value = newScale

        val vFocusNowX = state.ease(
          easing = params.easing,
          time = scaleElapsed,
          from = params.vFocusStart.x,
          change = params.vFocusEnd.x - params.vFocusStart.x,
          duration = duration
        )
        val vFocusNowY = state.ease(
          easing = params.easing,
          time = scaleElapsed,
          from = params.vFocusStart.y,
          change = params.vFocusEnd.y - params.vFocusStart.y,
          duration = duration
        )

        state.screenTranslate.xState.value -= (state.sourceToViewX(params.sCenterEnd.x) - vFocusNowX).toInt()
        state.screenTranslate.yState.value -= (state.sourceToViewY(params.sCenterEnd.y) - vFocusNowY).toInt()

        state.fitToBounds(finished || startScale == endScale)
        state.refreshRequiredTiles(finished)
      },
      onAnimationEnd = {
        onGestureEnded(offset)
        animatingQuickZoom = false
      }
    )

    currentAnimation!!.start()
  }

}