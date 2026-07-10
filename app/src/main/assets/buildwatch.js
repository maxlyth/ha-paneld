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
  function vc() {
    fetch("/health").then(function (r) { return r.text(); }).then(function (t) {
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
