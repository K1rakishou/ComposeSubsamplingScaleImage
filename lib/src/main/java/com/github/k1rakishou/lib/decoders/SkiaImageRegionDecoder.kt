package com.github.k1rakishou.lib.decoders

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory.Options
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.ui.unit.IntSize
import com.github.k1rakishou.lib.ComposeSubsamplingScaleImageDecoder
import com.github.k1rakishou.lib.helpers.Try
import java.io.InputStream
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock

class SkiaImageRegionDecoder(
  private val bitmapConfig: Bitmap.Config = Bitmap.Config.RGB_565
) : ComposeSubsamplingScaleImageDecoder {
  private var decoder: BitmapRegionDecoder? = null
  private val decoderLock: ReadWriteLock = ReentrantReadWriteLock(true)

  override fun init(context: Context, inputStream: InputStream): Result<IntSize> {
    return Result.Try {
      decoder = BitmapRegionDecoder.newInstance(inputStream, false)
      return@Try IntSize(decoder!!.width, decoder!!.height)
    }
  }

  override fun decodeRegion(sRect: Rect, sampleSize: Int): Result<Bitmap> {
    decoderLock.readLock().lock()

    return Result.Try {
      try {
        if (decoder != null && !decoder!!.isRecycled) {
          val options = Options()
          options.inSampleSize = sampleSize
          options.inPreferredConfig = bitmapConfig

          val bitmap: Bitmap = decoder!!.decodeRegion(sRect, options)
            ?: throw Exception("Failed to initialize image decoder: ${this.javaClass.simpleName}. Image format may not be supported")

          bitmap
        } else {
          throw IllegalStateException("Cannot decode region after decoder has been recycled")
        }
      } finally {
        decoderLock.readLock().unlock()
      }
    }
  }

  @Synchronized
  override fun isReady(): Boolean {
    return decoder != null && !decoder!!.isRecycled
  }

  @Synchronized
  override fun recycle() {
    decoderLock.writeLock().lock()

    try {
      decoder?.recycle()
      decoder = null
    } finally {
      decoderLock.writeLock().unlock()
    }
  }

}