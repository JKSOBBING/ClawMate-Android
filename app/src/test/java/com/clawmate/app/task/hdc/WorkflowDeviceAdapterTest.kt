package com.clawmate.app.task.hdc

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for [WorkflowDeviceAdapter] behavior: the default
 * disabled adapter always reports Unsupported/not-configured, and the
 * bridge-backed adapter maps only the safe protocol calls and reflects health
 * honestly (never claims connected before a live ok reply).
 */
class WorkflowDeviceAdapterTest {

    // ------------------------------------------------------------- fake utils

    private fun client(
        health: HdcBridgeResult = HdcBridgeResult.Error(HdcBridgeErrorKind.IO, "no health"),
        workflow: (String, Map<String, Any?>) -> HdcBridgeResult = { _, _ ->
            HdcBridgeResult.Error(HdcBridgeErrorKind.IO, "no workflow")
        },
    ): FakeHdcBridgeClient = FakeHdcBridgeClient(health, workflow)

    private class FakeHdcBridgeClient(
        var healthResult: HdcBridgeResult,
        var workflowFn: (String, Map<String, Any?>) -> HdcBridgeResult,
    ) : HdcBridgeClient {
        val workflowActions = mutableListOf<String>()
        override suspend fun health(): HdcBridgeResult = healthResult
        override suspend fun workflow(action: String, payload: Map<String, Any?>): HdcBridgeResult {
            workflowActions += action
            return workflowFn(action, payload)
        }
    }

    private fun okHealth(): HdcBridgeHealth = HdcBridgeHealth(
        status = "ok", hdcConnected = true, target = "192.168.1.30:5555",
        tunnelReady = true, fportReady = true, rportReady = true,
    )

    // -------------------------------------------------------------- disabled

    @Test
    fun `disabled adapter never configured and only unsupported`() = runBlocking {
        val a: WorkflowDeviceAdapter = DisabledWorkflowDeviceAdapter
        assertFalse(a.isConfigured)
        assertTrue(a.health() is HdcActionResult.Unsupported)
        assertTrue(a.startApp("x", "com.x") is HdcActionResult.Unsupported)
        assertTrue(a.screenshot(0.5) is HdcActionResult.Unsupported)
        assertTrue(a.guiAction(mapOf("action" to "click")) is HdcActionResult.Unsupported)
        assertTrue(a.executeDeciderAction("r", 100, 200) is HdcActionResult.Unsupported)
    }

    // ------------------------------------------------------- bridge configured

    @Test
    fun `adapter becomes configured only after a live ok control-ready health`() = runBlocking {
        val fake = client(health = HdcBridgeResult.Health(okHealth()))
        val adapter = BridgeBackedWorkflowDeviceAdapter(fake)
        assertFalse(adapter.isConfigured)
        val result = adapter.health()
        assertTrue(result is HdcActionResult.Success)
        assertTrue(adapter.isConfigured)
    }

    @Test
    fun `health ok but not control ready is a honest bridge error not configured`() = runBlocking {
        val notReady = HdcBridgeHealth(status = "ok", hdcConnected = true, fportReady = true, rportReady = false)
        val fake = client(health = HdcBridgeResult.Health(notReady))
        val adapter = BridgeBackedWorkflowDeviceAdapter(fake)
        val result = adapter.health()
        assertTrue(result is HdcActionResult.BridgeError)
        assertFalse(adapter.isConfigured)
    }

    @Test
    fun `health error envelope stays not configured`() = runBlocking {
        val err = HdcBridgeHealth(status = "error", message = "hdc not connected")
        val fake = client(health = HdcBridgeResult.Health(err))
        val adapter = BridgeBackedWorkflowDeviceAdapter(fake)
        assertTrue(adapter.health() is HdcActionResult.BridgeError)
        assertFalse(adapter.isConfigured)
    }

