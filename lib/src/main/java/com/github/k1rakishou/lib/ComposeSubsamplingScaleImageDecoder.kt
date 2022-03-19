package com.github.k1rakishou.lib

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.ui.unit.IntSize
import java.io.InputStream

interface ComposeSubsamplingScaleImageDecoder {
  /**
   * Caller must close [inputStream]
   * */
  fun init(context: Context, inputStream: InputStream): Result<IntSize>
  fun decodeRegion(sRect: Rect, sampleSize: Int): Result<Bitmap>
  fun isReady(): Boolean
  fun recycle()
}