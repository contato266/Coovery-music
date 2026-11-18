package com.maxrave.simpmusic

import androidx.compose.runtime.Composable
import platform.UIKit.UIDevice

actual fun getPlatform(): Platform = Platform.iOS

@Composable
actual fun isTelevision(): Boolean = false