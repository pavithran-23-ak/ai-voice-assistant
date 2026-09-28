package com.example.myaiassistant

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import java.net.URLEncoder

// --- 1. Notification Interceptor ---
class MyNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        val extras = sbn?.notification?.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return

        val pkg = sbn.packageName
        val app = when (pkg) {
            "com.whatsapp" -> "WhatsApp"
            "com.google.android.gm" -> "Gmail"
            else -> pkg
        }

        scope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            db.notificationDao().insertNotification(
                NotificationEntity(
                    packageName = pkg,
                    sourceApp = app,
                    sender = title,
                    body = text,
                    timestamp = sbn.postTime
                )
            )
        }
    }
}

// --- 2. WhatsApp Auto-Clicker ---
class WhatsAppAutomationService : AccessibilityService() {
    companion object {
        @Volatile var isAutoSendPending: Boolean = false

        fun triggerWhatsApp(context: Context, phone: String, message: String) {
            isAutoSendPending = true
            try {
                val clean = phone.replace("+", "").replace(" ", "").trim()
                val enc = URLEncoder.encode(message, "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$clean&text=$enc")).apply {
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                isAutoSendPending = false
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isAutoSendPending || event == null) return
        val rootNode = rootInActiveWindow ?: return

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (isAutoSendPending) {
                    val clicked = findSendNode(rootNode)
                    if (clicked) isAutoSendPending = false
                }
            } finally {
                rootNode.recycle()
            }
        }, 400)
    }

    private fun findSendNode(root: AccessibilityNodeInfo): Boolean {
        val byId = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
        for (node in byId) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        val byText = root.findAccessibilityNodeInfosByText("Send")
        for (node in byText) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    override fun onInterrupt() {
        isAutoSendPending = false
    }
}
