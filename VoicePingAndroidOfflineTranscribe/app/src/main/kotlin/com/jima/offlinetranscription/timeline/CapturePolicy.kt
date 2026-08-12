package com.voiceping.offlinetranscription.timeline

/**
 * Privacy boundary for autonomous capture. A package must be explicitly allowlisted,
 * and the denylist always wins. This class intentionally has no "capture all" mode.
 */
data class CapturePolicy(
    val allowedPackages: Set<String> = emptySet(),
    val deniedPackages: Set<String> = DEFAULT_DENYLIST,
) {
    fun decision(input: CaptureCandidate): CaptureDecision = when {
        input.packageName in deniedPackages -> CaptureDecision.SKIP_DENYLISTED_APP
        input.isSecureWindow -> CaptureDecision.SKIP_SECURE_WINDOW
        input.containsSensitiveField -> CaptureDecision.SKIP_SENSITIVE_CONTENT
        input.packageName !in allowedPackages -> CaptureDecision.SKIP_NOT_ALLOWLISTED
        else -> CaptureDecision.CAPTURE
    }

    companion object {
        /** Conservative defaults; users may extend this list but cannot override it accidentally. */
        val DEFAULT_DENYLIST = setOf(
            "com.android.settings",
            "com.google.android.apps.authenticator2",
            "com.authy.authy",
            "com.lastpass.lpandroid",
            "com.bitwarden",
            "com.dashlane",
            "com.1password.android",
        )
    }
}

data class CaptureCandidate(
    val packageName: String,
    val isSecureWindow: Boolean = false,
    val containsSensitiveField: Boolean = false,
)

enum class CaptureDecision {
    CAPTURE,
    SKIP_NOT_ALLOWLISTED,
    SKIP_DENYLISTED_APP,
    SKIP_SECURE_WINDOW,
    SKIP_SENSITIVE_CONTENT,
}
