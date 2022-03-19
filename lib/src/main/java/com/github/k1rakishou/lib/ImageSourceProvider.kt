package com.github.k1rakishou.lib


interface ImageSourceProvider {
  suspend fun provide(): ComposeSubsamplingScaleImageSource
}