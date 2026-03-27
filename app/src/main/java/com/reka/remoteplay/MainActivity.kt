package com.reka.remoteplay

import android.annotation.SuppressLint
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.reka.remoteplay.app.navigation.AppNavHost
import com.reka.remoteplay.feature.streaming.data.remote.ExternalInputHandler
import com.reka.remoteplay.ui.theme.AppBackgroundDark
import com.reka.remoteplay.ui.theme.RemotePlayTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var externalInputHandler: ExternalInputHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContent {
            RemotePlayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AppBackgroundDark
                ) {
                    val navController = rememberNavController()
                    AppNavHost(navController = navController)
                }
            }
        }
    }

    /**
     * Request pointer capture to hide local Android cursor and get relative mouse movement.
     * This solves the "double cursor" issue and allows raw mouse input.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.requestPointerCapture()
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (externalInputHandler.onGenericMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (externalInputHandler.onMouseButtonEvent(event)) return true
        return super.dispatchTouchEvent(event)
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (externalInputHandler.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
