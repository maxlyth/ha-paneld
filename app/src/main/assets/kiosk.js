// Kiosk variant — touch-first, big tap targets, segmented sections. Full feature parity: interactive
// screenshot, health, big tap-to-toggle controls, full settings, bundle, TTS. Tap toggles apply now.
(function () {
  "use strict";
  var UI = window.UI, el = UI.el;
  var state = { schema: [], values: {}, expose: {}, raw: {} };
  var section = "status";

  function status(main) {
    var shot = el("div", { class: "ktile wide" }, [UI.screenshot()]);
    main.appendChild(shot);
    var tiles = el("div", { class: "tiles" });
    tiles.appendChild(el("div", { class: "ktile" }, [el("div", { class: "ico", text: "🖥" }), el("div", { class: "name", text: state.raw.panel_id || "—" }), el("div", { class: "val", text: "ha-paneld " + (state.raw.version || "") })]));
    var perf = el("div", { class: "ktile" }, [el("div", { class: "ico", text: "⚡" }), el("div", { class: "name", text: "Performance" }), el("div", { class: "val", text: "…" })]);
    tiles.appendChild(perf);
    UI.poll("/api/v1/perf", 3000, function (d) { perf.lastChild.textContent = (d.cpu != null ? d.cpu : "?") + "% · " + d.memUsedMb + "MB" + (d.tempC != null ? " · " + d.tempC + "°C" : ""); });
    main.appendChild(tiles);
    main.appendChild(el("h2", { text: "Health" }));
    main.appendChild(UI.installPanel());
  }

  function controls(main) {
    main.appendChild(el("h2", { text: "Quick actions" }));
    var acts = el("div", { class: "tiles" });
    [["back", "←", "Back"], ["recents", "▢", "Recents"], ["launcher", "⊞", "Launcher"], ["admin_launcher", "⚙", "Admin"], ["voldn", "🔉", "Vol −"], ["volup", "🔊", "Vol +"], ["reboot", "⟳", "Reboot"]].forEach(function (a) {
      acts.appendChild(el("div", { class: "ktile", onclick: function () { UI.action(a[0]); } }, [el("div", { class: "ico", text: a[1] }), el("div", { class: "name", text: a[2] })]));
    });
    main.appendChild(acts);

    main.appendChild(el("h2", { text: "Toggles · tap to apply" }));
    var tiles = el("div", { class: "tiles" });
    state.schema.filter(function (f) { return f.type === "BOOL" && f.available; }).forEach(function (f) {
      var on = state.values[f.key] === "true";
      var tile = el("div", { class: "ktile" + (on ? " on" : "") }, [el("div", { class: "ico", text: on ? "✓" : "○" }), el("div", { class: "name", text: f.label }), el("div", { class: "val", text: on ? "on" : "off" })]);
      tile.addEventListener("click", function () {
        var nv = state.values[f.key] === "true" ? "false" : "true";
        state.values[f.key] = nv;
        tile.classList.toggle("on", nv === "true");
        tile.children[0].textContent = nv === "true" ? "✓" : "○";
        tile.children[2].textContent = nv === "true" ? "on" : "off";
        UI.save(state);
      });
      tiles.appendChild(tile);
    });
    main.appendChild(tiles);

    main.appendChild(el("h2", { text: "TTS test" }));
    main.appendChild(UI.tts());
  }

  function settings(main) {
    main.appendChild(el("h2", { text: "All settings" }));
    main.appendChild(UI.settingsForm(state, {}));
    main.appendChild(el("h2", { text: "Backup & restore" }));
    main.appendChild(UI.bundle());
  }

  function render() {
    document.querySelectorAll(".seg button").forEach(function (b) { b.classList.toggle("on", b.getAttribute("data-s") === section); });
    var main = document.getElementById("kiosk-main");
    main.innerHTML = "";
    ({ status: status, controls: controls, settings: settings }[section] || status)(main);
  }
  window.kSection = function (s) { section = s; render(); };

  UI.loadConfig().then(function (s) { state = s; render(); }).catch(function () { document.getElementById("kiosk-main").textContent = "Could not load."; });
})();
