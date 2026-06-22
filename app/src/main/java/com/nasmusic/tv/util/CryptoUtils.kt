package com.nasmusic.tv.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 加密工具类，基于 Android Keystore
 * 用于加密敏感数据（如密码、token）
 */
object CryptoUtils {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "nasmusic_secret_key"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE)
        keyStore.load(null)

        keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * 加密明文，返回 Base64 编码的 "iv:ciphertext" 字符串
     */
    fun encrypt(plainText: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = iv + encrypted
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("CryptoUtils", "encrypt failed", e)
            plainText // 失败时返回明文（降级处理）
        }
    }

    /**
     * 解密 Base64 编码的 "iv:ciphertext" 字符串
     */
    fun decrypt(encryptedText: String): String {
        return try {
            val combined = Base64.decode(encryptedText, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return encryptedText

            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val encrypted = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("CryptoUtils", "decrypt failed", e)
            encryptedText // 失败时返回原值（可能是明文，兼容旧版本）
        }
    }

    /**
     * 判断字符串是否已加密（Base64 格式且长度足够）
     */
    fun isEncrypted(text: String): Boolean {
        return try {
            val combined = Base64.decode(text, Base64.NO_WRAP)
            combined.size > GCM_IV_LENGTH
        } catch (e: Exception) {
            false
        }
    }
}
