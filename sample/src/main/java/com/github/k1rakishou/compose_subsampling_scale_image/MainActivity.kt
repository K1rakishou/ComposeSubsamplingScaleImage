package com.github.k1rakishou.compose_subsampling_scale_image

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import com.github.k1rakishou.lib.ComposeSubsamplingScaleImage
import com.github.k1rakishou.lib.ComposeSubsamplingScaleImageEventListener
import com.github.k1rakishou.lib.ComposeSubsamplingScaleImageSource
import com.github.k1rakishou.lib.ImageSourceProvider
import com.github.k1rakishou.lib.MaxTileSizeInfo
import com.github.k1rakishou.lib.MinimumScaleType
import com.github.k1rakishou.lib.helpers.logcat
import com.github.k1rakishou.lib.helpers.logcatError
import com.github.k1rakishou.lib.rememberComposeSubsamplingScaleImageState
import com.google.accompanist.pager.ExperimentalPagerApi
import com.google.accompanist.pager.HorizontalPager
import com.google.accompanist.pager.rememberPagerState
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

class MainActivity : ComponentActivity() {
  private val baseDir = "test_images"

  @OptIn(ExperimentalPagerApi::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContent {
      val images = remember { this@MainActivity.assets.list(baseDir)?.toList() ?: emptyList() }
      val pagerState = rememberPagerState()

      HorizontalPager(
        modifier = Modifier.fillMaxSize().background(Color.DarkGray),
        count = images.size,
        state = pagerState,
        key = { page -> images[page] }
      ) { page ->
        val imageFileName = images[page]
        DisplayFullImage(imageFileName = imageFileName)
      }
    }
  }

  @Composable
  private fun DisplayFullImage(
    imageFileName: String
  ) {
    val imageSourceProvider = remember(key1 = imageFileName) {
      object : ImageSourceProvider {
        override suspend fun provide(): ComposeSubsamplingScaleImageSource {
          return ComposeSubsamplingScaleImageSource(this@MainActivity.assets.open("$baseDir/${imageFileName}"))
        }
      }
    }

    val eventListener = remember {
      object : ComposeSubsamplingScaleImageEventListener() {
        override fun onImageInfoDecoded(fullImageSize: IntSize) {
          logcat(tag = "DisplayFullImage") { "onImageInfoDecoded() fullImageSize=$fullImageSize" }
        }

        override fun onFailedToDecodeImageInfo(error: Throwable) {
          logcat(tag = "DisplayFullImage") { "onFailedToDecodeImageInfo() error=${error.asLog()}" }
        }

        override fun onTileDecoded(tileIndex: Int, totalTilesInTopLayer: Int) {
          logcat(tag = "DisplayFullImage") { "onTileDecoded() ${tileIndex}/${totalTilesInTopLayer}" }
        }

        override fun onFailedToDecodeTile(
          tileIndex: Int,
          totalTilesInTopLayer: Int,
          error: Throwable
        ) {
          logcat(tag = "DisplayFullImage") { "onTileDecoded() ${tileIndex}/${totalTilesInTopLayer}, error=${error.asLog()}" }
        }

        override fun onFullImageLoaded() {
          logcat(tag = "DisplayFullImage") { "onFullImageLoaded()" }
        }

        override fun onFailedToLoadFullImage(error: Throwable) {
          logcatError(tag = "DisplayFullImage") { "onFailedToLoadFullImage() error=${error.asLog()}" }
        }
      }
    }

    val decoderDispatcherLazy = remember {
      lazy {
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
          .asCoroutineDispatcher()
      }
    }

    ComposeSubsamplingScaleImage(
      modifier = Modifier.fillMaxSize(),
      state = rememberComposeSubsamplingScaleImageState(
        minTileDpiDefault = 0,
        maxMaxTileSizeInfo = { MaxTileSizeInfo.Fixed(IntSize(2048, 2048)) },
        minimumScaleType = { MinimumScaleType.ScaleTypeCenterInside },
        maxScale = 5f,
        decoderDispatcherLazy = decoderDispatcherLazy,
        debugKey = imageFileName,
        debug = true
      ),
      imageSourceProvider = imageSourceProvider,
      eventListener = eventListener
    )
  }

}

private fun Throwable.asLog(): String {
  val stringWriter = StringWriter(256)
  val printWriter = PrintWriter(stringWriter, false)
  printStackTrace(printWriter)
  printWriter.flush()
  return stringWriter.toString()
}