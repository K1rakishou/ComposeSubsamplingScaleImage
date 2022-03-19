package com.github.k1rakishou.lib

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.NativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.github.k1rakishou.lib.helpers.logcat
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val TAG = "ComposeSubsamplingScaleImage"

private val defaultDecoderProvider = object : ImageDecoderProvider {
  override suspend fun provide(): ComposeSubsamplingScaleImageDecoder {
    return TachiyomiImageDecoder(debug = true)
  }
}

private val defaultDecoderDispatcher = lazy {
  Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    .asCoroutineDispatcher()
}

@Composable
fun rememberComposeSubsamplingScaleImageState(
  minTileDpiDefault: Int = 320,
  maxMaxTileSizeInfo: () -> MaxTileSizeInfo = { MaxTileSizeInfo.Auto() },
  minimumScaleType: () -> MinimumScaleType = { MinimumScaleType.ScaleTypeCenterInside },
  minScale: Float? = null,
  maxScale: Float? = null,
  debugKey: String? = null,
  debug: Boolean = false,
  decoderDispatcherLazy: Lazy<CoroutineDispatcher> = defaultDecoderDispatcher,
  ImageDecoderProvider: ImageDecoderProvider = remember { defaultDecoderProvider }
): ComposeSubsamplingScaleImageState {
  val context = LocalContext.current
  val maxMaxTileSizeInfoRemembered = remember { maxMaxTileSizeInfo() }
  val minimumScaleTypeRemembered = remember { minimumScaleType() }

  return remember {
    ComposeSubsamplingScaleImageState(
      context = context,
      maxMaxTileSizeInfo = maxMaxTileSizeInfoRemembered,
      minimumScaleType = minimumScaleTypeRemembered,
      minScaleParam = minScale,
      maxScaleParam = maxScale,
      ImageDecoderProvider = ImageDecoderProvider,
      decoderDispatcherLazy = decoderDispatcherLazy,
      debug = debug,
      minTileDpiDefault = minTileDpiDefault,
      debugKey = debugKey
    )
  }
}

@Composable
fun ComposeSubsamplingScaleImage(
  modifier: Modifier = Modifier,
  state: ComposeSubsamplingScaleImageState,
  imageSourceProvider: ImageSourceProvider,
  eventListener: ComposeSubsamplingScaleImageEventListener? = null,
  FullImageLoadingContent: (@Composable () -> Unit)? = null,
  FullImageErrorLoadingContent: (@Composable (Throwable) -> Unit)? = null
) {
  if (state.maxMaxTileSizeInfo is MaxTileSizeInfo.Auto) {
    val detected = detectCanvasMaxBitmapSize(
      onBitmapSizeDetected = { bitmapSize ->
        logcat(tag = TAG) { "CanvasMaxBitmapSize detected: ${bitmapSize}" }
        state.maxMaxTileSizeInfo.maxTileSizeState.value = bitmapSize
      }
    )

    if (!detected) {
      return
    }
  }

  val density = LocalDensity.current
  val debugValues = remember { DebugValues(density) }

  BoxWithConstraints(
    modifier = Modifier
      .fillMaxSize()
      .composeSubsamplingScaleImageGestureDetector(
        onZooming = { scale -> /*TODO*/ },
        onPanning = { viewport -> /*TODO*/ }
      )
  ) {
    val minWidthPx = with(density) { remember(key1 = minWidth) { minWidth.toPx().toInt() } }
    val minHeightPx = with(density) { remember(key1 = minHeight) { minHeight.toPx().toInt() } }

    LaunchedEffect(
      key1 = minWidth,
      key2 = minHeight,
      block = {
        if (minWidthPx <= 0 || minHeightPx <= 0) {
          return@LaunchedEffect
        }

        state.availableDimensions.value = IntSize(minWidthPx, minHeightPx)
        val initializationState = state.initialize(imageSourceProvider)
        state.initializationState.value = initializationState

        when (initializationState) {
          InitializationState.Uninitialized -> {
            // no-op
          }
          is InitializationState.Error -> {
            eventListener?.onFullImageFailedToLoad(initializationState.exception)
          }
          is InitializationState.Success -> {
            eventListener?.onFullImageLoaded()
          }
        }
      }
    )

    val initializationMut by state.initializationState


    when (val initialization = initializationMut) {
      InitializationState.Uninitialized -> {
        FullImageLoadingContent?.invoke()
      }
      is InitializationState.Error -> {
        FullImageErrorLoadingContent?.invoke(initialization.exception)
      }
      is InitializationState.Success -> {
        val invalidate by state.invalidate

        Canvas(
          modifier = modifier.then(Modifier.clipToBounds()),
          onDraw = {
            DrawTileGrid(
              state = state,
              sourceImageDimensions = state.sourceImageDimensions,
              invalidate = invalidate,
              debugValues = debugValues
            )
          }
        )
      }
    }
  }
}

