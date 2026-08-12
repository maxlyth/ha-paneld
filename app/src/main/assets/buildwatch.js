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
  function vc() {
    fetch("/health").then(function (r) { return r.text(); }).then(function (t) {
      var mh = t.match(/ha=(\S+)/);
      var ms = t.match(/ha_src=(\S+)/);
      var mr = t.match(/ha_refused=1/);
      haBanner(mh ? mh[1] : "", ms ? ms[1] : "", !!mr);
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
