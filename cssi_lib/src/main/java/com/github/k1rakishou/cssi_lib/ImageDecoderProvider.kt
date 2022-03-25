package com.github.k1rakishou.cssi_lib

interface ImageDecoderProvider {
  suspend fun provide(): ComposeSubsamplingScaleImageDecoder
}