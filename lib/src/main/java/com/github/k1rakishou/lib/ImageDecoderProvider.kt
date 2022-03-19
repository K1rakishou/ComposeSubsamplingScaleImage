package com.github.k1rakishou.lib

interface ImageDecoderProvider {
  suspend fun provide(): ComposeSubsamplingScaleImageDecoder
}