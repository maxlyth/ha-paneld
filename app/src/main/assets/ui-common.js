// Shared helpers for the alternative UI variants (Canvas / Console / Kiosk). All consume the same
// /api/v1 backend. Vanilla, no build. Exposed as window.UI.
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
  function getText(url) { return fetch(url).then(function (r) { return r.text(); }); }

  function postForm(url, obj) {
    var body = new URLSearchParams();
    Object.keys(obj).forEach(function (k) { if (obj[k] != null) body.set(k, obj[k]); });
    return fetch(url, {
      method: "POST",
      headers: { "Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded" },
      body: body.toString(),
    });
  }

  // Load the settings schema + current values + HA-exposure flags together.
  function loadConfig() {
    return Promise.all([getJSON("/api/v1/config/schema"), getJSON("/api/v1/config")]).then(function (res) {
      var values = res[1].settings || {};
      Object.keys(values).forEach(function (k) { if (typeof values[k] !== "string") values[k] = String(values[k]); });
      return { schema: res[0], values: values, expose: res[1].ha_expose || {}, raw: res[1] };
    });
  }

  // One input control bound to state.values[f.key]; calls onChange() when touched.
  function control(f, state, onChange) {
    var v = state.values[f.key];
    if (f.type === "BOOL") {
      var t = el("div", { class: "toggle" + (v === "true" ? " on" : ""), role: "switch", tabindex: "0" });
      t.addEventListener("click", function () {
        var nv = state.values[f.key] === "true" ? "false" : "true";
        state.values[f.key] = nv; t.classList.toggle("on", nv === "true"); onChange();
      });
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

  // The 🔗 expose-to-HA pip (only for HA-capable settings).
  function pip(f, state, onChange) {
    if (!f.ha) return null;
    var cb = el("input", { type: "checkbox" });
    cb.checked = state.expose[f.key] !== false;
    cb.addEventListener("change", function () { state.expose[f.key] = cb.checked; onChange(); });
    var lab = el("label", { class: "pip", title: "Show as an entity in Home Assistant" }, [cb, "🔗 HA"]);
    return lab;
  }

  // Save all settings (partial-merge) + expose flags. Secrets only sent when non-blank.
  function save(state) {
    var body = {};
    state.schema.forEach(function (f) {
      var v = state.values[f.key];
      if (f.secret) { if (v) body[f.key] = v; }
      else if (v != null) body[f.key] = v;
      if (f.ha) body["ha_expose_" + f.key] = state.expose[f.key] !== false ? "true" : "false";
    });
    return postForm("/api/v1/config", body);
  }

  function action(a) { return postForm("/action", { a: a }); }

  window.UI = {
    el: el, getJSON: getJSON, getText: getText, postForm: postForm,
    loadConfig: loadConfig, control: control, pip: pip, save: save, action: action,
  };
})();
