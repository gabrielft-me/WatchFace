package com.example.myapplication.watchface

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Configuration activity for the Timeline Watch Face.
 * Required by Wear OS to display the watch face in the picker.
 */
class WatchFaceConfigActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // This activity is required but doesn't need UI
        // Just finish immediately - no configuration needed
        Toast.makeText(this, "Timeline Watch Face activated!", Toast.LENGTH_SHORT).show()
        finish()
    }
}
