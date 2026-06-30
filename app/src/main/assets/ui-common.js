// Shared toolkit for the alternative UI variants (Canvas / Console / Kiosk). All consume the same
// /api/v1 backend. Composables return DOM elements each variant arranges in its own idiom, so every
// variant has the SAME feature depth (interactive screenshot, full settings, bundles, health, TTS).
// Vanilla, no build. Exposed as window.UI.
(function () {
  "use strict";

  function el(tag, attrs, kids) {
    var e = document.createElement(tag);
    attrs = attrs || {};
    for (var k in attrs) {
      if (k === "class") e.className = attrs[k];
      else if (k === "text") e.textContent = attrs[k];
      else if (k === "html") e.innerHTML = attrs[k];
      else if (k.indexOf("on") === 0 && typeof attrs[k] === "function") e.addEventListener(k.slice(2), attrs[k]);
      else if (attrs[k] != null) e.setAttribute(k, attrs[k]);
    }
    (kids || []).forEach(function (c) { if (c != null) e.appendChild(typeof c === "string" ? document.createTextNode(c) : c); });
    return e;
  }

  function getJSON(url) { return fetch(url).then(function (r) { return r.ok ? r.json() : Promise.reject(r.status); }); }

  function postForm(url, obj) {
    var body = new URLSearchParams();
    Object.keys(obj).forEach(function (k) { if (obj[k] != null) body.set(k, obj[k]); });
    return fetch(url, { method: "POST", headers: { "Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded" }, body: body.toString() });
  }

  function loadConfig() {
    return Promise.all([getJSON("/api/v1/config/schema"), getJSON("/api/v1/config")]).then(function (res) {
      var values = res[1].settings || {};
      Object.keys(values).forEach(function (k) { if (typeof values[k] !== "string") values[k] = String(values[k]); });
      return { schema: res[0], values: values, expose: res[1].ha_expose || {}, raw: res[1] };
    });
  }

  function control(f, state, onChange) {
    var v = state.values[f.key];
    if (f.type === "BOOL") {
      var t = el("div", { class: "toggle" + (v === "true" ? " on" : ""), role: "switch", tabindex: "0" });
      t.addEventListener("click", function () { var nv = state.values[f.key] === "true" ? "false" : "true"; state.values[f.key] = nv; t.classList.toggle("on", nv === "true"); onChange(); });
      return t;
    }
    if (f.type === "ENUM") {
      var s = el("select");
      (f.options || []).forEach(function (o) { var op = el("option", { value: o, text: o }); if (o === v) op.selected = true; s.appendChild(op); });
      s.addEventListener("change", function () { state.values[f.key] = s.value; onChange(); });
      return s;
    }
    var type = f.type === "PASSWORD" ? "password" : (f.type === "INT" || f.type === "FLOAT") ? "number" : "text";
    var inp = el("input", { type: type, value: f.secret ? "" : (v == null ? "" : v) });
    if (f.secret) inp.placeholder = "blank keeps current";
    if (f.min != null) inp.min = f.min;
    if (f.max != null) inp.max = f.max;
    if (f.step != null) inp.step = f.step;
    inp.addEventListener("input", function () { state.values[f.key] = inp.value; onChange(); });
    return inp;
  }

  function pip(f, state, onChange) {
    if (!f.ha) return null;
    var cb = el("input", { type: "checkbox" });
    cb.checked = state.expose[f.key] !== false;
    cb.addEventListener("change", function () { state.expose[f.key] = cb.checked; onChange(); });
    return el("label", { class: "pip", title: "Show as an entity in Home Assistant" }, [cb, "🔗 HA"]);
  }

  function save(state) {
    var body = {};
    state.schema.forEach(function (f) {
      var v = state.values[f.key];
      if (f.secret) { if (v) body[f.key] = v; } else if (v != null) body[f.key] = v;
      if (f.ha) body["ha_expose_" + f.key] = state.expose[f.key] !== false ? "true" : "false";
    });
    return postForm("/api/v1/config", body);
  }

  function action(a) { return postForm("/action", { a: a }); }

  function poll(url, ms, cb) { function t() { if (url) getJSON(url).then(cb).catch(function () {}); else cb(); } t(); var id = setInterval(t, ms); return id; }

  // ---- composables (each returns a DOM element with the SAME feature as the Tabbed UI) ----

  // Interactive screenshot: View / Control toggle; in Control, tapping maps to device px → /api/v1/input.
  function screenshot() {
    var wrap = el("div");
    var img = el("img", { alt: "panel screen", style: "max-width:100%;border-radius:8px;border:1px solid #2a2a31;display:block" });
    var mode = "view", timer = null;
    function refresh() { img.src = "/screenshot.png?t=" + Date.now(); }
    function schedule() { clearInterval(timer); timer = setInterval(refresh, mode === "control" ? 1500 : 8000); }
    var vbtn = el("button", { class: "btn on", text: "👁 View" });
    var cbtn = el("button", { class: "btn", text: "✋ Control" });
    var hint = el("div", { class: "muted", style: "margin-top:6px;display:none", text: "Tap the image to send a touch to the panel (needs root)." });
    vbtn.addEventListener("click", function () { mode = "view"; vbtn.classList.add("on"); cbtn.classList.remove("on"); img.style.cursor = "default"; hint.style.display = "none"; schedule(); });
    cbtn.addEventListener("click", function () { mode = "control"; cbtn.classList.add("on"); vbtn.classList.remove("on"); img.style.cursor = "crosshair"; hint.style.display = "block"; schedule(); });
    img.addEventListener("click", function (e) {
      if (mode !== "control" || !img.naturalWidth) return;
      var r = img.getBoundingClientRect();
      var x = Math.round((e.clientX - r.left) / r.width * img.naturalWidth);
      var y = Math.round((e.clientY - r.top) / r.height * img.naturalHeight);
      postForm("/api/v1/input", { x: x, y: y }).then(function (resp) { if (resp.status === 503) hint.textContent = "No tap-injection capability on this panel (needs root)."; setTimeout(refresh, 500); });
    });
    wrap.appendChild(el("div", { style: "display:flex;gap:8px;margin-bottom:8px;flex-wrap:wrap" }, [vbtn, cbtn, el("button", { class: "btn", text: "↻", onclick: refresh })]));
    wrap.appendChild(img); wrap.appendChild(hint);
    refresh(); schedule();
    return wrap;
  }

  function navActions() {
    var d = el("div", { style: "display:flex;gap:8px;flex-wrap:wrap" });
    [["back", "← Back"], ["recents", "▢ Recents"], ["launcher", "⊞ Launcher"], ["admin_launcher", "⚙ Admin"], ["voldn", "Vol −"], ["volup", "Vol +"], ["reboot", "⟳ Reboot"]]
      .forEach(function (a) { d.appendChild(el("button", { class: "btn", text: a[1], onclick: function () { action(a[0]); } })); });
    return d;
  }

  function tts() {
    var inp = el("input", { type: "text", placeholder: "https://…/clip.mp3", style: "flex:1;min-width:180px" });
    var msg = el("span", { class: "muted" });
    var btn = el("button", { class: "btn", text: "▶ Play", onclick: function () {
      var u = inp.value.trim(); if (!u) return; msg.textContent = "playing…";
      fetch("/play", { method: "POST", body: u }).then(function (r) { return r.text(); }).then(function (t) { msg.textContent = t.trim(); }).catch(function () { msg.textContent = "failed"; });
    } });
    return el("div", { style: "display:flex;gap:8px;flex-wrap:wrap;align-items:center" }, [inp, btn, msg]);
  }

  function importFile(input, out) {
    var file = input.files && input.files[0]; if (!file) return;
    file.text().then(function (text) {
      return fetch("/api/v1/config/import?dry_run=1", { method: "POST", body: text }).then(function (r) { return r.json(); }).then(function (dry) {
        var n = (dry.changes || []).length;
        var sum = n ? dry.changes.map(function (c) { return "  " + c.key + ": " + (c.from == null ? "(unset)" : c.from) + " → " + c.to; }).join("\n") : "  (no changes)";
        if (!confirm("Import will change " + n + " setting(s):\n\n" + sum + "\n\nApply now?")) { out.textContent = "Cancelled."; return; }
        return fetch("/api/v1/config/import", { method: "POST", body: text }).then(function (r) { return r.json(); }).then(function (res) {
          out.textContent = "Import " + res.status + " · applied " + (res.applied || []).length + ", skipped " + (res.skipped || []).length +
            ((res.warnings || []).length ? "\nwarnings: " + res.warnings.join("; ") : "") + ((res.errors || []).length ? "\nerrors: " + res.errors.join("; ") : "");
        });
      });
    }).catch(function (e) { out.textContent = "failed: " + e; });
    input.value = "";
  }

  function bundle() {
    var out = el("pre", { class: "muted", style: "white-space:pre-wrap;margin-top:10px" });
    var f = el("input", { type: "file", accept: "application/json", style: "display:none" });
    f.addEventListener("change", function () { importFile(f, out); });
    var imp = el("label", { class: "btn", style: "cursor:pointer" }, ["⭱ Import…", f]);
    return el("div", {}, [
      el("div", { style: "display:flex;gap:10px;flex-wrap:wrap;align-items:center" }, [
        el("a", { class: "btn", href: "/api/v1/config/export", text: "⭳ Export" }),
        el("a", { class: "btn", href: "/api/v1/config/export?include_secrets=1", text: "⭳ Export + secrets" }),
        imp,
      ]),
      out,
    ]);
  }

  // Full settings form (all settings, grouped, expose pips, save). opts: {search, filter}.
  function settingsForm(state, opts) {
    opts = opts || {};
    var query = "", modOnly = false, dirty = false;
    var msg = el("span", { class: "muted" });
    var saveBtn = el("button", { class: "btn primary", text: "Save changes", disabled: "", onclick: function () {
      UI.save(state).then(function () { dirty = false; saveBtn.disabled = true; msg.textContent = "Saved · reconnecting…"; });
    } });
    function onChange() { dirty = true; saveBtn.disabled = false; }
    var list = el("div");
    var root = el("div");
    if (opts.search) {
      var c = el("input", { type: "checkbox" });
      c.addEventListener("change", function () { modOnly = c.checked; render(); });
      root.appendChild(el("div", { class: "searchbar" }, [
        el("input", { type: "search", placeholder: "Search settings…", oninput: function (e) { query = e.target.value.toLowerCase(); render(); } }),
        el("label", { class: "modwrap" }, [c, "Modified only"]),
      ]));
    }
    root.appendChild(list);
    root.appendChild(el("div", { class: "savebar" }, [saveBtn, msg]));
    function match(f) {
      if (opts.filter && !opts.filter(f)) return false;
      if (query) { var hay = (f.label + " " + f.key + " " + f.group + " " + (f.help || "")).toLowerCase(); if (hay.indexOf(query) < 0) return false; }
      if (modOnly && !f.secret && String(state.values[f.key]) === String(f.default)) return false;
      return true;
    }
    function render() {
      list.innerHTML = "";
      var groups = []; state.schema.forEach(function (f) { if (groups.indexOf(f.group) < 0) groups.push(f.group); });
      var shown = 0;
      groups.forEach(function (g) {
        var fields = state.schema.filter(function (f) { return f.group === g && f.available && match(f); });
        if (!fields.length) return;
        list.appendChild(el("div", { class: "grp", text: g }));
        fields.forEach(function (f) {
          shown++;
          list.appendChild(el("div", { class: "setrow" }, [
            el("div", { class: "meta" }, [el("b", { text: f.label }), f.help ? el("small", { text: f.help }) : null]),
            el("div", { class: "ctl" }, [pip(f, state, onChange), control(f, state, onChange)]),
          ]));
        });
      });
      if (!shown) list.appendChild(el("p", { class: "muted", text: "No settings match." }));
    }
    render();
    return root;
  }

  // Install/health panel: warnings (WebView/dashboard/updates) + capabilities matrix, from /api/v1/status.
  function installPanel() {
    var d = el("div");
    getJSON("/api/v1/status").then(function (s) {
      (s.warnings || []).forEach(function (w) { d.appendChild(el("div", { class: "warn", html: w })); });
      if (!(s.warnings || []).length) d.appendChild(el("p", { class: "muted", text: "✓ No setup problems detected." }));
      var tbl = el("table", { class: "captbl" });
      (s.capabilities || []).forEach(function (c) {
        tbl.appendChild(el("tr", {}, [el("th", { text: c.name }), el("td", { html: '<span style="color:' + (c.color || "#888") + '">●</span> ' + c.note })]));
      });
      d.appendChild(tbl);
      d.appendChild(el("p", { class: "muted" }, [el("a", { href: "/diag", target: "_blank", text: "⭳ Diagnostics dump" })]));
    }).catch(function () { d.appendChild(el("p", { class: "muted", text: "status unavailable" })); });
    return d;
  }

  window.UI = {
    el: el, getJSON: getJSON, postForm: postForm, loadConfig: loadConfig, poll: poll,
    control: control, pip: pip, save: save, action: action,
    screenshot: screenshot, navActions: navActions, tts: tts, bundle: bundle,
    settingsForm: settingsForm, installPanel: installPanel,
  };
})();
