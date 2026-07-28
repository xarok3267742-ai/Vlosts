package com.vslot.app.ui.privacy

import java.net.URI

object PrivacyUrlPolicy {
    fun isLoadable(url: String): Boolean {
        val uri = parse(url) ?: return false
        return uri.isHttpsWithHost()
    }

    fun isAllowed(policyUrl: String, candidateUrl: String): Boolean {
        val policyUri = parse(policyUrl) ?: return false
        val candidateUri = parse(candidateUrl) ?: return false
        return policyUri.isHttpsWithHost() &&
            candidateUri.isHttpsWithHost() &&
            candidateUri.host.equals(policyUri.host, ignoreCase = true) &&
            candidateUri.effectivePort() == policyUri.effectivePort()
    }

    fun analyticsOrigin(url: String): String? {
        val uri = parse(url) ?: return null
        if (!uri.isHttpsWithHost()) return null
        val portSuffix = if (uri.effectivePort() == DEFAULT_HTTPS_PORT) "" else ":${uri.effectivePort()}"
        return "https://${uri.host.lowercase()}$portSuffix"
    }

    private fun parse(url: String): URI? {
        return runCatching { URI(url.trim()) }.getOrNull()
    }

    private fun URI.isHttpsWithHost(): Boolean {
        return scheme.equals("https", ignoreCase = true) &&
            !host.isNullOrBlank() &&
            rawUserInfo == null
    }

    private fun URI.effectivePort(): Int {
        return if (port == -1) DEFAULT_HTTPS_PORT else port
    }

    private const val DEFAULT_HTTPS_PORT = 443
}
