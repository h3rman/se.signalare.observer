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

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle
import Logger

/**
 * Configuration for the HTTP POST interceptor proxy.
 *
 * Populated at VPN startup from Android Enterprise managed restrictions (DPC push)
 * or from hardcoded defaults. The DPC app sets these via
 * DevicePolicyManager.setApplicationRestrictions().
 *
 * Managed restriction keys:
 *   interceptor_enabled      - "true"/"false"
 *   interceptor_target_hosts - comma-separated hostnames/IPs, e.g. "192.168.1.100,target.example.com"
 *   interceptor_target_port  - integer string, e.g. "80"
 *   interceptor_proxy_port   - integer string, e.g. "8080"
 *   interceptor_rules        - semicolon-separated "key=value" pairs to inject/override in POST body
 *                              e.g. "station_id=SE-001;operator=signalare"
 */
object InterceptorConfig {

    private const val TAG = "InterceptorConfig"

    // Managed restriction keys used by the DPC
    private const val KEY_ENABLED       = "interceptor_enabled"
    private const val KEY_TARGET_HOSTS  = "interceptor_target_hosts"
    private const val KEY_TARGET_PORT   = "interceptor_target_port"
    private const val KEY_PROXY_PORT    = "interceptor_proxy_port"
    private const val KEY_RULES         = "interceptor_rules"

    const val DEFAULT_PROXY_PORT  = 8080
    const val DEFAULT_TARGET_PORT = 80

    @Volatile var enabled: Boolean = false
        private set

    @Volatile var targetHosts: Set<String> = emptySet()
        private set

    @Volatile var targetPort: Int = DEFAULT_TARGET_PORT
        private set

    @Volatile var proxyPort: Int = DEFAULT_PROXY_PORT
        private set

    // Each pair: (paramName, newValue) applied to URL-encoded POST bodies
    @Volatile var postModificationRules: List<Pair<String, String>> = emptyList()
        private set

    /**
     * Load configuration from Android Enterprise managed restrictions.
     * Falls back to defaults when no DPC restrictions are present.
     * Call from BraveVPNService.onCreate().
     */
    fun loadFromManagedConfig(context: Context) {
        try {
            val rm = context.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager
            val restrictions: Bundle = rm?.applicationRestrictions ?: Bundle()
            applyBundle(restrictions)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load managed config, using defaults: ${e.message}")
        }
    }

    private fun applyBundle(b: Bundle) {
        enabled     = b.getString(KEY_ENABLED, "false").equals("true", ignoreCase = true)
        targetPort  = b.getString(KEY_TARGET_PORT,  DEFAULT_TARGET_PORT.toString()).toIntOrNull()  ?: DEFAULT_TARGET_PORT
        proxyPort   = b.getString(KEY_PROXY_PORT,   DEFAULT_PROXY_PORT.toString()).toIntOrNull()   ?: DEFAULT_PROXY_PORT
        targetHosts = b.getString(KEY_TARGET_HOSTS, "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        postModificationRules = b.getString(KEY_RULES, "")
            .split(";")
            .mapNotNull { entry ->
                val idx = entry.indexOf('=')
                if (idx > 0) {
                    Pair(entry.substring(0, idx).trim(), entry.substring(idx + 1).trim())
                } else null
            }

        Logger.i(TAG, "Loaded config: enabled=$enabled, hosts=$targetHosts, " +
            "targetPort=$targetPort, proxyPort=$proxyPort, rules=${postModificationRules.size}")
    }

    /**
     * Returns true if the given destination IP and port should be routed through the
     * local interceptor proxy.
     */
    fun shouldIntercept(dstIp: String, dstPort: Int): Boolean {
        if (!enabled) return false
        if (dstPort != targetPort) return false
        if (targetHosts.isEmpty()) return false
        return targetHosts.contains(dstIp)
    }

    fun isEnabled(): Boolean = enabled && targetHosts.isNotEmpty()
}
