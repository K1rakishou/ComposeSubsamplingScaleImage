package com.github.k1rakishou.lib

import android.content.Context
import android.graphics.Paint
import android.graphics.PointF
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.NativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.github.k1rakishou.lib.decoders.SkiaImageRegionDecoder
import com.github.k1rakishou.lib.gestures.PanGestureDetector
import com.github.k1rakishou.lib.gestures.ZoomGestureDetector
import com.github.k1rakishou.lib.gestures.composeSubsamplingScaleImageGestureDetector
import com.github.k1rakishou.lib.helpers.isAndroid11
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

private const val TAG = "ComposeSubsamplingScaleImage"

private val defaultDecoderProvider = object : ImageDecoderProvider {
  override suspend fun provide(): ComposeSubsamplingScaleImageDecoder {
    return SkiaImageRegionDecoder()
  }
}

private val defaultDecoderDispatcher = lazy {
  Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    .asCoroutineDispatcher()
}

private val tileDebugColors by lazy {
  arrayOf(
    Color.Blue,
    Color.Cyan,
    Color.Green,
    Color.Yellow,
    Color.Red,
    Color.Magenta,
  )
}

/**
 * Note: fling animation is an animation that is executed after the finger stops moving on the screen.
 * This is basically the inertia scrolling animation.
 * Note: quick zoom animation is an animation that is executed after double-tapping (zoom in/zoom out).
 *
 * [minFlingMoveDistPx] how many pixels a finger must move before we start considering
 * it a fling and run the fling animation. The higher the value is the harder it will be to trigger
 * fling animation.
 * [minFlingVelocityPxPerSecond] the minimum speed required for us to start the fling animation.
 * [quickZoomTimeoutMs] the maximum wait time between taps before a gesture is considered the quick
 * zoom gesture and we either run the quick zoom animation or the gesture becomes the single finger
 * zoom gesture.
 * [flingAnimationDurationMs] the duration of the fling animation
 * [maxMaxTileSizeInfo] the maximum size of a tile before we start subdividing it into smaller tiles.
 * The higher it is the better since we will have to decode less tiles which will make image region
 * loading faster (less regions to decode -> the faster it is). We can't set to be Int.MAX_VALUE or
 * some other big number because Canvas has internal limitations on the bitmap size.
 * See https://developer.android.com/reference/android/graphics/Canvas#getMaximumBitmapHeight() and
 * https://developer.android.com/reference/android/graphics/Canvas#getMaximumBitmapWidth()
 * [debug] enables/disables internal logging
 * [decoderDispatcherLazy] the coroutine dispatcher that will be used to decode images.
 * [imageDecoderProvider] the bitmap decoder that does the job
 * */
