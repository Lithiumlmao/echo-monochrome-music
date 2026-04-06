package dev.brahmkshatriya.echo.extension.monochrome

import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.helpers.*
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class ArtistClient(
    private val client: OkHttpClient,
) : dev.brahmkshatriya.echo.common.clients.ArtistClient {

    private val baseUrl: String
        get() = MonochromePreferences.currentBaseUrl

    override suspend fun loadArtist(artist: Artist): Artist = artist

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> = withContext(Dispatchers.IO) {
        Feed(emptyList()) { _ ->
            Feed.Data(PagedData.Single {
                val shelves = mutableListOf<Shelf>()
                
                try {
                    // CRITICAL FIX: The proxy uses a single /artist/ endpoint that contains both tracks and albums
                    val req = Request.Builder().url("$baseUrl/artist/?id=${artist.id}").build()
                    client.newCall(req).execute().use { response ->
                        if (response.isSuccessful) {
                            val root = Json.parseToJsonElement(response.body!!.string()).jsonObject
                            val data = root["data"]?.jsonObject ?: root

                            // 1. Extract Top Tracks
                            val topTracksNode = data["topTracks"]?.jsonObject
                            val topTrackItems = topTracksNode?.get("items")?.jsonArray ?: JsonArray(emptyList())
                            val tracks = topTrackItems.mapNotNull { element ->
                                // Safely unwrap the "item" envelope if it exists
                                val itemObj = element.jsonObject["item"]?.jsonObject ?: element.jsonObject
                                parseTrack(itemObj)
                            }
                            if (tracks.isNotEmpty()) {
                                shelves.add(Shelf.Lists.Tracks("top_tracks", "Top Tracks", tracks))
                            }

                            // 2. Extract Albums
                            val albumsNode = data["albums"]?.jsonObject
                            val albumItems = albumsNode?.get("items")?.jsonArray ?: JsonArray(emptyList())
                            val albums = albumItems.mapNotNull { element ->
                                // Safely unwrap the "item" envelope if it exists
                                val itemObj = element.jsonObject["item"]?.jsonObject ?: element.jsonObject
                                parseAlbum(itemObj)
                            }
                            if (albums.isNotEmpty()) {
                                shelves.add(Shelf.Lists.Items("albums", "Albums", albums))
                            }
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
                
                shelves
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

    private fun parseAlbum(obj: JsonObject): Album? {
        val id = obj["id"]?.jsonPrimitive?.content ?: obj["uuid"]?.jsonPrimitive?.content ?: return null
        val title = obj["title"]?.jsonPrimitive?.content ?: obj["name"]?.jsonPrimitive?.content ?: "Unknown"
        
        val coverUuid = obj["cover"]?.jsonPrimitive?.content ?: obj["picture"]?.jsonPrimitive?.content ?: obj["image"]?.jsonPrimitive?.content
        val coverUrl = buildCoverUrl(coverUuid, "320x320")
        val cover = coverUrl?.let { ImageHolder.NetworkRequestImageHolder(NetworkRequest(it), false) }

        val artistsArray = obj["artists"]?.jsonArray ?: obj["artist"]?.let { JsonArray(listOf(it)) }
        val artists = artistsArray?.mapNotNull { a ->
            val name = a.jsonObject["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            Artist(id = name, name = name)
        } ?: emptyList()

        return Album(id = id, title = title, artists = artists, cover = cover)
    }
}
