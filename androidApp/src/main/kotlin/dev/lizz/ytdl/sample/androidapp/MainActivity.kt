package dev.lizz.ytdl.sample.androidapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.lizz.ytdl.sample.compose.SampleApp
import dev.lizz.ytdl.sample.compose.createAndroidSampleDownloader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SampleApp(downloader = createAndroidSampleDownloader(this))
        }
    }
}
