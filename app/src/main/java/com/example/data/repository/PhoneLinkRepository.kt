package com.example.data.repository

import com.example.data.local.CallLogEntity
import com.example.data.local.ChatMessageEntity
import com.example.data.local.PhoneLinkDao
import com.example.data.local.SmsLogEntity
import kotlinx.coroutines.flow.Flow

class PhoneLinkRepository(private val dao: PhoneLinkDao) {
    val callLogs: Flow<List<CallLogEntity>> = dao.getAllCallLogs()
    val smsLogs: Flow<List<SmsLogEntity>> = dao.getAllSmsLogs()
    val chatMessages: Flow<List<ChatMessageEntity>> = dao.getAllChatMessages()

    suspend fun addCallLog(isVideo: Boolean, direction: String, durationSeconds: Int, status: String) {
        dao.insertCallLog(
            CallLogEntity(
                isVideo = isVideo,
                direction = direction,
                durationSeconds = durationSeconds,
                status = status
            )
        )
    }

    suspend fun addSmsLog(commandId: String, phoneNumber: String, body: String, direction: String, status: String) {
        dao.insertSmsLog(
            SmsLogEntity(
                commandId = commandId,
                phoneNumber = phoneNumber,
                messageBody = body,
                direction = direction,
                status = status
            )
        )
    }

    suspend fun updateSmsStatus(commandId: String, status: String) {
        dao.updateSmsStatus(commandId, status)
    }

    suspend fun addChatMessage(text: String, sender: String) {
        dao.insertChatMessage(
            ChatMessageEntity(
                text = text,
                sender = sender
            )
        )
    }

    suspend fun clearHistory() {
        dao.clearCallLogs()
        dao.clearSmsLogs()
        dao.clearChatMessages()
    }
}
