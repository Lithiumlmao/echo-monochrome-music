package dev.brahmkshatriya.echo.extension.monochrome

import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.helpers.*
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class SearchClient(
    private val client: OkHttpClient,
) : SearchFeedClient {

    private val baseUrl: String
        get() = MonochromePreferences.currentBaseUrl

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val tabs = listOf(
            Tab("all", "All"),
            Tab("track", "Tracks"),
            Tab("artist", "Artists"),
            Tab("album", "Albums"),
            Tab("playlist", "Playlists")
        )

        return Feed(tabs) { tab ->
            Feed.Data(PagedData.Single {
                withContext(Dispatchers.IO) {
                    val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                    val shelves = mutableListOf<Shelf>()

                    when (tab?.id) {
                        "track" -> fetchTracks("$baseUrl/search/?s=$encoded&limit=25")?.let { shelves.add(it) }
                        "artist" -> fetchArtists("$baseUrl/search/?a=$encoded&limit=25")?.let { shelves.add(it) }
                        "album" -> fetchAlbums("$baseUrl/search/?al=$encoded&limit=25")?.let { shelves.add(it) }
                        "playlist" -> fetchPlaylists("$baseUrl/search/?p=$encoded&limit=25")?.let { shelves.add(it) }
                        else -> {
                            fetchTracks("$baseUrl/search/?s=$encoded&limit=12")?.let { shelves.add(it) }
                            fetchArtists("$baseUrl/search/?a=$encoded&limit=8")?.let { shelves.add(it) }
                            fetchAlbums("$baseUrl/search/?al=$encoded&limit=8")?.let { shelves.add(it) }
                            fetchPlaylists("$baseUrl/search/?p=$encoded&limit=8")?.let { shelves.add(it) }
                        }
                    }
                    shelves
                }
            })
        }
    }

    private suspend fun fetchTracks(url: String): Shelf? {
        val items = fetchItems(url, "tracks")
        val list = items.mapNotNull { parseTrack(it.jsonObject) }
        return if (list.isNotEmpty()) Shelf.Lists.Tracks("tracks", "Tracks", list) else null
    }

    private suspend fun fetchArtists(url: String): Shelf? {
        val items = fetchItems(url, "artists")
        val list = items.mapNotNull { parseArtist(it.jsonObject) }
        return if (list.isNotEmpty()) Shelf.Lists.Items("artists", "Artists", list) else null
    }

    private suspend fun fetchAlbums(url: String): Shelf? {
        val items = fetchItems(url, "albums")
        val list = items.mapNotNull { parseAlbum(it.jsonObject) }
        return if (list.isNotEmpty()) Shelf.Lists.Items("albums", "Albums", list) else null
    }

    private suspend fun fetchPlaylists(url: String): Shelf? {
        val items = fetchItems(url, "playlists")
        val list = items.mapNotNull { parsePlaylist(it.jsonObject) }
        return if (list.isNotEmpty()) Shelf.Lists.Items("playlists", "Playlists", list) else null
    }

    private suspend fun fetchItems(url: String, expectedKey: String): JsonArray {
        val request = Request.Builder().url(url).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return JsonArray(emptyList())

                val root = Json.parseToJsonElement(response.body!!.string()).jsonObject
                val data = root["data"] ?: return JsonArray(emptyList())

                if (data is JsonArray) return data
                val dataObj = data.jsonObject
                
                val specificNode = dataObj[expectedKey]
                if (specificNode != null) {
                    if (specificNode is JsonArray) return specificNode
                    if (specificNode is JsonObject) {
                        val items = specificNode["items"]
                        if (items is JsonArray) return items
                    }
                }

                val genericNode = dataObj["items"] ?: dataObj["results"]
                if (genericNode is JsonArray) return genericNode

                JsonArray(emptyList())
            }
        } catch (e: Exception) { JsonArray(emptyList()) }
    }

    // --- SMART URL BUILDER ---
    private fun buildCoverUrl(uuidOrUrl: String?, resolution: String = "320x320"): String? {
        if (uuidOrUrl.isNullOrBlank()) return null
        // If the API gave us a ready-to-use URL, just return it.
        if (uuidOrUrl.startsWith("http")) return uuidOrUrl
        // Otherwise, construct the Tidal CDN link.
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

    private fun parseArtist(obj: JsonObject): Artist? {
        val id = obj["id"]?.jsonPrimitive?.content ?: obj["uuid"]?.jsonPrimitive?.content ?: return null
        val name = obj["name"]?.jsonPrimitive?.content ?: obj["title"]?.jsonPrimitive?.content ?: "Unknown Artist"
        
        val pictureUuid = obj["picture"]?.jsonPrimitive?.content 
            ?: obj["selectedAlbumCoverFallback"]?.jsonPrimitive?.content
            ?: obj["cover"]?.jsonPrimitive?.content
            ?: obj["image"]?.jsonPrimitive?.content
            
        val coverUrl = buildCoverUrl(pictureUuid, "320x320")
        val cover = coverUrl?.let { ImageHolder.NetworkRequestImageHolder(NetworkRequest(it), false) }
        
        return Artist(id = id, name = name, cover = cover)
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

    private fun parsePlaylist(obj: JsonObject): Playlist? {
        val id = obj["uuid"]?.jsonPrimitive?.content ?: obj["id"]?.jsonPrimitive?.content ?: return null
        val title = obj["title"]?.jsonPrimitive?.content ?: obj["name"]?.jsonPrimitive?.content ?: "Unknown"
        
        // FIXED: Prioritize 'squareImage' first for playlists, and added fallback to 'picture'/'image'
        val coverUuid = obj["squareImage"]?.jsonPrimitive?.content 
            ?: obj["picture"]?.jsonPrimitive?.content 
            ?: obj["image"]?.jsonPrimitive?.content 
            ?: obj["cover"]?.jsonPrimitive?.content
            
        val coverUrl = buildCoverUrl(coverUuid, "320x320")
        val cover = coverUrl?.let { ImageHolder.NetworkRequestImageHolder(NetworkRequest(it), false) }

        return Playlist(id = id, title = title, cover = cover, isEditable = false)
    }
}
