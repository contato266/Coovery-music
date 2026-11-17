package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable

/** Keeps the display awake while [enabled]. Used on Android TV so the screensaver does not close playback. */
@Composable
internal expect fun KeepScreenOn(enabled: Boolean)
