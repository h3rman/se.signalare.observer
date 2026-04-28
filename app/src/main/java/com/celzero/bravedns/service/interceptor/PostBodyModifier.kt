/*
 * Copyright 2025 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.service.interceptor

import Logger
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Modifies HTTP POST payloads according to configured rules.
 *
 * Supports:
 *  - application/x-www-form-urlencoded  (standard HTML form POST)
 *
 * Returns the modified body bytes and the new Content-Length.
 */
object PostBodyModifier {

    private const val TAG = "PostBodyModifier"
    private val UTF8 = StandardCharsets.UTF_8.name()

    data class ModifyResult(val body: ByteArray, val contentLength: Int)

    /**
     * Apply modification rules to [bodyBytes].
     *
     * @param bodyBytes     raw bytes of the original POST body
     * @param contentType   value of the Content-Type header (may be null)
     * @param rules         list of (paramName -> newValue) pairs to inject/override
     * @return ModifyResult with possibly-modified body and updated length
     */
    fun modify(
        bodyBytes: ByteArray,
        contentType: String?,
        rules: List<Pair<String, String>>
    ): ModifyResult {
        if (rules.isEmpty()) {
            return ModifyResult(bodyBytes, bodyBytes.size)
        }

        return try {
            val ct = contentType?.lowercase() ?: ""
            when {
                ct.contains("application/x-www-form-urlencoded") ->
                    modifyUrlEncoded(bodyBytes, rules)
                ct.contains("application/json") ->
                    modifyJson(bodyBytes, rules)
                else -> {
                    // Unknown content type – apply rules as URL-encoded (best-effort)
                    Logger.w(TAG, "Unknown content-type '$contentType', treating as form-encoded")
                    modifyUrlEncoded(bodyBytes, rules)
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Body modification failed: ${e.message}; sending original body")
            ModifyResult(bodyBytes, bodyBytes.size)
        }
    }

    // --------------- URL-encoded form data ---------------

    private fun modifyUrlEncoded(bodyBytes: ByteArray, rules: List<Pair<String, String>>): ModifyResult {
        val original = String(bodyBytes, StandardCharsets.UTF_8)
        val params = parseUrlEncoded(original).toMutableMap()

        var changed = 0
        for ((key, newValue) in rules) {
            if (params[key] != newValue) {
                params[key] = newValue
                changed++
            }
        }

        if (changed == 0) {
            Logger.v(TAG, "No changes needed for URL-encoded body")
            return ModifyResult(bodyBytes, bodyBytes.size)
        }

        val modified = encodeUrlParams(params)
        val modifiedBytes = modified.toByteArray(StandardCharsets.UTF_8)
        Logger.d(TAG, "URL-encoded body modified: $changed param(s) changed, " +
            "original=${bodyBytes.size}B new=${modifiedBytes.size}B")
        return ModifyResult(modifiedBytes, modifiedBytes.size)
    }

    private fun parseUrlEncoded(body: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        if (body.isBlank()) return map
        for (pair in body.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            if (idx < 0) {
                val key = URLDecoder.decode(pair, UTF8)
                map[key] = ""
            } else {
                val key   = URLDecoder.decode(pair.substring(0, idx), UTF8)
                val value = URLDecoder.decode(pair.substring(idx + 1), UTF8)
                map[key] = value
            }
        }
        return map
    }

    private fun encodeUrlParams(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, UTF8)}=${URLEncoder.encode(v, UTF8)}"
        }

    // --------------- JSON (simple key-value override) ---------------

    /**
     * Very lightweight JSON field override – replaces the VALUE of top-level string
     * fields matched by [rules]. Does not handle nested objects or arrays.
     * For complex modifications, integrate a proper JSON library.
     */
    private fun modifyJson(bodyBytes: ByteArray, rules: List<Pair<String, String>>): ModifyResult {
        var json = String(bodyBytes, StandardCharsets.UTF_8)
        var changed = 0
        for ((key, newValue) in rules) {
            val escapedKey = escapeJsonString(key)
            val escapedValue = escapeJsonString(newValue)
            // Match: "key" : "any-value"  (with flexible spacing)
            val regex = Regex("\"${Regex.escape(escapedKey)}\"\\s*:\\s*\"[^\"]*\"")
            val replacement = "\"$escapedKey\": \"$escapedValue\""
            val after = regex.replace(json, replacement)
            if (after != json) {
                json = after
                changed++
            }
        }
        val modifiedBytes = json.toByteArray(StandardCharsets.UTF_8)
        Logger.d(TAG, "JSON body modified: $changed field(s) changed, " +
            "original=${bodyBytes.size}B new=${modifiedBytes.size}B")
        return ModifyResult(modifiedBytes, modifiedBytes.size)
    }

    private fun escapeJsonString(s: String): String =
        s.replace("\\", "\\\\")
         .replace("\"", "\\\"")
         .replace("\n", "\\n")
         .replace("\r", "\\r")
         .replace("\t", "\\t")
}
