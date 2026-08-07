package com.code2hack.dealer.asr

import com.code2hack.pokerdealer.protocol.PokerAsrSource

internal enum class DealerAsrStartMode {
    DEALER_PHONE,
    GLASSES,
    UNAVAILABLE,
}

internal data class DealerAsrStartDecision(
    val mode: DealerAsrStartMode,
    val preferredSource: PokerAsrSource,
    val reason: String? = null,
) {
    val source: PokerAsrSource?
        get() = when (mode) {
            DealerAsrStartMode.DEALER_PHONE -> PokerAsrSource.DEALER_PHONE
            DealerAsrStartMode.GLASSES -> PokerAsrSource.GLASSES
            DealerAsrStartMode.UNAVAILABLE -> null
        }

    val fellBackToGlasses: Boolean
        get() = preferredSource == PokerAsrSource.DEALER_PHONE && mode == DealerAsrStartMode.GLASSES
}

/** Owns the future source setting and one immutable source snapshot per active session. */
internal class DealerAsrSourceSession(
    initialSource: PokerAsrSource = PokerAsrSource.GLASSES,
) {
    var futureSource: PokerAsrSource = initialSource
        private set

    var activeSource: PokerAsrSource? = null
        private set

    fun setFutureSource(source: PokerAsrSource) {
        futureSource = source
    }

    fun preferredSource(requestSource: PokerAsrSource): PokerAsrSource =
        if (requestSource == PokerAsrSource.DEALER_PHONE) requestSource else futureSource

    fun start(
        requestSource: PokerAsrSource,
        phoneAvailable: Boolean,
        glassesAvailable: Boolean,
    ): DealerAsrStartDecision {
        check(activeSource == null) { "ASR source session is already active" }
        val preferred = preferredSource(requestSource)
        val decision = when (preferred) {
            PokerAsrSource.GLASSES -> if (glassesAvailable) {
                DealerAsrStartDecision(DealerAsrStartMode.GLASSES, preferred)
            } else {
                DealerAsrStartDecision(
                    DealerAsrStartMode.UNAVAILABLE,
                    preferred,
                    reason = "glasses-source-unavailable",
                )
            }
            PokerAsrSource.DEALER_PHONE -> when {
                phoneAvailable -> DealerAsrStartDecision(DealerAsrStartMode.DEALER_PHONE, preferred)
                glassesAvailable -> DealerAsrStartDecision(DealerAsrStartMode.GLASSES, preferred)
                else -> DealerAsrStartDecision(
                    DealerAsrStartMode.UNAVAILABLE,
                    preferred,
                    reason = "dealer-phone-and-glasses-unavailable",
                )
            }
        }
        activeSource = decision.source
        return decision
    }

    fun end() {
        activeSource = null
    }
}

internal fun shouldRequestDealerAsrPhonePermission(
    source: PokerAsrSource,
    permissionGranted: Boolean,
): Boolean = source == PokerAsrSource.DEALER_PHONE && !permissionGranted

@Suppress("UNUSED_PARAMETER")
internal fun dealerAsrSourceSelectionEnabled(activeSession: Boolean): Boolean {
    // The active source is immutable; this control edits the next-session preference.
    return true
}
