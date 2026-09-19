package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sms_logs")
data class SmsLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val commandId: String,
    val phoneNumber: String,
    val messageBody: String,
    val direction: String, // "SENT_VIA_LAPTOP", "RECEIVED_ON_PHONE"
    val status: String, // "SUCCESS", "FAILED", "PENDING"
    val timestamp: Long = System.currentTimeMillis()
)
