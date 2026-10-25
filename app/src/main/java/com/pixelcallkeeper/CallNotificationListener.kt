package com.pixelcallkeeper

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class CallNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile var app = ""
        @Volatile var name = ""
        @Volatile var time = 0L
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn)
    }

    override fun onListenerConnected() {
        try { activeNotifications?.forEach { handle(it) } } catch (_: Exception) {}
    }

    private fun handle(sbn: StatusBarNotification) {
        val label = when (sbn.packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            "com.instagram.android" -> "Instagram"
            else -> return
        }
        val n = sbn.notification ?: return
        if (n.category != Notification.CATEGORY_CALL) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val generic = Regex("call|calling|ringing|video|voice|ongoing|incoming", RegexOption.IGNORE_CASE)
        val nm = listOf(title, text).firstOrNull { it.isNotBlank() && !generic.containsMatchIn(it) } ?: ""
        app = label
        if (nm.isNotBlank()) name = nm
        time = System.currentTimeMillis()
    }
}
