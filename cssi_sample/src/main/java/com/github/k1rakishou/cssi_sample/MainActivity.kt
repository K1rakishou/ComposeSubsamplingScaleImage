package com.github.k1rakishou.cssi_sample

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import com.github.k1rakishou.cssi_lib.ComposeSubsamplingScaleImage
import com.github.k1rakishou.cssi_lib.ComposeSubsamplingScaleImageEventListener
import com.github.k1rakishou.cssi_lib.ComposeSubsamplingScaleImageSource
import com.github.k1rakishou.cssi_lib.ImageSourceProvider
import com.github.k1rakishou.cssi_lib.ScrollableContainerDirection
import com.github.k1rakishou.cssi_lib.rememberComposeSubsamplingScaleImageState
import com.google.accompanist.pager.ExperimentalPagerApi
import com.google.accompanist.pager.HorizontalPager
import com.google.accompanist.pager.PagerState
import com.google.accompanist.pager.rememberPagerState
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : ComponentActivity() {
  private val baseDir = "test_images"
  private var prevToast: Toast? = null

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
        DisplayFullImage(imageFileName = imageFileName, pagerState = pagerState)
      }
    }
  }

  @OptIn(ExperimentalPagerApi::class)
  @Composable
  private fun DisplayFullImage(imageFileName: String, pagerState: PagerState) {
    val imageSourceProvider = remember(key1 = imageFileName) {
      object : ImageSourceProvider {
        override suspend fun provide(): ComposeSubsamplingScaleImageSource {
          return ComposeSubsamplingScaleImageSource(
            debugKey = imageFileName,
            inputStream = this@MainActivity.assets.open("$baseDir/${imageFileName}")
          )
        }
      }
    }

    val eventListener = remember {
      object : ComposeSubsamplingScaleImageEventListener() {
        override fun onImageInfoDecoded(fullImageSize: IntSize) {
          Log.d("DisplayFullImage", "onImageInfoDecoded() fullImageSize=$fullImageSize")
        }

        override fun onFailedToDecodeImageInfo(error: Throwable) {
          Log.d("DisplayFullImage", "onFailedToDecodeImageInfo() error=${error.asLog()}")
        }

        override fun onTileDecoded(tileIndex: Int, totalTilesInTopLayer: Int) {
          Log.d("DisplayFullImage", "onTileDecoded() ${tileIndex}/${totalTilesInTopLayer}")
        }

        override fun onFailedToDecodeTile(
          tileIndex: Int,
          totalTilesInTopLayer: Int,
          error: Throwable
        ) {
          Log.d("DisplayFullImage", "onTileDecoded() ${tileIndex}/${totalTilesInTopLayer}, error=${error.asLog()}")
        }

        override fun onFullImageLoaded() {
          Log.d("DisplayFullImage", "onFullImageLoaded()")
        }

        override fun onFailedToLoadFullImage(error: Throwable) {
          Log.e("DisplayFullImage", "onFailedToLoadFullImage() error=${error.asLog()}")
        }
      }
    }

    val state = rememberComposeSubsamplingScaleImageState(
      maxScale = 3f,
      doubleTapZoom = 2f,
      scrollableContainerDirection = ScrollableContainerDirection.Horizontal,
      debug = true
    )

    ComposeSubsamplingScaleImage(
      modifier = Modifier.fillMaxSize(),
      pointerInputKey = pagerState.currentPage,
      state = state,
      imageSourceProvider = imageSourceProvider,
      eventListener = eventListener,
      onImageTapped = { offset ->
        Log.d("DisplayFullImage", "Image tapped at ${offset}")
        toast("Image tapped at ${offset}")
      },
      onImageLongTapped = { offset ->
        Log.d("DisplayFullImage", "Image long tapped at ${offset}")
        toast("Image long tapped at ${offset}")
      }
    )
  }

  private fun toast(message: String) {
    prevToast?.cancel()
    prevToast = null

    prevToast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
    prevToast!!.show()
  }

}

private fun Throwable.asLog(): String {
  val stringWriter = StringWriter(256)
  val printWriter = PrintWriter(stringWriter, false)
  printStackTrace(printWriter)
  printWriter.flush()
  return stringWriter.toString()
}