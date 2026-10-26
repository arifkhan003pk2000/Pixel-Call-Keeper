package com.pixelcallkeeper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

// Ye service screen ka koi data nahi padhti. Sirf Android ko batati hai ke
// ye app accessibility app hai, taake call ke dauran mic recording chal sake.
class PckAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var connected = false
    }

    override fun onServiceConnected() {
        connected = true
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }
}
