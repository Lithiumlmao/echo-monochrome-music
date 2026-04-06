package dev.brahmkshatriya.echo.extension.monochrome

import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.helpers.*
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class AlbumClient(
    private val client: OkHttpClient,
) : dev.brahmkshatriya.echo.common.clients.AlbumClient {

    private val baseUrl: String
        get() = MonochromePreferences.currentBaseUrl

    override suspend fun loadAlbum(album: Album): Album = album

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null

    override suspend fun loadTracks(album: Album): Feed<Track>? = withContext(Dispatchers.IO) {
        // FIXED: Replaced null with emptyList()
        Feed(emptyList()) { _ ->
            Feed.Data(PagedData.Single {
                val request = Request.Builder().url("$baseUrl/album/?id=${album.id}").build()
                val tracks = mutableListOf<Track>()
                
                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val root = Json.parseToJsonElement(response.body!!.string()).jsonObject
                            val data = root["data"]?.jsonObject ?: root
                            val items = data["items"]?.jsonArray ?: data["tracks"]?.jsonArray ?: JsonArray(emptyList())
                            
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
