package com.github.k1rakishou.cssi_lib

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.IntSize

sealed class MaxTileSize(
  val maxTileSizeState: MutableState<IntSize?>
) {
  val width: Int
    get() = maxTileSizeState.value!!.width
  val height: Int
    get() = maxTileSizeState.value!!.height

  // May crash if the canvas doesn't support one of the sizes (width/height)
  class Fixed(
    size: IntSize
  ) : MaxTileSize(mutableStateOf(size))

  // Will be detected automatically by using Canvas() composable to get the native canvas and then
  // calling getMaximumBitmapWidth/getMaximumBitmapHeight
  class Auto(
    maxTileSizeState: MutableState<IntSize?> = mutableStateOf<IntSize?>(null)
  ) : MaxTileSize(maxTileSizeState)
}
