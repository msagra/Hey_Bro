package com.example.hey_bro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalCommandProcessorTest {
    @Test
    fun explicitFlashlightCommandsUseDeterministicFastPath() {
        assertEquals(
            "flashlightOn",
            LocalCommandProcessor.checkFastPath("turn on flashlight")?.action?.optString("action")
        )
        assertEquals(
            "flashlightOff",
            LocalCommandProcessor.checkFastPath("turn off flashlight")?.action?.optString("action")
        )
    }

    @Test
    fun questionsAndNegatedCommandsDoNotTriggerFastPathActions() {
        assertNull(LocalCommandProcessor.checkFastPath("what is a flashlight"))
        assertNull(LocalCommandProcessor.checkFastPath("don't turn on flashlight"))
        assertNull(LocalCommandProcessor.checkFastPath("do not turn on flashlight"))
    }

    @Test
    fun partialFastPathWaitsForCompletePhrasesAndRequiredArguments() {
        assertNull(LocalCommandProcessor.checkFastPath("open w", partial = true))
        assertNull(LocalCommandProcessor.checkFastPath("set timer"))

        assertEquals(
            "flashlightOn",
            LocalCommandProcessor.checkFastPath("turn on flashlight", partial = true)
                ?.action?.optString("action")
        )
        assertEquals(
            300,
            LocalCommandProcessor.checkFastPath("set timer for 5 minutes")
                ?.action?.optInt("seconds")
        )
    }

    @Test
    fun localHardwareFastPathUsesTypedBooleanParameters() {
        val result = LocalCommandProcessor.checkFastPath("turn on wifi")
        assertEquals("toggleWifi", result?.action?.optString("action"))
        assertEquals(true, result?.action?.opt("enabled"))
    }
}
