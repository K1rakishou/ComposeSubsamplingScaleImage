package com.github.k1rakishou.cssi_lib

sealed class MinimumScaleType {
  object ScaleTypeCenterInside : MinimumScaleType()
  object ScaleTypeCenterCrop : MinimumScaleType()
  object ScaleTypeFitWidth : MinimumScaleType()
  object ScaleTypeFitHeight : MinimumScaleType()
  object ScaleTypeOriginalSize : MinimumScaleType()
  object ScaleTypeSmartFit : MinimumScaleType()
  object ScaleTypeCustom : MinimumScaleType()
}