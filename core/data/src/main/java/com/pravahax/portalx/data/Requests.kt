package com.pravahax.portalx.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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

/**
 * v0.7.1: tasks POST. work-api needs the acting user and an initial status; the gateway forwards both.
 * An empty description is left out entirely (as before), while the nullable ids are sent as explicit nulls.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class CreateTaskRequest(
    val title: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val description: String? = null,
    val priority: String,
    val dueDate: String?,
    val projectId: JsonPrimitive?,
    val assigneeId: JsonPrimitive?,
    val actorUserId: Long?,
    val status: String = "todo",
)
/** leave/review and attendance/corrections/review: approve or reject one request. */
@Serializable data class DecisionRequest(val id: JsonPrimitive, val decision: String)

private val requestJson = Json { encodeDefaults = true }
fun ApplyLeaveRequest.toJson(): JsonElement = requestJson.encodeToJsonElement(this)
fun TaskStatusRequest.toJson(): JsonElement = requestJson.encodeToJsonElement(this)
fun TaskCommentRequest.toJson(): JsonElement = requestJson.encodeToJsonElement(this)
fun CreateTaskRequest.toJson(): JsonElement = requestJson.encodeToJsonElement(this)
fun DecisionRequest.toJson(): JsonElement = requestJson.encodeToJsonElement(this)

/**
 * v0.9: the on-device liveness result sent with a check-in/out selfie. [passed] false means the check couldn't run.
 * v0.9.1: sent as the `X-PortalX-Liveness` header (`passed; challenges=blink,turn` | `unavailable`), never as form
 * fields, so a gateway that validates the multipart body strictly can't reject the punch because of it.
 */
data class LivenessResult(val passed: Boolean, val challenges: List<String> = emptyList()) {
    fun header(): Pair<String, String> = HEADER to (if (passed) "passed" else "unavailable") +
        (if (challenges.isNotEmpty()) "; challenges=" + challenges.joinToString(",") else "")
    companion object { const val HEADER = "X-PortalX-Liveness" }
}
