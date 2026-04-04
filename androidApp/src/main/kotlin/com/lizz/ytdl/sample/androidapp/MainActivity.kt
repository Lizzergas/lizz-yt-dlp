package com.lizz.ytdl.sample.androidapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.lizz.ytdl.sample.compose.SampleApp
import com.lizz.ytdl.sample.compose.createAndroidSampleDownloader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SampleApp(downloader = createAndroidSampleDownloader(this))
        }
    }
}
