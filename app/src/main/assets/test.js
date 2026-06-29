// Test tab — interactive screenshot (View / Control), on-screen nav actions, TTS test. Vanilla.
(function () {
  "use strict";
  var mode = "view", timer = null;
  var wrap = document.getElementById("shotwrap");
  var img = document.getElementById("shot");
  if (!wrap || !img) return;

  function refresh() { img.src = "/screenshot.png?t=" + Date.now(); }
  function schedule() { clearInterval(timer); timer = setInterval(refresh, mode === "control" ? 1500 : 4000); }

  window.tstMode = function (m) {
    mode = m;
    document.getElementById("m-view").classList.toggle("on", m === "view");
    document.getElementById("m-ctl").classList.toggle("on", m === "control");
    wrap.classList.toggle("control", m === "control");
    document.getElementById("ctl-hint").style.display = m === "control" ? "block" : "none";
    schedule();
  };
  window.tstRefresh = refresh;

  function ripple(x, y) {
    var r = document.createElement("div");
    r.className = "ripple"; r.style.left = x + "px"; r.style.top = y + "px";
    wrap.appendChild(r); setTimeout(function () { r.remove(); }, 500);
  }

  // Map a click on the scaled image back to device pixels (the PNG's natural size == framebuffer).
  wrap.addEventListener("click", function (e) {
    if (mode !== "control" || !img.naturalWidth) return;
    var rect = img.getBoundingClientRect();
    var x = Math.round((e.clientX - rect.left) / rect.width * img.naturalWidth);
    var y = Math.round((e.clientY - rect.top) / rect.height * img.naturalHeight);
    ripple(e.clientX - rect.left, e.clientY - rect.top);
    var b = new URLSearchParams(); b.set("x", x); b.set("y", y);
    fetch("/api/v1/input", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: b.toString() })
      .then(function (r) {
        if (r.status === 503) document.getElementById("ctl-hint").textContent = "This panel has no tap-injection capability (needs root).";
        setTimeout(refresh, 500);
      });
  });

  window.tstAction = function (a) {
    var b = new URLSearchParams(); b.set("a", a);
    fetch("/action", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: b.toString() })
      .then(function () { setTimeout(refresh, 500); });
  };

  window.tstPlay = function () {
    var url = document.getElementById("tts-url").value.trim();
    if (!url) return;
    var msg = document.getElementById("tts-msg"); msg.textContent = "playing…";
    fetch("/play", { method: "POST", body: url })
      .then(function (r) { return r.text(); })
      .then(function (t) { msg.textContent = t.trim(); })
      .catch(function () { msg.textContent = "failed"; });
  };

  refresh();
  schedule();
})();
