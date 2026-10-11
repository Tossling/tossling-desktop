package com.kopylovis.tossling.desktop

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class OwnTokenTest {

    private val settings = Settings(server = "https://s", token = "tk_room", room = "tossling-r", key = "k", deviceId = "d1", own = "tk_own")

    @Test
    fun requestsGoWithTheComputersOwnToken() {
        assertEquals("tk_own", settings.endpoint.token)
        assertEquals("tk_room", settings.roomEndpoint.token)
        assertEquals("tk_room", settings.copy(own = "").endpoint.token)
    }

    @Test
    fun theOwnTokenSurvivesASaveAndARotation() {
        val json = Json { ignoreUnknownKeys = true }
        val back = json.decodeFromString(Settings.serializer(), json.encodeToString(Settings.serializer(), settings.copy(token = "tk_next")))
        assertEquals("tk_own", back.own)
        assertEquals("tk_next", back.token)
    }
}
