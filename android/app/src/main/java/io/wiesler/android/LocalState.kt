package io.wiesler.android

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedHistory(
    val trend: List<Rate?> = emptyList(), val forwardTrend: List<Rate?> = emptyList(),
    val rx: Double = 0.0, val tx: Double = 0.0,
    val logs: List<RequestLog> = emptyList(),
    val sampledAt: String = "尚未采样", val forwardSampledAt: String = "尚未采样"
) {
    fun encode(): String {
        fun samples(values: List<Rate?>) = JSONArray().apply {
            values.takeLast(60).forEach { add -> put(add?.let { JSONArray().put(it.rx).put(it.tx) } ?: JSONObject.NULL) }
        }
        return JSONObject().put("trend", samples(trend)).put("forwardTrend", samples(forwardTrend))
            .put("rx", rx).put("tx", tx).put("sampledAt", sampledAt).put("forwardSampledAt", forwardSampledAt)
            .put("logs", JSONArray().apply {
                logs.take(100).forEach { put(JSONObject().put("time", it.time).put("source", it.source)
                    .put("status", it.status ?: JSONObject.NULL).put("error", it.error ?: JSONObject.NULL)) }
            }).toString()
    }

    companion object {
        fun decode(text: String): SavedHistory {
            val json = JSONObject(text)
            fun samples(key: String): List<Rate?> {
                val array = json.optJSONArray(key) ?: return emptyList()
                return (maxOf(0, array.length() - 60) until array.length()).map { index ->
                    array.optJSONArray(index)?.let {
                        val rx = it.optDouble(0); val tx = it.optDouble(1)
                        if (rx.isFinite() && tx.isFinite() && rx >= 0 && tx >= 0) Rate(rx, tx) else null
                    }
                }
            }
            val logs = json.optJSONArray("logs") ?: JSONArray()
            return SavedHistory(samples("trend"), samples("forwardTrend"),
                json.optDouble("rx", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0,
                json.optDouble("tx", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0,
                (0 until minOf(100, logs.length())).map { index ->
                    val entry = logs.getJSONObject(index)
                    RequestLog(entry.getString("time"), entry.getString("source"),
                        if (entry.isNull("status")) null else entry.getInt("status"),
                        if (entry.isNull("error")) null else entry.getString("error"))
                }, json.optString("sampledAt", "尚未采样"), json.optString("forwardSampledAt", "尚未采样"))
        }
    }
}

class ControllerSecretStore(private val prefs: SharedPreferences, private val alias: String = "wiesler.controller.v1") {
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Controller key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    fun save(address: String, secret: String, enabled: Boolean): Boolean {
        val encrypted = if (secret.isEmpty()) null else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(true)) }
            // 密文绑定控制器地址，防止换地址误用密钥。 / Bind ciphertext to its controller address.
            cipher.updateAAD(address.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
                Base64.encodeToString(cipher.doFinal(secret.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }
        return prefs.edit().putString("controller", address).putString("controllerSecret", encrypted)
            .putBoolean("controllerEnabled", enabled).commit()
    }

    fun read(address: String): String {
        val encrypted = prefs.getString("controllerSecret", null) ?: return ""
        val parts = encrypted.split('.')
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        }
        cipher.updateAAD(address.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
