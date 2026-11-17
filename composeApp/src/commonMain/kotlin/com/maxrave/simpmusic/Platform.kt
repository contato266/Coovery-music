package com.maxrave.simpmusic

import androidx.compose.runtime.Composable

sealed class Platform {
    object Android : Platform()
    object iOS : Platform()
    object Desktop : Platform()

    fun osName(): String = when (this) {
        Android -> "android"
        iOS -> "iOS"
        Desktop -> System.getProperty("os.name") ?: "jvm"
    }
}

expect fun getPlatform(): Platform

/** True only on an Android TV / Leanback device. Phones and desktop stay false. */
@Composable
expect fun isTelevision(): Boolean