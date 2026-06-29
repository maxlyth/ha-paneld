// Console variant — sidebar with Monitor / Settings / Tools / Fleet. Settings is a searchable,
// filterable list (VS Code style) with a "modified only" toggle + inline expose pips.
(function () {
  "use strict";
  var UI = window.UI, el = UI.el;
  var state = { schema: [], values: {}, expose: {}, raw: {} };
  var section = "monitor", query = "", modifiedOnly = false, dirty = false;

  function poll(url, ms, cb) { function t() { if (url) UI.getJSON(url).then(cb).catch(function () {}); else cb(); } t(); setInterval(t, ms); }
  function host(b) { return (b || "").replace(/^.*:\/\//, "").split(":")[0]; }

  // ---- Monitor ----
  function monitor(main) {
    var cards = el("div", { class: "cards" });
    var status = el("div", { class: "card" }, [el("h3", { text: "Panel" })]);
    status.appendChild(el("div", { text: state.raw.panel_id || "—", style: "font-size:1.4rem;font-weight:600" }));
    status.appendChild(el("div", { class: "muted", text: "ha-paneld " + (state.raw.version || "") }));
    status.appendChild(el("div", { class: "muted", text: "MQTT: " + (host(state.raw.mqtt_broker) || "(none)") }));
    cards.appendChild(status);

    var perf = el("div", { class: "card" }, [el("h3", { text: "Performance" }), el("div", { class: "muted", text: "…" })]);
    cards.appendChild(perf);
    poll("/api/v1/perf", 3000, function (d) {
      perf.innerHTML = ""; perf.appendChild(el("h3", { text: "Performance" }));
      perf.appendChild(el("div", { text: (d.cpu != null ? d.cpu : "?") + "% CPU", style: "font-size:1.4rem;font-weight:600" }));
      perf.appendChild(el("div", { class: "muted", text: "RAM " + d.memUsedMb + "/" + d.memTotalMb + " MB" + (d.tempC != null ? " · " + d.tempC + "°C" : "") }));
      if (d.render) perf.appendChild(el("div", { class: "muted", text: "dashboard " + d.render.verdict }));
    });

    var shotCard = el("div", { class: "card", style: "grid-column:1/-1" }, [el("h3", { text: "Panel screen" })]);
    var img = el("img", { alt: "screen", style: "max-width:100%;border-radius:8px;border:1px solid #2a2a31" });
    function r() { img.src = "/screenshot.png?t=" + Date.now(); } r(); poll(null, 10000, r);
    shotCard.appendChild(img);
    cards.appendChild(shotCard);
    main.appendChild(cards);
  }

  // ---- Settings (searchable) ----
  function settings(main) {
    main.appendChild(el("h1", { text: "Settings" }));
    var bar = el("div", { class: "searchbar" }, [
      el("input", { type: "search", placeholder: "Search settings…", oninput: function (e) { query = e.target.value.toLowerCase(); renderList(); } }),
      el("label", { class: "modwrap" }, [
        (function () { var c = el("input", { type: "checkbox" }); c.addEventListener("change", function () { modifiedOnly = c.checked; renderList(); }); return c; })(),
        "Modified only",
      ]),
    ]);
    main.appendChild(bar);
    var list = el("div", { id: "setlist" });
    main.appendChild(list);
    var saveBtn = el("button", { class: "btn primary", text: "Save changes", disabled: "",
      onclick: function () { UI.save(state).then(function () { dirty = false; saveBtn.disabled = true; msg.textContent = "Saved · reconnecting…"; }); } });
    var msg = el("span", { class: "muted" });
    main.appendChild(el("div", { class: "savebar" }, [saveBtn, msg]));

    function onChange() { dirty = true; saveBtn.disabled = false; }
    function matches(f) {
      var hay = (f.label + " " + f.key + " " + f.group + " " + (f.help || "")).toLowerCase();
      if (query && hay.indexOf(query) < 0) return false;
      if (modifiedOnly && !f.secret && String(state.values[f.key]) === String(f.default)) return false;
      return true;
    }
    function renderList() {
      list.innerHTML = "";
      var groups = [];
      state.schema.forEach(function (f) { if (groups.indexOf(f.group) < 0) groups.push(f.group); });
      var shown = 0;
      groups.forEach(function (g) {
        var fields = state.schema.filter(function (f) { return f.group === g && f.available && matches(f); });
        if (!fields.length) return;
        list.appendChild(el("div", { class: "grp", text: g }));
        fields.forEach(function (f) {
          shown++;
          list.appendChild(el("div", { class: "setrow" }, [
            el("div", { class: "meta" }, [el("b", { text: f.label }), f.help ? el("small", { text: f.help }) : null]),
            el("div", { class: "ctl" }, [UI.pip(f, state, onChange), UI.control(f, state, onChange)]),
          ]));
        });
      });
      if (!shown) list.appendChild(el("p", { class: "muted", text: "No settings match." }));
    }
    renderList();
  }

  // ---- Tools ----
  function tools(main) {
    main.appendChild(el("h1", { text: "Tools" }));
    var cards = el("div", { class: "cards" });
    var shot = el("div", { class: "card", style: "grid-column:1/-1" }, [el("h3", { text: "Screenshot" })]);
    var img = el("img", { alt: "screen", style: "max-width:100%;border-radius:8px;border:1px solid #2a2a31" });
    function r() { img.src = "/screenshot.png?t=" + Date.now(); } r();
    shot.appendChild(img);
    shot.appendChild(el("button", { class: "btn", text: "↻ Refresh", style: "margin-top:8px", onclick: r }));
    cards.appendChild(shot);
    var act = el("div", { class: "card" }, [el("h3", { text: "On-screen actions" })]);
    [["back", "← Back"], ["recents", "▢ Recents"], ["launcher", "⊞ Launcher"], ["reboot", "⟳ Reboot"]].forEach(function (a) {
      act.appendChild(el("button", { class: "btn", text: a[1], style: "margin:0 6px 6px 0", onclick: function () { UI.action(a[0]); } }));
    });
    cards.appendChild(act);
    main.appendChild(cards);
  }

  function fleet(main) {
    main.appendChild(el("h1", { text: "Fleet" }));
    main.appendChild(el("p", { class: "muted", text: "Multi-panel roster coming soon — discovery hooks (mDNS + MQTT availability) are in place." }));
  }

  function render() {
    document.querySelectorAll(".side a").forEach(function (a) { a.classList.toggle("active", a.getAttribute("data-s") === section); });
    var main = document.getElementById("console-main");
    main.innerHTML = "";
    ({ monitor: monitor, settings: settings, tools: tools, fleet: fleet }[section] || monitor)(main);
  }
  window.cnSection = function (s) { section = s; render(); };

  UI.loadConfig().then(function (s) { state = s; render(); }).catch(function () {
    document.getElementById("console-main").textContent = "Could not load.";
  });
})();
