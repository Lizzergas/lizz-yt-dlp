package dev.lizz.ytdl.iosapp

import androidx.compose.ui.window.ComposeUIViewController
import dev.lizz.ytdl.sample.compose.SampleApp
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    SampleApp()
}
