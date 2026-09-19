package com.example.sms

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log

object SmsManagerHelper {
    private const val TAG = "SmsManagerHelper"

    fun sendSms(
        context: Context,
        recipient: String,
        message: String,
        onResult: (success: Boolean, errorMsg: String?) -> Unit
    ) {
        try {
            val cleanNumber = recipient.trim()
            if (cleanNumber.isEmpty() || message.trim().isEmpty()) {
                onResult(false, "Phone number or message body is empty")
                return
            }

            @Suppress("DEPRECATION")
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
            } else {
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(cleanNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(cleanNumber, null, message, null, null)
            }

            Log.d(TAG, "SMS dispatched to $cleanNumber via native SmsManager")
            onResult(true, null)
        } catch (e: SecurityException) {
            Log.e(TAG, "SEND_SMS permission not granted: ${e.message}")
            onResult(false, "Permission SEND_SMS denied: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS: ${e.message}")
            onResult(false, e.localizedMessage ?: "Failed to dispatch SMS")
        }
    }
}
