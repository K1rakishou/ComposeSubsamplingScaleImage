package com.github.k1rakishou.lib

import kotlin.math.absoluteValue

class PanInfo(
  val top: Float,
  val left: Float,
  val bottom: Float,
  val right: Float,
  val horizontalTolerance: Float,
  val verticalTolerance: Float
) {

  fun touchesLeft(): Boolean {
    return left.absoluteValue < horizontalTolerance
  }

  fun touchesRight(): Boolean {
    return right.absoluteValue < horizontalTolerance
  }

  fun touchesTop(): Boolean {
    return top.absoluteValue < verticalTolerance
  }

  fun touchesBottom(): Boolean {
    return bottom.absoluteValue < verticalTolerance
  }

  fun touchesLeftAndRight(): Boolean {
    return touchesLeft() && touchesRight()
  }

  fun touchesTopAndBottom(): Boolean {
    return touchesTop() && touchesBottom()
  }

  fun touchesAllSides(): Boolean {
    return touchesLeftAndRight() && touchesTopAndBottom()
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as PanInfo

    if (top != other.top) return false
    if (left != other.left) return false
    if (bottom != other.bottom) return false
    if (right != other.right) return false

    return true
  }

  override fun hashCode(): Int {
    var result = top.hashCode()
    result = 31 * result + left.hashCode()
    result = 31 * result + bottom.hashCode()
    result = 31 * result + right.hashCode()
    return result
  }

  override fun toString(): String {
    return "PanInfo(top=$top, left=$left, bottom=$bottom, right=$right)"
  }

  companion object {
    const val DEFAULT_TOLERANCE = 3f
  }

}