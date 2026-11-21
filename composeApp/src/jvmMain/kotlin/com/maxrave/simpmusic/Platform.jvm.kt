package com.maxrave.simpmusic

import androidx.compose.runtime.Composable

actual fun getPlatform(): Platform = Platform.Desktop

@Composable
actual fun isTelevision(): Boolean = false