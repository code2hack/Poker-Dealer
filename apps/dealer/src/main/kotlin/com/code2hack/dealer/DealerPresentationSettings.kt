package com.code2hack.dealer

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.code2hack.pokerdealer.protocol.POKER_FONT_SCALE_DEFAULT_PERCENT
import com.code2hack.pokerdealer.protocol.PokerFontScaleState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dealerPresentationDataStore by preferencesDataStore("dealer_presentation")

/** Dealer-local preference; Poker's value is deliberately stored and owned by Dealer separately. */
internal class DealerPresentationSettings(
    private val context: Context,
) {
    val dealerFontScale: Flow<PokerFontScaleState> = context.dealerPresentationDataStore.data.map { values ->
        val percent = values[DealerFontPercent]
        runCatching {
            PokerFontScaleState(percent = percent ?: POKER_FONT_SCALE_DEFAULT_PERCENT)
        }.getOrDefault(PokerFontScaleState())
    }

    suspend fun saveDealerFontScale(percent: Int) {
        val scale = PokerFontScaleState(percent = percent)
        context.dealerPresentationDataStore.edit { values ->
            values[DealerFontPercent] = scale.percent
        }
    }

    private companion object {
        val DealerFontPercent = intPreferencesKey("dealer_font_percent")
    }
}
