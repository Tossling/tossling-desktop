package com.kopylovis.tossling.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkChangeTest {

    @Test
    fun aChangedSetOfAddressesMeansConnectingAgain() {
        val home = setOf("wlan0 192.168.1.20")
        assertFalse(Room.networkChanged(before = home, after = setOf("wlan0 192.168.1.20")))
        assertTrue(Room.networkChanged(before = home, after = setOf("wlan0 10.0.0.7")))
        assertTrue(Room.networkChanged(before = home, after = home + "tun0 10.8.0.2"))
        assertTrue(Room.networkChanged(before = home, after = emptySet()))
        assertTrue(Room.networkChanged(before = emptySet(), after = home))
    }

    @Test
    fun anUnknownListChangesNothing() {
        assertFalse(Room.networkChanged(before = null, after = setOf("wlan0 192.168.1.20")))
        assertFalse(Room.networkChanged(before = setOf("wlan0 192.168.1.20"), after = null))
    }

    @Test
    fun loopbackAndLinkLocalAddressesAreLeftOut() {
        val addresses = Room.networkAddresses() ?: return
        assertTrue(addresses.none { it.endsWith(" 127.0.0.1") || it.contains(" 0:0:0:0:0:0:0:1") || it.contains(" fe80:") })
    }
}
