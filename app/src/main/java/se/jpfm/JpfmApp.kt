package se.jpfm

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import se.jpfm.download.Downloads
import se.jpfm.playback.QueueBuilder
import se.jpfm.playback.QueueStore
import se.jpfm.playback.StreamResolver
import se.jpfm.plex.Library
import se.jpfm.plex.PlexClient
import se.jpfm.plex.PlexSession
import se.jpfm.update.Updater
import java.io.File
import java.util.concurrent.TimeUnit

class JpfmApp : Application(), ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.start()
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(graph.http)
        .crossfade(220)
        .respectCacheHeaders(false)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(400L * 1024 * 1024).build() }
        .build()
}

/** Appens delar, skapade en gång. Tjänsten och gränssnittet delar på dem. */
class AppGraph(val app: JpfmApp) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val prefs = Prefs(app)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val client = PlexClient(prefs.clientId, http)
    val session = PlexSession(prefs, client, scope)
    val library = Library(session)
    val network = NetworkMonitor(app)

    /** Hämtar musik från servern, med appens egna adresser översatta (se [StreamResolver]). */
    val upstreamFactory: DataSource.Factory = ResolvingDataSource.Factory(
        OkHttpDataSource.Factory(http).setUserAgent("JPFM/${BuildConfig.VERSION_NAME}"),
        StreamResolver(session, client),
    )

    val downloads = Downloads(app, scope)

    /** Spelaren läser först från nedladdat, sedan från servern. Strömmen skrivs inte till nedladdat. */
    val playbackDataSourceFactory: DataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(downloads.cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    val queueStore = QueueStore(app.filesDir)
    val queues = QueueBuilder(this)
    val updater = Updater(app, http)

    fun start() {
        downloads.init(upstreamFactory)
        session.start()
        network.start { session.onNetworkChanged() }
        scope.launch(Dispatchers.IO) { pruneArtCache() }
    }

    /** Kvaliteten för nätet just nu. */
    fun currentQuality(): Quality {
        val s = prefs.settings.value
        return if (network.metered.value) s.mobileQuality else s.wifiQuality
    }

    private fun pruneArtCache() {
        val dir = File(app.cacheDir, "art")
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in files) {
            total += f.length()
            if (total > 150L * 1024 * 1024) f.delete()
        }
    }
}

/** Håller koll på nätet: om det finns, om det kostar (mobildata) och när det byts. */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _metered = MutableStateFlow(cm?.isActiveNetworkMetered ?: false)
    val metered: StateFlow<Boolean> = _metered.asStateFlow()
    private val _online = MutableStateFlow(cm?.activeNetwork != null)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var debounce: Job? = null

    fun start(onChange: () -> Unit) {
        val manager = cm ?: return
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _online.value = true
                changed(onChange)
            }

            override fun onLost(network: Network) {
                _online.value = false
                changed(onChange)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val m = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                if (m != _metered.value) {
                    _metered.value = m
                    changed(onChange)
                }
            }
        })
    }

    private fun changed(onChange: () -> Unit) {
        debounce?.cancel()
        debounce = scope.launch {
            delay(1500)
            onChange()
        }
    }
}
