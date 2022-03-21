package com.github.k1rakishou.lib

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.util.DisplayMetrics
import android.util.Log
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.IntSize
import com.github.k1rakishou.lib.gestures.GestureAnimationEasing
import com.github.k1rakishou.lib.helpers.BackgroundUtils
import com.github.k1rakishou.lib.helpers.Try
import com.github.k1rakishou.lib.helpers.asLog
import com.github.k1rakishou.lib.helpers.errorMessageOrClassName
import com.github.k1rakishou.lib.helpers.exceptionOrThrow
import com.github.k1rakishou.lib.helpers.power
import com.github.k1rakishou.lib.helpers.unwrap
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal val maximumBitmapSizeState = mutableStateOf<IntSize?>(null)

class ComposeSubsamplingScaleImageState(
  val context: Context,
  val maxMaxTileSizeInfo: MaxTileSizeInfo,
  val minimumScaleType: MinimumScaleType,
  val minScaleParam: Float?,
  val maxScaleParam: Float?,
  val imageDecoderProvider: ImageDecoderProvider,
  val decoderDispatcherLazy: Lazy<CoroutineDispatcher>,
  val debug: Boolean,
  val minFlingMoveDistPx: Int,
  val minFlingVelocityPxPerSecond: Int,
  val doubleTapGestureMaxAllowedDistanceBetweenTapsPx: Int,
  val animationUpdateIntervalMs: Long,
  val zoomAnimationDurationMs: Int,
  val panFlingAnimationDurationMs: Int,
  private val minTileDpiDefault: Int,
  private val debugKey: String?
) : RememberObserver {
  private val decoderDispatcher by decoderDispatcherLazy
  private lateinit var coroutineScope: CoroutineScope

  private val defaultMaxScale = 2f
  internal val tileMap = LinkedHashMap<Int, MutableList<Tile>>()
  private val minTileDpi by lazy { minTileDpi() }
  val minScale by lazy { calculateMinScale() }
  val maxScale by lazy { calculateMaxScale(minTileDpi) }
  val doubleTapZoomScale by lazy { calculateDoubleTapZoomScale(minTileDpi) }

  private var satTemp = ScaleAndTranslate()
  private var needInitScreenTranslate = true

  var sourceImageDimensions: IntSize? = null
    private set

  val bitmapPaint by lazy {
    Paint().apply {
      isAntiAlias = true
      isFilterBitmap = true
      isDither = true
    }
  }
  val bitmapMatrix by lazy { Matrix() }
  val srcArray = FloatArray(8)
  val dstArray = FloatArray(8)

  private val subsamplingImageDecoder = AtomicReference<ComposeSubsamplingScaleImageDecoder?>(null)

  val screenTranslate = PointState()
  val initializationState = mutableStateOf<InitializationState>(InitializationState.Uninitialized)
  val scaleState = mutableStateOf(0f)
  val fullImageSampleSizeState = mutableStateOf(0)
  val availableDimensions = mutableStateOf(IntSize.Zero)

  // Tile are loaded asynchronously.
  // invalidate value is incremented every time we decode a new tile.
  // It's needed to notify the composition to redraw current tileMap.
  val invalidate = mutableStateOf(0)

  val availableWidth: Int
    get() = availableDimensions.value.width
  val availableHeight: Int
    get() = availableDimensions.value.height

  val sourceWidth: Int
    get() = requireNotNull(sourceImageDimensions?.width) { "sourceImageDimensions is null!" }
  val sourceHeight: Int
    get() = requireNotNull(sourceImageDimensions?.height) { "sourceImageDimensions is null!" }

  val currentScale: Float
    get() = scaleState.value

  val isReady: Boolean
    get() = initializationState.value is InitializationState.Success

  override fun onRemembered() {
    coroutineScope = CoroutineScope(decoderDispatcher)
  }

  override fun onForgotten() {
    reset()
  }

  override fun onAbandoned() {
    reset()
  }

  private fun reset() {
    screenTranslate.reset()

    tileMap.entries.forEach { (_, tiles) -> tiles.forEach { tile -> tile.recycle() } }
    tileMap.clear()
    coroutineScope.cancel()

    satTemp.reset()
    bitmapMatrix.reset()
    sourceImageDimensions = null
    scaleState.value = 1f
    fullImageSampleSizeState.value = 0
    availableDimensions.value = IntSize.Zero
    srcArray.fill(0f)
    dstArray.fill(0f)
    subsamplingImageDecoder.getAndSet(null)?.recycle()
    needInitScreenTranslate = true
    initializationState.value = InitializationState.Uninitialized
  }

  private fun calculateDoubleTapZoomScale(dpi: Int): Float {
    val metrics = getResources().displayMetrics
    val averageDpi = (metrics.xdpi + metrics.ydpi) / 2
    return averageDpi / dpi.toFloat()
  }

  private fun minTileDpi(): Int {
    if (minTileDpiDefault <= 0) {
      return 0
    }

    val metrics = context.resources.displayMetrics
    val averageDpi = (metrics.xdpi + metrics.ydpi) / 2
    return Math.min(averageDpi, minTileDpiDefault.toFloat()).toInt()
  }

  suspend fun initialize(
    imageSourceProvider: ImageSourceProvider,
    eventListener: ComposeSubsamplingScaleImageEventListener?
  ): InitializationState {
    BackgroundUtils.ensureMainThread()

    if (subsamplingImageDecoder.get() == null) {
      val decoder = imageDecoderProvider.provide()

      val success = subsamplingImageDecoder.compareAndSet(null, decoder)
      if (!success) {
        val exception = IllegalStateException("Decoder was already initialized!")
        eventListener?.onFailedToDecodeImageInfo(exception)

        return InitializationState.Error(exception)
      } else {
        logcat { "initialize() using ${decoder.javaClass.simpleName} decoder" }
      }
    }

    val imageDimensionsInfoResult = withContext(coroutineScope.coroutineContext) {
      imageSourceProvider.provide()
        .inputStream
        .use { inputStream -> decodeImageDimensions(inputStream) }
    }

    val imageDimensions = if (imageDimensionsInfoResult.isFailure) {
      val error = imageDimensionsInfoResult.exceptionOrThrow()
      logcat {
        "initialize() decodeImageDimensions() Failure!\n" +
          "sourceDebugKey=${debugKey}\n" +
          "imageDimensionsInfoResultError=${error.asLog()}"
      }

      eventListener?.onFailedToDecodeImageInfo(error)
      reset()
      return InitializationState.Error(error)
    } else {
      imageDimensionsInfoResult.getOrThrow()
    }

    eventListener?.onImageInfoDecoded(imageDimensions)
    sourceImageDimensions = imageDimensions

    if (debug) {
      logcat { "initialize() decodeImageDimensions() Success! imageDimensions=$imageDimensions" }
    }

    satTemp.reset()
    fitToBounds(true, satTemp)

    fullImageSampleSizeState.value = calculateInSampleSize(
      sourceWidth = imageDimensions.width,
      sourceHeight = imageDimensions.height,
      scale = satTemp.scale
    )

    if (fullImageSampleSizeState.value > 1) {
      fullImageSampleSizeState.value /= 2
    }

    if (debug) {
      logcat { "initialize() fullImageSampleSizeState=${fullImageSampleSizeState.value}" }
      logcat { "initialiseTileMap maxTileDimensions=${maxMaxTileSizeInfo.width}x${maxMaxTileSizeInfo.height}" }
    }

    initialiseTileMap(
      sourceWidth = imageDimensions.width,
      sourceHeight = imageDimensions.height,
      maxTileWidth = maxMaxTileSizeInfo.width,
      maxTileHeight = maxMaxTileSizeInfo.height,
      availableWidth = availableWidth,
      availableHeight = availableHeight,
      fullImageSampleSize = fullImageSampleSizeState.value
    )

    if (debug) {
      tileMap.entries.forEach { (sampleSize, tiles) ->
        logcat { "initialiseTileMap sampleSize=$sampleSize, tilesCount=${tiles.size}" }
      }
    }

    val currentSampleSize = fullImageSampleSizeState.value
    val baseGrid = tileMap[currentSampleSize]!!

    val loadTilesResult = loadTiles(
      currentSampleSize = currentSampleSize,
      tilesToLoad = baseGrid,
      eventListener = eventListener
    )

    if (loadTilesResult.isFailure) {
      return InitializationState.Error(loadTilesResult.exceptionOrThrow())
    }

    fitToBounds(false)

    val refreshTilesResult = refreshRequiredTilesInternal(
      load = true,
      sourceWidth = imageDimensions.width,
      sourceHeight = imageDimensions.height,
      fullImageSampleSize = fullImageSampleSizeState.value,
      scale = scaleState.value
    )

    if (refreshTilesResult.isFailure) {
      return InitializationState.Error(refreshTilesResult.exceptionOrThrow())
    }

    return InitializationState.Success
  }

  private fun decodeImageDimensions(
    inputStream: InputStream
  ): Result<IntSize> {
    BackgroundUtils.ensureBackgroundThread()

    return Result.Try {
      val decoder = subsamplingImageDecoder.get()
        ?: error("Decoder is not initialized!")

      return@Try decoder.init(context, inputStream).unwrap()
    }
  }

  private suspend fun loadTiles(
    currentSampleSize: Int,
    tilesToLoad: List<Tile>,
    eventListener: ComposeSubsamplingScaleImageEventListener?
  ): Result<Unit> {
    if (tilesToLoad.isEmpty()) {
      eventListener?.onFullImageLoaded()
      return Result.success(Unit)
    }

    if (debug) {
      logcat { "tilesToLoadCount=${tilesToLoad.size}" }
    }

    val decoder = subsamplingImageDecoder.get()
    if (decoder == null) {
      val exception = IllegalStateException("Decoder is not initialized!")
      eventListener?.onFailedToLoadFullImage(exception)
      return Result.failure(exception)
    }

    val totalTilesCount = tilesToLoad.size
    val remaining = AtomicInteger(totalTilesCount)

    coroutineScope {
      val totalCount = tilesToLoad.size

      tilesToLoad.forEachIndexed { index, tile ->
        coroutineScope.launch {
          BackgroundUtils.ensureBackgroundThread()
          val threadName = Thread.currentThread().name

          try {
            if (!tile.updateStateAsLoading()) {
              // Skip already loaded
              if (debug) {
                logcat {
                  "loadTiles($index/$totalCount, @$currentSampleSize) " +
                    "skipping already loaded tile at ${tile.xy}"
                }
              }

              return@launch
            }

            if (debug) {
              logcat {
                "loadTiles($index/$totalCount, @$currentSampleSize, ${threadName}) " +
                "decoding tile at ${tile.xy}"
              }
            }

            ensureActive()

            val decodedTileBitmap = runInterruptible {
              decoder.decodeRegion(
                sRect = tile.fileSourceRect.toAndroidRect(),
                sampleSize = tile.sampleSize
              ).unwrap()
            }

            eventListener?.onTileDecoded(index + 1, totalTilesCount)
            tile.onTileLoaded(decodedTileBitmap)
          } catch (error: Throwable) {
            if (debug) {
              logcatError {
                "loadTiles($index/$totalCount, @$currentSampleSize, ${threadName}) " +
                  "Failed to decode tile at ${tile.xy}, error: ${error.errorMessageOrClassName()}"
              }
            }

            eventListener?.onFailedToDecodeTile(index + 1, totalTilesCount, error)
            tile.onTileLoadError(error)

            // Consume all non CancellationException errors
            if (error is CancellationException) {
              throw error
            }
          } finally {
            val allProcessed = remaining.addAndGet(-1) == 0
            if (allProcessed) {
              eventListener?.onFullImageLoaded()
            }

            invalidate.value = invalidate.value + 1
          }
        }
      }
    }

    return Result.success(Unit)
  }

  fun refreshRequiredTiles(load: Boolean) {
    BackgroundUtils.ensureMainThread()

    coroutineScope.launch(Dispatchers.Main.immediate) {
      if (!isReady) {
        return@launch
      }

      val refreshTilesResult = refreshRequiredTilesInternal(
        load = load,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        fullImageSampleSize = fullImageSampleSizeState.value,
        scale = scaleState.value
      )

      if (refreshTilesResult.isFailure && debug) {
        logcatError {
          val errorMessage = refreshTilesResult.exceptionOrThrow().errorMessageOrClassName()
          "refreshRequiredTilesInternal() error: $errorMessage"
        }
      }
    }
  }

  private suspend fun refreshRequiredTilesInternal(
    load: Boolean,
    sourceWidth: Int,
    sourceHeight: Int,
    fullImageSampleSize: Int,
    scale: Float
  ): Result<Unit> {
    BackgroundUtils.ensureMainThread()

    val currentSampleSize = Math.min(
      fullImageSampleSize,
      calculateInSampleSize(
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        scale = scale
      )
    )

    val tilesToLoad = mutableListOf<Tile>()

    // Load tiles of the correct sample size that are on screen. Discard tiles off screen,
    // and those that are higher resolution than required, or lower res than required but
    // not the base layer, so the base layer is always present.
    for ((_, tiles) in tileMap) {
      for (tile in tiles) {
        val tileSampleSize = tile.sampleSize

        if (tileSampleSize != currentSampleSize && tileSampleSize != fullImageSampleSize) {
          tile.visible = false
          tile.recycle()
        }

        if (tileSampleSize == currentSampleSize) {
          if (tileVisible(tile)) {
            tile.visible = true

            if (load && tile.canLoad) {
              tilesToLoad += tile
            }
          } else if (tileSampleSize != fullImageSampleSize) {
            tile.visible = false
            tile.recycle()
          }
        } else if (tileSampleSize == fullImageSampleSize) {
          tile.visible = true
        }
      }
    }

    val loadTilesResult = loadTiles(
      currentSampleSize = currentSampleSize,
      tilesToLoad = tilesToLoad,
      eventListener = null
    )

    if (loadTilesResult.isFailure) {
      tilesToLoad.forEach { tile -> tile.onTileLoadError(loadTilesResult.exceptionOrThrow()) }
    }

    return loadTilesResult
  }

  private fun tileVisible(tile: Tile): Boolean {
    val sVisLeft = viewToSourceX(0f)
    val sVisRight = viewToSourceX(availableWidth.toFloat())
    val sVisTop = viewToSourceY(0f)
    val sVisBottom = viewToSourceY(availableHeight.toFloat())

    return !(sVisLeft > tile.sourceRect.right
      || tile.sourceRect.left > sVisRight
      || sVisTop > tile.sourceRect.bottom
      || tile.sourceRect.top > sVisBottom
    )
  }

  private fun viewToSourceX(vx: Float): Float {
    return (vx - screenTranslate.x) / scaleState.value
  }

  private fun viewToSourceY(vy: Float): Float {
    return (vy - screenTranslate.y) / scaleState.value
  }

  fun sourceToViewX(sx: Float): Float {
    return sx * scaleState.value + screenTranslate.x
  }

  fun sourceToViewY(sy: Float): Float {
    return sy * scaleState.value + screenTranslate.y
  }

  internal fun calculateInSampleSize(sourceWidth: Int, sourceHeight: Int, scale: Float): Int {
    var modifiedScale = scale

    if (minTileDpi > 0) {
      val metrics: DisplayMetrics = getResources().displayMetrics
      val averageDpi = (metrics.xdpi + metrics.ydpi) / 2
      modifiedScale *= (minTileDpi / averageDpi)
    }

    val reqWidth = (sourceWidth * modifiedScale).toInt()
    val reqHeight = (sourceHeight * modifiedScale).toInt()

    var inSampleSize = 1
    if (reqWidth == 0 || reqHeight == 0) {
      return 32
    }

    if (sourceHeight > reqHeight || sourceWidth > reqWidth) {
      val heightRatio = Math.round(sourceHeight.toFloat() / reqHeight.toFloat())
      val widthRatio = Math.round(sourceWidth.toFloat() / reqWidth.toFloat())

      inSampleSize = if (heightRatio < widthRatio) heightRatio else widthRatio
    }

    return inSampleSize.power()
  }

  private fun initialiseTileMap(
    sourceWidth: Int,
    sourceHeight: Int,
    maxTileWidth: Int,
    maxTileHeight: Int,
    availableWidth: Int,
    availableHeight: Int,
    fullImageSampleSize: Int
  ) {
    tileMap.clear()
    var sampleSize = fullImageSampleSize
    var xTiles = 1
    var yTiles = 1

    while (true) {
      var sTileWidth: Int = sourceWidth / xTiles
      var sTileHeight: Int = sourceHeight / yTiles
      var subTileWidth = sTileWidth / sampleSize
      var subTileHeight = sTileHeight / sampleSize

      while (
        subTileWidth + xTiles + 1 > maxTileWidth ||
        (subTileWidth > availableWidth * 1.25 && sampleSize < fullImageSampleSize)
      ) {
        xTiles += 1
        sTileWidth = sourceWidth / xTiles
        subTileWidth = sTileWidth / sampleSize
      }

      while (
        subTileHeight + yTiles + 1 > maxTileHeight ||
        (subTileHeight > availableHeight * 1.25 && sampleSize < fullImageSampleSize)
      ) {
        yTiles += 1
        sTileHeight = sourceHeight / yTiles
        subTileHeight = sTileHeight / sampleSize
      }

      val tileGrid = ArrayList<Tile>(xTiles * yTiles)

      for (x in 0 until xTiles) {
        for (y in 0 until yTiles) {
          val tile = Tile(x, y)
          tile.sampleSize = sampleSize
          tile.visible = sampleSize == fullImageSampleSize

          tile.sourceRect.set(
            left = x * sTileWidth,
            top = y * sTileHeight,
            right = if (x == xTiles - 1) sourceWidth else (x + 1) * sTileWidth,
            bottom = if (y == yTiles - 1) sourceHeight else (y + 1) * sTileHeight
          )

          tile.screenRect.set(0, 0, 0, 0)
          tile.fileSourceRect.set(tile.sourceRect)

          tileGrid.add(tile)
        }
      }

      tileMap[sampleSize] = tileGrid

      if (sampleSize == 1) {
        break
      }

      sampleSize /= 2
    }
  }

  fun fitToBounds(center: Boolean) {
    satTemp.scale = scaleState.value
    satTemp.screenTranslate.set(
      screenTranslate.x.toFloat(),
      screenTranslate.y.toFloat()
    )

    fitToBounds(center, satTemp)

    scaleState.value = satTemp.scale
    screenTranslate.set(
      satTemp.screenTranslate.x.toInt(),
      satTemp.screenTranslate.y.toInt()
    )

    if (needInitScreenTranslate) {
      needInitScreenTranslate = false

      screenTranslate.set(
        vTranslateForSCenter(
          sCenterX = (sourceWidth / 2).toFloat(),
          sCenterY = (sourceHeight / 2).toFloat(),
          scale = scaleState.value
        )
      )
    }
  }

  private fun fitToBounds(shouldCenter: Boolean, sat: ScaleAndTranslate) {
    var center = shouldCenter
//    if (panLimit == PAN_LIMIT_OUTSIDE && isReady()) {
//      center = false
//    }

    check(availableWidth > 0) { "Bad availableWidth" }
    check(availableHeight > 0) { "Bad availableHeight" }

    val vTranslate: PointF = sat.screenTranslate
    val scale: Float = limitedScale(sat.scale)
    val scaleWidth: Float = scale * sourceWidth
    val scaleHeight: Float = scale * sourceHeight

    /*if (panLimit == PAN_LIMIT_CENTER && isReady()) {
      vTranslate.x = Math.max(vTranslate.x, availableWidth / 2 - scaleWidth)
      vTranslate.y = Math.max(vTranslate.y, availableHeight / 2 - scaleHeight)
    } else */
    if (center) {
      vTranslate.x = Math.max(vTranslate.x, availableWidth - scaleWidth)
      vTranslate.y = Math.max(vTranslate.y, availableHeight - scaleHeight)
    } else {
      vTranslate.x = Math.max(vTranslate.x, -scaleWidth)
      vTranslate.y = Math.max(vTranslate.y, -scaleHeight)
    }

    // Asymmetric padding adjustments
//    val xPaddingRatio = if (getPaddingLeft() > 0 || getPaddingRight() > 0) {
//      getPaddingLeft() / (getPaddingLeft() + getPaddingRight()) as Float
//    } else {
//      0.5f
//    }
//    val yPaddingRatio = if (getPaddingTop() > 0 || getPaddingBottom() > 0) {
//      getPaddingTop() / (getPaddingTop() + getPaddingBottom()) as Float
//    } else {
//      0.5f
//    }

    val xPaddingRatio = 0.5f
    val yPaddingRatio = 0.5f

    val maxTx: Float
    val maxTy: Float

    /*if (panLimit == PAN_LIMIT_CENTER && isReady()) {
      maxTx = Math.max(0, availableWidth / 2).toFloat()
      maxTy = Math.max(0, availableHeight / 2).toFloat()
    } else */
    if (center) {
      maxTx = Math.max(0f, (availableWidth - scaleWidth) * xPaddingRatio)
      maxTy = Math.max(0f, (availableHeight - scaleHeight) * yPaddingRatio)
    } else {
      maxTx = Math.max(0, availableWidth).toFloat()
      maxTy = Math.max(0, availableHeight).toFloat()
    }
    vTranslate.x = Math.min(vTranslate.x, maxTx)
    vTranslate.y = Math.min(vTranslate.y, maxTy)
    sat.scale = scale
  }

  private fun limitedScale(targetScale: Float): Float {
    // TODO(KurobaEx): gotta limit it somehow
//    check(minScale < maxScale) {
//      "minScale must be less than maxScale! (minScale: ${minScale}, maxScale: ${maxScale})"
//    }

    var resultScale = targetScale
    resultScale = Math.max(minScale, resultScale)
    resultScale = Math.min(maxScale, resultScale)
    return resultScale
  }

  private fun calculateMaxScale(dpi: Int): Float {
    if (maxScaleParam != null) {
      return maxScaleParam
    }

    if (dpi <= 0) {
      return defaultMaxScale
    }

    val metrics = getResources().displayMetrics
    val averageDpi = (metrics.xdpi + metrics.ydpi) / 2
    return averageDpi / dpi
  }

  fun calculateMinScale(): Float {
    // TODO(KurobaEx): paddings
    val hPadding = 0
    val vPadding = 0

    check(availableWidth > 0) { "availableWidth is zero" }
    check(availableHeight > 0) { "availableHeight is zero" }
    check(sourceWidth > 0) { "sourceWidth is zero" }
    check(sourceHeight > 0) { "sourceHeight is zero" }

    if (minimumScaleType == MinimumScaleType.ScaleTypeCenterInside) {
      return Math.min(
        (availableWidth - hPadding) / sourceWidth.toFloat(),
        (availableHeight - vPadding) / sourceHeight.toFloat()
      )
    } else if (minimumScaleType == MinimumScaleType.ScaleTypeCenterCrop) {
      return Math.max(
        (availableWidth - hPadding) / sourceWidth.toFloat(),
        (availableHeight - vPadding) / sourceHeight.toFloat()
      )
    } else if (minimumScaleType == MinimumScaleType.ScaleTypeFitWidth) {
      return (availableWidth - hPadding) / sourceWidth.toFloat()
    } else if (minimumScaleType == MinimumScaleType.ScaleTypeFitHeight) {
      return (availableHeight - vPadding) / sourceHeight.toFloat()
    } else if (minimumScaleType == MinimumScaleType.ScaleTypeOriginalSize) {
      return 1f
    } else if (minimumScaleType == MinimumScaleType.ScaleTypeSmartFit) {
      return if (sourceHeight > sourceWidth) {
        // Fit to width
        (availableWidth - hPadding) / sourceWidth.toFloat()
      } else {
        // Fit to height
        (availableHeight - vPadding) / sourceHeight.toFloat()
      }
    } else if (minimumScaleType is MinimumScaleType.ScaleTypeCustom && (minScaleParam != null && minScaleParam > 0f)) {
      return minScaleParam
    } else {
      return Math.min(
        (availableWidth - hPadding) / sourceWidth.toFloat(),
        (availableHeight - vPadding) / sourceHeight.toFloat()
      )
    }
  }

  private fun getResources(): Resources = context.resources

  @SuppressLint("LongLogTag")
  private fun logcat(
    message: () -> String
  ) {
    val msg = buildString {
      if (debugKey != null) {
        append("debugKey=${debugKey}, ")
      }

      append(message())
    }

    Log.d(TAG, msg)
  }

  @SuppressLint("LongLogTag")
  private fun logcatError(
    message: () -> String
  ) {
    val msg = buildString {
      if (debugKey != null) {
        append("debugKey=${debugKey}, ")
      }

      append(message())
    }

    Log.e(TAG, msg)
  }

  internal fun sourceToViewRect(source: RectMut, target: RectMut) {
    target.set(
      sourceToViewX(source.left.toFloat()).toInt(),
      sourceToViewY(source.top.toFloat()).toInt(),
      sourceToViewX(source.right.toFloat()).toInt(),
      sourceToViewY(source.bottom.toFloat()).toInt()
    )
  }

  fun getCenter(): PointF {
    val mX: Int = availableWidth / 2
    val mY: Int = availableHeight / 2
    return viewToSourceCoord(mX.toFloat(), mY.toFloat())
  }

  fun viewToSourceCoord(vx: Float, vy: Float): PointF {
    return viewToSourceCoord(vx, vy, PointF())
  }

  fun viewToSourceCoord(vxy: PointF): PointF {
    return viewToSourceCoord(vxy.x, vxy.y, PointF())
  }

  fun viewToSourceCoord(vx: Float, vy: Float, sTarget: PointF): PointF {
    sTarget.set(viewToSourceX(vx), viewToSourceY(vy))
    return sTarget
  }

  fun sourceToViewCoord(sxy: PointF): PointF {
    return sourceToViewCoord(sxy.x, sxy.y, PointF())
  }

  fun sourceToViewCoord(sx: Float, sy: Float): PointF {
    return sourceToViewCoord(sx, sy, PointF())
  }

  fun sourceToViewCoord(sx: Float, sy: Float, vTarget: PointF): PointF {
    vTarget.set(sourceToViewX(sx), sourceToViewY(sy))
    return vTarget
  }

  fun limitedSCenter(
    sCenterX: Float,
    sCenterY: Float,
    scale: Float,
    sTarget: PointF
  ): PointF {
    val vTranslate = vTranslateForSCenter(sCenterX, sCenterY, scale)

    val vxCenter: Int = availableWidth / 2
    val vyCenter: Int = availableHeight / 2

    val sx = (vxCenter - vTranslate.x) / scale
    val sy = (vyCenter - vTranslate.y) / scale
    sTarget[sx] = sy
    return sTarget
  }

  fun vTranslateForSCenter(sCenterX: Float, sCenterY: Float, scale: Float): PointF {
    val vxCenter: Int = availableWidth / 2
    val vyCenter: Int = availableHeight / 2

    satTemp.scale = scale
    satTemp.screenTranslate.set(vxCenter - sCenterX * scale, vyCenter - sCenterY * scale)

    fitToBounds(true, satTemp)
    return satTemp.screenTranslate
  }

  fun ease(gestureAnimationEasing: GestureAnimationEasing, time: Long, from: Float, change: Float, duration: Long): Float {
    return when (gestureAnimationEasing) {
      GestureAnimationEasing.EaseInOutQuad -> easeInOutQuad(time, from, change, duration)
      GestureAnimationEasing.EaseOutQuad -> easeOutQuad(time, from, change, duration)
      else -> throw java.lang.IllegalStateException("Unexpected easing type: $gestureAnimationEasing")
    }
  }

  fun getPanInfo(
    horizontalTolerance: Float = PanInfo.DEFAULT_TOLERANCE,
    verticalTolerance: Float = PanInfo.DEFAULT_TOLERANCE
  ): PanInfo? {
    if (!isReady) {
      return null
    }

    val scale = scaleState.value
    val scaleWidth: Float = scale * sourceWidth
    val scaleHeight: Float = scale * sourceHeight

    return PanInfo(
      top = Math.max(0f, -screenTranslate.y.toFloat()),
      left = Math.max(0f, -screenTranslate.x.toFloat()),
      bottom = Math.max(0f, scaleHeight + screenTranslate.y - availableHeight),
      right = Math.max(0f, scaleWidth + screenTranslate.x - availableWidth),
      horizontalTolerance = horizontalTolerance,
      verticalTolerance = verticalTolerance
    )
  }

  private fun easeOutQuad(time: Long, from: Float, change: Float, duration: Long): Float {
    val progress = time.toFloat() / duration.toFloat()
    return -change * progress * (progress - 2) + from
  }

  private fun easeInOutQuad(time: Long, from: Float, change: Float, duration: Long): Float {
    var timeF = time / (duration / 2f)

    if (timeF < 1) {
      return change / 2f * timeF * timeF + from
    }

    timeF--
    return -change / 2f * (timeF * (timeF - 2) - 1) + from
  }

  companion object {
    private const val TAG = "ComposeSubsamplingScaleImageState"
  }

}