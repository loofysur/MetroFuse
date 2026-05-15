/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.spotify.bridge

import java.io.Closeable
import java.io.File

object SpotifyBridge {
    private const val LIBRARY_NAME = "metrofuse_spotify"

    private val libraryLoadResult: Result<Unit> =
        runCatching {
            System.loadLibrary(LIBRARY_NAME)
        }

    val isAvailable: Boolean
        get() = libraryLoadResult.isSuccess

    val loadError: Throwable?
        get() = libraryLoadResult.exceptionOrNull()

    fun version(): String? =
        runNative {
            nativeVersion()
        }.getOrNull()

    fun create(cacheDir: File): Result<NativeHandle> = create(cacheDir.absolutePath)

    fun create(cacheDir: String): Result<NativeHandle> =
        runNative {
            require(cacheDir.isNotBlank()) { "Spotify native cache directory is empty" }
            val handle = nativeInit(cacheDir)
            check(handle != 0L) { "Spotify native bridge returned an empty handle" }
            NativeHandle(handle)
        }

    private inline fun <T> runNative(block: () -> T): Result<T> {
        libraryLoadResult.exceptionOrNull()?.let { error ->
            return Result.failure(SpotifyBridgeUnavailableException(error))
        }

        return runCatching(block)
    }

    class NativeHandle internal constructor(
        private var handle: Long,
    ) : Closeable {
        val isClosed: Boolean
            get() = handle == 0L

        fun smokeTest(): Boolean {
            val currentHandle = handle
            if (currentHandle == 0L || !isAvailable) return false

            return runCatching {
                nativeSmokeTest(currentHandle)
            }.getOrDefault(false)
        }

        override fun close() {
            val currentHandle = handle
            if (currentHandle == 0L) return

            handle = 0L
            if (isAvailable) {
                runCatching {
                    nativeRelease(currentHandle)
                }
            }
        }
    }

    class SpotifyBridgeUnavailableException(
        cause: Throwable,
    ) : IllegalStateException("Spotify native bridge is unavailable", cause)

    @JvmStatic
    private external fun nativeVersion(): String

    @JvmStatic
    private external fun nativeInit(cacheDir: String): Long

    @JvmStatic
    private external fun nativeRelease(handle: Long)

    @JvmStatic
    private external fun nativeSmokeTest(handle: Long): Boolean
}
