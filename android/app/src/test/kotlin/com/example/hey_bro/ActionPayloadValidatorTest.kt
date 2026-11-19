package com.example.hey_bro

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPayloadValidatorTest {
    @Test
    fun validatesActionEnvelopeAndRejectsCoercedBooleans() {
        assertTrue(
            ActionPayloadValidator.isValidActionEnvelope(
                JSONObject("""{"type":"ACTION","action":"toggleAirplaneMode","params":{"enabled":false}}""")
            )
        )
        assertFalse(
            ActionPayloadValidator.isValidActionEnvelope(
                JSONObject("""{"type":"ACTION","action":"toggleAirplaneMode","params":{"enabled":"false"}}""")
            )
        )
        assertFalse(
            ActionPayloadValidator.isValidActionEnvelope(
                JSONObject("""{"type":"ACTION","action":"makeCall","params":{"number":123}}""")
            )
        )
    }

    @Test
    fun requiresConfirmationForSensitiveActionsAndSensitiveCompoundSteps() {
        val sensitiveActions = listOf(
            "makeCall", "sendWhatsApp", "sendSms", "toggleAirplaneMode", "lockScreen"
        )
        sensitiveActions.forEach { action ->
            assertTrue(
                "$action should require confirmation",
                ActionPayloadValidator.requiresVoiceConfirmation(JSONObject().put("action", action))
            )
        }

        assertTrue(
            ActionPayloadValidator.requiresVoiceConfirmation(
                JSONObject().put("type", "MULTI_ACTION").put(
                    "actions",
                    JSONArray().put(JSONObject().put("action", "controlHardware").put(
                        "params",
                        JSONObject().put("device", "airplane")
                    ))
                )
            )
        )
        assertTrue(
            ActionPayloadValidator.requiresVoiceConfirmation(
                JSONObject().put("action", "makeCall").put("voiceConfirmed", true)
            )
        )
    }

    @Test
    fun validatesAllMultiActionStepsAndEnforcesSixteenStepLimit() {
        val steps = JSONArray()
        repeat(ActionPayloadValidator.MAX_MULTI_ACTION_STEPS) {
            steps.put(JSONObject().put("action", "globalBack"))
        }
        val valid = JSONObject().put("type", "MULTI_ACTION").put("actions", steps)
        assertTrue(ActionPayloadValidator.isValidMultiAction(valid))

        steps.put(JSONObject().put("action", "globalBack"))
        assertFalse(ActionPayloadValidator.isValidMultiAction(valid))

        val malformedLateStep = JSONObject().put("type", "MULTI_ACTION").put(
            "actions",
            JSONArray()
                .put(JSONObject().put("action", "globalBack"))
                .put(JSONObject().put("action", "openApp").put("params", JSONObject()))
        )
        assertFalse(ActionPayloadValidator.isValidMultiAction(malformedLateStep))
    }
}
