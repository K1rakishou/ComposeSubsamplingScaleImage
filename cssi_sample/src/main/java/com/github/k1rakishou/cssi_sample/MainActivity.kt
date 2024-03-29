package com.github.k1rakishou.cssi_sample

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
      val images = remember {
        val filesInDir = this@MainActivity.assets.list(baseDir)?.toList()
          ?: return@remember emptyList()

        return@remember filesInDir + filesInDir.first()
      }

      val pagerState = rememberPagerState()

      HorizontalPager(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.DarkGray),
        count = images.size,
        state = pagerState
      ) { page ->
        val imageFileName = images[page]
        val isLastPage = page == images.lastIndex

        DisplayFullImage(
          imageFileName = imageFileName,
          pagerState = pagerState,
          isLastPage = isLastPage
        )
      }
    }
  }

  @OptIn(ExperimentalPagerApi::class)
  @Composable
  private fun DisplayFullImage(
    imageFileName: String,
    pagerState: PagerState,
    isLastPage: Boolean
  ) {
    val imageSourceProvider = remember(key1 = imageFileName) {
      object : ImageSourceProvider {
        override suspend fun provide(): Result<ComposeSubsamplingScaleImageSource> {
          val source = ComposeSubsamplingScaleImageSource(
            debugKey = imageFileName,
            inputStream = this@MainActivity.assets.open("$baseDir/${imageFileName}")
          )

          return Result.success(source)
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

        override fun onInitializationCanceled() {
          Log.e("DisplayFullImage", "onInitializationCanceled()")
        }
      }
    }

    var maxSize by remember { mutableStateOf(true) }

    val sizeModifier = if (maxSize) {
      Modifier.fillMaxSize()
    } else {
      Modifier.size(300.dp, 300.dp)
    }

    Box(
      modifier = Modifier
        .then(sizeModifier),
      contentAlignment = Alignment.Center
    ) {
      val state = rememberComposeSubsamplingScaleImageState(
        maxScale = 3f,
        doubleTapZoom = 2f,
        scrollableContainerDirection = ScrollableContainerDirection.Horizontal,
        debug = true
      )

      ComposeSubsamplingScaleImage(
        modifier = Modifier.fillMaxSize(),
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

      if (isLastPage) {
        Button(onClick = { maxSize = !maxSize }) {
          Text(text = "Click me")
        }
      }
    }
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