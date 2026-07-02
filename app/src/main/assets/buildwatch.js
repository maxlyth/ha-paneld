// Build watch (shared by every page). /health carries a per-INSTALL build token (changes on every
// (re)install, even a same-version dev re-spin — deliberately NOT the version name, which internal
// builds don't bump). If it changes while this page is open, the app was updated → AUTO-RELOAD to
// pull fresh html/css/js (assets are no-cache). Exception: if the user is mid-edit in a field, show
// the #verbar banner instead so typed input isn't lost. Baseline = <body data-build>.
(function () {
  "use strict";
  var LB = document.body.getAttribute("data-build") || "";
  function vc() {
    fetch("/health").then(function (r) { return r.text(); }).then(function (t) {
      var m = t.match(/build=(\S+)/);
      if (!m || !LB || m[1] === LB) return;
      if (document.querySelector("input:focus,textarea:focus")) {
        var b = document.getElementById("verbar");
        if (b) b.style.display = "";
      } else {
        location.reload();
      }
    }).catch(function () {});
  }
  setInterval(vc, 10000);
  vc();
})();
