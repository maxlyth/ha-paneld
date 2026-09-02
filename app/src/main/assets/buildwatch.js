// Build + config watch (shared by every page). /health carries two tokens:
//  - build= : per-INSTALL token (changes on every (re)install, even a same-version dev re-spin). If it
//    changes while a page is open, the app was updated → AUTO-RELOAD to pull fresh html/css/js.
//  - cfg=   : fingerprint of the panel settings. If it changes while the CONFIGURE page is open, the
//    settings were changed underneath this tab (the API, an HA entity, another browser) → auto-reload
//    so the form shows reality.
// Exception for both: unsaved edits (a focused field, or the Configure form's enabled Save button)
// must never be destroyed — show the #verbar banner instead and let the user choose.
// Baselines = <body data-build> / <body data-cfg>; configure.js re-stamps data-cfg after its own save
// so a save in THIS tab doesn't look like an external change.
(function () {
  "use strict";
  var LB = document.body.getAttribute("data-build") || "";
  function dirty() {
    if (document.querySelector("input:focus,textarea:focus")) return true;
    var save = document.getElementById("savebtn");
    return !!(save && !save.disabled); // enabled Save = unsaved Configure edits
  }
  function banner(text) {
    var b = document.getElementById("verbar");
    if (!b) return;
    b.innerHTML = "⟳ " + text + " — <a href=\"#\" onclick=\"location.reload();return false\">reload</a> to show it.";
    b.style.display = "";
  }
  // Home Assistant lifecycle. /health carries `ha=<state>` only while the panel is watching, so an
  // absent token means "nothing to say" rather than "healthy". Rendered here rather than through the
  // Information page's banner zone because that hydrates once and would freeze mid-outage; this poll
  // already runs on every page.
  // `ha_src` matters for one state only, and it may be ABSENT: it names a source only when one
  // actually observed the state. Only the socket — Home Assistant saying so itself — proves a shutdown
  // was deliberate, so the stronger wording requires ha_src=socket by name and everything else (a
  // broker will, or no attributed source) claims only that Home Assistant is gone.
  var HA_TEXT = {
    shutting_down: "⚠ Home Assistant has gone offline — controls may be temporarily unavailable.",
    starting: "⟳ Home Assistant is starting — controls will return shortly.",
    back_online: "✓ Home Assistant is back online."
  };
  var HA_TEXT_SOCKET = {
    shutting_down: "⚠ Home Assistant is shutting down — controls may be temporarily unavailable."
  };
  // The diagnostics row's idle wording, kept here so the row and the banner are rendered from the SAME
  // /health observation. A server-rendered advisory used to sit beside them; it could not retract
  // itself after recovery, so it was removed rather than kept in step.
  var HA_ROW_REFUSED = "watching; Home Assistant does not permit WebSocket lifecycle events for this user";
  function haBanner(state, src, refused) {
    var b = document.getElementById("halifebar");
    if (b) {
      var text = (src === "socket" && HA_TEXT_SOCKET[state]) || HA_TEXT[state];
      if (!text) { b.style.display = "none"; } else { b.textContent = text; b.style.display = ""; }
    }
    var row = document.getElementById("halifecell");
    if (!row) return;
    if (!state) { row.textContent = ""; return; }
    var full = (src === "socket" && HA_TEXT_SOCKET[state]) || HA_TEXT[state];
    if (full) { row.textContent = full.replace(/^[^A-Za-z]+/, ""); return; }
    if (state === "connection_lost") { row.textContent = "connection lost"; return; }
    row.textContent = refused ? HA_ROW_REFUSED : "watching";
  }
  // Home Assistant network path. /health carries `ha_net=<healthy|warning|severe>` plus terse numbers
  // only while the panel holds a Home Assistant socket, so an absent token means "not measured", and
  // the banner and row are rendered from the SAME observation as the lifecycle pair above. The banner
  // reuses the existing severe-warning presentation (`setup crit`) for the severe verdict and the soft
  // amber `setup` tone for a warning; both retract themselves on the next poll after recovery.
  var HA_NET_TEXT = {
    warning: "⚠ Probes to Home Assistant are going missing",
    severe: "⚠ The network path to Home Assistant is failing"
  };
  var HA_NET_ADVICE = "Packets are not getting through. Check the Wi-Fi path between this panel and Home Assistant before blaming the panel.";
  var HA_NET_ROW = { healthy: "healthy", warning: "losing probes", severe: "failing", settling: "settling after startup; no verdict yet" };
  // The other half of the same measurement: how fast Home Assistant answers on a path that is intact.
  // It becomes a CLAUSE in the same row and can NEVER raise the banner — latency alone is a performance
  // observation, not a reason to interrupt anyone, and treating it as one told a wired panel its
  // network was slow. Mirrors HaNetworkPathPresentation.responsivenessClause.
  var HA_RESP_CLAUSE = { healthy: "", warning: "Home Assistant answering slowly; ", severe: "Home Assistant answering very slowly; " };
  // An empty window is two facts: a socket that has only just connected, and a stream that parked
  // and stopped probing. Only the age of the last reply tells them apart (same wording as Kotlin).
  function haNetAge(ms) { return ms < 60000 ? Math.floor(ms / 1000) + " s" : Math.floor(ms / 60000) + " min"; }
  function haNetEvidence(p95, n, miss, age) {
    if (!n) {
      return age < 0 ? "no probes yet in the last 5 min"
        : "no probe answered in the last 5 min; last reply " + haNetAge(age) + " ago";
    }
    var head = p95 < 0 ? "no reply" : "p95 " + Number(p95).toLocaleString("en-GB") + " ms";
    var misses = miss > 0 ? miss + " of " + n + " probes missed" : "no misses";
    return head + ", " + misses + " in the last 5 min";
  }
  function haNetBanner(state, resp, p95, n, miss, age) {
    var b = document.getElementById("hanetbar");
    if (b) {
      var text = HA_NET_TEXT[state];
      if (!text) { b.style.display = "none"; } else {
        b.textContent = text + ": " + haNetEvidence(p95, n, miss, age) + ". " + HA_NET_ADVICE;
        b.className = state === "severe" ? "setup crit" : "setup";
        b.style.display = "";
      }
    }
    var row = document.getElementById("hanetcell");
    if (!row) return;
    if (!state) { row.textContent = ""; return; }
    if (state === "settling") { row.textContent = HA_NET_ROW.settling; return; }
    var clause = HA_RESP_CLAUSE[resp] || "";
    row.textContent = (HA_NET_ROW[state] || state) + "; " + clause + haNetEvidence(p95, n, miss, age);
  }
  function vc() {
    fetch("/health").then(function (r) { return r.text(); }).then(function (t) {
      var mh = t.match(/ha=(\S+)/);
      var ms = t.match(/ha_src=(\S+)/);
      var mr = t.match(/ha_refused=1/);
      haBanner(mh ? mh[1] : "", ms ? ms[1] : "", !!mr);
      var mn = t.match(/ha_net=(\S+)/);
      var mrs = t.match(/ha_resp=(\S+)/);
      var mp = t.match(/ha_net_p95=(-?\d+)/);
      var mc = t.match(/ha_net_n=(\d+)/);
      var mm = t.match(/ha_net_miss=(\d+)/);
      var ma = t.match(/ha_net_age=(-?\d+)/);
      haNetBanner(mn ? mn[1] : "", mrs ? mrs[1] : "", mp ? parseInt(mp[1], 10) : -1, mc ? parseInt(mc[1], 10) : 0, mm ? parseInt(mm[1], 10) : 0, ma ? parseInt(ma[1], 10) : -1);
      var mb = t.match(/build=(\S+)/);
      if (mb && LB && mb[1] !== LB) {
        if (dirty()) banner("A newer ha-paneld is installed"); else location.reload();
        return;
      }
      var mc = t.match(/cfg=(\S+)/);
      var LC = document.body.getAttribute("data-cfg") || "";
      if (mc && LC && mc[1] !== LC && location.pathname.indexOf("/configure") === 0) {
        if (dirty()) banner("Settings were changed outside this page"); else location.reload();
      }
    }).catch(function () {});
  }
  setInterval(vc, 10000);
  vc();
})();
