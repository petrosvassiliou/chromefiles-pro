package de.chromefiles.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import dagger.hilt.android.AndroidEntryPoint
import de.chromefiles.app.ui.ChromeFilesScreen
import de.chromefiles.app.ui.theme.ChromeFilesTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var incomingUri by mutableStateOf<Uri?>(null)
    private val _rightClickEvents = MutableSharedFlow<Offset>(extraBufferCapacity = 16)
    val rightClickEvents = _rightClickEvents.asSharedFlow()

    private var lastPointerPosition: Offset = Offset(240f, 240f)
    private var lastRightClickAtMillis: Long = 0L

    private fun emitRightClick(x: Float, y: Float) {
        val now = System.currentTimeMillis()
        if (now - lastRightClickAtMillis < 180L) return
        lastRightClickAtMillis = now
        val pos = if (x > 0f || y > 0f) Offset(x, y) else lastPointerPosition
        lastPointerPosition = pos
        _rightClickEvents.tryEmit(pos)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && intent?.action == Intent.ACTION_VIEW) {
            incomingUri = intent?.data
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window?.decorView?.setOnContextClickListener {
                emitRightClick(lastPointerPosition.x, lastPointerPosition.y)
                true
            }
        }
        setContent {
            ChromeFilesTheme {
                ChromeFilesScreen(
                    incomingUri = incomingUri,
                    onConsumeIncomingUri = { incomingUri = null },
                    rightClickEvents = rightClickEvents
                )
            }
        }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.x > 0f || ev.y > 0f) {
            lastPointerPosition = Offset(ev.x, ev.y)
        }
        val action = ev.actionMasked
        val secondaryMask =
            MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_BACK
        val isActionButtonPress =
            action == MotionEvent.ACTION_BUTTON_PRESS && (ev.actionButton and secondaryMask) != 0
        val isContextClick = action == 16
        val isSecondaryState =
            (ev.buttonState and secondaryMask) != 0 &&
                (action == MotionEvent.ACTION_BUTTON_PRESS || isContextClick)
        if (isActionButtonPress || isContextClick || isSecondaryState) {
            emitRightClick(ev.x, ev.y)
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.x > 0f || ev.y > 0f) {
            lastPointerPosition = Offset(ev.x, ev.y)
        }
        val secondaryMask =
            MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_BACK
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val isSecondaryButton = (ev.buttonState and secondaryMask) != 0
            val isAltClick = (ev.metaState and KeyEvent.META_ALT_ON) != 0
            if (isSecondaryButton || isAltClick) {
                emitRightClick(ev.x, ev.y)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Auf einigen ChromeOS/Android-Geräten sendet die rechte Maustaste KEYCODE_BACK mit SOURCE_MOUSE
        val isMouseBack =
            event.keyCode == KeyEvent.KEYCODE_BACK &&
                ((event.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
                    (event.source and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD)
        if (isMouseBack) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                emitRightClick(lastPointerPosition.x, lastPointerPosition.y)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            incomingUri = intent.data
        }
    }
}

