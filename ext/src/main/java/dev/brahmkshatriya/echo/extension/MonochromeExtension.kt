package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.AlbumClient as EchoAlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient as EchoArtistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient as EchoPlaylistClient
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.monochrome.*
import okhttp3.*
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
    override suspend fun getSettingItems(): List<Setting> = emptyList()
    override fun setSettings(settings: Settings) {}
}
