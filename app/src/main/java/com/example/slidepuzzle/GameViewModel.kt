package com.example.slidepuzzle

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel

/** Holds the decoded master photo across configuration changes. */
class GameViewModel : ViewModel() {
    var masterBitmap: Bitmap? = null
}
