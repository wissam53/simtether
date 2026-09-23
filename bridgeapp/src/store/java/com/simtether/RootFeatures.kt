package com.simtether

import android.content.Context

/**
 * Store (Play) flavor: carries no root code. The rooted source set
 * provides the real implementation — keep this API surface in sync
 * with src/rooted/java/com/simtether/RootFeatures.kt.
 */
object RootFeatures {
    /** This build has no root feature code at all. */
    const val HAS_ROOT_FEATURES = false

    fun install(@Suppress("UNUSED_PARAMETER") context: Context) = Unit
    fun rootAvailable() = false
    fun probeAsync(done: (Boolean) -> Unit) = done(false)
    fun recheckRoot() = Unit
    fun audioRelayEnabled(@Suppress("UNUSED_PARAMETER") context: Context) = false
    fun setAudioRelayEnabled(@Suppress("UNUSED_PARAMETER") context: Context,
                             @Suppress("UNUSED_PARAMETER") on: Boolean) = Unit
}
