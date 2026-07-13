package io.github.maxlyth.hapaneld.input

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import io.github.maxlyth.hapaneld.device.EvdevButton
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Streams hardware-button events from the root helper daemon (`hapaneld-helper`, abstract UNIX socket
 * `@hapaneld-helper`) for
 * keys the Android input pipeline doesn't usefully deliver to the app (e.g. a `KEY_MICMUTE` adc-key,
 * or the power key). On a background thread it connects, sends `WATCH <node> <grab>` for each
 * [EvdevButton] — grabbing where asked, so e.g. the WF1589T power key no longer sleeps the panel —
 * then `SUBSCRIBE`, and maps each `KEY <code> <value>` line (on DOWN) to the button's HA `event_type`
 * via [ButtonBus] (the same path the accessibility key capture uses). Reconnects with a short backoff
 * if the daemon isn't up yet or restarts, so the grab is re-established automatically.
 */
object EvdevButtonClient {
    private const val SOCK = "hapaneld-helper"   // abstract socket name; matches SOCK_NAME in main.c
    private const val TAG = "ha-paneld/evdev"

    private class Run(val buttons: List<EvdevButton>) {
        @Volatile var socket: LocalSocket? = null
        lateinit var thread: Thread

        fun attach(candidate: LocalSocket): Boolean = synchronized(this) {
            if (thread.isInterrupted) {
                runCatching { candidate.close() }
                false
            } else {
                socket = candidate
                true
            }
        }

        fun detach(candidate: LocalSocket) = synchronized(this) {
            if (socket === candidate) socket = null
        }

        fun cancelAndJoin() {
            thread.interrupt()
            synchronized(this) {
                runCatching { socket?.close() } // interruption alone does not unblock LocalSocket readLine()
                socket = null
            }
            runCatching { thread.join(STOP_JOIN_MS) }
        }
    }

    @Volatile private var active: Run? = null

    /** Idempotent. No-op when [buttons] is empty (panels with no evdev-instrumented buttons). */
    @Synchronized
    fun start(buttons: List<EvdevButton>) {
        if (active != null || buttons.isEmpty()) return
        val r = Run(buttons)
        r.thread = Thread({ run(r) }, "evdev-buttons").apply { isDaemon = true }
        active = r
        r.thread.start()
    }

    fun stop() {
        // Keep the run published while it is being cancelled so a concurrent service start cannot
        // admit a second socket reader before the first has actually left readLine().
        val r = synchronized(this) { active }
        r?.cancelAndJoin()
        synchronized(this) {
            if (active === r && r?.thread?.isAlive == false) active = null
        }
    }

    private fun run(run: Run) {
        try {
            while (!Thread.currentThread().isInterrupted) {
                var candidate: LocalSocket? = null
                try {
                    candidate = LocalSocket()
                    if (!run.attach(candidate)) break
                    candidate.use { s ->
                        s.connect(LocalSocketAddress(SOCK, LocalSocketAddress.Namespace.ABSTRACT))
                        val out = s.outputStream
                        val br = BufferedReader(InputStreamReader(s.inputStream))
                        // Establish the watches (idempotent in the daemon) then subscribe to the stream.
                        run.buttons.forEach { out.write("WATCH ${it.node} ${if (it.grab) 1 else 0}\n".toByteArray()) }
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
                                run.buttons.firstOrNull { it.code == code && it.sw == isSw }?.let { b ->
                                    // KEY: emit on DOWN; SW: emit on every toggle (each press flips it).
                                    if (isSw || down) ButtonBus.emit(b.eventType)
                                }
                            }
                            line = br.readLine()
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "evdev stream unavailable (${e.message}); retrying")
                } finally {
                    candidate?.let { run.detach(it) }
                }
                if (Thread.currentThread().isInterrupted) break
                try { Thread.sleep(3000) } catch (e: InterruptedException) { break }  // backoff before reconnect
            }
        } finally {
            synchronized(this) {
                if (active === run) active = null
            }
        }
    }

    private const val STOP_JOIN_MS = 1_000L
}
