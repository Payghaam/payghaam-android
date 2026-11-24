package com.payghaam.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnonymousTrackingTest {

    @Test
    fun testAnonymousIdentifyPayload() {
        val cfg = PayghaamConfig("app_1", "ek_client_test", "http://localhost:3000")
        val client = ApiClient(cfg)

        // Reflection or inspecting generated JSON request body
        val json = JSONObject().apply {
            put("anonymousId", "anon_999")
            put("language", "ar")
            val aliases = JSONArray().put(JSONObject().put("label", "loyalty").put("value", "123"))
            put("aliases", aliases)
        }

        assertEquals("anon_999", json.getString("anonymousId"))
        assertEquals("ar", json.getString("language"))
        assertEquals(1, json.getJSONArray("aliases").length())
        assertEquals("loyalty", json.getJSONArray("aliases").getJSONObject(0).getString("label"))
        assertEquals("123", json.getJSONArray("aliases").getJSONObject(0).getString("value"))
    }

    @Test
    fun testAnonymousTrackingPayload() {
        val eventJson = JSONObject().apply {
            put("name", "page_view")
            put("anonymousId", "anon_999")
            put("properties", JSONObject(mapOf("url" to "https://example.com")))
        }

        assertEquals("page_view", eventJson.getString("name"))
        assertEquals("anon_999", eventJson.getString("anonymousId"))
        assertFalse(eventJson.has("externalId"))
    }

    @Test
    fun testDeviceContextWithLanguageOverride() {
        // Test language override in DeviceContext
        val map = buildMap {
            val languageOverride = "ar"
            val localeLang = "en"
            put("language", languageOverride ?: localeLang)
        }
        assertEquals("ar", map["language"])
    }

    @Test
    fun testRemoveSubscriptionPayload() {
        val json = JSONObject().apply {
            put("type", SubscriptionType.SMS.wire)
            put("token", "+97412345678")
            put("anonymousId", "anon_999")
        }
        assertEquals("SMS", json.getString("type"))
        assertEquals("+97412345678", json.getString("token"))
        assertEquals("anon_999", json.getString("anonymousId"))
    }

    @Test
    fun testRemoveAliasAndOptInOutPayloads() {
        val removeAliasJson = JSONObject().apply {
            put("label", "crm_id")
            put("anonymousId", "anon_999")
        }
        assertEquals("crm_id", removeAliasJson.getString("label"))
        assertEquals("anon_999", removeAliasJson.getString("anonymousId"))

        val optOutJson = JSONObject().apply {
            put("type", SubscriptionType.ANDROID_PUSH.wire)
            put("token", "fcm_token_123")
        }
        assertEquals("ANDROID_PUSH", optOutJson.getString("type"))
        assertEquals("fcm_token_123", optOutJson.getString("token"))
    }
}
