/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.providers

import android.content.Context
import com.metrolist.music.apple.AppleMusicSongResolver
import com.metrolist.music.constants.AudioProviderOrder
import com.metrolist.music.constants.AudioProviderOrderItem
import com.metrolist.music.constants.AudioProviderOrderKey
import com.metrolist.music.constants.DeezerAudioQuality
import com.metrolist.music.constants.DeezerAudioQualityKey
import com.metrolist.music.constants.DeezerResolverUrlKey
import com.metrolist.music.constants.QobuzBackend
import com.metrolist.music.constants.QobuzBackendKey
import com.metrolist.music.constants.QobuzCountryKey
import com.metrolist.music.constants.SoundCloudAuthTokenKey
import com.metrolist.music.deezer.DeezerAudioProvider
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.qobuz.QobuzAudioProvider
import com.metrolist.music.soundcloud.SoundCloudAudioProvider
import com.metrolist.music.tidal.TidalAudioProvider
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object ProviderMatchSearch {
    suspend fun search(
        context: Context,
        metadata: MediaMetadata,
        perProviderLimit: Int = 6,
    ): List<ProviderMatchCandidate> =
        withContext(Dispatchers.IO) {
            val order = AudioProviderOrder.deserialize(context.dataStore.get(AudioProviderOrderKey, ""))
            val candidates = mutableListOf<ProviderMatchCandidate>()

            order.forEach { provider ->
                runCatching {
                    candidates += searchProvider(context, metadata, provider, perProviderLimit)
                }
            }
            candidates
                .distinctBy { "${it.provider.name}:${it.providerTrackId}" }
        }

    private fun searchProvider(
        context: Context,
        metadata: MediaMetadata,
        provider: AudioProviderOrderItem,
        limit: Int,
    ): List<ProviderMatchCandidate> =
        when (provider) {
            AudioProviderOrderItem.SOUNDCLOUD -> {
                val term = metadata.searchTerm()
                SoundCloudAudioProvider.searchMetadata(
                    term = term,
                    limit = limit,
                ).map { track ->
                    ProviderMatchCandidate(
                        provider = provider,
                        providerTrackId = track.permalinkUrl,
                        title = track.title,
                        artist = track.artist,
                        album = null,
                        durationMs = track.durationMs,
                    )
                }
            }
            AudioProviderOrderItem.TIDAL ->
                TidalAudioProvider.searchCandidates(metadata.toTidalQuery(), limit).map { track ->
                    ProviderMatchCandidate(
                        provider = provider,
                        providerTrackId = track.trackId,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        durationMs = track.durationMs,
                    )
                }
            AudioProviderOrderItem.DEEZER -> {
                val quality = context.dataStore.get(DeezerAudioQualityKey).toEnum(DeezerAudioQuality.MP3_128)
                val resolverUrl = context.dataStore.get(DeezerResolverUrlKey, DeezerAudioProvider.DEFAULT_RESOLVER_URL)
                DeezerAudioProvider.searchCandidates(metadata.toDeezerQuery(resolverUrl, quality), limit).map { track ->
                    ProviderMatchCandidate(
                        provider = provider,
                        providerTrackId = track.trackId,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        durationMs = track.durationMs,
                    )
                }
            }
            AudioProviderOrderItem.YOUTUBE_MUSIC ->
                listOf(
                    ProviderMatchCandidate(
                        provider = provider,
                        providerTrackId = metadata.id,
                        title = metadata.title,
                        artist = metadata.artists.joinToString(", ") { it.name },
                        album = metadata.album?.title,
                        durationMs = metadata.duration.takeIf { it > 0 }?.toLong()?.times(1000L),
                    ),
                )
            AudioProviderOrderItem.QOBUZ -> {
                val backend = context.dataStore.get(QobuzBackendKey).toEnum(QobuzBackend.JUMO)
                val country = context.dataStore.get(QobuzCountryKey, "US")
                QobuzAudioProvider.searchCandidates(
                    query = metadata.toQobuzQuery(country, backend.toProviderBackend()),
                    limit = limit,
                ).map { track ->
                    ProviderMatchCandidate(
                        provider = provider,
                        providerTrackId = track.trackId,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        durationMs = track.durationMs,
                    )
                }
            }
            AudioProviderOrderItem.APPLE_MUSIC ->
                AppleMusicSongResolver.searchCandidates(metadata.toAppleQuery(), limit).map { track ->
                    ProviderMatchCandidate(
                        provider = provider,
                        providerTrackId = track.adamId,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        durationMs = track.durationMs,
                    )
                }
            AudioProviderOrderItem.INSTAGRAM -> emptyList()
        }

    private fun MediaMetadata.toTidalQuery(): TidalAudioProvider.Query =
        TidalAudioProvider.Query(
            mediaId = id,
            title = title,
            artists = artists.map { it.name },
            album = album?.title,
            isrc = ProviderIsrc.firstOf(id),
            durationMs = duration.takeIf { it > 0 }?.toLong()?.times(1000L),
        )

    private fun MediaMetadata.toDeezerQuery(
        resolverUrl: String,
        quality: DeezerAudioQuality,
    ): DeezerAudioProvider.Query =
        DeezerAudioProvider.Query(
            mediaId = id,
            title = title,
            artists = artists.map { it.name },
            album = album?.title,
            isrc = ProviderIsrc.firstOf(id),
            durationMs = duration.takeIf { it > 0 }?.toLong()?.times(1000L),
            resolverUrl = resolverUrl,
            quality = quality,
        )

    private fun MediaMetadata.toQobuzQuery(
        countryCode: String,
        backend: QobuzAudioProvider.ResolverBackend,
    ): QobuzAudioProvider.Query =
        QobuzAudioProvider.Query(
            mediaId = id,
            title = title,
            artists = artists.map { it.name },
            album = album?.title,
            isrc = ProviderIsrc.firstOf(id),
            durationMs = duration.takeIf { it > 0 }?.toLong()?.times(1000L),
            countryCode = countryCode
                .trim()
                .uppercase(Locale.US)
                .takeIf { it.matches(Regex("[A-Z]{2}")) }
                ?: "US",
            backend = backend,
        )

    private fun MediaMetadata.toAppleQuery(): AppleMusicSongResolver.Query =
        AppleMusicSongResolver.Query(
            mediaId = id,
            title = title,
            artists = artists.map { it.name },
            album = album?.title,
            isrc = ProviderIsrc.firstOf(id),
            durationMs = duration.takeIf { it > 0 }?.toLong()?.times(1000L),
            explicit = explicit,
        )

    private fun MediaMetadata.searchTerm(): String =
        listOf(title, artists.firstOrNull()?.name, album?.title)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")

    private fun QobuzBackend.toProviderBackend(): QobuzAudioProvider.ResolverBackend =
        when (this) {
            QobuzBackend.TRYPT -> QobuzAudioProvider.ResolverBackend.TRYPT
            QobuzBackend.JUMO -> QobuzAudioProvider.ResolverBackend.JUMO
            QobuzBackend.KENNY -> QobuzAudioProvider.ResolverBackend.KENNY
            QobuzBackend.SQUID -> QobuzAudioProvider.ResolverBackend.SQUID
        }
}
