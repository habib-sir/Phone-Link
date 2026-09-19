package com.example.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.model.SignalingMessage
import com.example.network.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class IncomingSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            val sender = messages[0].displayOriginatingAddress ?: "Unknown"
            val bodyBuilder = StringBuilder()
            for (sms in messages) {
                bodyBuilder.append(sms.displayMessageBody)
            }
            val body = bodyBuilder.toString()
            val timestamp = System.currentTimeMillis()

            Log.d(TAG, "Intercepted incoming SMS from $sender: $body")

            // Relay to laptop via WebSocket
            val signaling = SignalingClient.getInstance()
            signaling.sendMessage(
                SignalingMessage(
                    type = "sms-received",
                    from = sender,
                    body = body,
                    timestamp = timestamp
                )
            )

            // Save to local Room database
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getInstance(context)
                    db.phoneLinkDao().insertSmsLog(
                        com.example.data.local.SmsLogEntity(
                            commandId = "rcv_${System.currentTimeMillis()}",
                            phoneNumber = sender,
                            messageBody = body,
                            direction = "RECEIVED_ON_PHONE",
                            status = "RECEIVED",
                            timestamp = timestamp
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving received SMS: ${e.message}")
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        private const val TAG = "IncomingSmsReceiver"
    }
}
