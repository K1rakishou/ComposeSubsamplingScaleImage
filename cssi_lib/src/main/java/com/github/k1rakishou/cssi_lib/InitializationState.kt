package com.github.k1rakishou.cssi_lib

sealed class InitializationState {
  object Uninitialized : InitializationState()
  data class Error(val exception: Throwable) : InitializationState()
  object Success : InitializationState()
}