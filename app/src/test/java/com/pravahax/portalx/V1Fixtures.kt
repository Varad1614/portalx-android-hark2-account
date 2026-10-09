package com.pravahax.portalx

import com.pravahax.portalx.net.Fn
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/** Maps a recorded v1 request back to its route (templates like tasks/{taskId} match any segment). */
fun fnFor(req: RecordedRequest): Fn? {
    val path = req.path!!.substringAfter("/api/v1/").substringBefore("?")
    return Fn.entries.firstOrNull { fn ->
        fn.m.name == req.method && Regex("^" + fn.path.substringBefore("?").replace(Regex("\\{\\w+\\}"), "[^/]+") + "$").matches(path)
    }
}

/** v1 success envelope `{success:true,data}` exactly as router.ts jsonSuccess() writes it. */
fun v1(fn: Fn?, data: JsonElement): MockResponse {
    val payload = data
    return MockResponse().setHeader("Content-Type", "application/json")
        .setBody(buildJsonObject { put("success", true); put("data", payload) }.toString())
}

/** The gateway's 404 for an unmatched /api/v1 route (router.ts, last line of handleMobileApiRequest). */
fun v1NotFound(req: RecordedRequest): MockResponse = MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json")
    .setBody("""{"success":false,"error":{"code":"NOT_FOUND","message":"API endpoint '${req.method} ${req.path?.substringBefore("?")}' not found."}}""")

/** The services' answer when a permission is missing (thrown Error → gateway 500 INTERNAL_ERROR with the message). */
fun v1Forbidden(): MockResponse = MockResponse().setResponseCode(500).setHeader("Content-Type", "application/json")
    .setBody("""{"success":false,"error":{"code":"INTERNAL_ERROR","message":"You do not have permission to perform this action."}}""")
