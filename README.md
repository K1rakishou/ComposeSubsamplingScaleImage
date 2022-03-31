## ComposeSubsamplingScaleImage

![Sample](art/sample.gif)

### Link (expect bugs)

```
dependencies {
  implementation 'com.github.K1rakishou:ComposeSubsamplingScaleImage:fab4ae38cb'
}
```

### Why?
The original SubsamplingScaleImageView doesn't fully work with compose when using it inside of the HorizontalPager/LazyRow (probably the most common use-case: image gallery), namely swiping left/right doesn't work because SubsamplingScaleImageView, internally, calls requestDisallowInterceptTouchEvent(true) (basically tells every parent view to stop handling motion events/cancel all gestures) right after the first pointer touches the screen which (from my understanding) cancels awaitDownAndSlop() (code below awaitFirstDownOnPass() is never called so I assume the whole scope is getting canceled) function inside of the Modifier.draggable() which is used by Modifier.scrollable() which is used by LazyRow which is used by HorizontalPager. Even tough SubsamplingScaleImageView calls requestDisallowInterceptTouchEvent(false) after it detects that it cannot process the gesture (touch slop checks), Compose doesn't restart the awaitPointerEventScope after requestDisallowInterceptTouchEvent(false) is called (since it then waits for all pointers to be up or canceled) so the gesture stops working. I don't know whether it's a bug or is just not handled yet.

### Why Partial?
I don't plan on porting all the features of the original SubsamplingScaleImageView, only the stuff that I personally need. Feel free to fork it and add whatever you need.

### What currently works?
- Tap detection.
- Long tap detection.
- Zoom/pan with two fingers.
- One finger zoom (double-tap then move finger up/down for zoom).
- Quick zoom (double tap to zoom in/zoom out).
- Bunch of configurable parameters like minScale, maxScale, minScaleType, animation durations, etc.
- Store/restore last scale/position upon configuration change.

### What doesn't work but is planned?
- It's impossible to replace one image with another without recreating the whole ComposeSubsamplingScaleImage composable.
- Paddings.
- PanLimit (PAN_LIMIT_INSIDE, PAN_LIMIT_OUTSIDE).
- Bitmap pooling.
