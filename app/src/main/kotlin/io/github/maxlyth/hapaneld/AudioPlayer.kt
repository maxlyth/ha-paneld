package io.github.maxlyth.hapaneld

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Downloads an audio URL and plays it through the panel speaker. Mirrors the bash reference
 * implementation (`curl -sLk … | sox play`): the `-k` insecure flag is reproduced here because
 * the HA internal_url often serves a self-signed cert and the panels live on a trusted LAN.
 */
object AudioPlayer {
    private const val TAG = "ha-paneld/audio"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Download [url] to a temp file then play it. Blocking download on IO dispatcher. */
    suspend fun play(cacheDir: File, url: String) = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("audio", ".media", cacheDir)
        try {
            download(url, tmp)
            playAndAwait(tmp)
        } catch (e: Exception) {
            Log.w(TAG, "play failed for $url", e)
            tmp.delete()
        }
    }

    private fun download(url: String, dest: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }
        if (conn is HttpsURLConnection) {
            // LAN-trust: accept self-signed certs, matching the `curl -k` reference contract.
            conn.sslSocketFactory = trustAllFactory()
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
    }

    /**
     * Plays [file] and suspends until playback completes (or errors). Suspending is what keeps a
     * strong reference to the MediaPlayer for the whole playback — an earlier version let the
     * player fall out of scope after start(), so GC finalised it mid-playback and cut longer clips
     * short (confirmed on an NSPanel Pro, 2026-06-03: a 7s TTS render was truncated to ~2.4s).
     */
    private suspend fun playAndAwait(file: File) = suspendCancellableCoroutine<Unit> { cont ->
        val player = MediaPlayer().apply {
            // USAGE_MEDIA -> STREAM_MUSIC, matching the bash reference (`sox play`). The earlier
            // USAGE_ASSISTANCE_ACCESSIBILITY routed to the accessibility stream, which is separate
            // from — and far quieter than — the music volume the panels actually have turned up
            // (confirmed near-inaudible on an NSPanel Pro, 2026-06-03 on-device test).
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setVolume(1f, 1f)
            setOnCompletionListener {
                it.release()
                file.delete()
                if (cont.isActive) cont.resume(Unit)
            }
            setOnErrorListener { mp, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                mp.release()
                file.delete()
                if (cont.isActive) cont.resume(Unit)
                true
            }
            setDataSource(file.absolutePath)
            prepare()
            start()
        }
        Log.d(TAG, "playing ${file.name} (${file.length()} bytes)")
        cont.invokeOnCancellation { runCatching { player.release() } }
    }

    private fun trustAllFactory() = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
        }), java.security.SecureRandom())
    }.socketFactory
}
