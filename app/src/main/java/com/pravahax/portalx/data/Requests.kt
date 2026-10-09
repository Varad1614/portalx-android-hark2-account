package com.pravahax.portalx.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/**
 * v0.7: typed request bodies for the writes the outbox can queue. A misspelt field is now a compile error,
 * not a 400 from the gateway hours later when the queued write is finally sent.
 * Ids stay [JsonPrimitive] because the server uses both numeric and string ids (see idOf()).
 */
@Serializable data class ApplyLeaveRequest(
    val leaveTypeId: JsonPrimitive, val startDate: String, val endDate: String, val halfDay: Boolean, val reason: String,
)
@Serializable data class TaskStatusRequest(val taskId: JsonPrimitive, val status: String)
@Serializable data class TaskCommentRequest(val taskId: JsonPrimitive, val body: String)

private val requestJson = Json { encodeDefaults = true }
fun ApplyLeaveRequest.toJson(): JsonElement = requestJson.encodeToJsonElement(this)
fun TaskStatusRequest.toJson(): JsonElement = requestJson.encodeToJsonElement(this)
fun TaskCommentRequest.toJson(): JsonElement = requestJson.encodeToJsonElement(this)
