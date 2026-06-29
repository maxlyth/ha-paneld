// Canvas variant — everything is a draggable/resizable card (Gridstack). View ⇄ Edit; layout
// persisted per-panel via /api/v1/ui/layout. Degrades to a static stack if Gridstack fails to load.
(function () {
  "use strict";
  var UI = window.UI, el = UI.el;
  var state = { schema: [], values: {}, expose: {}, raw: {} };
  var grid = null, editing = false;

  function poll(url, ms, cb) {
    function tick() { if (url) UI.getJSON(url).then(cb).catch(function () {}); else cb(); }
    tick(); setInterval(tick, ms);
  }

  // ---- tile renderers (each fills a body element) ----
  function tStatus(b) {
    b.appendChild(el("div", { class: "big", text: state.raw.panel_id || "—" }));
    b.appendChild(el("div", { class: "muted", text: "ha-paneld " + (state.raw.version || "") }));
    var host = (state.raw.mqtt_broker || "").replace(/^.*:\/\//, "").split(":")[0] || "(no broker)";
    b.appendChild(el("div", { class: "muted", text: "MQTT: " + host }));
    b.appendChild(el("div", { class: "muted", text: state.raw.mqtt_password_set ? "auth set" : "anonymous" }));
  }
  function tPerf(b) {
    b.appendChild(el("div", { class: "muted", text: "loading…" }));
    poll("/api/v1/perf", 3000, function (d) {
      b.innerHTML = "";
      b.appendChild(el("div", { class: "big", text: (d.cpu != null ? d.cpu : "?") + "% CPU" }));
      b.appendChild(el("div", { class: "muted", text: "RAM " + d.memUsedMb + " / " + d.memTotalMb + " MB" }));
      var line = []; if (d.gpu != null) line.push("GPU " + d.gpu + "%"); if (d.tempC != null) line.push(d.tempC + "°C");
      b.appendChild(el("div", { class: "muted", text: line.join(" · ") }));
      if (d.render) b.appendChild(el("div", { class: "muted", text: "dashboard: " + d.render.verdict + " (" + d.render.mainPct + "%)" }));
    });
  }
  function tShot(b) {
    var img = el("img", { alt: "panel screen" });
    function r() { img.src = "/screenshot.png?t=" + Date.now(); }
    b.appendChild(img);
    b.appendChild(el("button", { class: "btn", text: "↻ Refresh", onclick: r, style: "margin-top:8px" }));
    r(); poll(null, 10000, r);
  }
  function tActions(b) {
    [["back", "← Back"], ["recents", "▢ Recents"], ["launcher", "⊞ Launcher"], ["admin_launcher", "⚙ Admin"], ["reboot", "⟳ Reboot"]].forEach(function (a) {
      b.appendChild(el("button", { class: "btn", text: a[1], style: "margin:0 6px 6px 0", onclick: function () { UI.action(a[0]); } }));
    });
  }
  function tToggles(b) {
    var bools = state.schema.filter(function (f) { return f.type === "BOOL" && f.available; }).slice(0, 6);
    if (!bools.length) { b.appendChild(el("div", { class: "muted", text: "no toggleable settings" })); return; }
    var dirty = false;
    var saveBtn = el("button", { class: "btn primary", text: "Save", disabled: "", style: "margin-top:8px",
      onclick: function () { UI.save(state).then(function () { dirty = false; saveBtn.disabled = true; }); } });
    function onChange() { dirty = true; saveBtn.disabled = false; }
    bools.forEach(function (f) {
      b.appendChild(el("div", { class: "row" }, [
        el("span", { class: "lab", text: f.label }),
        UI.pip(f, state, onChange),
        UI.control(f, state, onChange),
      ]));
    });
    b.appendChild(saveBtn);
  }
  function tProcs(b) {
    poll("/api/v1/perf", 4000, function (d) {
      b.innerHTML = "";
      (d.top || []).slice(0, 6).forEach(function (p) {
        b.appendChild(el("div", { class: "row" }, [
          el("span", { class: "lab", text: p.name.split(".").pop(), title: p.name }),
          el("span", { class: "muted", text: p.cpu + "%" }),
        ]));
      });
    });
  }

  // id, title, default geometry, renderer
  var TILES = [
    { id: "status", title: "Status", w: 3, h: 2, x: 0, y: 0, r: tStatus },
    { id: "perf", title: "Performance", w: 3, h: 2, x: 3, y: 0, r: tPerf },
    { id: "screenshot", title: "Panel screen", w: 6, h: 5, x: 6, y: 0, r: tShot },
    { id: "actions", title: "Quick actions", w: 3, h: 3, x: 0, y: 2, r: tActions },
    { id: "toggles", title: "Settings", w: 3, h: 5, x: 3, y: 2, r: tToggles },
    { id: "procs", title: "Top processes", w: 6, h: 3, x: 0, y: 5, r: tProcs },
  ];

  function buildItem(t) {
    var body = el("div", { class: "body" });
    var item = el("div", { class: "grid-stack-item", "gs-id": t.id, "gs-w": t.w, "gs-h": t.h, "gs-x": t.x, "gs-y": t.y }, [
      el("div", { class: "grid-stack-item-content" }, [
        el("div", { class: "tile" }, [
          el("h3", {}, [t.title, el("span", { class: "drag", text: "⠿" })]),
          body,
        ]),
      ]),
    ]);
    t.r(body);
    return item;
  }

  function persist() {
    if (!grid) return;
    var layout = grid.save(false); // [{id,x,y,w,h}]
    UI.postForm("/api/v1/ui/layout", { layout: JSON.stringify(layout) });
  }

  function start() {
    var holder = document.getElementById("canvas");
    var gridEl = el("div", { class: "grid-stack" });
    TILES.forEach(function (t) { gridEl.appendChild(buildItem(t)); });
    holder.appendChild(gridEl);

    if (typeof GridStack === "undefined") return; // fallback: static stacked tiles (still usable)
    try {
      grid = GridStack.init({ column: 12, cellHeight: 70, margin: 8, float: true, disableResize: true, disableDrag: true }, gridEl);
      UI.getJSON("/api/v1/ui/layout").then(function (d) {
        if (!d || !d.layout) return;
        try {
          JSON.parse(d.layout).forEach(function (n) {
            var elNode = gridEl.querySelector('[gs-id="' + n.id + '"]');
            if (elNode) grid.update(elNode, { x: n.x, y: n.y, w: n.w, h: n.h });
          });
        } catch (e) {}
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
    TILES.forEach(function (t) {
      var elNode = document.querySelector('[gs-id="' + t.id + '"]');
      if (elNode) grid.update(elNode, { x: t.x, y: t.y, w: t.w, h: t.h });
    });
    persist();
  };

  UI.loadConfig().then(function (s) {
    state = s;
    start();
  }).catch(function () { start(); });
})();
