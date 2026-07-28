package com.vslot.app.analytics

data class PushOpenPayload(
    val campaignId: String,
    val payloadType: String
) {
    companion object {
        private val CampaignKeys = listOf(
            "campaign_id",
            "campaignId",
            "appmetrica_campaign_id",
            "appmetrica_push_campaign_id"
        )
        private val PayloadTypeKeys = listOf(
            "payload_type",
            "payloadType",
            "type"
        )

        fun fromExtras(extras: Map<String, String?>): PushOpenPayload? {
            val campaignId = CampaignKeys.firstNotBlank(extras) ?: return null
            val payloadType = PayloadTypeKeys.firstNotBlank(extras) ?: "push"
            return PushOpenPayload(campaignId, payloadType)
        }

        private fun List<String>.firstNotBlank(extras: Map<String, String?>): String? {
            return firstNotNullOfOrNull { key ->
                extras[key]?.analyticsToken()
            }
        }

        private fun String.analyticsToken(): String? {
            val trimmed = trim()
            if (trimmed.hasUnsafeAnalyticsTokenMarker()) return null
            val token = trimmed
                .take(MAX_ANALYTICS_TOKEN_LENGTH)
                .map { character ->
                    if (character.isLetterOrDigit() || character == '_' || character == '-' || character == '.') {
                        character
                    } else {
                        '_'
                    }
                }
                .joinToString("")
                .trim('_', '-', '.')
            return token.takeIf { it.isNotBlank() }
        }

        private fun String.hasUnsafeAnalyticsTokenMarker(): Boolean {
            return contains("://") || any { it == '@' || it == '?' || it == '=' || it == '&' || it == '#' }
        }

        private const val MAX_ANALYTICS_TOKEN_LENGTH = 80
    }
}
