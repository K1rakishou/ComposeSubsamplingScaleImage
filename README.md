## Work in progress!

### Why?
The original SubsamplingScaleImageView doesn't fully work with compose when using it inside of the HorizontalPager/LazyRow (probably the most common use-case: image gallery), namely swiping left/right doesn't work because SubsamplingScaleImageView, internally, calls requestDisallowInterceptTouchEvent(true) right after the first pointer touches the screen which (from my understanding) cancels awaitDownAndSlop() (code below awaitFirstDownOnPass() is never called so I assume the whole scope is getting canceled) function inside of the Modifier.draggable() which is used by Modifier.scrollable() which is used by LazyRow which is used by HorizontalPager. Even tough SubsamplingScaleImageView calls requestDisallowInterceptTouchEvent(false) after it detects that it cannot process the gesture (touch slop checks), Compose doesn't restart the awaitPointerEventScope after requestDisallowInterceptTouchEvent(false) is called (since it then waits for all pointers to be up or canceled) so the gesture stops working. I don't know whether it's a bug or is just not handled yet.

### Why Partial?
I don't plan on porting all the features of the original SubsamplingScaleImageView, only the stuff that I personally need. Feel free to fork it and add whatever you need.
