package com.lizz.ytdl.iosapp

import androidx.compose.ui.window.ComposeUIViewController
import com.lizz.ytdl.sample.compose.SampleApp
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    SampleApp()
}
