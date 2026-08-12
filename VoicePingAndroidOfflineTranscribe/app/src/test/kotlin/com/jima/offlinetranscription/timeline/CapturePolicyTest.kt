package com.voiceping.offlinetranscription.timeline

import org.junit.Test
import kotlin.test.assertEquals

class CapturePolicyTest {
    private val policy = CapturePolicy(allowedPackages = setOf("org.telegram.messenger"))

    @Test
    fun captureRequiresExplicitAllowlist() {
        assertEquals(
            CaptureDecision.SKIP_NOT_ALLOWLISTED,
            policy.decision(CaptureCandidate("com.example.unselected"))
        )
        assertEquals(
            CaptureDecision.CAPTURE,
            policy.decision(CaptureCandidate("org.telegram.messenger"))
        )
    }

    @Test
    fun privacyGuardsWinOverAllowlist() {
        assertEquals(
            CaptureDecision.SKIP_SECURE_WINDOW,
            policy.decision(CaptureCandidate("org.telegram.messenger", isSecureWindow = true))
        )
        assertEquals(
            CaptureDecision.SKIP_SENSITIVE_CONTENT,
            policy.decision(CaptureCandidate("org.telegram.messenger", containsSensitiveField = true))
        )
        assertEquals(
            CaptureDecision.SKIP_DENYLISTED_APP,
            policy.decision(CaptureCandidate("com.bitwarden"))
        )
    }

    @Test
    fun bankingLikePackagesAreDeniedEvenWhenExplicitlySelected() {
        val unsafePolicy = CapturePolicy(allowedPackages = setOf("com.example.bank.mobile"))
        assertEquals(
            CaptureDecision.SKIP_DENYLISTED_APP,
            unsafePolicy.decision(CaptureCandidate("com.example.bank.mobile"))
        )
    }
}
