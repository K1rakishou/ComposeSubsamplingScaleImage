package com.github.k1rakishou.cssi_lib


interface ImageSourceProvider {
  /**
   * You can do some checks, like check whether the resource that provides an image exists or not
   * corrupted or whatever and return Result.failure() if it's either of those and in this case
   * [ComposeSubsamplingScaleImageEventListener.onFailedToProvideSource] callback will be called.
   * */
  suspend fun provide(): Result<ComposeSubsamplingScaleImageSource>
}