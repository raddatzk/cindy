package com.google.android.datatransport.runtime

import android.content.Context
import com.google.android.datatransport.DroppingTransportFactory
import com.google.android.datatransport.TransportFactory

/** Where events would go; see `com.google.android.datatransport.NoOpTransport.kt`. */
interface Destination

/** Inert stand-in for Google's transport runtime: every factory drops its events. */
class TransportRuntime private constructor() {
    fun newFactory(destination: Destination): TransportFactory = DroppingTransportFactory

    companion object {
        private val instance = TransportRuntime()

        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun initialize(context: Context) = Unit

        @JvmStatic
        fun getInstance(): TransportRuntime = instance
    }
}
