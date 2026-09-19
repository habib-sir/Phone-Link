package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "call_logs")
data class CallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val isVideo: Boolean,
    val direction: String, // "INCOMING" or "OUTGOING"
    val durationSeconds: Int,
    val status: String, // "ANSWERED", "MISSED", "DECLINED"
    val timestamp: Long = System.currentTimeMillis()
)
