package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.platform.RootShell
import io.github.maxlyth.hapaneld.util.CompanionInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads + repairs the HA Companion's connection database — the `servers` table in `HomeAssistantDB`.
 *
 * Incident 2026-07-01: a Companion `servers` row with an EMPTY `internal_url` (while `internal_ssids`
 * matched the panel's Wi-Fi) makes the Companion request a host-less URL, which HA 2026.7 (new aiohttp)
 * rejects with a full-screen **"Missing 'Host' header in request."** — blanking the dashboard on the
 * whole restored fleet. This detects that state (a panel-health warning) and offers a one-tap repair:
 * copy the row's `external_url` into the empty `internal_url`. The same repair is what the planned
 * backup/restore path should run after writing back a captured DB.
 *
 * Root-only (the DB is app-private); a safe no-op on panels without su or without the Companion. The
 * parse + decision logic is pure ([parseServers] / [needsRepair]) and unit-tested; only the
 * sqlite3-over-su calls touch the device.
 */
object CompanionDb {
    private const val TAG = "ha-paneld/companiondb"

    // ASCII Unit Separator (0x1F) — a field delimiter that can't appear in a URL, so the parse is robust
    // against NULLs and any '|' in a value (unlike sqlite3's default pipe separator). Built with char(31).
    private const val US = '\u001f'

    // Read the servers table as `id US internal US external`, one row per line, NULLs coalesced to "".
    private const val DUMP_SQL =
        "SELECT id||char(31)||coalesce(internal_url,'')||char(31)||coalesce(external_url,'') FROM servers;"

    // Copy external_url into every blank internal_url. Mirrors [needsRepair] so a re-read confirms zero left.
    private const val REPAIR_SQL =
        "UPDATE servers SET internal_url=external_url " +
            "WHERE (internal_url IS NULL OR trim(internal_url)='') " +
            "AND external_url IS NOT NULL AND trim(external_url)<>'';"

    data class ServerRow(val id: String, val internalUrl: String, val externalUrl: String)

    /** Result of a health read: whether any row needs repair and how many. */
    data class UrlStatus(val needsRepair: Boolean, val affected: Int)

    private fun isBlank(u: String): Boolean = u.isBlank() || u == "null"

    /** A row needs repair when its internal URL is blank but a usable external URL exists to copy. */
    fun needsRepair(r: ServerRow): Boolean = isBlank(r.internalUrl) && !isBlank(r.externalUrl)

    /** Parse the `id US internal US external` lines emitted by [DUMP_SQL]. Tolerant of blank lines and
     *  short rows (a missing trailing field → ""). A line with no id is skipped. */
    fun parseServers(output: String): List<ServerRow> =
        output.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.mapNotNull { line ->
            val f = line.split(US)
            if (f[0].isBlank()) null else ServerRow(f[0], f.getOrElse(1) { "" }, f.getOrElse(2) { "" })
        }.toList()

    private fun dbPath(pkg: String) = "/data/data/$pkg/databases/HomeAssistantDB"

    // ---- login borrow: reuse the Companion's sign-in for the built-in renderer ----
    // Same panel, same HA user; HA does not rotate refresh tokens on use, so both apps refresh
    // independently off the same token and the Companion keeps working as a fallback (mechanism in
    // production on the maintainer fleet since 2026-07-09; the DB columns are plain text).

    /** The client_id the Companion's refresh token was issued to — a refresh only works with it. */
    const val COMPANION_CLIENT_ID = "https://home-assistant.io/android"

    private const val LOGIN_SQL =
        "SELECT id||char(31)||coalesce(internal_url,'')||char(31)||coalesce(external_url,'')||char(31)||" +
            "coalesce(refresh_token,'')||char(31)||coalesce(access_token,'')||char(31)||coalesce(token_expiration,'') FROM servers;"

    data class LoginRow(
        val id: String,
        val internalUrl: String,
        val externalUrl: String,
        val refreshToken: String,
        val accessToken: String,
        val expirySec: Long,           // epoch SECONDS (the Companion's unit; DashboardAuth's too)
    ) {
        val url: String get() = (internalUrl.takeUnless(::isBlank) ?: externalUrl).trimEnd('/')
    }

    /** Parse the `LOGIN_SQL` lines. Same tolerance rules as [parseServers]. */
    fun parseLogins(output: String): List<LoginRow> =
        output.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.mapNotNull { line ->
            val f = line.split(US)
            if (f[0].isBlank()) null else LoginRow(
                f[0], f.getOrElse(1) { "" }, f.getOrElse(2) { "" },
                f.getOrElse(3) { "" }, f.getOrElse(4) { "" },
                f.getOrElse(5) { "" }.toLongOrNull() ?: 0L,
            )
        }.toList()

    /** The Companion's active server id from `session_0.xml` (`<int name="active_server" value="N"/>`),
     *  or null when unreadable — multi-server installs must borrow the server the panel actually shows. */
    fun parseActiveServer(xml: String?): String? =
        xml?.let { Regex("\"active_server\"\\s+value=\"(\\d+)\"").find(it)?.groupValues?.get(1) }

    /** Pick the row to borrow: the active server when known, else the first signed-in row (has a
     *  refresh token and a URL). Null = nothing borrowable. */
    fun pickLogin(rows: List<LoginRow>, activeId: String?): LoginRow? {
        val signed = rows.filter { it.refreshToken.isNotBlank() && !isBlank(it.url) }
        return signed.firstOrNull { it.id == activeId } ?: signed.firstOrNull()
    }

    /** Read the borrowable login via root, or null (no root / no Companion / no signed-in server).
     *  Read-only immutable open — never leaves root-owned WAL files. Call OFF the main thread. */
    fun readLogin(context: Context, root: RootShell): LoginRow? {
        val pkg = CompanionInstaller.installedPkg(context) ?: return null
        val db = dbPath(pkg)
        val out = root.runOutput("""sqlite3 "file:$db?immutable=1" "$LOGIN_SQL" 2>/dev/null""") ?: return null
        val active = parseActiveServer(root.runOutput("""cat "/data/data/$pkg/shared_prefs/session_0.xml" 2>/dev/null"""))
        return pickLogin(parseLogins(out), active)
    }

    /** Read the Companion `servers` rows via root sqlite3, or null when unavailable (no root / no
     *  Companion / no DB / no sqlite3). Opens the file as an **immutable** URI: read-only, WAL ignored,
     *  and crucially it never creates the `-wal`/`-shm` files — so a root read can't leave root-owned
     *  sidecar files the app then can't open. Safe while the Companion is running. (Android's bundled
     *  sqlite3 — 3.19 on PX30 — lacks the `-readonly` flag but supports `file:…?immutable=1`.) */
    fun readServers(context: Context, root: RootShell): List<ServerRow>? {
        val pkg = CompanionInstaller.installedPkg(context) ?: return null
        val db = dbPath(pkg)
        val out = root.runOutput("""sqlite3 "file:$db?immutable=1" "$DUMP_SQL" 2>/dev/null""") ?: return null
        return parseServers(out)
    }

    /** The active HA server URL as the Companion knows it — internal_url preferred, else external_url,
     *  from the first server row that has one. Null when there's no Companion / no root / no server row.
     *  A root sqlite read — call off the main thread. Used as the header "Open in HA" target when the
     *  panel's own HA device-page URL hasn't resolved (e.g. a remote panel over a tunnel). */
    fun serverUrl(context: Context, root: RootShell): String? {
        val rows = readServers(context, root) ?: return null
        for (r in rows) {
            val u = r.internalUrl.takeUnless { isBlank(it) } ?: r.externalUrl.takeUnless { isBlank(it) }
            if (u != null) return u.trimEnd('/')
        }
        return null
    }

    /** Health status for the Install-tab warning. Never throws; (false, 0) when it can't tell. */
    fun internalUrlStatus(context: Context, root: RootShell): UrlStatus {
        val rows = readServers(context, root) ?: return UrlStatus(false, 0)
        val bad = rows.count(::needsRepair)
        return UrlStatus(bad > 0, bad)
    }

    /**
     * Repair every blank `internal_url` by copying the row's `external_url`. Force-stops the Companion
     * first (so Room isn't mid-write), checkpoints + drops the WAL so sqlite doesn't leave root-owned
     * `-wal`/`-shm` the app can't read, restores the DB owner + SELinux context, then relaunches the
     * Companion. Returns a short human status. Root-only; call OFF the main thread.
     */
    suspend fun repairInternalUrl(context: Context, root: RootShell): String = withContext(Dispatchers.IO) {
        val pkg = CompanionInstaller.installedPkg(context) ?: return@withContext "no HA Companion app installed"
        val before = readServers(context, root) ?: return@withContext "cannot read the Companion database (needs root)"
        val bad = before.count(::needsRepair)
        if (bad == 0) return@withContext "nothing to repair — every server already has an internal URL"

        val db = dbPath(pkg)
        // One privileged operation: stop → repair → checkpoint+drop WAL → restore owner/context → relaunch.
        // Running under su means the relaunch (monkey) is in the root domain, so BAL doesn't gate it.
        val script = buildString {
            append("am force-stop $pkg; ")
            append("""U=$(stat -c %u "/data/data/$pkg" 2>/dev/null); """)
            append("""sqlite3 "$db" "$REPAIR_SQL PRAGMA wal_checkpoint(TRUNCATE);" 2>&1; """)
            append("""rm -f "$db-wal" "$db-shm" 2>/dev/null; """)
            append("""[ -n "${'$'}U" ] && { chown "${'$'}U:${'$'}U" "$db" 2>/dev/null; restorecon "$db" 2>/dev/null; }; """)
            append("monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; echo ok")
        }
        val out = root.runOutput(script) ?: return@withContext "repair command failed (needs root)"
        if (!out.contains("ok")) Log.w(TAG, "repair script output: ${out.take(200)}")

        val remaining = readServers(context, root)?.count(::needsRepair) ?: -1
        Log.i(TAG, "internal_url repair: $bad row(s) fixed, $remaining remaining")
        when {
            remaining == 0 -> "repaired $bad server${if (bad == 1) "" else "s"} — internal URL set to the external URL; Companion relaunched"
            remaining < 0 -> "repair ran ($bad row(s)); could not re-verify the database"
            else -> "repair incomplete — $remaining server(s) still have no internal URL"
        }
    }
}
