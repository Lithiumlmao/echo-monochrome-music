package dev.brahmkshatriya.echo.extension.monochrome

import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.helpers.*
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class PlaylistClient(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://hifi-one.spotisaver.net"
) : dev.brahmkshatriya.echo.common.clients.PlaylistClient {

    override suspend fun loadPlaylist(playlist: Playlist): Playlist = playlist

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = null

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> = withContext(Dispatchers.IO) {
        Feed(emptyList()) { _ ->
            Feed.Data(PagedData.Single {
                // CRITICAL FIX: The parameter is 'id', not 'uuid' according to the docs!
                val request = Request.Builder().url("$baseUrl/playlist/?id=${playlist.id}").build()
                val tracks = mutableListOf<Track>()
                
                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val root = Json.parseToJsonElement(response.body!!.string()).jsonObject
                            // Deep-dive fallback just in case Tidal nests it
                            val data = root["data"]?.jsonObject ?: root
                            val data2 = data["data"]?.jsonObject ?: data
                            val items = data2["items"]?.jsonArray ?: data2["tracks"]?.jsonArray ?: JsonArray(emptyList())
                            
                            tracks.addAll(items.mapNotNull { element ->
                                val itemObj = element.jsonObject["item"]?.jsonObject ?: element.jsonObject
                                parseTrack(itemObj)
                            })
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
                
                tracks
            })
        }
    }

    private fun buildCoverUrl(uuidOrUrl: String?, resolution: String = "640x640"): String? {
        if (uuidOrUrl.isNullOrBlank()) return null
        if (uuidOrUrl.startsWith("http")) return uuidOrUrl
        return "https://resources.tidal.com/images/${uuidOrUrl.replace("-", "/")}/$resolution.jpg"
    }

    private fun parseTrack(obj: JsonObject): Track? {
        val id = obj["id"]?.jsonPrimitive?.content ?: obj["uuid"]?.jsonPrimitive?.content ?: return null
        val title = obj["title"]?.jsonPrimitive?.content ?: obj["name"]?.jsonPrimitive?.content ?: "Unknown"

        val artistsArray = obj["artists"]?.jsonArray ?: obj["artist"]?.let { JsonArray(listOf(it)) } ?: JsonArray(emptyList())
        val artists = artistsArray.mapNotNull { a ->
            val name = a.jsonObject["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            Artist(id = name, name = name)
        }.ifEmpty { listOf(Artist("unknown", "Unknown Artist")) }

        val coverUuid = obj["album"]?.jsonObject?.get("cover")?.jsonPrimitive?.content
        val coverUrl = buildCoverUrl(coverUuid, "640x640")
        val cover = coverUrl?.let { ImageHolder.NetworkRequestImageHolder(NetworkRequest(it), false) }

        val duration = obj["duration"]?.jsonPrimitive?.content?.toLongOrNull()?.times(1000)

        return Track(id = id, title = title, artists = artists, cover = cover, duration = duration)
    }
}
