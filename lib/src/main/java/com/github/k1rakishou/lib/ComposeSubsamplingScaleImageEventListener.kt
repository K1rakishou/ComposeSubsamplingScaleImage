package com.github.k1rakishou.lib

interface ComposeSubsamplingScaleImageEventListener {
  fun onFullImageLoaded()
  fun onFullImageFailedToLoad(error: Throwable)
}
