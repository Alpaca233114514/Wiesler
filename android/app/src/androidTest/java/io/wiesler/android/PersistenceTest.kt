package io.wiesler.android

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import java.security.KeyStore

class PersistenceTest : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = runCatching {
            testHistoryRoundTripWithGapsAndBoundedRetention()
            testEncryptedSecretRestoresAndRejectsDifferentController()
        }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", if (result.isSuccess) "Persistence checks: 2 passed" else "Persistence checks failed: ${result.exceptionOrNull()?.javaClass?.simpleName}") })
    }
    private fun assertTrue(value: Boolean) { check(value) }
    private fun assertFalse(value: Boolean) { check(!value) }
    private fun assertEquals(expected: Any?, actual: Any?) { check(expected == actual) }

    fun testHistoryRoundTripWithGapsAndBoundedRetention() {
        val saved = SavedHistory(List(80) { if (it % 3 == 0) null else Rate(it.toDouble(), 2.0) },
            listOf(Rate(2.0, 3.0), null), 200.0, 50.0,
            List(110) { RequestLog("11-01 15:00:00", "controller", 401, "鉴权失败") }, "11-01 15:00:00", "11-01 15:00:01")
        val prefs = targetContext.getSharedPreferences("persistence-test", 0)
        try {
            assertTrue(prefs.edit().putString("history", saved.encode()).commit())
            val restored = SavedHistory.decode(prefs.getString("history", "")!!)
            assertEquals(saved.trend.takeLast(60), restored.trend)
            assertEquals(saved.forwardTrend, restored.forwardTrend)
            assertEquals(saved.logs.take(100), restored.logs)
            assertEquals(saved.rx, restored.rx)
            assertEquals(saved.sampledAt, restored.sampledAt)
        } finally { targetContext.deleteSharedPreferences("persistence-test") }
    }

    fun testEncryptedSecretRestoresAndRejectsDifferentController() {
        val prefs = targetContext.getSharedPreferences("secret-test", 0)
        val alias = "wiesler.persistence-test"
        val address = "http://127.0.0.1:9090"
        val testSecret = java.util.UUID.randomUUID().toString()
        try {
            assertTrue(ControllerSecretStore(prefs, alias).save(address, testSecret, true))
            assertFalse(prefs.getString("controllerSecret", "")!!.contains(testSecret))
            assertEquals(testSecret, ControllerSecretStore(prefs, alias).read(address))
            assertTrue(prefs.getBoolean("controllerEnabled", false))
            assertTrue(runCatching { ControllerSecretStore(prefs, alias).read("http://127.0.0.1:9091") }.isFailure)
            assertTrue(ControllerSecretStore(prefs, alias).save(address, "", false))
            assertEquals("", ControllerSecretStore(prefs, alias).read(address))
            assertFalse(prefs.getBoolean("controllerEnabled", true))
        } finally {
            targetContext.deleteSharedPreferences("secret-test")
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
