package com.github.k1rakishou.cssi_lib

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.ui.unit.IntSize
import java.io.InputStream

interface ComposeSubsamplingScaleImageDecoder {
  fun init(context: Context, inputStream: InputStream): Result<IntSize>
  fun decodeRegion(sRect: Rect, sampleSize: Int): Result<Bitmap>
  fun isReady(): Boolean
  fun recycle()
}