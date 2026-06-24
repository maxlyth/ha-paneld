package io.github.maxlyth.hapaneld.input

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import io.github.maxlyth.hapaneld.device.EvdevButton
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Streams hardware-button events from the root helper daemon (`hapaneld-ledd`, abstract UNIX socket
 * `@hapaneld-ledd`) for
 * keys the Android input pipeline doesn't usefully deliver to the app (e.g. a `KEY_MICMUTE` adc-key,
 * or the power key). On a background thread it connects, sends `WATCH <node> <grab>` for each
 * [EvdevButton] — grabbing where asked, so e.g. the WF1589T power key no longer sleeps the panel —
 * then `SUBSCRIBE`, and maps each `KEY <code> <value>` line (on DOWN) to the button's HA `event_type`
 * via [ButtonBus] (the same path the accessibility key capture uses). Reconnects with a short backoff
 * if the daemon isn't up yet or restarts, so the grab is re-established automatically.
 */
object EvdevButtonClient {
    private const val SOCK = "hapaneld-ledd"   // abstract socket name; matches SOCK_NAME in ledd.c
    private const val TAG = "ha-paneld/evdev"

    @Volatile private var thread: Thread? = null

    /** Idempotent. No-op when [buttons] is empty (panels with no evdev-instrumented buttons). */
    @Synchronized
    fun start(buttons: List<EvdevButton>) {
        if (thread != null || buttons.isEmpty()) return
        thread = Thread({ run(buttons) }, "evdev-buttons").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun run(buttons: List<EvdevButton>) {
        while (!Thread.currentThread().isInterrupted) {
            try {
                LocalSocket().use { s ->
                    s.connect(LocalSocketAddress(SOCK, LocalSocketAddress.Namespace.ABSTRACT))
                    val out = s.outputStream
                    val br = BufferedReader(InputStreamReader(s.inputStream))
                    // Establish the watches (idempotent in the daemon) then subscribe to the stream.
                    buttons.forEach { out.write("WATCH ${it.node} ${if (it.grab) 1 else 0}\n".toByteArray()) }
                    out.write("SUBSCRIBE\n".toByteArray())
                    out.flush()
                    var line = br.readLine()
                    while (line != null && !Thread.currentThread().isInterrupted) {
                        // "KEY <code> <value>" (momentary) or "SW <code> <value>" (latching switch).
                        val p = line.trim().split(" ")
                        if (p.size == 3 && (p[0] == "KEY" || p[0] == "SW")) {
                            val isSw = p[0] == "SW"
                            val code = p[1].toIntOrNull()
                            val down = p[2] == "1"
                            buttons.firstOrNull { it.code == code && it.sw == isSw }?.let { b ->
                                // KEY: emit on DOWN; SW: emit on every toggle (each press flips it).
                                if (isSw || down) ButtonBus.emit(b.eventType)
                            }
                        }
                        line = br.readLine()
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "evdev stream unavailable (${e.message}); retrying")
            }
            if (Thread.currentThread().isInterrupted) break
            try { Thread.sleep(3000) } catch (e: InterruptedException) { break }  // backoff before reconnect
        }
    }
}
