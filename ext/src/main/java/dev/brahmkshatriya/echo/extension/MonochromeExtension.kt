package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.AlbumClient as EchoAlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient as EchoArtistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient as EchoPlaylistClient
import dev.brahmkshatriya.echo.common.settings.*
import dev.brahmkshatriya.echo.extension.monochrome.*
import okhttp3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.concurrent.TimeUnit

private val stealthInterceptor = Interceptor { chain ->
    val request = chain.request().newBuilder()
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
        .header("Accept", "application/json, text/plain, */*")
        .header("Accept-Language", "en-US,en;q=0.9")
        .header("Sec-Fetch-Mode", "cors")
        .header("Referer", "https://monochrome.tf/")
        .build()
    chain.proceed(request)
}

private val chromeConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
    .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
    .cipherSuites(
        CipherSuite.TLS_AES_128_GCM_SHA256,
        CipherSuite.TLS_AES_256_GCM_SHA384,
        CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
    )
    .build()

private val customClient = OkHttpClient.Builder()
    .protocols(listOf(Protocol.HTTP_1_1))
    .retryOnConnectionFailure(true)
    .connectionSpecs(listOf(chromeConnectionSpec, ConnectionSpec.CLEARTEXT))
    .addInterceptor(stealthInterceptor)
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(45, TimeUnit.SECONDS)
    .build()

class MonochromeExtension : ExtensionClient,
    SearchFeedClient by SearchClient(customClient),
    TrackClient by AudioClient(customClient),
    EchoAlbumClient by AlbumClient(customClient),
    EchoArtistClient by ArtistClient(customClient),
    EchoPlaylistClient by PlaylistClient(customClient) 
{
    private var instancesCache: List<Pair<String, String>>? = null

    override fun setSettings(settings: Settings) {
        val savedUrl = settings.getString("monochrome_instance")
        if (savedUrl != null) {
            MonochromePreferences.currentBaseUrl = savedUrl
        }
    }

    override suspend fun getSettingItems(): List<Setting> {
        val instances = fetchInstances()
        
        val entryTitles = instances.map { it.first }
        val entryValues = instances.map { it.second }
        
        val currentIndex = entryValues.indexOf(MonochromePreferences.currentBaseUrl).let {
            if (it == -1) 0 else it
        }

        val settingList = SettingList(
            title = "Monochrome Instance",
            key = "monochrome_instance",
            summary = "Select an active Monochrome instance. Healthy instances are marked with 🟢, unreachable ones with 🔴.",
            entryTitles = entryTitles,
            entryValues = entryValues,
            defaultEntryIndex = currentIndex
        )

        return listOf(settingList)
    }

    private suspend fun fetchInstances(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        if (instancesCache != null) return@withContext instancesCache!!

        val list = mutableListOf<Pair<String, String>>()
        try {
            val request = Request.Builder().url("https://tidal-uptime.jiffy-puffs-1j.workers.dev/").build()
            customClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val root = Json.parseToJsonElement(response.body!!.string()).jsonObject
                    
                    // 1. Parse Healthy "API" Instances
                    val apiNodes = root["api"]?.jsonArray ?: emptyList()
                    for (node in apiNodes) {
                        val url = node.jsonObject["url"]?.jsonPrimitive?.content ?: continue
                        val version = node.jsonObject["version"]?.jsonPrimitive?.content ?: "Unknown"
                        val host = url.replace("https://", "").replace("http://", "")
                        list.add("🟢 $host (v$version)" to url)
                    }

                    // 2. Parse "Down" Instances
                    val downNodes = root["down"]?.jsonArray ?: emptyList()
                    for (node in downNodes) {
                        val url = node.jsonObject["url"]?.jsonPrimitive?.content ?: continue
                        val error = node.jsonObject["error"]?.jsonPrimitive?.content ?: "Unknown Error"
                        val host = url.replace("https://", "").replace("http://", "")
                        list.add("🔴 $host (Down: $error)" to url)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        instancesCache = list
        list
    }
}
