package com.payghaam.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveActivitiesTest {

    @Test
    fun testLiveUpdatePayloadContract() {
        // Wire contract for Live Activity push payload on Android
        val payload = mapOf(
            PayghaamNotifications.KEY_LIVE_ID to "order_sim_123",
            PayghaamNotifications.KEY_LIVE_EVENT to "update",
            PayghaamNotifications.KEY_LIVE_PROGRESS to "65",
            "title" to "Driver approaching",
            "body" to "Your order is 3 minutes away",
            "ek_url" to "myapp://orders/order_sim_123"
        )

        assertEquals("order_sim_123", payload[PayghaamNotifications.KEY_LIVE_ID])
        assertEquals("update", payload[PayghaamNotifications.KEY_LIVE_EVENT])
        assertEquals(65, payload[PayghaamNotifications.KEY_LIVE_PROGRESS]?.toIntOrNull())
        assertEquals("Driver approaching", payload["title"])
    }

    @Test
    fun testLiveUpdateLifecycleEvents() {
        val events = listOf("start", "update", "end")
        for (event in events) {
            val json = JSONObject()
                .put(PayghaamNotifications.KEY_LIVE_ID, "live_trip_789")
                .put(PayghaamNotifications.KEY_LIVE_EVENT, event)
                .put(PayghaamNotifications.KEY_LIVE_PROGRESS, if (event == "end") 100 else 50)

            assertEquals("live_trip_789", json.getString(PayghaamNotifications.KEY_LIVE_ID))
            assertEquals(event, json.getString(PayghaamNotifications.KEY_LIVE_EVENT))
        }
    }

    @Test
    fun testLiveUpdateSegmentsAndPointsJson() {
        val segmentsJson = JSONArray()
            .put(JSONObject().put("length", 40).put("color", "#10B981"))
            .put(JSONObject().put("length", 60).put("color", "#3B82F6"))
            .toString()

        val pointsJson = JSONArray()
            .put(JSONObject().put("at", 40).put("color", "#FFFFFF"))
            .toString()

        val parsedSegments = JSONArray(segmentsJson)
        assertEquals(2, parsedSegments.length())
        assertEquals(40, parsedSegments.getJSONObject(0).getInt("length"))
        assertEquals("#10B981", parsedSegments.getJSONObject(0).getString("color"))

        val parsedPoints = JSONArray(pointsJson)
        assertEquals(1, parsedPoints.length())
        assertEquals(40, parsedPoints.getJSONObject(0).getInt("at"))
    }
}
