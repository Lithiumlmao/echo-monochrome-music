package dev.brahmkshatriya.echo.extension.monochrome

import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.helpers.*
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.Base64

class AudioClient(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://hifi-one.spotisaver.net"
) : TrackClient {

    private val placeholderPrefix = "monochrome:"

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        val qualities = listOf(
            "LOSSLESS" to "Lossless (16-bit)",
            "HIGH" to "AAC 320kbps",
            "LOW" to "AAC 96kbps"
        )

        val streamables = qualities.mapIndexed { index, (apiName, displayName) ->
            Streamable.server(
                id = placeholderPrefix + track.id + ":" + apiName,
                quality = 30 - index,
                title = displayName
            )
        }

        return track.copy(streamables = streamables)
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media =
        withContext(Dispatchers.IO) {
            if (!streamable.id.startsWith(placeholderPrefix)) {
                throw Exception("Invalid streamable ID")
            }

            val parts = streamable.id.removePrefix(placeholderPrefix).split(":")
            val trackId = parts[0]
            val quality = parts.getOrNull(1) ?: "LOSSLESS"

            val url = "$baseUrl/track/?id=$trackId&quality=$quality"
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No body"
                    throw Exception("API error ${response.code} — $errorBody")
                }

                val json = Json.parseToJsonElement(response.body!!.string()).jsonObject
                val data = json["data"]?.jsonObject ?: throw Exception("Missing data block")

                var b64 = data["manifest"]?.jsonPrimitive?.content ?: throw Exception("Missing manifest")
                val mimeType = data["manifestMimeType"]?.jsonPrimitive?.content

                while (b64.length % 4 != 0) b64 += "="

                val streamUrl = if (mimeType == "application/dash+xml") {
                    "data:application/dash+xml;base64,$b64"
                } else {
                    val decoded = try {
                        String(Base64.getDecoder().decode(b64))
                    } catch (e: Exception) {
                        String(Base64.getUrlDecoder().decode(b64))
                    }
                    val manifestJson = Json.parseToJsonElement(decoded).jsonObject
                    manifestJson["urls"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
                        ?: throw Exception("No stream URL found")
                }

                Streamable.Media.Server(
                    sources = listOf(
                        Streamable.Source.Http(
                            request = NetworkRequest(streamUrl),
                            title = streamable.title ?: quality
                        )
                    ),
                    merged = false
                )
            }
        }
}