@Composable
private fun detectCanvasMaxBitmapSize(onBitmapSizeDetected: (IntSize) -> Unit): Boolean {
  var maximumBitmapSizeMut by maximumBitmapSizeState
  if (maximumBitmapSizeMut == null) {
    Canvas(
      modifier = Modifier.wrapContentWidth(),
      onDraw = {
        val width = drawContext.canvas.nativeCanvas.maximumBitmapWidth
        val height = drawContext.canvas.nativeCanvas.maximumBitmapHeight

        val maxBitmapSize = IntSize(width, height)

        maximumBitmapSizeMut = maxBitmapSize
        onBitmapSizeDetected(maxBitmapSize)
      }
    )
  } else {
    LaunchedEffect(
      key1 = maximumBitmapSizeMut,
      block = {
        val maximumBitmapSize = maximumBitmapSizeMut
        if (maximumBitmapSize != null) {
          onBitmapSizeDetected(maximumBitmapSize)
        }
      })
  }

  return maximumBitmapSizeMut != null
}

private fun DrawScope.DrawTileGrid(
  state: ComposeSubsamplingScaleImageState,
  sourceImageDimensions: IntSize?,
  invalidate: Int,
  debugValues: DebugValues
) {
  if (sourceImageDimensions == null) {
    return
  }

  val fullImageSampleSize by state.fullImageSampleSizeState
  val scale by state.scaleState

  state.fitToBounds(false)

  val nativeCanvas = drawContext.canvas.nativeCanvas
  val tileMap = state.tileMap
  val bitmapPaint = state.bitmapPaint
  val bitmapMatrix = state.bitmapMatrix
  val debugTextPaint = debugValues.debugTextPaint
  val borderWidthPx = debugValues.borderWidthPx

  val sampleSize = Math.min(
    fullImageSampleSize,
    state.calculateInSampleSize(
      sourceWidth = sourceImageDimensions.width,
      sourceHeight = sourceImageDimensions.height,
      scale = scale
    )
  )

  val hasMissingTiles = state.hasMissingTiles(sampleSize)

  for ((key, tiles) in tileMap.entries) {
    if (key == sampleSize || hasMissingTiles) {
      for (tile in tiles) {
        val tileState = tile.tileState

        state.sourceToViewRect(
          source = tile.sourceRect,
          target = tile.screenRect
        )

        if (tileState is TileState.Loaded) {
          val bitmap = tileState.bitmap
          bitmapMatrix.reset()

          state.srcArray[0] = 0f                                // top_left.x
          state.srcArray[1] = 0f                                // top_left.y
          state.srcArray[2] = bitmap.width.toFloat()            // top_right.x
          state.srcArray[3] = 0f                                // top_right.y
          state.srcArray[4] = 0f                                // bottom_left.x
          state.srcArray[5] = bitmap.height.toFloat()           // bottom_left.y
          state.srcArray[6] = bitmap.width.toFloat()            // bottom_right.x
          state.srcArray[7] = bitmap.height.toFloat()           // bottom_right.y

          state.dstArray[0] = tile.screenRect.left.toFloat()    // top_left.x
          state.dstArray[1] = tile.screenRect.top.toFloat()     // top_left.y
          state.dstArray[2] = tile.screenRect.right.toFloat()   // top_right.x
          state.dstArray[3] = tile.screenRect.top.toFloat()     // top_right.y
          state.dstArray[4] = tile.screenRect.left.toFloat()    // bottom_left.x
          state.dstArray[5] = tile.screenRect.bottom.toFloat()  // bottom_left.y
          state.dstArray[6] = tile.screenRect.right.toFloat()   // bottom_right.x
          state.dstArray[7] = tile.screenRect.bottom.toFloat()  // bottom_right.y

          bitmapMatrix.setPolyToPoly(state.srcArray, 0, state.dstArray, 0, 4)
          nativeCanvas.drawBitmap(bitmap, bitmapMatrix, bitmapPaint)

          if (state.debug) {
            drawRect(
              color = Color.Red.copy(alpha = 0.15f),
              topLeft = tile.screenRect.topLeft,
              size = tile.screenRect.size
            )
            drawRect(
              color = Color.Red,
              topLeft = tile.screenRect.topLeft,
              size = tile.screenRect.size,
              style = Stroke(width = borderWidthPx)
            )
          }
        }

        if (state.debug) {
          drawDebugInfo(
            tile = tile,
            nativeCanvas = nativeCanvas,
            debugTextPaint = debugTextPaint
          )
        }
      }
    }
  }

}

