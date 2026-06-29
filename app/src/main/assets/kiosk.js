// Kiosk variant — touch-first, big tap targets, segmented sections (Status / Controls / Settings).
// Toggles apply immediately (no Save button) — right for someone standing at the wall panel.
(function () {
  "use strict";
  var UI = window.UI, el = UI.el;
  var state = { schema: [], values: {}, expose: {}, raw: {} };
  var section = "status";

  function poll(url, ms, cb) { function t() { if (url) UI.getJSON(url).then(cb).catch(function () {}); else cb(); } t(); setInterval(t, ms); }

  function status(main) {
    var shot = el("div", { class: "ktile wide" });
    var img = el("img", { class: "shot", alt: "screen" });
    function r() { img.src = "/screenshot.png?t=" + Date.now(); } r(); poll(null, 8000, r);
    shot.appendChild(img);
    main.appendChild(shot);

    var tiles = el("div", { class: "tiles" });
    var st = el("div", { class: "ktile" }, [el("div", { class: "ico", text: "🖥" }), el("div", { class: "name", text: state.raw.panel_id || "—" }), el("div", { class: "val", text: "ha-paneld " + (state.raw.version || "") })]);
    tiles.appendChild(st);
    var perf = el("div", { class: "ktile" }, [el("div", { class: "ico", text: "⚡" }), el("div", { class: "name", text: "Performance" }), el("div", { class: "val", text: "…" })]);
    tiles.appendChild(perf);
    poll("/api/v1/perf", 3000, function (d) { perf.lastChild.textContent = (d.cpu != null ? d.cpu : "?") + "% CPU · " + d.memUsedMb + "MB" + (d.tempC != null ? " · " + d.tempC + "°C" : ""); });
    main.appendChild(tiles);
  }

  function controls(main) {
    main.appendChild(el("h2", { text: "Quick actions" }));
    var acts = el("div", { class: "tiles" });
    [["back", "←", "Back"], ["recents", "▢", "Recents"], ["launcher", "⊞", "Launcher"], ["admin_launcher", "⚙", "Admin"], ["reboot", "⟳", "Reboot"]].forEach(function (a) {
      acts.appendChild(el("div", { class: "ktile", onclick: function () { UI.action(a[0]); } }, [el("div", { class: "ico", text: a[1] }), el("div", { class: "name", text: a[2] })]));
    });
    main.appendChild(acts);

    main.appendChild(el("h2", { text: "Toggles" }));
    var tiles = el("div", { class: "tiles" });
    var bools = state.schema.filter(function (f) { return f.type === "BOOL" && f.available; });
    bools.forEach(function (f) {
      var on = state.values[f.key] === "true";
      var tile = el("div", { class: "ktile" + (on ? " on" : "") }, [
        el("div", { class: "ico", text: on ? "✓" : "○" }),
        el("div", { class: "name", text: f.label }),
        el("div", { class: "val", text: on ? "on" : "off" }),
      ]);
      tile.addEventListener("click", function () {
        var nv = state.values[f.key] === "true" ? "false" : "true";
        state.values[f.key] = nv;
        tile.classList.toggle("on", nv === "true");
        tile.children[0].textContent = nv === "true" ? "✓" : "○";
        tile.children[2].textContent = nv === "true" ? "on" : "off";
        UI.save(state); // apply immediately
      });
      tiles.appendChild(tile);
    });
    main.appendChild(tiles);
  }

  function settings(main) {
    main.appendChild(el("h2", { text: "Basic settings" }));
    var dirty = false;
    var saveBtn = el("button", { class: "btn primary", text: "Save", disabled: "", style: "margin-top:14px;padding:14px 28px;font-size:1.05rem",
      onclick: function () { UI.save(state).then(function () { dirty = false; saveBtn.disabled = true; }); } });
    function onChange() { dirty = true; saveBtn.disabled = false; }
    var basics = state.schema.filter(function (f) { return f.tier === "BASIC" && f.available; });
    basics.forEach(function (f) {
      main.appendChild(el("div", { class: "krow" }, [
        el("div", { class: "lab" }, [f.label, f.help ? el("small", { text: f.help }) : null]),
        UI.control(f, state, onChange),
      ]));
    });
    main.appendChild(saveBtn);
  }

  function render() {
    document.querySelectorAll(".seg button").forEach(function (b) { b.classList.toggle("on", b.getAttribute("data-s") === section); });
    var main = document.getElementById("kiosk-main");
    main.innerHTML = "";
    ({ status: status, controls: controls, settings: settings }[section] || status)(main);
  }
  window.kSection = function (s) { section = s; render(); };

  UI.loadConfig().then(function (s) { state = s; render(); }).catch(function () {
    document.getElementById("kiosk-main").textContent = "Could not load.";
  });
})();
