package com.code2hack.dealer.asr

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.code2hack.pokerdealer.protocol.PokerAsrSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dealerAsrSourceDataStore by preferencesDataStore("dealer_asr_source")

/** Persists only the source for the next ASR session; an active session owns its snapshot. */
internal class DealerAsrSourceSettings(
    private val context: Context,
) {
    suspend fun read(): PokerAsrSource = context.dealerAsrSourceDataStore.data
        .map { values ->
            runCatching {
                values[Source]?.let(PokerAsrSource::valueOf)
            }.getOrNull() ?: PokerAsrSource.GLASSES
        }
        .first()

    suspend fun save(source: PokerAsrSource) {
        context.dealerAsrSourceDataStore.edit { values ->
            values[Source] = source.name
        }
    }

    private companion object {
        val Source = stringPreferencesKey("source")
    }
}
