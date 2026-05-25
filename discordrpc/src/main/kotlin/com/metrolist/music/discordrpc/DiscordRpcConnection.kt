package com.metrolist.music.discordrpc

import com.metrolist.music.discordrpc.entities.Activity
import com.metrolist.music.discordrpc.entities.Assets
import com.metrolist.music.discordrpc.entities.Button
import com.metrolist.music.discordrpc.entities.Metadata
import com.metrolist.music.discordrpc.entities.Timestamps
import com.metrolist.music.discordrpc.entities.Presence
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import org.json.JSONObject
import timber.log.Timber

enum class ActivityType(val value: Int) {
    PLAYING(0),
    STREAMING(1),
    LISTENING(2),
    WATCHING(3),
    COMPETING(5),
}

class DiscordRpcConnection(
    private val token: String,
    os: String = "Android",
    browser: String = "Discord Android",
    device: String = "Generic Android Device",
    private val userAgent: String = "Discord-Android/314013;RNA",
    private val superPropertiesBase64: String? = null,
) {
    private val tag = "DiscordRpc"
    private val gateway = GatewayWebSocket(token, os, browser, device)
    fun isRunning(): Boolean = gateway.isSessionEstablished()

    fun connect() {
        Timber.tag(tag).i("connect() called")
        gateway.connect()
    }

    suspend fun setActivity(
        name: String?,
        type: ActivityType = ActivityType.LISTENING,
        state: String? = null,
        details: String? = null,
        timestamps: Timestamps? = null,
        largeImage: String? = null,
        largeText: String? = null,
        smallImage: String? = null,
        smallText: String? = null,
        buttons: List<Button>? = null,
        status: String = "online",
        since: Long? = null,
        applicationId: String? = null,
    ) {
        Timber.tag(tag).i("setActivity: type=$type state=$state details=$details buttons=${buttons?.size}")

        if (!isRunning()) {
            Timber.tag(tag).d("setActivity: gateway not running, connecting...")
            gateway.connect()
        }

        val resolvedApplicationId = applicationId ?: APPLICATION_ID
        val resolvedLargeImage = largeImage?.let {
            Timber.tag(tag).v("Resolving large image: $it")
            resolveImage(it).also { result ->
                Timber.tag(tag).v("Large image resolved: $result")
            }
        }
        val resolvedSmallImage = smallImage?.let {
            Timber.tag(tag).v("Resolving small image: $it")
            resolveImage(it).also { result ->
                Timber.tag(tag).v("Small image resolved: $result")
            }
        }

        val buttonLabels = buttons?.map { it.label }?.takeIf { it.isNotEmpty() }
        val buttonUrls = buttons?.map { it.url }?.takeIf { it.isNotEmpty() }

        Timber.tag(tag).d("Sending presence update to gateway...")
        gateway.updatePresence(
            Presence(
                activities = listOf(
                    Activity(
                        name = name ?: "Metrolist",
                        type = type.value,
                        applicationId = resolvedApplicationId,
                        state = state,
                        details = details,
                        timestamps = timestamps,
                        assets = if (resolvedLargeImage != null || resolvedSmallImage != null) {
                            Assets(
                                largeImage = resolvedLargeImage,
                                largeText = largeText,
                                smallImage = resolvedSmallImage,
                                smallText = smallText,
                            )
                        } else null,
                        buttons = buttonLabels,
                        metadata = buttonUrls?.let { Metadata(buttonUrls = it) },
                    ),
                ),
                since = since,
                status = status,
                afk = false,
            ),
        )
        Timber.tag(tag).i("setActivity completed")
    }

    suspend fun clearActivity(status: String = "online") {
        if (isRunning()) {
            Timber.tag(tag).i("Clearing activity")
            gateway.clearPresence()
        }
    }

    suspend fun close() {
        Timber.tag(tag).i("close() called")
        clearActivity()
        gateway.close()
    }

    fun closeDirect() {
        Timber.tag(tag).i("closeDirect() called")
        gateway.close()
    }

    private fun resolveImage(image: String): String? {
        val normalizedImage = image.trim()
        if (normalizedImage.isBlank()) return null

        return if (normalizedImage.startsWith("mp:")) {
            Timber.tag(tag).d("Image already mp: $normalizedImage")
            normalizedImage
        } else if (normalizedImage.startsWith("http", ignoreCase = true)) {
            Timber.tag(tag).d("Using external image URL directly: $normalizedImage")
            normalizedImage
        } else {
            "mp:$normalizedImage"
        }
    }

    companion object {
        private const val APPLICATION_ID = "1411019391843172514"
        private const val TAG = "DiscordRpc"

        suspend fun getUserInfo(
            token: String,
            userAgent: String = SuperProperties.userAgent,
            superPropertiesBase64: String? = null,
        ): Result<UserInfo> = runCatching {
            Timber.tag(TAG).i("Fetching user info from Discord API...")
            val client = HttpClient()
            try {
                val response = client.get("https://discord.com/api/v9/users/@me") {
                    header("Authorization", token)
                    header("User-Agent", userAgent)
                    if (superPropertiesBase64 != null) {
                        header("X-Super-Properties", superPropertiesBase64)
                    }
                }
                val text = response.bodyAsText()
                val json = JSONObject(text)
                val id = json.getString("id")
                val username = json.getString("username")
                val name = json.optString("global_name", username)
                val avatarHash = json.optString("avatar")
                val avatar = if (avatarHash.isNotEmpty() && avatarHash != "null") {
                    "https://cdn.discordapp.com/avatars/$id/$avatarHash.png"
                } else null
                Timber.tag(TAG).i("User info fetched successfully")
                UserInfo(id, username, name, avatar)
            } finally {
                client.close()
            }
        }
    }
}
