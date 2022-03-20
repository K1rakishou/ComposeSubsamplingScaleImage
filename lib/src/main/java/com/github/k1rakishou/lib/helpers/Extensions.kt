package com.github.k1rakishou.lib.helpers

import java.io.InterruptedIOException
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlinx.coroutines.CancellationException

internal inline fun <T> Result.Companion.Try(func: () -> T): Result<T> {
  return try {
    Result.success(func())
  } catch (error: Throwable) {
    Result.failure(error)
  }
}

internal fun Result<*>.exceptionOrThrow(): Throwable {
  if (this.isSuccess) {
    error("Expected Failure but got Success")
  }

  return exceptionOrNull()!!
}

fun <T> Result<T>.unwrap(): T {
  return getOrThrow()
}

@Suppress("ReplaceSizeCheckWithIsNotEmpty", "NOTHING_TO_INLINE")
@OptIn(ExperimentalContracts::class)
inline fun CharSequence?.isNotNullNorBlank(): Boolean {
  contract {
    returns(true) implies (this@isNotNullNorBlank != null)
  }

  return this != null && this.isNotBlank()
}


fun Throwable.isExceptionImportant(): Boolean {
  return when (this) {
    is CancellationException,
    is InterruptedIOException,
    is InterruptedException -> false
    else -> true
  }
}

fun Throwable.errorMessageOrClassName(): String {
  if (!isExceptionImportant()) {
    return this::class.java.name
  }

  val actualMessage = if (cause?.message?.isNotNullNorBlank() == true) {
    cause!!.message
  } else {
    message
  }

  if (!actualMessage.isNullOrBlank()) {
    return actualMessage
  }

  return this::class.java.name
}

internal fun Int.power(): Int {
  var power = 1
  while ((power * 2) < this) {
    power *= 2
  }

  return power
}