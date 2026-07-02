// Configure tab — schema-driven form. Fetches /api/v1/config/schema (metadata) + /api/v1/config
// (current values + per-key HA-exposure flags), renders Basic/Advanced grouped fields with an inline
// "expose to HA" pip on each HA-capable row, and saves via partial-merge POST. Vanilla, no build.
(function () {
  "use strict";
  // Advanced is the DEFAULT view until the reduced Basic set is settled (user, 2026-07-01).
  var schema = [], values = {}, expose = {}, advanced = true, dirty = false;

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

  // The little 🔗 expose-to-HA pip (only on settings that are HA entities).
  function pip(f) {
    if (!f.ha) return null;
    var cb = el("input", { type: "checkbox" });
    cb.checked = expose[f.key] !== false;
    cb.addEventListener("change", function () { expose[f.key] = cb.checked; setDirty(); });
    var lab = el("label", { class: "pip", title: "Show this as an entity in Home Assistant" }, [cb]);
    lab.appendChild(document.createTextNode("🔗 HA"));
    return lab;
  }

  function row(f) {
    var label = el("div", { class: "flabel" }, [
      el("span", { text: f.label }),
      f.help ? el("small", { text: f.help }) : null,
    ]);
    var ctl = el("div", { class: "fctl" }, [pip(f), control(f)]);
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
      .then(function () { msg.textContent = "Saved · reconnecting MQTT…"; dirty = false; document.getElementById("savebtn").disabled = true; load(); })
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

  function load() {
    Promise.all([
      fetch("/api/v1/config/schema").then(function (r) { return r.json(); }),
      fetch("/api/v1/config").then(function (r) { return r.json(); }),
    ]).then(function (res) {
      schema = res[0];
      values = res[1].settings || {};
      expose = res[1].ha_expose || {};
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
    }).catch(function (e) {
      document.getElementById("cfg-status").textContent = "Could not load settings (" + e + ").";
    });
  }

  load();
})();
