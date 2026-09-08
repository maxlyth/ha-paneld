package io.github.maxlyth.hapaneld.sensors

import java.io.File
import java.io.InputStream

/** This ABI exposes a cached ADC count, not a timestamped hardware transaction. */
internal class StkRawProximityReader(
    private val valueFile: File = File("/sys/class/st_psensor/psensor_value"),
    private val devices: File = File("/sys/bus/i2c/devices"),
) {
    // Pin a present interface even when access or contract validation fails. Never reinterpret an
    // in-flight scalar calibration as the Android binary source after an access failure.
    fun isPresent(): Boolean = try { valueFile.exists() || valueFile.parentFile?.exists() == true } catch (_: SecurityException) { true }

    fun read(): Int {
        check(devices.listFiles()?.any { device ->
            File(device, "name").let { name ->
                name.isFile && name.inputStream().use { readBounded(it, 32) }.trim() == "ps_stk3a5x"
            } && File(device, "driver").isDirectory
        } == true) { "Raw proximity driver contract unavailable" }
        return valueFile.inputStream().use(::parse)
    }

    companion object {
        const val SOURCE_IDENTITY = "sysfs-stk3a5x-raw16-v1"
        fun parse(input: InputStream): Int {
            val text = readBounded(input, 8)
            require(text.matches(Regex("[0-9]{1,5}\\n?"))) { "Malformed raw proximity value" }
            return text.trimEnd('\n').toInt().also { require(it in 0..65535) }
        }
        private fun readBounded(input: InputStream, limit: Int): String {
            val bytes = ByteArray(limit + 1)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                check(read > 0) { "Empty read" }
                count += read
            }
            require(count <= limit) { "Oversized sysfs value" }
            return String(bytes, 0, count, Charsets.US_ASCII)
        }
    }
}

/** Value changes renew a short lease; repeated cached values never manufacture freshness. */
internal class StkRawProximityGate(private val leaseMs: Long = 2_000L) {
    data class Observation(val raw: Int?, val fresh: Boolean, val wakeEligible: Boolean, val becameUnavailable: Boolean)
    private var seed: Int? = null
    private var changedAt: Long? = null
    private var lastAt: Long? = null
    private var available = false
    private var closed = false

    @Synchronized
    fun observe(raw: Int?, now: Long): Observation? {
        if (closed) return null
        val wasAvailable = available
        val expired = changedAt?.let { now - it >= leaseMs } ?: true
        val invalid = raw == null || raw !in 0..65535 || now < 0 || lastAt?.let { now < it } == true
        lastAt = now
        if (invalid) {
            seed = null
            changedAt = null
            available = false
            return Observation(null, false, false, wasAvailable)
        }
        val changed = seed != null && seed != raw
        seed = raw
        if (changed) changedAt = now
        available = changedAt != null && (changed || !expired)
        return Observation(raw, available, available && wasAvailable && !expired, wasAvailable && expired)
    }

    @Synchronized
    fun expire(now: Long): Boolean {
        if (closed || !available) return false
        if (changedAt?.let { now < it || now - it >= leaseMs } != false) {
            available = false
            return true
        }
        return false
    }

    @Synchronized
    fun close() { closed = true }
}

/** Includes filesystem time and handler delay; a late completion is never a fresh sample. */
internal fun stkRawReadWithinDeadline(raw: Int?, startedAt: Long, deliveredAt: Long): Int? =
    raw.takeIf { startedAt >= 0L && deliveredAt >= startedAt && deliveredAt - startedAt <= 500L }
