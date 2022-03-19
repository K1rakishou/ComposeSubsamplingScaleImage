package com.github.k1rakishou.lib

import android.graphics.Bitmap

internal class Tile {
  @get:Synchronized
  @set:Synchronized
  var tileState: TileState? = null
  @get:Synchronized
  @set:Synchronized
  var sampleSize = 0
  @get:Synchronized
  @set:Synchronized
  var visible = false

  @get:Synchronized
  val isLoading: Boolean
    get() = tileState is TileState.Loading
  @get:Synchronized
  val isLoaded: Boolean
    get() = tileState is TileState.Loaded
  @get:Synchronized
  val isError: Boolean
    get() = tileState is TileState.Error
  @get:Synchronized
  val bitmapOrNull: Bitmap?
    get() = (tileState as? TileState.Loaded)?.bitmap

  // sRect
  @get:Synchronized
  val sourceRect: RectMut = RectMut(0, 0, 0, 0)

  // vRect
  @get:Synchronized
  val screenRect: RectMut = RectMut(0, 0, 0, 0)

  // fileSRect
  @get:Synchronized
  val fileSourceRect: RectMut = RectMut(0, 0, 0, 0)

  @Synchronized
  fun recycle() {
    (tileState as? TileState.Loaded)?.bitmap?.recycle()
    tileState = null
    visible = false
  }

  override fun toString(): String {
    return "Tile(sampleSize=$sampleSize, tileState=$tileState, visible=$visible, " +
      "sourceRect=$sourceRect, screenRect=$screenRect, fileSourceRect=$fileSourceRect)"
  }

}

internal sealed class TileState {
  object Loading : TileState()
  class Loaded(val bitmap: Bitmap) : TileState()
  class Error(val error: Throwable) : TileState()

  override fun toString(): String {
    return when (this) {
      is Error -> "Error(message=${this.error.message})"
      is Loaded -> "Loaded(bitmap=${this.bitmap.width}x${this.bitmap.height}, recycled=${this.bitmap.isRecycled})"
      Loading -> "Loading"
    }
  }
}