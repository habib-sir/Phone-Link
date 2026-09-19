package com.example.data.model

import org.json.JSONObject

/**
 * Data classes representing signaling and communication messages between Phone and Laptop
 */
data class SignalingMessage(
    val type: String,
    val role: String? = null,
    val pairCode: String? = null,
    val peerRole: String? = null,
    val status: String? = null,
    val peerOnline: Boolean = false,
    val isVideo: Boolean = false,
    val sdp: String? = null,
    val candidate: String? = null,
    val reason: String? = null,
    val id: String? = null,
    val to: String? = null,
    val from: String? = null,
    val body: String? = null,
    val text: String? = null,
    val sender: String? = null,
    val success: Boolean = false,
    val message: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("type", type)
        role?.let { json.put("role", it) }
        pairCode?.let { json.put("pairCode", it) }
        peerRole?.let { json.put("peerRole", it) }
        status?.let { json.put("status", it) }
        if (type == "join-success") json.put("peerOnline", peerOnline)
        if (isVideo) json.put("isVideo", true)
        sdp?.let { json.put("sdp", it) }
        candidate?.let { json.put("candidate", it) }
        reason?.let { json.put("reason", it) }
        id?.let { json.put("id", it) }
        to?.let { json.put("to", it) }
        from?.let { json.put("from", it) }
        body?.let { json.put("body", it) }
        text?.let { json.put("text", it) }
        sender?.let { json.put("sender", it) }
        if (type == "sms-status") json.put("success", success)
        message?.let { json.put("message", it) }
        json.put("timestamp", timestamp)
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): SignalingMessage? {
            return try {
                val json = JSONObject(jsonStr)
                SignalingMessage(
                    type = json.optString("type", "unknown"),
                    role = json.optString("role", null),
                    pairCode = json.optString("pairCode", null),
                    peerRole = json.optString("peerRole", null),
                    status = json.optString("status", null),
                    peerOnline = json.optBoolean("peerOnline", false),
                    isVideo = json.optBoolean("isVideo", false),
                    sdp = if (json.has("sdp")) json.opt("sdp").toString() else null,
                    candidate = if (json.has("candidate")) json.opt("candidate").toString() else null,
                    reason = json.optString("reason", null),
                    id = json.optString("id", null),
                    to = json.optString("to", null),
                    from = json.optString("from", null),
                    body = json.optString("body", null),
                    text = json.optString("text", null),
                    sender = json.optString("sender", null),
                    success = json.optBoolean("success", false),
                    message = json.optString("message", null),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis())
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
