// Canvas variant — everything is a draggable/resizable card (Gridstack), full feature parity with
// the Tabbed UI. View ⇄ Arrange; layout persisted per-panel via /api/v1/ui/layout. Degrades to a
// static stack if Gridstack fails to load.
(function () {
  "use strict";
  var UI = window.UI, el = UI.el;
  var state = { schema: [], values: {}, expose: {}, raw: {} };
  var grid = null, editing = false;

  function tStatus(b) {
    b.appendChild(el("div", { class: "big", text: state.raw.panel_id || "—" }));
    b.appendChild(el("div", { class: "muted", text: "ha-paneld " + (state.raw.version || "") }));
    var host = (state.raw.mqtt_broker || "").replace(/^.*:\/\//, "").split(":")[0] || "(no broker)";
    b.appendChild(el("div", { class: "muted", text: "MQTT: " + host + (state.raw.mqtt_password_set ? " · auth" : "") }));
    b.appendChild(el("a", { href: "/ui", class: "muted", text: "↔ switch UI", style: "display:inline-block;margin-top:8px" }));
  }
  function tPerf(b) {
    b.appendChild(el("div", { class: "muted", text: "loading…" }));
    UI.poll("/api/v1/perf", 3000, function (d) {
      b.innerHTML = "";
      b.appendChild(el("div", { class: "big", text: (d.cpu != null ? d.cpu : "?") + "% CPU" }));
      b.appendChild(el("div", { class: "muted", text: "RAM " + d.memUsedMb + " / " + d.memTotalMb + " MB" }));
      var line = []; if (d.gpu != null) line.push("GPU " + d.gpu + "%"); if (d.tempC != null) line.push(d.tempC + "°C");
      b.appendChild(el("div", { class: "muted", text: line.join(" · ") }));
      if (d.render) b.appendChild(el("div", { class: "muted", text: "dashboard: " + d.render.verdict }));
    });
  }
  function tProcs(b) {
    UI.poll("/api/v1/perf", 4000, function (d) {
      b.innerHTML = "";
      (d.top || []).slice(0, 6).forEach(function (p) {
        b.appendChild(el("div", { class: "row" }, [el("span", { class: "lab", text: p.name.split(".").pop(), title: p.name }), el("span", { class: "muted", text: p.cpu + "%" })]));
      });
    });
  }

  var TILES = [
    { id: "status", title: "Status", w: 3, h: 3, x: 0, y: 0, r: tStatus },
    { id: "perf", title: "Performance", w: 3, h: 3, x: 3, y: 0, r: tPerf },
    { id: "screenshot", title: "Panel screen", w: 6, h: 6, x: 6, y: 0, r: function (b) { b.appendChild(UI.screenshot()); } },
    { id: "actions", title: "Quick actions", w: 3, h: 3, x: 0, y: 3, r: function (b) { b.appendChild(UI.navActions()); } },
    { id: "procs", title: "Top processes", w: 3, h: 3, x: 3, y: 3, r: tProcs },
    { id: "settings", title: "Settings", w: 6, h: 8, x: 0, y: 6, r: function (b) { b.appendChild(UI.settingsForm(state, { search: true })); } },
    { id: "install", title: "Install · health", w: 3, h: 6, x: 6, y: 6, r: function (b) { b.appendChild(UI.installPanel()); } },
    { id: "bundle", title: "Backup & restore", w: 3, h: 3, x: 9, y: 6, r: function (b) { b.appendChild(UI.bundle()); } },
    { id: "tts", title: "TTS test", w: 3, h: 3, x: 9, y: 9, r: function (b) { b.appendChild(UI.tts()); } },
  ];

  function buildItem(t) {
    var body = el("div", { class: "body" });
    var item = el("div", { class: "grid-stack-item", "gs-id": t.id, "gs-w": t.w, "gs-h": t.h, "gs-x": t.x, "gs-y": t.y }, [
      el("div", { class: "grid-stack-item-content" }, [
        el("div", { class: "tile" }, [el("h3", {}, [t.title, el("span", { class: "drag", text: "⠿" })]), body]),
      ]),
    ]);
    t.r(body);
    return item;
  }

  function persist() { if (grid) UI.postForm("/api/v1/ui/layout", { layout: JSON.stringify(grid.save(false)) }); }

  function start() {
    var holder = document.getElementById("canvas");
    var gridEl = el("div", { class: "grid-stack" });
    TILES.forEach(function (t) { gridEl.appendChild(buildItem(t)); });
    holder.appendChild(gridEl);
    if (typeof GridStack === "undefined") return; // fallback: static stack (still fully usable)
    try {
      grid = GridStack.init({ column: 12, cellHeight: 70, margin: 8, float: true, disableResize: true, disableDrag: true }, gridEl);
      UI.getJSON("/api/v1/ui/layout").then(function (d) {
        if (!d || !d.layout) return;
        try { JSON.parse(d.layout).forEach(function (n) { var e = gridEl.querySelector('[gs-id="' + n.id + '"]'); if (e) grid.update(e, { x: n.x, y: n.y, w: n.w, h: n.h }); }); } catch (e) {}
      }).catch(function () {});
      grid.on("change", function () { if (editing) persist(); });
    } catch (e) { grid = null; }
  }

  window.cvEdit = function (on) {
    editing = on;
    document.getElementById("cv-view").classList.toggle("on", !on);
    document.getElementById("cv-edit").classList.toggle("on", on);
    document.body.classList.toggle("editing", on);
    if (grid) { grid.enableMove(on); grid.enableResize(on); }
  };
  window.cvReset = function () {
    if (!grid) return;
    TILES.forEach(function (t) { var e = document.querySelector('[gs-id="' + t.id + '"]'); if (e) grid.update(e, { x: t.x, y: t.y, w: t.w, h: t.h }); });
    persist();
  };

  UI.loadConfig().then(function (s) { state = s; start(); }).catch(function () { start(); });
})();
