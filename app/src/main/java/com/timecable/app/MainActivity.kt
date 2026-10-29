package com.timecable.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    private lateinit var box: CableBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        box = CableBox(applicationContext)
        box.start()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (box.mode == Mode.GUIDE) box.mode = Mode.WATCH else finish()
            }
        })
        setContent { CableUi(box) }
    }

    override fun onStart() {
        super.onStart()
        box.resume()
    }

    override fun onStop() {
        super.onStop()
        box.pause()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && box.onKey(e.keyCode)) return true
        return super.dispatchKeyEvent(e)
    }

    override fun onDestroy() {
        super.onDestroy()
        box.release()
    }
}