    @Test
    fun `health transport error maps to typed transport error`() = runBlocking {
        val fake = client(health = HdcBridgeResult.Error(HdcBridgeErrorKind.TIMEOUT, "timed out"))
        val adapter = BridgeBackedWorkflowDeviceAdapter(fake)
        val result = adapter.health() as HdcActionResult.TransportError
        assertEquals(HdcBridgeErrorKind.TIMEOUT, result.kind)
        assertFalse(adapter.isConfigured)
    }

    // ------------------------------------------------------- safe action maps

    @Test
    fun `app_start maps to workflow app_start and parses success`() = runBlocking {
        val expected = HdcBridgeResponse(status = "ok", message = "app_start com.x", packageName = "com.x")
        val fake = client(workflow = { _, _ -> HdcBridgeResult.Response(expected) })
        val adapter = BridgeBackedWorkflowDeviceAdapter(fake)
        val result = adapter.startApp("x", "com.x", resetFirst = true)
        assertTrue(result is HdcActionResult.Success)
        assertEquals(listOf("app_start"), fake.workflowActions)
    }

    @Test
    fun `screenshot sends factor and style payload`() = runBlocking {
        val fake = client(workflow = { _, p ->
            assertEquals(0.25, p["factor"])
            assertEquals("local", p["style"])
            HdcBridgeResult.Response(HdcBridgeResponse(status = "ok", width = 480, height = 540))
        })
        BridgeBackedWorkflowDeviceAdapter(fake).screenshot(0.25)
        assertEquals(listOf("screenshot"), fake.workflowActions)
    }

    @Test
    fun `gui_action rounds to workflow gui_action only`() = runBlocking {
        val fake = client(workflow = { _, p ->
            assertEquals("click", p["action"])
            HdcBridgeResult.Response(HdcBridgeResponse(status = "ok"))
        })
        val result = BridgeBackedWorkflowDeviceAdapter(fake).guiAction(mapOf("action" to "click", "x" to 10, "y" to 20))
        assertTrue(result is HdcActionResult.Success)
        assertEquals(listOf("gui_action"), fake.workflowActions)
    }

    @Test
    fun `execute_decider_action maps to workflow action`() = runBlocking {
        val fake = client(workflow = { action, p ->
            assertEquals("execute_decider_action", action)
            assertEquals(100, p["width"])
            assertEquals(200, p["height"])
            HdcBridgeResult.Response(HdcBridgeResponse(status = "ok", action = "click_input"))
        })
        val result = BridgeBackedWorkflowDeviceAdapter(fake).executeDeciderAction("response-text", 100, 200)
        assertTrue(result is HdcActionResult.Success)
        assertEquals(listOf("execute_decider_action"), fake.workflowActions)
    }

    @Test
    fun `bridge error envelope surfaces honestly not as success`() = runBlocking {
        val fake = client(workflow = { _, _ ->
            HdcBridgeResult.Response(HdcBridgeResponse(status = "error", message = "app_identity_mismatch"))
        })
        val result = BridgeBackedWorkflowDeviceAdapter(fake).startApp("x", "com.x")
        assertTrue(result is HdcActionResult.BridgeError)
    }

    @Test
    fun `adapter never sends run_cmd or arbitrary command strings`() = runBlocking {
        val fake = client(workflow = { action, _ ->
            HdcBridgeResult.Response(HdcBridgeResponse(status = "ok"))
        })
        val adapter = BridgeBackedWorkflowDeviceAdapter(fake)
        adapter.startApp("x", "com.x")
        adapter.screenshot(0.5)
        adapter.guiAction(mapOf("action" to "swipe"))
        adapter.executeDeciderAction("r", 1, 1)
        assertFalse(fake.workflowActions.any { it == "run_cmd" })
        fake.workflowActions.forEach { assertTrue(it in ALLOWED_ACTIONS) }
    }

    private val ALLOWED_ACTIONS =
        setOf("app_start", "screenshot", "gui_action", "execute_decider_action", "health")
}
