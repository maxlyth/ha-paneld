package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.security.SensitiveOperation

internal enum class ConfigSensitiveAdmissionResult { AUTHORIZED, DENIED, SEPARATE_SENSITIVE_CHANGES }

/** Stateless admission for sensitive Configure changes; exact peer/payload binding stays in the broker. */
internal object ConfigSensitiveAdmission {
    suspend fun authorize(
        hardenedSecurityEnabled: Boolean,
        loopbackPeer: Boolean,
        requestedOperations: List<SensitiveOperation>,
        authorize: suspend (SensitiveOperation) -> Boolean,
    ): ConfigSensitiveAdmissionResult {
        val operations = requestedOperations.distinct()
        if (hardenedSecurityEnabled && !loopbackPeer && operations.size > 1) {
            return ConfigSensitiveAdmissionResult.SEPARATE_SENSITIVE_CHANGES
        }
        if (!hardenedSecurityEnabled || loopbackPeer) return ConfigSensitiveAdmissionResult.AUTHORIZED
        for (operation in operations) {
            if (!authorize(operation)) return ConfigSensitiveAdmissionResult.DENIED
        }
        return ConfigSensitiveAdmissionResult.AUTHORIZED
    }
}