private fun DrawScope.drawDebugInfo(
  tile: Tile,
  nativeCanvas: NativeCanvas,
  debugTextPaint: Paint
) {
  val tileState = tile.tileState

  if (tile.visible) {
    val debugText = buildString {
      append("VIS@")
      append(tile.sampleSize)
      append(" RECT (")
      append(tile.sourceRect.top)
      append(",")
      append(tile.sourceRect.left)
      append(",")
      append(tile.sourceRect.bottom)
      append(",")
      append(tile.sourceRect.right)
      append(")")
    }

    nativeCanvas.drawText(
      debugText,
      (tile.screenRect.left + (5.dp.toPx())),
      (tile.screenRect.top + (15.dp.toPx())),
      debugTextPaint
    )
  } else {
    val debugText = buildString {
      append("INV@")
      append(tile.sampleSize)
      append(" RECT (")
      append(tile.sourceRect.top)
      append(",")
      append(tile.sourceRect.left)
      append(",")
      append(tile.sourceRect.bottom)
      append(",")
      append(tile.sourceRect.right)
      append(")")
    }

    nativeCanvas.drawText(
      debugText,
      (tile.screenRect.left + (5.dp.toPx())),
      (tile.screenRect.top + (15.dp.toPx())),
      debugTextPaint
    )
  }

  if (tileState is TileState.Loading) {
    nativeCanvas.drawText(
      "LOADING",
      (tile.screenRect.left + (5.dp.toPx())),
      (tile.screenRect.top + (35.dp.toPx())),
      debugTextPaint
    )
  }

  if (tileState is TileState.Error) {
    nativeCanvas.drawText(
      "ERROR",
      (tile.screenRect.left + (5.dp.toPx())),
      (tile.screenRect.top + (55.dp.toPx())),
      debugTextPaint
    )

    drawRect(
      color = Color.Red.copy(alpha = 0.5f),
      topLeft = tile.screenRect.topLeft,
      size = tile.screenRect.size
    )
  }

  // TODO(KurobaEx):
//  canvas.drawText(
//    "Scale: " + String.format(Locale.ENGLISH, "%.2f", scale) + " (" + String.format(
//      Locale.ENGLISH, "%.2f", minScale()
//    ) + " - " + String.format(Locale.ENGLISH, "%.2f", maxScale) + ")",
//    px(5).toFloat(),
//    px(15).toFloat(),
//    debugTextPaint
//  )
//  canvas.drawText(
//    "Translate: " + String.format(Locale.ENGLISH, "%.2f", vTranslate.x) + ":" + String.format(
//      Locale.ENGLISH, "%.2f", vTranslate.y
//    ), px(5).toFloat(), px(30).toFloat(), debugTextPaint
//  )
//  val center: PointF = getCenter()
//  canvas.drawText(
//    "Source center: " + String.format(Locale.ENGLISH, "%.2f", center.x) + ":" + String.format(
//      Locale.ENGLISH, "%.2f", center.y
//    ), px(5).toFloat(), px(45).toFloat(), debugTextPaint
//  )
//  if (anim != null) {
//    val vCenterStart: PointF = sourceToViewCoord(anim.sCenterStart)
//    val vCenterEndRequested: PointF = sourceToViewCoord(anim.sCenterEndRequested)
//    val vCenterEnd: PointF = sourceToViewCoord(anim.sCenterEnd)
//    canvas.drawCircle(vCenterStart.x, vCenterStart.y, px(10).toFloat(), debugLinePaint)
//    debugLinePaint.setColor(android.graphics.Color.RED)
//    canvas.drawCircle(
//      vCenterEndRequested.x,
//      vCenterEndRequested.y,
//      px(20).toFloat(),
//      debugLinePaint
//    )
//    debugLinePaint.setColor(android.graphics.Color.BLUE)
//    canvas.drawCircle(vCenterEnd.x, vCenterEnd.y, px(25).toFloat(), debugLinePaint)
//    debugLinePaint.setColor(android.graphics.Color.CYAN)
//    canvas.drawCircle(
//      (getWidth() / 2).toFloat(),
//      (getHeight() / 2).toFloat(),
//      px(30).toFloat(),
//      debugLinePaint
//    )
//  }
//  if (vCenterStart != null) {
//    debugLinePaint.setColor(android.graphics.Color.RED)
//    canvas.drawCircle(vCenterStart.x, vCenterStart.y, px(20).toFloat(), debugLinePaint)
//  }
//  if (quickScaleSCenter != null) {
//    debugLinePaint.setColor(android.graphics.Color.BLUE)
//    canvas.drawCircle(
//      sourceToViewX(quickScaleSCenter.x),
//      sourceToViewY(quickScaleSCenter.y),
//      px(35).toFloat(),
//      debugLinePaint
//    )
//  }
//  if (quickScaleVStart != null && isQuickScaling) {
//    debugLinePaint.setColor(android.graphics.Color.CYAN)
//    canvas.drawCircle(quickScaleVStart.x, quickScaleVStart.y, px(30).toFloat(), debugLinePaint)
//  }
//  debugLinePaint.setColor(android.graphics.Color.MAGENTA)
}

fun Modifier.composeSubsamplingScaleImageGestureDetector(
  onZooming: (Float) -> Unit,
  onPanning: (Rect) -> Unit
) = composed(
  inspectorInfo = {
    name = "composeSubsamplingScaleImageGestureDetector"
    properties["onZooming"] = onZooming
    properties["onPanning"] = onPanning
  },
  factory = {
    pointerInput(
      key1 = Unit,
      block = {
        processGestures(
          onZooming,
          onPanning
        )
      }
    )
  }
)

private suspend fun PointerInputScope.processGestures(
  onZooming: (Float) -> Unit,
  onPanning: (Rect) -> Unit
) {
  val velocityTracker = VelocityTracker()

  forEachGesture {
    coroutineScope {
      val detectDoubleTapJob = launch {
        awaitPointerEventScope {
          val down = awaitFirstDown(requireUnconsumed = false)

          if (waitForUpOrCancellation() == null) {
            return@awaitPointerEventScope
          }

          velocityTracker.resetTracking()
          // TODO(KurobaEx):
        }
      }


    }
  }
}
