// Configure tab — schema-driven form. Fetches /api/v1/config/schema (metadata) + /api/v1/config
// (current values + per-key HA-exposure flags), renders Basic/Advanced grouped fields with an inline
// "expose to HA" pip on each HA-capable row, and saves via partial-merge POST. Vanilla, no build.
(function () {
  "use strict";
  // Advanced is the DEFAULT view until the reduced Basic set is settled (user, 2026-07-01).
  var schema = [], values = {}, expose = {}, advanced = true, dirty = false, apps = [];

  function el(tag, attrs, kids) {
    var e = document.createElement(tag);
    attrs = attrs || {};
    for (var k in attrs) {
      if (k === "class") e.className = attrs[k];
      else if (k === "text") e.textContent = attrs[k];
      else if (k === "html") e.innerHTML = attrs[k];
      else e.setAttribute(k, attrs[k]);
    }
    (kids || []).forEach(function (c) { if (c) e.appendChild(c); });
    return e;
  }
  function setDirty() { dirty = true; document.getElementById("savebtn").disabled = false; }

  // One input control bound to values[f.key]; flips Save on when touched.
  function control(f) {
    var v = values[f.key];
    if (f.type === "BOOL") {
      var t = el("div", { class: "toggle" + (v === "true" ? " on" : ""), role: "switch", tabindex: "0" });
      t.addEventListener("click", function () {
        v = (values[f.key] === "true") ? "false" : "true";
        values[f.key] = v; t.classList.toggle("on", v === "true"); setDirty();
      });
      return t;
    }
    if (f.type === "ENUM") {
      var s = el("select");
      f.options.forEach(function (o) {
        var op = el("option", { value: o, text: o }); if (o === v) op.selected = true; s.appendChild(op);
      });
      s.addEventListener("change", function () { values[f.key] = s.value; setDirty(); });
      return s;
    }
    // Package picker: a dropdown of installed apps. Blank = "Auto-detect"; a currently-set package that
    // isn't in the list (e.g. since-uninstalled or a manual entry) is kept as its own option.
    if (f.picker === "package") {
      var cur = v == null ? "" : v;
      var sel = el("select", { class: "pkgsel" });
      var autoLabel = f.placeholder || "Auto-detect";
      sel.appendChild(el("option", { value: "", text: autoLabel }));
      var seen = {};
      apps.forEach(function (a) {
        seen[a.pkg] = true;
        var op = el("option", { value: a.pkg, text: a.label + " · " + a.pkg });
        if (a.pkg === cur) op.selected = true;
        sel.appendChild(op);
      });
      if (cur && !seen[cur]) {
        var o2 = el("option", { value: cur, text: cur + " · (not installed)" });
        o2.selected = true; sel.appendChild(o2);
      }
      sel.addEventListener("change", function () { values[f.key] = sel.value; setDirty(); });
      return sel;
    }
    var type = f.type === "PASSWORD" ? "password" : (f.type === "INT" || f.type === "FLOAT") ? "number" : "text";
    var inp = el("input", { type: type, value: f.secret ? "" : (v == null ? "" : v) });
    if (f.secret) inp.placeholder = "blank keeps current";
    else if (f.placeholder) inp.placeholder = f.placeholder;   // e.g. "auto (io.homeassistant…)" on package fields
    if (f.min != null) inp.min = f.min;
    if (f.max != null) inp.max = f.max;
    if (f.step != null) inp.step = f.step;
    if (f.type === "FLOAT" && f.step == null) inp.step = "any";
    inp.addEventListener("input", function () { values[f.key] = inp.value; setDirty(); });
    return inp;
  }

  // Link / broken-link icons (Lucide link + unlink — a complementary pair; currentColor so CSS tints them).
  var SVG_ATTRS = 'viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"';
  var ICON_LINK = '<svg ' + SVG_ATTRS + '><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>';
  var ICON_UNLINK = '<svg ' + SVG_ATTRS + '><path d="M18.84 12.25l1.72-1.71a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M5.17 11.75l-1.71 1.71a5 5 0 0 0 7.07 7.07l1.71-1.71"/><line x1="8" y1="2" x2="8" y2="5"/><line x1="2" y1="8" x2="5" y2="8"/><line x1="16" y1="19" x2="16" y2="22"/><line x1="19" y1="16" x2="22" y2="16"/></svg>';

  // Expose-to-HA toggle (only on settings that are HA entities): an icon button, no checkbox —
  // a link icon = exposed as an HA entity (highlighted), a broken-link icon = hidden. Click toggles it.
  function pip(f) {
    if (!f.ha) return null;
    var on = expose[f.key] !== false;
    var btn = el("button", { class: "pip", type: "button" });
    function render() {
      btn.classList.toggle("on", on);
      btn.innerHTML = on ? ICON_LINK : ICON_UNLINK;
      btn.title = on ? "Exposed to Home Assistant — click to hide"
                     : "Hidden from Home Assistant — click to expose";
      btn.setAttribute("aria-label", on ? "Exposed to Home Assistant" : "Hidden from Home Assistant");
      btn.setAttribute("aria-pressed", on ? "true" : "false");
    }
    btn.addEventListener("click", function () { on = !on; expose[f.key] = on; render(); setDirty(); });
    render();
    return btn;
  }

  function row(f) {
    var label = el("div", { class: "flabel" }, [
      el("span", { text: f.label }),
      f.help ? el("small", { text: f.help }) : null,
    ]);
    // Read-only rows (diagnostic sensors) have no editable value — just the expose-to-HA pip.
    var ctl = el("div", { class: "fctl" }, f.readOnly ? [pip(f)] : [pip(f), control(f)]);
    // Anchor id so dashboard "edit" icons can deep-link straight to this setting.
    return el("div", { class: "frow" + (f.available ? "" : " muted"), id: "cfg-" + f.key }, [label, ctl]);
  }

  function render() {
    var root = document.getElementById("cfg-groups");
    root.innerHTML = "";
    var groups = [];
    schema.forEach(function (f) { if (groups.indexOf(f.group) < 0) groups.push(f.group); });
    var shown = 0;
    groups.forEach(function (g) {
      var fields = schema.filter(function (f) {
        return f.group === g && f.available && (advanced || f.tier === "BASIC");
      });
      if (!fields.length) return;
      shown += fields.length;
      var card = el("div", { class: "card" }, [el("h2", { text: g })]);
      fields.forEach(function (f) { card.appendChild(row(f)); });
      root.appendChild(card);
    });
    document.getElementById("cfg-status").style.display = shown ? "none" : "block";
    if (!shown) document.getElementById("cfg-status").textContent = "No settings in this view.";
  }

  window.cfgTab = function (adv) {
    advanced = adv;
    document.getElementById("tab-basic").classList.toggle("on", !adv);
    document.getElementById("tab-adv").classList.toggle("on", adv);
    render();
  };

  window.cfgSave = function () {
    var body = new URLSearchParams();
    schema.forEach(function (f) {
      var v = values[f.key];
      if (f.secret) { if (v) body.set(f.key, v); }       // blank password = keep current
      else if (v != null) body.set(f.key, v);
      if (f.ha) body.set("ha_expose_" + f.key, expose[f.key] !== false ? "true" : "false");
    });
    var msg = document.getElementById("cfg-msg");
    msg.textContent = "Saving…";
    fetch("/api/v1/config", {
      method: "POST", headers: { "Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded" },
      body: body.toString(),
    }).then(function (r) { return r.ok ? r.json() : Promise.reject(r.status); })
      .then(function () {
        msg.textContent = "Saving…"; dirty = false; document.getElementById("savebtn").disabled = true;
        // Reload the form from the server, then land on a terminal message — don't leave a
        // "reconnecting…" string hanging (it reads as stuck even though the save is done).
        load(function (ok) {
          msg.textContent = ok ? "Saved." : "Saved (reload failed — refresh the page).";
          if (ok) setTimeout(function () { if (msg.textContent === "Saved.") msg.textContent = ""; }, 2500);
        });
      })
      .catch(function (e) { msg.textContent = "Save failed (" + e + ")"; });
  };

  window.cfgImport = function (input) {
    var file = input.files && input.files[0]; if (!file) return;
    var out = document.getElementById("imp-result");
    file.text().then(function (text) {
      // Dry-run first so the user sees the diff before anything is written.
      return fetch("/api/v1/config/import?dry_run=1", { method: "POST", body: text })
        .then(function (r) { return r.json(); })
        .then(function (dry) {
          var n = (dry.changes || []).length;
          var summary = n ? dry.changes.map(function (c) { return "  " + c.key + ": " + (c.from == null ? "(unset)" : c.from) + " → " + c.to; }).join("\n") : "  (no changes)";
          if (!confirm("Import will change " + n + " setting(s):\n\n" + summary + "\n\nApply now?")) { out.textContent = "Import cancelled."; return; }
          return fetch("/api/v1/config/import", { method: "POST", body: text })
            .then(function (r) { return r.json(); })
            .then(function (res) {
              out.textContent = "Import " + res.status + " · applied " + (res.applied || []).length +
                ", skipped " + (res.skipped || []).length +
                ((res.warnings || []).length ? "\nwarnings:\n  " + res.warnings.join("\n  ") : "") +
                ((res.errors || []).length ? "\nerrors:\n  " + res.errors.join("\n  ") : "");
              load();
            });
        });
    }).catch(function (e) { out.textContent = "Import failed: " + e; });
    input.value = "";
  };

  function load(done) {
    Promise.all([
      fetch("/api/v1/config/schema").then(function (r) { return r.json(); }),
      fetch("/api/v1/config").then(function (r) { return r.json(); }),
      // Installed launchable apps for the package pickers; tolerate failure (picker falls back to text).
      fetch("/api/v1/apps").then(function (r) { return r.json(); }).catch(function () { return { apps: [] }; }),
    ]).then(function (res) {
      schema = res[0];
      values = res[1].settings || {};
      expose = res[1].ha_expose || {};
      apps = (res[2] && res[2].apps) || [];
      // Normalize bool values to the "true"/"false" strings the toggle compares against.
      schema.forEach(function (f) {
        if (f.type === "BOOL" && typeof values[f.key] === "boolean") values[f.key] = values[f.key] ? "true" : "false";
        if (values[f.key] != null && typeof values[f.key] !== "string") values[f.key] = String(values[f.key]);
      });
      render();
      // Deep-link support: /configure#cfg-<key> scrolls to and flashes the target setting/card.
      if (location.hash) {
        var t = document.getElementById(location.hash.slice(1));
        if (t) {
          t.scrollIntoView({ block: "center" });
          t.classList.add("flash");
          setTimeout(function () { t.classList.remove("flash"); }, 1800);
        }
      }
      if (done) done(true);
    }).catch(function (e) {
      document.getElementById("cfg-status").textContent = "Could not load settings (" + e + ").";
      if (done) done(false);
    });
  }

  load();
})();
