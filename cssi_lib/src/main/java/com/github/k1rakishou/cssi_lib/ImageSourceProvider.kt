package com.github.k1rakishou.cssi_lib


interface ImageSourceProvider {
  suspend fun provide(): ComposeSubsamplingScaleImageSource
}