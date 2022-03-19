package com.github.k1rakishou.lib

sealed class InitializationState {
  object Uninitialized : InitializationState()
  data class Error(val exception: Throwable) : InitializationState()
  object Success : InitializationState()
}