@Composable
fun rememberComposeSubsamplingScaleImageState(
  minFlingMoveDistPx: Int = 50,
  minFlingVelocityPxPerSecond: Int? = null,
  quickZoomTimeoutMs: Int? = null,
  zoomAnimationDurationMs: Int = 250,
  flingAnimationDurationMs: Int = 250,
  minDpi: Int? = 160,
  minTileDpi: Int? = null,
  doubleTapZoomDpiDefault: Int? = 160,
  maxMaxTileSizeInfo: () -> MaxTileSizeInfo = { MaxTileSizeInfo.Auto() },
  minimumScaleType: () -> MinimumScaleType = { MinimumScaleType.ScaleTypeCenterInside },
  minScale: Float? = null,
  maxScale: Float? = null,
  debug: Boolean = false,
  decoderDispatcherLazy: Lazy<CoroutineDispatcher> = defaultDecoderDispatcher,
  imageDecoderProvider: ImageDecoderProvider = remember { defaultDecoderProvider }
): ComposeSubsamplingScaleImageState {
  val context = LocalContext.current
  val composeViewConfiguration = LocalViewConfiguration.current

  val maxMaxTileSizeInfoRemembered = remember { maxMaxTileSizeInfo() }
  val minimumScaleTypeRemembered = remember { minimumScaleType() }
  val androidViewConfiguration = remember { ViewConfiguration.get(context) }

  val minFlingVelocity = remember(key1 = minFlingVelocityPxPerSecond) {
    if (minFlingVelocityPxPerSecond != null) {
      return@remember minFlingVelocityPxPerSecond
    }

    return@remember androidViewConfiguration.scaledMinimumFlingVelocity
  }

  val animationUpdateIntervalMs = remember {
    val display = if (isAndroid11()) {
      context.display
    } else {
      (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
    }

    val updateInterval = if (display != null) {
      1000f / display.refreshRate
    } else {
      1000f / 60f
    }

    return@remember updateInterval.toInt()
  }

  val quickZoomTimeout = remember {
    if (quickZoomTimeoutMs != null && quickZoomTimeoutMs > 0) {
      return@remember quickZoomTimeoutMs
    }

    return@remember composeViewConfiguration.doubleTapTimeoutMillis.toInt()
  }

  return remember {
    ComposeSubsamplingScaleImageState(
      context = context,
      maxTileSizeInfo = maxMaxTileSizeInfoRemembered,
      minimumScaleType = minimumScaleTypeRemembered,
      minScaleParam = minScale,
      maxScaleParam = maxScale,
      imageDecoderProvider = imageDecoderProvider,
      decoderDispatcherLazy = decoderDispatcherLazy,
      debug = debug,
      minFlingMoveDistPx = minFlingMoveDistPx,
      minFlingVelocityPxPerSecond = minFlingVelocity,
      quickZoomTimeoutMs = quickZoomTimeout,
      animationUpdateIntervalMs = animationUpdateIntervalMs,
      zoomAnimationDurationMs = zoomAnimationDurationMs,
      flingAnimationDurationMs = flingAnimationDurationMs,
      minDpiDefault = minDpi,
      minTileDpiDefault = minTileDpi,
      doubleTapZoomDpiDefault = doubleTapZoomDpiDefault
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
  if (state.maxTileSizeInfo is MaxTileSizeInfo.Auto) {
    val detected = detectCanvasMaxBitmapSize(
      onBitmapSizeDetected = { bitmapSize ->
        state.maxTileSizeInfo.maxTileSizeState.value = bitmapSize
      }
    )

    if (!detected) {
      return
    }
  }

  val density = LocalDensity.current
  val debugValues = remember { DebugValues(density) }
  val zoomGestureDetector = remember { ZoomGestureDetector(density, state) }
  val panGestureDetector = remember { PanGestureDetector(density, state) }

  BoxWithConstraints(
    modifier = Modifier
      .fillMaxSize()
      .composeSubsamplingScaleImageGestureDetector(
        state = state,
        zoomGestureDetector = zoomGestureDetector,
        panGestureDetector = panGestureDetector
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
        val initializationState = state.initialize(imageSourceProvider, eventListener)
        state.initializationState.value = initializationState
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

            if (state.debug) {
              zoomGestureDetector.debugDraw(this)
            }
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

/**
 * Do not remove [invalidate] parameter even though it says it's unused!
 * It is used to notify the Canvas to redraw currently loaded tiles since they are being loaded
 * asynchronously.
 * */
@Suppress("UNUSED_PARAMETER")
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

  // First check for missing tiles - if there are any we need the base layer underneath to avoid gaps
  var hasMissingTiles = false
  for ((key, value) in tileMap) {
    if (key != sampleSize) {
      continue
    }

    for (tile in value) {
      if (tile.visible && !tile.isLoaded) {
        hasMissingTiles = true
        break
      }
    }
  }

  var index = 0

  for ((layerSampleSize, tileLayer) in tileMap.entries) {
    if (layerSampleSize == sampleSize || hasMissingTiles) {
      for (tile in tileLayer) {
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
            val color = tileDebugColors.getOrNull(index)
              ?: tileDebugColors.last()

            drawRect(
              color = color.copy(alpha = 0.15f),
              topLeft = tile.screenRect.topLeft,
              size = tile.screenRect.size
            )
            drawRect(
              color = color,
              topLeft = tile.screenRect.topLeft,
              size = tile.screenRect.size,
              style = Stroke(width = borderWidthPx)
            )
          }
        }

        if (state.debug) {
          drawTileDebugInfo(
            tile = tile,
            nativeCanvas = nativeCanvas,
            debugTextPaint = debugTextPaint
          )
        }
      }
    }

    ++index
  }

  if (state.debug) {
    drawDebugInfo(state, nativeCanvas, debugTextPaint)
  }
}

private fun DrawScope.drawDebugInfo(
  state: ComposeSubsamplingScaleImageState,
  nativeCanvas: NativeCanvas,
  debugTextPaint: Paint
) {
  val scale = state.currentScale
  val minScale = state.minScale
  val maxScale = state.maxScale
  val screenTranslateX = state.vTranslate.x
  val screenTranslateY = state.vTranslate.y

  nativeCanvas.drawText(
    formatScaleText(scale, minScale, maxScale),
    5.dp.toPx(),
    15.dp.toPx(),
    debugTextPaint
  )

  nativeCanvas.drawText(
    formatTranslateText(screenTranslateX, screenTranslateY),
    5.dp.toPx(),
    30.dp.toPx(),
    debugTextPaint
  )

  val center = state.getCenter()

  nativeCanvas.drawText(
    formatSourceCenterText(center),
    5.dp.toPx(),
    45.dp.toPx(),
    debugTextPaint
  )
}

private fun DrawScope.drawTileDebugInfo(
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
      "LDNG",
      (tile.screenRect.left + (5.dp.toPx())),
      (tile.screenRect.top + (35.dp.toPx())),
      debugTextPaint
    )
  }

  if (tileState is TileState.Error) {
    nativeCanvas.drawText(
      "ERR",
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
}

private fun formatSourceCenterText(center: PointF): String {
  return buildString {
    append("Source center: ")
    append(String.format(Locale.ENGLISH, "%.2f", center.x))
    append("; ")
    append(String.format(Locale.ENGLISH, "%.2f", center.y))
  }
}

private fun formatTranslateText(
  screenTranslateX: Int,
  screenTranslateY: Int
): String {
  return buildString {
    append("Translate: ")
    append(String.format(Locale.ENGLISH, "%.2f", screenTranslateX.toFloat()))
    append("; ")
    append(String.format(Locale.ENGLISH, "%.2f", screenTranslateY.toFloat()))
  }
}

private fun formatScaleText(
  scale: Float,
  minScale: Float,
  maxScale: Float
): String {
  return buildString {
    append("Scale: ")
    append(String.format(Locale.ENGLISH, "%.2f", scale))
    append(" (")
    append(String.format(Locale.ENGLISH, "%.2f", minScale))
    append(" - ")
    append(String.format(Locale.ENGLISH, "%.2f", maxScale))
    append(")")
  }
}