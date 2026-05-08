/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.tidal

import android.util.Base64
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

object TidalAudioProvider {
    private const val API_BASE_URL = "https://tidal.com/v1"
    private const val SONG_LINK_API_URL = "https://api.song.link/v1-alpha.1/links"
    private const val DOWNLOAD_API_URL = "https://api.zarz.moe/v1/dl/tid2"
    private const val PUBLIC_TOKEN = "49YxDN9a2aFV6RTG"
    private const val COUNTRY_CODE = "US"
    private const val LOCALE = "en_US"
    private const val DEVICE_TYPE = "BROWSER"
    private const val DOWNLOAD_USER_AGENT = "SpotiFLAC-Mobile/4.5.1"
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
    private const val STREAM_CACHE_MS = 4 * 60 * 1000L
    private const val TRACK_CACHE_MS = 60 * 60 * 1000L
    private const val SEARCH_LIMIT = 10
    private const val MAX_STREAM_CANDIDATES = 6
    private const val MIN_MATCH_SCORE = 42
    private const val REJECT_SCORE = -1_000_000
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val AMAZON_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US)

    data class Query(
        val mediaId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val isrc: String?,
        val durationMs: Long?,
    )

    data class Resolved(
        val mediaUri: String,
        val trackId: String,
        val label: String,
        val mimeType: String,
        val codecs: String,
        val bitrate: Int,
        val sampleRate: Int?,
        val contentLength: Long?,
        val expiresAtMs: Long,
    )

    class TidalAudioResolutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private data class CachedTrack(
        val track: MatchedTrack,
        val expiresAtMs: Long,
    )

    private data class MatchedTrack(
        val trackId: String,
        val title: String,
        val artistNames: List<String>,
        val album: String?,
        val isrc: String?,
        val durationMs: Long?,
        val audioQuality: String?,
        val sampleRate: Int?,
    )

    private data class ParsedManifest(
        val url: String,
        val mimeType: String,
        val codecs: String,
        val sampleRate: Int?,
        val bitrate: Int?,
        val expiryUrl: String?,
        val isDash: Boolean,
    )

    private data class StreamMetadata(
        val mimeType: String?,
        val contentLength: Long?,
    )

    private data class ScoredTrack(
        val track: MatchedTrack,
        val score: Int,
    )

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()

    private val trackCache = ConcurrentHashMap<String, CachedTrack>()
    private val streamCache = ConcurrentHashMap<String, Resolved>()

    fun resolve(query: Query): Resolved {
        val now = System.currentTimeMillis()
        val directTrackId = query.mediaId.toTidalTrackIdOrNull()
        val trackCacheKey = query.trackCacheKey()
        val tracks = if (directTrackId != null) {
            listOf(query.toDirectMatchedTrack(directTrackId))
        } else {
            val cached =
                trackCache[trackCacheKey]
                ?.takeIf { it.expiresAtMs > now }
                ?.track
            val candidates = findCandidateTracks(query)
            buildList {
                cached?.let(::add)
                addAll(candidates)
            }.distinctBy { it.trackId }
        }

        if (tracks.isEmpty()) {
            throw TidalAudioResolutionException("TIDAL match not found for ${query.title}")
        }

        val errors = mutableListOf<String>()
        tracks.take(MAX_STREAM_CANDIDATES).forEach { track ->
            streamQualityCandidates(track).forEach { quality ->
                val streamCacheKey = "${query.mediaId}::${track.trackId}::$quality::flac"
                streamCache[streamCacheKey]
                    ?.takeIf { it.expiresAtMs > now + 20_000L }
                    ?.let { return it }

                val streamAttempt = runCatching {
                    requestDirectFlac(track, quality, query.durationMs ?: track.durationMs, now)
                }.onFailure { error ->
                    Timber.tag("TidalAudio").w(error, "TIDAL $quality stream failed for ${track.trackId}")
                    errors += "${track.trackId}/$quality: ${error.message ?: error.javaClass.simpleName}"
                }

                streamAttempt.getOrNull()?.let { resolved ->
                    streamCache[streamCacheKey] = resolved
                    if (directTrackId == null) {
                        trackCache[trackCacheKey] = CachedTrack(track, now + TRACK_CACHE_MS)
                    }
                    return resolved
                }
            }
        }

        throw TidalAudioResolutionException(
            "TIDAL FLAC stream not found for ${query.title}: ${errors.joinToString("; ").take(720)}",
        )
    }

    fun invalidate(mediaId: String) {
        streamCache.keys
            .filter { it.startsWith("$mediaId::") }
            .forEach { streamCache.remove(it) }
        trackCache.remove(mediaId.trackCacheKeyFallback())
    }

    fun isTidalTrackId(value: String): Boolean = value.toTidalTrackIdOrNull() != null

    fun normalizeIsrc(value: String?): String? {
        val compact = value
            ?.uppercase(Locale.US)
            ?.replace(Regex("[^A-Z0-9]"), "")
            ?: return null
        return Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")
            .find(compact)
            ?.value
    }

    private fun findCandidateTracks(query: Query): List<MatchedTrack> {
        val terms = searchTerms(query)
        val candidates = mutableListOf<ScoredTrack>()
        resolveSongLinkTidalTrackId(query)?.let { tidalId ->
            candidates += ScoredTrack(query.toDirectMatchedTrack(tidalId), Int.MAX_VALUE / 4)
        }
        for (term in terms) {
            val results = searchTracks(term) ?: continue
            candidates += selectCandidateTracks(results, query)
        }
        return candidates
            .groupBy { it.track.trackId }
            .mapNotNull { (_, matches) -> matches.maxByOrNull { it.score } }
            .sortedByDescending { it.score }
            .map { it.track }
    }

    private fun searchTracks(term: String): JSONArray? {
        if (term.isBlank()) return null
        val url =
            "$API_BASE_URL/search/tracks"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("countryCode", COUNTRY_CODE)
                .addQueryParameter("locale", LOCALE)
                .addQueryParameter("deviceType", DEVICE_TYPE)
                .addQueryParameter("query", term)
                .addQueryParameter("limit", SEARCH_LIMIT.toString())
                .addQueryParameter("offset", "0")
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("x-tidal-token", PUBLIC_TOKEN)
                .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body.string().takeIf { it.isNotBlank() } ?: return@use null
                JSONObject(body).optJSONArray("items")
            }
        }.getOrNull()
    }

    private fun selectCandidateTracks(
        results: JSONArray,
        query: Query,
    ): List<ScoredTrack> {
        val wantedTitle = query.title.normalized()
        val wantedArtists = query.artists.map { it.normalized() }.filter { it.isNotBlank() }
        val wantedAlbum = query.album.normalized()
        val wantedIsrc = normalizeIsrc(query.isrc)
        val wantedDurationMs = query.durationMs?.takeIf { it > 0L }
        val candidates = mutableListOf<ScoredTrack>()
        for (index in 0 until results.length()) {
            val obj = results.optJSONObject(index) ?: continue
            val track = obj.toMatchedTrack() ?: continue
            val score = scoreTrack(track, wantedTitle, wantedArtists, wantedAlbum, wantedIsrc, wantedDurationMs)
            if (score >= MIN_MATCH_SCORE) {
                candidates += ScoredTrack(track, score)
            }
        }
        return candidates.sortedByDescending { it.score }
    }

    private fun scoreTrack(
        track: MatchedTrack,
        wantedTitle: String,
        wantedArtists: List<String>,
        wantedAlbum: String,
        wantedIsrc: String?,
        wantedDurationMs: Long?,
    ): Int {
        val candidateTitle = track.title.normalized()
        val candidateArtists = track.artistNames.map { it.normalized() }.filter { it.isNotBlank() }
        val candidateAlbum = track.album.normalized()
        val candidateIsrc = normalizeIsrc(track.isrc)

        if (wantedTitle.isBlank() || candidateTitle.isBlank()) return REJECT_SCORE
        if (hasVersionMismatch(wantedTitle, candidateTitle)) return REJECT_SCORE

        var score = 0
        when {
            candidateTitle == wantedTitle -> score += 110
            candidateTitle.contains(wantedTitle) || wantedTitle.contains(candidateTitle) -> score += 62
            else -> {
                val titleOverlap = tokenOverlap(significantTokens(wantedTitle), significantTokens(candidateTitle))
                score += (titleOverlap * 48).roundToInt()
            }
        }

        if (wantedArtists.isNotEmpty() && candidateArtists.isNotEmpty()) {
            val artistHit = wantedArtists.any { wanted ->
                candidateArtists.any { candidate ->
                    candidate == wanted || candidate.contains(wanted) || wanted.contains(candidate)
                }
            }
            if (artistHit) {
                score += 38
            } else {
                score -= 45
            }
        }

        if (wantedAlbum.isNotBlank() && candidateAlbum.isNotBlank()) {
            score += when {
                candidateAlbum == wantedAlbum -> 18
                candidateAlbum.contains(wantedAlbum) || wantedAlbum.contains(candidateAlbum) -> 10
                else -> 0
            }
        }

        if (wantedIsrc != null) {
            score += when {
                candidateIsrc == wantedIsrc -> 160
                candidateIsrc != null -> -30
                else -> 0
            }
        }

        val candidateDurationMs = track.durationMs
        if (wantedDurationMs != null && candidateDurationMs != null) {
            val diffSeconds = abs(wantedDurationMs - candidateDurationMs) / 1000L
            score += when {
                diffSeconds <= 3 -> 36
                diffSeconds <= 8 -> 18
                diffSeconds <= 20 -> 4
                else -> -50
            }
        }

        return score
    }

    private fun requestDirectFlac(
        track: MatchedTrack,
        quality: String,
        durationMs: Long?,
        now: Long,
    ): Resolved {
        val body =
            JSONObject()
                .put("id", track.trackId)
                .put("quality", quality)
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)
        val request =
            Request
                .Builder()
                .url(DOWNLOAD_API_URL)
                .post(body)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", DOWNLOAD_USER_AGENT)
                .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (response.code == 429) {
                throw TidalAudioResolutionException("TIDAL FLAC resolver is rate limited")
            }
            if (!response.isSuccessful) {
                throw TidalAudioResolutionException(
                    "TIDAL FLAC resolver HTTP ${response.code}: ${responseBody.take(180)}",
                )
            }
            val root = JSONObject(responseBody)
            val data = root.optJSONObject("data")
                ?: throw TidalAudioResolutionException("TIDAL FLAC resolver returned no data")
            if (data.optString("assetPresentation").equals("PREVIEW", ignoreCase = true)) {
                throw TidalAudioResolutionException("TIDAL FLAC resolver returned a preview asset")
            }
            val manifestPayload = data.stringOrNull("manifest")
                ?: throw TidalAudioResolutionException("TIDAL FLAC resolver returned no manifest")
            val resolvedQuality = data.stringOrNull("audioQuality") ?: quality
            val effectiveDurationMs = durationMs ?: track.durationMs
            val manifest = parseManifest(manifestPayload, data.stringOrNull("manifestMimeType"), effectiveDurationMs)
            val isLosslessResponse = resolvedQuality.contains("LOSSLESS", ignoreCase = true)
            val isFlacLikeManifest =
                manifest.mimeType.contains("flac", ignoreCase = true) ||
                    manifest.codecs.contains("flac", ignoreCase = true)
            if (!isLosslessResponse && !isFlacLikeManifest) {
                throw TidalAudioResolutionException(
                    "TIDAL $quality downgraded to ${resolvedQuality.ifBlank { "unknown" }} (${manifest.mimeType}, ${manifest.codecs})",
                )
            }

            val streamMetadata = if (manifest.isDash) {
                StreamMetadata("application/dash+xml", null)
            } else {
                fetchStreamMetadata(manifest.url)
            }
            val sampleRate = normalizeSampleRate(data.doubleOrNull("sampleRate"))
                ?: manifest.sampleRate
                ?: normalizeSampleRate(track.sampleRate?.toDouble())
            val bitrate = manifest.bitrate ?: estimateBitrate(streamMetadata.contentLength, effectiveDurationMs)
            if (!manifest.isDash) {
                validateLikelyFullTrack(track, effectiveDurationMs, streamMetadata.contentLength)
            }
            Timber.tag("TidalAudio").i(
                "Resolved TIDAL ${resolvedQuality.ifBlank { quality }} stream for ${track.trackId}: dash=${manifest.isDash}, bitrate=$bitrate, sampleRate=$sampleRate",
            )

            return Resolved(
                mediaUri = manifest.url,
                trackId = track.trackId,
                label = if (resolvedQuality.contains("HI_RES", ignoreCase = true)) "TIDAL HiRes FLAC" else "TIDAL FLAC",
                mimeType = manifest.mimeType,
                codecs = manifest.codecs.takeIf { it.isNotBlank() }
                    ?: "flac",
                bitrate = bitrate ?: 0,
                sampleRate = sampleRate,
                contentLength = streamMetadata.contentLength,
                expiresAtMs = extractExpiryMs(manifest.expiryUrl ?: manifest.url, now),
            )
        }
    }

    private fun MatchedTrack.requestedStreamQuality(): String =
        audioQuality
            ?.uppercase(Locale.US)
            ?.let { quality ->
                if ("HI_RES" in quality || "HIRES" in quality || "MAX" in quality) {
                    "HI_RES_LOSSLESS"
                } else {
                    "LOSSLESS"
                }
            }
            ?: "LOSSLESS"

    private fun streamQualityCandidates(track: MatchedTrack): List<String> =
        buildList {
            add("HI_RES_LOSSLESS")
            add(track.requestedStreamQuality())
            add("LOSSLESS")
        }.distinct()

    private fun parseManifest(
        manifestB64: String,
        declaredMimeType: String?,
        durationMs: Long?,
    ): ParsedManifest {
        val text =
            String(
                Base64.decode(manifestB64.trim(), Base64.DEFAULT),
                Charsets.UTF_8,
            ).trim()
        if (text.isBlank()) {
            throw TidalAudioResolutionException("TIDAL FLAC manifest was empty")
        }
        if (text.startsWith("{")) {
            val root = JSONObject(text)
            val urls = root.optJSONArray("urls")
                ?: throw TidalAudioResolutionException("TIDAL FLAC manifest had no URLs")
            val directUrl = urls.firstStringOrNull()
                ?: throw TidalAudioResolutionException("TIDAL FLAC manifest URL was empty")
            val mimeType = root.stringOrNull("mimeType") ?: declaredMimeType ?: "audio/mp4"
            val codecs = root.stringOrNull("codecs") ?: if (mimeType.contains("flac", ignoreCase = true)) "flac" else "mp4a.40.2"
            val sampleRate = normalizeSampleRate(root.doubleOrNull("sampleRate"))
            val bitrate = root.longOrNull("bitrate")?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
                ?: root.longOrNull("bandwidth")?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
            return ParsedManifest(
                url = directUrl,
                mimeType = mimeType,
                codecs = codecs,
                sampleRate = sampleRate,
                bitrate = bitrate,
                expiryUrl = directUrl,
                isDash = false,
            )
        }
        if (text.contains("<MPD", ignoreCase = true)) {
            return parseDashManifest(text.sanitizeXmlEntities(), declaredMimeType)
        }
        throw TidalAudioResolutionException("TIDAL FLAC manifest format was not recognized")
    }

    private fun parseDashManifest(
        text: String,
        declaredMimeType: String?,
    ): ParsedManifest {
        if (
            !text.contains("<SegmentTemplate", ignoreCase = true) &&
            !text.contains("<SegmentList", ignoreCase = true) &&
            !text.contains("<SegmentBase", ignoreCase = true) &&
            !text.contains("<BaseURL", ignoreCase = true)
        ) {
            throw TidalAudioResolutionException("TIDAL DASH manifest had no playable segments")
        }
        val codecs = firstXmlAttr(text, "codecs").ifBlank { "flac" }
        val representationMime = firstXmlAttr(text, "mimeType")
        val sampleRate = firstXmlAttr(text, "audioSamplingRate").toIntOrNull()
        val bandwidth = xmlAttrValues(text, "bandwidth").maxOrNull()
        val firstSegmentUrl = Regex("""https?://[^"'<>\s]+""")
            .find(text)
            ?.value
            ?.xmlUnescape()
        return ParsedManifest(
            url = text.toDashDataUri(),
            mimeType = representationMime.ifBlank { declaredMimeType ?: "application/dash+xml" },
            codecs = codecs,
            sampleRate = sampleRate,
            bitrate = bandwidth,
            expiryUrl = firstSegmentUrl,
            isDash = true,
        )
    }

    private fun fetchStreamMetadata(url: String): StreamMetadata {
        val httpUrl = url.toHttpUrlOrNull() ?: return StreamMetadata("audio/flac", null)
        val builder =
            Request
                .Builder()
                .url(httpUrl)
                .header("Accept", "audio/flac,audio/mp4,audio/*,*/*;q=0.8")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", BROWSER_USER_AGENT)

        runCatching {
            client.newCall(builder.head().build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                StreamMetadata(
                    mimeType = response.header("Content-Type")?.substringBefore(';'),
                    contentLength = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L },
                )
            }
        }.getOrNull()?.let { return it }

        return runCatching {
            client.newCall(
                builder
                    .get()
                    .header("Range", "bytes=0-0")
                    .build(),
            ).execute().use { response ->
                StreamMetadata(
                    mimeType = response.header("Content-Type")?.substringBefore(';'),
                    contentLength =
                        response.header("Content-Range")
                            ?.substringAfterLast('/', missingDelimiterValue = "")
                            ?.toLongOrNull()
                            ?.takeIf { it > 0L }
                            ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L && response.code == 206 },
                )
            }
        }.getOrElse {
            StreamMetadata("audio/flac", null)
        }
    }

    private fun validateLikelyFullTrack(
        track: MatchedTrack,
        durationMs: Long?,
        contentLength: Long?,
    ) {
        val duration = durationMs?.takeIf { it >= 60_000L } ?: return
        val length = contentLength?.takeIf { it > 0L } ?: return
        val minimumBytesForFullTrack = (duration / 1000.0 * 96_000.0 / 8.0 * 0.70).toLong()
        if (length < minimumBytesForFullTrack) {
            throw TidalAudioResolutionException(
                "TIDAL returned a likely preview for ${track.title}: $length bytes for ${duration / 1000L}s",
            )
        }
    }

    private fun searchTerms(query: Query): List<String> =
        buildList {
            normalizeIsrc(query.isrc)?.let(::add)
            val artistText = query.artists.joinToString(" ").trim()
            add(listOf(query.title, artistText).filter { it.isNotBlank() }.joinToString(" "))
            query.album?.takeIf { it.isNotBlank() }?.let { album ->
                add(listOf(query.title, artistText, album).filter { it.isNotBlank() }.joinToString(" "))
            }
            add(query.title)
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun Query.toDirectMatchedTrack(trackId: String): MatchedTrack =
        MatchedTrack(
            trackId = trackId,
            title = title,
            artistNames = artists,
            album = album,
            isrc = isrc,
            durationMs = durationMs,
            audioQuality = null,
            sampleRate = null,
        )

    private fun resolveSongLinkTidalTrackId(query: Query): String? {
        for (sourceUrl in songLinkSourceUrls(query.mediaId)) {
            val url =
                SONG_LINK_API_URL
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("url", sourceUrl)
                    .build()
            val request =
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .header("Accept", "application/json")
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body.string().takeIf { it.isNotBlank() } ?: return@use null
                    val root = JSONObject(body)
                    root.optJSONObject("linksByPlatform")
                        ?.optJSONObject("tidal")
                        ?.stringOrNull("url")
                        ?.toTidalTrackIdOrNull()
                        ?: root.optJSONObject("songUrls")
                            ?.stringOrNull("Tidal")
                            ?.toTidalTrackIdOrNull()
                }
            }.getOrNull()?.let { trackId ->
                Timber.tag("TidalAudio").i("Resolved TIDAL track $trackId through song.link from $sourceUrl")
                return trackId
            }
        }
        return null
    }

    private fun songLinkSourceUrls(mediaId: String): List<String> {
        val trimmed = mediaId.trim()
        if (trimmed.isBlank()) return emptyList()
        return buildList {
            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                add(trimmed)
            }
            Regex("""spotify[:/](?:track[:/])?([A-Za-z0-9]{22})""", RegexOption.IGNORE_CASE)
                .find(trimmed)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { add("https://open.spotify.com/track/$it") }
            if (trimmed.matches(Regex("[A-Za-z0-9]{22}"))) {
                add("https://open.spotify.com/track/$trimmed")
            }
            if (trimmed.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                add("https://music.youtube.com/watch?v=$trimmed")
            }
        }.distinct()
    }

    private fun estimateBitrate(
        contentLength: Long?,
        durationMs: Long?,
    ): Int? {
        val length = contentLength?.takeIf { it > 0L } ?: return null
        val duration = durationMs?.takeIf { it > 0L } ?: return null
        val bitrate = (length * 8L * 1000L) / duration
        return bitrate
            .takeIf { it in 32_000L..20_000_000L }
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?.toInt()
    }

    private fun normalizeSampleRate(value: Double?): Int? {
        val sampleRate = value?.takeIf { it > 0.0 } ?: return null
        return when {
            sampleRate < 1000.0 -> (sampleRate * 1000.0).roundToInt()
            else -> sampleRate.roundToInt()
        }.takeIf { it > 0 }
    }

    private fun extractExpiryMs(
        url: String,
        now: Long,
    ): Long {
        val httpUrl = url.toHttpUrlOrNull() ?: return now + STREAM_CACHE_MS
        val expiresSeconds = httpUrl.queryParameterIgnoreCase("X-Amz-Expires")?.toLongOrNull()
        val date = httpUrl.queryParameterIgnoreCase("X-Amz-Date")
        if (expiresSeconds != null && date != null) {
            runCatching {
                val issuedAt = LocalDateTime.parse(date, AMAZON_DATE).toInstant(ZoneOffset.UTC).toEpochMilli()
                return (issuedAt + expiresSeconds * 1000L - 30_000L).coerceAtLeast(now + 60_000L)
            }
        }
        return now + STREAM_CACHE_MS
    }

    private fun JSONObject.toMatchedTrack(): MatchedTrack? {
        val trackId = stringOrNull("id") ?: return null
        val title = stringOrNull("title") ?: stringOrNull("name") ?: return null
        val artists = collectArtistNames()
        val album = optJSONObject("album")?.stringOrNull("title") ?: optJSONObject("album")?.stringOrNull("name")
        return MatchedTrack(
            trackId = trackId,
            title = title,
            artistNames = artists,
            album = album,
            isrc = stringOrNull("isrc"),
            durationMs = longOrNull("duration")?.takeIf { it > 0L }?.times(1000L),
            audioQuality = stringOrNull("audioQuality"),
            sampleRate = normalizeSampleRate(doubleOrNull("sampleRate")),
        )
    }

    private fun JSONObject.collectArtistNames(): List<String> {
        val names = mutableListOf<String>()
        optJSONArray("artists")?.let { array ->
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.stringOrNull("name")?.takeIf { it.isNotBlank() }?.let(names::add)
            }
        }
        optJSONObject("artist")?.stringOrNull("name")?.takeIf { it.isNotBlank() }?.let(names::add)
        return names.distinct()
    }

    private fun String.toTidalTrackIdOrNull(): String? {
        val trimmed = trim()
        if (trimmed.matches(Regex("\\d+"))) return trimmed
        Regex("""^tidal:track:(\d+)$""", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }
        Regex("""tidal\.com/(?:browse/)?track/(\d+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }
        return null
    }

    private fun String.trackCacheKeyFallback(): String = "direct:${trim().lowercase(Locale.US)}"

    private fun Query.trackCacheKey(): String =
        mediaId.toTidalTrackIdOrNull()
            ?.let { "direct:$it" }
            ?: listOf(
                title.normalized(),
                artists.joinToString(",") { it.normalized() },
                album.normalized(),
                durationMs?.div(1000L)?.toString().orEmpty(),
            ).joinToString("::")

    private fun String?.normalized(): String =
        this
            ?.lowercase(Locale.US)
            ?.let { Normalizer.normalize(it, Normalizer.Form.NFD) }
            ?.replace(Regex("\\p{Mn}+"), "")
            ?.replace(Regex("[^a-z0-9]+"), " ")
            ?.trim()
            .orEmpty()

    private fun significantTokens(value: String): Set<String> =
        value
            .split(' ')
            .map { it.trim() }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toSet()

    private fun tokenOverlap(
        wanted: Set<String>,
        candidate: Set<String>,
    ): Double {
        if (wanted.isEmpty() || candidate.isEmpty()) return 0.0
        val shared = wanted.intersect(candidate).size
        return shared.toDouble() / wanted.size.coerceAtLeast(candidate.size).toDouble()
    }

    private fun hasVersionMismatch(
        wanted: String,
        candidate: String,
    ): Boolean {
        val wantedLive = wanted.contains(" live ")
        val candidateLive = candidate.contains(" live ")
        if (wantedLive != candidateLive) return true
        val wantedInstrumental = wanted.contains(" instrumental ")
        val candidateInstrumental = candidate.contains(" instrumental ")
        return wantedInstrumental != candidateInstrumental
    }

    private fun JSONArray.firstStringOrNull(): String? {
        for (index in 0 until length()) {
            val value = optString(index).takeIf { it.isNotBlank() }
            if (value != null) return value
        }
        return null
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.longOrNull(key: String): Long? =
        if (has(key)) optLong(key).takeIf { it > 0L } else null

    private fun JSONObject.doubleOrNull(key: String): Double? =
        if (has(key)) optDouble(key).takeIf { it > 0.0 && !it.isNaN() } else null

    private fun HttpUrl.queryParameterIgnoreCase(name: String): String? {
        val key = queryParameterNames.firstOrNull { it.equals(name, ignoreCase = true) } ?: return null
        return queryParameter(key)
    }

    private fun firstXmlAttr(
        text: String,
        attr: String,
    ): String =
        Regex("""\b${Regex.escape(attr)}\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

    private fun xmlAttrValues(
        text: String,
        attr: String,
    ): List<Int> =
        Regex("""\b${Regex.escape(attr)}\s*=\s*"(\d+)"""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .toList()

    private fun String.xmlUnescape(): String =
        replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    private fun String.sanitizeXmlEntities(): String =
        replace(Regex("""&(?!amp;|lt;|gt;|quot;|apos;|#\d+;|#x[0-9a-fA-F]+;)"""), "&amp;")

    private fun String.toDashDataUri(): String =
        "data:application/dash+xml;base64," +
            Base64.encodeToString(toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private val STOP_WORDS =
        setOf(
            "the",
            "and",
            "feat",
            "ft",
            "with",
            "remaster",
            "remastered",
            "version",
            "explicit",
            "clean",
            "audio",
            "official",
        )
}
