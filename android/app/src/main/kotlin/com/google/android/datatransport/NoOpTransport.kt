package com.google.android.datatransport

/*
 * Cindy promises no network access of any kind. MediaPipe Tasks always creates a usage logger
 * (`com.google.mediapipe.tasks.core.logging.RemoteLoggingClient`) that sends app id, version and
 * task statistics through Google's data-transport library. There is no switch for it, so the app
 * excludes that library (app/build.gradle.kts) and provides this minimal, inert replacement of the
 * few classes the logger touches: events are accepted and dropped, nothing is stored, scheduled or
 * sent. Only the signatures `RemoteLoggingClient` links against are implemented.
 */

/** Payload encoding name (e.g. "proto"). */
class Encoding private constructor(val name: String) {
    companion object {
        @JvmStatic
        fun of(name: String): Encoding = Encoding(name)
    }
}

/** A log event; the payload is never looked at. */
class Event<T> private constructor(val payload: T) {
    companion object {
        @JvmStatic
        fun <T> ofData(payload: T): Event<T> = Event(payload)
    }
}

fun interface Transformer<T, U> {
    fun apply(input: T): U
}

interface Transport<T> {
    fun send(event: Event<T>)
}

interface TransportFactory {
    fun <T> getTransport(name: String, payloadType: Class<T>, encoding: Encoding, transformer: Transformer<T, ByteArray>): Transport<T>
}

/** Drops every event. */
internal object DroppingTransportFactory : TransportFactory {
    private val transport = object : Transport<Any?> {
        override fun send(event: Event<Any?>) = Unit
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> getTransport(
        name: String,
        payloadType: Class<T>,
        encoding: Encoding,
        transformer: Transformer<T, ByteArray>,
    ): Transport<T> = transport as Transport<T>
}
