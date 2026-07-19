// Configure tab — schema-driven form. Fetches /api/v1/config/schema (metadata) + /api/v1/config
// (current values + per-key HA-exposure flags), renders Basic/Advanced grouped fields with an inline
// "expose to HA" pip on each HA-capable row, and saves via partial-merge POST. Vanilla, no build.
(function () {
  "use strict";
  // Advanced is the DEFAULT view until the reduced Basic set is settled (user, 2026-07-01).
  var schema = [], values = {}, expose = {}, advanced = true, dirty = false, saving = false, editGeneration = 0, apps = [], radio = null;
  var savedValues = {}, savedExpose = {};
  var dirtyValues = Object.create(null), dirtyExpose = Object.create(null);
  var joinCooldownUntil = 0, joinPollTimer = null, hashFocused = false;
  var haSourceItems = [], haSourceRequest = 0, haSourceTimer = null;
  var haPickerCleanup = null;
  var autoBrightStatus = null, autoBrightHistory = null, autoBrightLoading = false, autoBrightMessage = "";
  var autoBrightHistoryTimer = null, autoBrightRequest = 0;
  var HARDENED_APPROVAL_SETTING_KEYS = {
    self_update: true, update_channel: true, companion_auto_update: true,
    companion_update_channel: true, webview_auto_update: true
  };

  function approvalMessage(body) {
    return body && body.message || "Approve this request on the panel, then retry it.";
  }

  function approvalAwareJson(response) {
    return response.json().catch(function () { return {}; }).then(function (body) {
      if (response.status === 202 && body && body.error === "approval-required") {
        var error = new Error(approvalMessage(body));
        error.approvalRequired = true;
        error.body = body;
        throw error;
      }
      return body;
    });
  }

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
  function updateSaveUi() {
    var button = document.getElementById("savebtn");
    var bar = document.getElementById("savebar");
    button.disabled = !dirty || saving;
    button.textContent = saving ? "Saving…" : "Save changes";
    bar.hidden = !dirty && !saving;
    document.body.classList.toggle("cfg-dirty", dirty || saving);
  }
  function recomputeDirty() {
    dirtyValues = Object.create(null);
    dirtyExpose = Object.create(null);
    schema.forEach(function (f) {
      if (values[f.key] !== savedValues[f.key]) dirtyValues[f.key] = true;
      if (f.ha && (expose[f.key] !== false) !== (savedExpose[f.key] !== false)) dirtyExpose[f.key] = true;
    });
    dirty = Object.keys(dirtyValues).length > 0 || Object.keys(dirtyExpose).length > 0;
  }
  function setDirty() {
    editGeneration++;
    recomputeDirty();
    updateSaveUi();
  }
  function clearDirty() {
    dirty = false;
    dirtyValues = Object.create(null);
    dirtyExpose = Object.create(null);
    updateSaveUi();
  }

  function ambientLightSourceReady() {
    var selected = String(values.auto_brightness_ha_entity || "").trim();
    var statusEntity = autoBrightStatus && (autoBrightStatus.entityId || autoBrightStatus.entity_id) || "";
    var statusLux = autoBrightStatus && (autoBrightStatus.latestLux != null ? autoBrightStatus.latestLux : autoBrightStatus.latest_lux);
    var statusReady = !!(autoBrightStatus &&
      (autoBrightStatus.sourceAvailable === true || autoBrightStatus.source_available === true) &&
      typeof statusLux === "number" && isFinite(statusLux));
    if (!selected) return statusReady && !statusEntity;
    if (statusReady && statusEntity === selected) return true;
    return haSourceItems.some(function (item) {
      var id = item.entity_id || item.entityId || "";
      var lux = item.current_lux != null ? item.current_lux : item.currentLux;
      return id === selected && item.available === true && typeof lux === "number" && isFinite(lux);
    });
  }

  function ambientSourcePlaceholder() {
    if (!autoBrightStatus) return "Checking ambient light source…";
    var localPresent = autoBrightStatus.localSourcePresent === true || autoBrightStatus.local_source_present === true;
    return localPresent ? "Panel ambient light sensor" : "Select a Home Assistant illuminance sensor";
  }

  // One input control bound to values[f.key]; Save appears only while the form differs from its baseline.
  function control(f) {
    var v = values[f.key];
    if (f.type === "BOOL") {
      var sourceBlocked = f.key === "auto_brightness" && !ambientLightSourceReady();
      var t = el("div", {
        class: "toggle" + (v === "true" && !sourceBlocked ? " on" : "") + (sourceBlocked ? " blocked" : ""),
        role: "switch", tabindex: sourceBlocked ? "-1" : "0", "aria-label": f.label,
        "aria-checked": v === "true" && !sourceBlocked ? "true" : "false", "aria-disabled": sourceBlocked ? "true" : "false"
      });
      if (sourceBlocked) t.title = "Waiting for a valid ambient light reading.";
      function toggleValue() {
        if (f.key === "auto_brightness" && !ambientLightSourceReady()) return;
        v = (values[f.key] === "true") ? "false" : "true";
        values[f.key] = v;
        t.classList.toggle("on", v === "true");
        t.setAttribute("aria-checked", v === "true" ? "true" : "false");
        setDirty(f.key);
      }
      t.addEventListener("click", toggleValue);
      t.addEventListener("keydown", function (event) {
        if (event.key !== "Enter" && event.key !== " ") return;
        event.preventDefault();
        toggleValue();
      });
      return t;
    }
    if (f.type === "ENUM") {
      var s = el("select");
      f.options.forEach(function (o) {
        var op = el("option", { value: o, text: o }); if (o === v) op.selected = true; s.appendChild(op);
      });
      s.addEventListener("change", function () { values[f.key] = s.value; setDirty(f.key); });
      return s;
    }
    // Dashboard-app picker: strictly the KNOWN renderers (not every installed app) — the built-in
    // renderer, plus whichever of the HA Companion / Fully Kiosk are installed. Blank = auto-detect.
    if (f.picker === "renderer") {
      var cur = v == null ? "" : v;
      var sel = el("select", { class: "pkgsel" });
      sel.appendChild(el("option", { value: "", text: f.placeholder || "Auto-detect" }));
      var seen = { "": true };
      // Ordered known renderers; each shown only if it's the built-in one or an installed package.
      var KNOWN = [
        { pkg: "builtin", label: "Built-in renderer (ha-paneld)" },
        { pkg: "io.homeassistant.companion.android", label: "Home Assistant Companion" },
        { pkg: "io.homeassistant.companion.android.minimal", label: "Home Assistant Companion (minimal)" },
        { pkg: "de.ozerov.fully", label: "Fully Kiosk Browser" }
      ];
      var installed = {}; apps.forEach(function (a) { installed[a.pkg] = true; });
      KNOWN.forEach(function (r) {
        if (r.pkg !== "builtin" && !installed[r.pkg]) return; // only offer installed dashboard apps
        seen[r.pkg] = true;
        var op = el("option", { value: r.pkg, text: r.label });
        if (r.pkg === cur) op.selected = true;
        sel.appendChild(op);
      });
      // A currently-set value that isn't a known/installed renderer is preserved so it isn't silently lost.
      if (cur && !seen[cur]) {
        var o2 = el("option", { value: cur, text: cur + " · (not a known renderer)" });
        o2.selected = true; sel.appendChild(o2);
      }
      sel.addEventListener("change", function () {
        values[f.key] = sel.value;
        setDirty(f.key);
        // Renderer-owned rows follow the in-flight picker value immediately; saving is not required
        // just to reveal the built-in renderer's controls.
        if (f.key === "dashboard_package") render();
      });
      return sel;
    }
    // Package picker: a dropdown of installed apps. Blank = "Auto-detect"; a currently-set package that
    // isn't in the list (e.g. since-uninstalled or a manual entry) is kept as its own option.
    if (f.picker === "package") {
      var cur = v == null ? "" : v;
      var sel = el("select", { class: "pkgsel" });
      var autoLabel = f.placeholder || "Auto-detect";
      sel.appendChild(el("option", { value: "", text: autoLabel }));
      var seen = { "": true };
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
      sel.addEventListener("change", function () { values[f.key] = sel.value; setDirty(f.key); });
      return sel;
    }
    // Home Assistant illuminance source: a searchable editable combobox. The listbox is ordinary page
    // DOM rather than a browser-owned datalist so it stays inside the browser viewport on multi-monitor
    // desktops. Free-form values remain valid when HA or its entity catalog is temporarily unavailable.
    if (f.picker === "ha_illuminance") {
      var current = v == null ? "" : v;
      var sourceReadyAtRender = ambientLightSourceReady();
      var listId = "ha-illuminance-listbox";
      var input = el("input", {
        type: "text", value: current, class: "ha-entity-input", role: "combobox",
        "aria-label": f.label, "aria-autocomplete": "list", "aria-expanded": "false",
        "aria-controls": listId, "aria-haspopup": "listbox",
        placeholder: ambientSourcePlaceholder(), autocomplete: "off", maxlength: f.maxLength || 255
      });
      var list = el("div", { id: listId, class: "ha-entity-listbox", role: "listbox" });
      list.hidden = true;
      var note = el("small", { class: "picker-note", text: "Focus to load Home Assistant illuminance sensors." });
      var picker = el("div", { class: "ha-entity-picker" }, [input, note]);
      var sourcePolls = 0;
      var activeIndex = -1;
      var renderedItems = [];
      var disposed = false;

      // The listbox is a fixed body-level portal: no card overflow or transformed ancestor can move it,
      // while it still inherits the page theme and remains part of the input's ARIA relationship.
      document.body.appendChild(list);

      function viewportSize() {
        var visual = window.visualViewport;
        var left = visual && visual.offsetLeft || 0;
        var top = visual && visual.offsetTop || 0;
        var width = visual && visual.width || window.innerWidth || document.documentElement.clientWidth;
        var height = visual && visual.height || window.innerHeight || document.documentElement.clientHeight;
        return {
          left: left, top: top, width: width, height: height,
          right: left + width, bottom: top + height
        };
      }
      function positionList() {
        if (disposed || list.hidden) return;
        var rect = input.getBoundingClientRect();
        var viewport = viewportSize();
        var edge = 8, gap = 4, preferredHeight = 280, preferredWidth = 520;
        var viewportCapacity = Math.max(0, viewport.width - edge * 2);
        var minimumWidth = Math.min(Math.max(rect.width, 240), viewportCapacity);
        var desiredWidth = Math.min(Math.max(rect.width, preferredWidth), viewportCapacity);
        // Stay aligned with the input when its right-hand side has room, widening only into that space.
        // On a narrow/right-edge layout, shift left only as much as needed to preserve the old minimum.
        var left = Math.max(viewport.left + edge, Math.min(rect.left, viewport.right - minimumWidth - edge));
        var width = Math.min(desiredWidth, Math.max(0, viewport.right - edge - left));
        if (width < minimumWidth) {
          width = minimumWidth;
          left = Math.max(viewport.left + edge, viewport.right - edge - width);
        }
        var below = Math.max(0, viewport.bottom - rect.bottom - gap - edge);
        var above = Math.max(0, rect.top - viewport.top - gap - edge);
        var opensAbove = below < 160 && above > below;
        var available = opensAbove ? above : below;
        var maxHeight = Math.min(preferredHeight, Math.max(72, available), Math.max(0, viewport.height - edge * 2));
        var desiredTop = opensAbove ? rect.top - gap - maxHeight : rect.bottom + gap;
        list.style.width = width + "px";
        list.style.maxHeight = maxHeight + "px";
        list.style.left = left + "px";
        list.style.top = Math.max(viewport.top + edge, Math.min(desiredTop, viewport.bottom - edge - maxHeight)) + "px";
      }
      function setActive(index) {
        if (!renderedItems.length) index = -1;
        else if (index < 0) index = renderedItems.length - 1;
        else if (index >= renderedItems.length) index = 0;
        activeIndex = index;
        Array.prototype.forEach.call(list.children, function (option, i) {
          option.classList.toggle("active", i === activeIndex);
          option.setAttribute("aria-selected", i === activeIndex ? "true" : "false");
        });
        if (activeIndex < 0) input.removeAttribute("aria-activedescendant");
        else {
          var active = list.children[activeIndex];
          input.setAttribute("aria-activedescendant", active.id);
          if (active.scrollIntoView) active.scrollIntoView({ block: "nearest" });
        }
      }
      function closeList() {
        list.hidden = true;
        input.setAttribute("aria-expanded", "false");
        input.removeAttribute("aria-activedescendant");
        activeIndex = -1;
      }
      function choose(index) {
        var item = renderedItems[index];
        if (!item) return;
        input.value = item.id;
        values[f.key] = item.id;
        setDirty(f.key);
        closeList();
        input.focus();
      }
      function openList() {
        if (disposed || !renderedItems.length) { closeList(); return; }
        list.hidden = false;
        input.setAttribute("aria-expanded", "true");
        positionList();
      }
      function populate() {
        list.innerHTML = "";
        renderedItems = [];
        activeIndex = -1;
        haSourceItems.forEach(function (item) {
          var id = item.entity_id || item.entityId || "";
          if (!id) return;
          var name = item.friendly_name || item.friendlyName || item.name || id;
          var unit = item.unit || item.unit_of_measurement || "lx";
          var index = renderedItems.length;
          renderedItems.push({ id: id, name: name, unit: unit });
          var option = el("div", {
            id: listId + "-option-" + index, class: "ha-entity-option", role: "option",
            "aria-selected": "false"
          }, [
            el("span", { class: "ha-entity-name", text: name }),
            el("small", { text: id + (unit ? " · " + unit : "") })
          ]);
          option.addEventListener("pointerdown", function (event) {
            // Keep input focus stable until selection completes (covers mouse, pen and touch).
            event.preventDefault();
            choose(index);
          });
          // Assistive technology and old WebViews may emit click without a preceding pointer event.
          option.addEventListener("click", function () {
            if (input.value !== id || !list.hidden) choose(index);
          });
          list.appendChild(option);
        });
        if (haSourceItems.length) note.textContent = haSourceItems.length + " illuminance source" + (haSourceItems.length === 1 ? "" : "s") + " available. Blank uses the panel sensor.";
        if (document.activeElement === input) openList();
        else closeList();
      }
      function loadSources(query) {
        var request = ++haSourceRequest;
        note.textContent = "Loading Home Assistant illuminance sensors…";
        fetch("/api/v1/auto-brightness/sources?q=" + encodeURIComponent(query || "") + "&limit=200")
          .then(function (r) { if (!r.ok) throw r.status; return r.json(); })
          .then(function (body) {
            if (request !== haSourceRequest) return;
            haSourceItems = (body && (body.items || body.candidates)) || [];
            populate();
            if (!body || body.available === false) {
              note.textContent = (body && body.detail) || "Home Assistant sources are unavailable; an exact sensor entity id can still be entered.";
            } else if (!haSourceItems.length) {
              if (body.refreshing === true && sourcePolls < 20) {
                sourcePolls++;
                note.textContent = "Loading Home Assistant illuminance sensors…";
                setTimeout(function () { if (request === haSourceRequest) loadSources(query); }, 500);
              } else {
                note.textContent = "No Home Assistant illuminance sensors found; an exact sensor entity id can still be entered.";
              }
            }
          })
          .catch(function () {
            if (request !== haSourceRequest) return;
            note.textContent = "Could not load Home Assistant sources; an exact sensor entity id can still be entered.";
          });
      }
      input.addEventListener("focus", function () {
        if (!haSourceItems.length) loadSources(input.value.trim());
        else openList();
      });
      input.addEventListener("input", function () {
        values[f.key] = input.value; setDirty(f.key);
        closeList();
        if (haSourceTimer) clearTimeout(haSourceTimer);
        var query = input.value.trim();
        haSourceTimer = setTimeout(function () { loadSources(query); }, query.length >= 2 ? 250 : 500);
      });
      input.addEventListener("keydown", function (event) {
        if (event.key === "ArrowDown" || event.key === "ArrowUp") {
          if (!renderedItems.length) return;
          event.preventDefault();
          openList();
          setActive(activeIndex + (event.key === "ArrowDown" ? 1 : -1));
        } else if (event.key === "Enter" && !list.hidden && activeIndex >= 0) {
          event.preventDefault(); choose(activeIndex);
        } else if (event.key === "Escape" && !list.hidden) {
          event.preventDefault(); closeList();
        } else if (event.key === "Tab") closeList();
      });
      input.addEventListener("blur", function () {
        setTimeout(function () {
          if (disposed || document.activeElement === input) return;
          closeList();
          if (sourceReadyAtRender !== ambientLightSourceReady()) render();
        }, 0);
      });
      function outsidePointer(event) {
        if (event.target !== input && !list.contains(event.target)) closeList();
      }
      function reposition() { positionList(); }
      document.addEventListener("pointerdown", outsidePointer, true);
      window.addEventListener("resize", reposition);
      window.addEventListener("scroll", reposition, true);
      if (window.visualViewport) {
        window.visualViewport.addEventListener("resize", reposition);
        window.visualViewport.addEventListener("scroll", reposition);
      }
      haPickerCleanup = function () {
        if (disposed) return;
        disposed = true;
        haSourceRequest++; // invalidate fetches and catalog-refresh polls owned by this render
        if (haSourceTimer) { clearTimeout(haSourceTimer); haSourceTimer = null; }
        document.removeEventListener("pointerdown", outsidePointer, true);
        window.removeEventListener("resize", reposition);
        window.removeEventListener("scroll", reposition, true);
        if (window.visualViewport) {
          window.visualViewport.removeEventListener("resize", reposition);
          window.visualViewport.removeEventListener("scroll", reposition);
        }
        if (list.parentNode) list.parentNode.removeChild(list);
        haPickerCleanup = null;
      };
      populate();
      return picker;
    }
    var type = f.type === "PASSWORD" ? "password" : (f.type === "INT" || f.type === "FLOAT") ? "number" : "text";
    var inp = el("input", { type: type, value: f.secret ? "" : (v == null ? "" : v) });
    if (f.secret) inp.placeholder = "blank keeps current";
    else if (f.placeholder) inp.placeholder = f.placeholder;   // e.g. "auto (io.homeassistant…)" on package fields
    if (f.min != null) inp.min = f.min;
    if (f.max != null) inp.max = f.max;
    if (f.maxLength != null) inp.maxLength = f.maxLength;
    if (f.step != null) inp.step = f.step;
    if (f.type === "FLOAT" && f.step == null) inp.step = "any";
    if (f.key === "auto_brightness_sensitivity" && !ambientLightSourceReady()) {
      inp.disabled = true;
      inp.title = "Select an ambient light source first.";
    }
    inp.addEventListener("input", function () {
      values[f.key] = inp.value; setDirty(f.key);
      if (f.key === "auto_brightness_sensitivity") queueAutoBrightnessHistory();
    });
    return inp;
  }

  function pointValue(point, names) {
    for (var i = 0; i < names.length; i++) {
      var value = point[names[i]];
      if (typeof value === "number" && isFinite(value)) return value;
    }
    return null;
  }

  function normalizedChartPoints() {
    var raw = autoBrightHistory && (autoBrightHistory.points || autoBrightHistory.items) || [];
    return raw.map(function (point) {
      return {
        minute: pointValue(point, ["minute", "epoch_minute", "epochMinute"]),
        mean: pointValue(point, ["mean_lux", "observed_mean_lux", "observedMeanLux", "lux"]),
        min: pointValue(point, ["min_lux", "minLux"]),
        max: pointValue(point, ["max_lux", "maxLux"]),
        expected: pointValue(point, ["baseline_lux", "expected_lux", "expectedLux"]),
        brightness: pointValue(point, ["proposed_brightness", "proposedBrightness"])
      };
    }).filter(function (point) { return point.minute != null && point.mean != null; });
  }

  function drawAutoBrightnessChart() {
    var canvas = document.getElementById("auto-brightness-chart");
    if (!canvas) return;
    var points = normalizedChartPoints();
    var width = Math.max(280, canvas.clientWidth || 0), height = 190;
    var ratio = Math.min(2, window.devicePixelRatio || 1);
    canvas.width = Math.round(width * ratio); canvas.height = Math.round(height * ratio);
    var ctx = canvas.getContext("2d"); ctx.scale(ratio, ratio);
    ctx.clearRect(0, 0, width, height);
    if (!points.length) {
      ctx.fillStyle = "#888"; ctx.font = "13px sans-serif"; ctx.textAlign = "center";
      ctx.fillText("No ambient-light history yet", width / 2, height / 2);
      return;
    }
    var pad = { left: 42, right: 34, top: 10, bottom: 24 };
    var plotW = width - pad.left - pad.right, plotH = height - pad.top - pad.bottom;
    var first = points[0].minute, last = points[points.length - 1].minute;
    var maxLux = 1;
    points.forEach(function (p) { maxLux = Math.max(maxLux, p.max == null ? p.mean : p.max, p.expected || 0); });
    var maxLog = Math.log(maxLux + 1);
    function x(p) { return pad.left + (last === first ? .5 : (p.minute - first) / (last - first)) * plotW; }
    function y(value) { return pad.top + plotH - Math.log(Math.max(0, value) + 1) / maxLog * plotH; }
    function yBrightness(value) { return pad.top + plotH - Math.max(0, Math.min(255, value)) / 255 * plotH; }
    ctx.strokeStyle = "#343a44"; ctx.lineWidth = 1;
    [0, .5, 1].forEach(function (f) {
      var yy = pad.top + plotH * f; ctx.beginPath(); ctx.moveTo(pad.left, yy); ctx.lineTo(width - pad.right, yy); ctx.stroke();
    });
    // Preserve short artificial-light spikes as a translucent min/max envelope.
    ctx.fillStyle = "rgba(74,158,255,.16)"; ctx.beginPath();
    points.forEach(function (p, i) { var yy = y(p.max == null ? p.mean : p.max); if (!i) ctx.moveTo(x(p), yy); else ctx.lineTo(x(p), yy); });
    points.slice().reverse().forEach(function (p) { ctx.lineTo(x(p), y(p.min == null ? p.mean : p.min)); });
    ctx.closePath(); ctx.fill();
    function line(field, color, widthPx) {
      var started = false; ctx.beginPath();
      points.forEach(function (p) {
        var value = p[field]; if (value == null) return;
        if (!started) { ctx.moveTo(x(p), y(value)); started = true; } else ctx.lineTo(x(p), y(value));
      });
      if (started) { ctx.strokeStyle = color; ctx.lineWidth = widthPx; ctx.stroke(); }
    }
    line("mean", "#4a9eff", 1.6); line("expected", "#f1bd52", 1.4);
    var brightnessStarted = false; ctx.beginPath();
    points.forEach(function (p) {
      if (p.brightness == null) return;
      if (!brightnessStarted) { ctx.moveTo(x(p), yBrightness(p.brightness)); brightnessStarted = true; }
      else ctx.lineTo(x(p), yBrightness(p.brightness));
    });
    if (brightnessStarted) { ctx.strokeStyle = "#b77cff"; ctx.lineWidth = 1.4; ctx.stroke(); }
    ctx.fillStyle = "#888"; ctx.font = "11px sans-serif";
    ctx.textAlign = "right"; ctx.fillText(Math.round(maxLux) + " lx", pad.left - 5, pad.top + 4); ctx.fillText("0", pad.left - 5, pad.top + plotH);
    ctx.textAlign = "left"; ctx.fillText("255", width - pad.right + 5, pad.top + 4); ctx.fillText("0", width - pad.right + 5, pad.top + plotH);
    ctx.textAlign = "left"; ctx.fillText("7 days ago", pad.left, height - 6);
    ctx.textAlign = "right"; ctx.fillText("now", width - pad.right, height - 6);
  }

  function autoBrightnessSummary() {
    if (!autoBrightStatus) return autoBrightLoading ? "Loading adaptive brightness…" : "Adaptive brightness status is unavailable.";
    if (autoBrightStatus.available === false) return autoBrightStatus.detail || "Adaptive brightness runtime is unavailable.";
    var state = autoBrightStatus.state || "learning";
    if (autoBrightStatus.preferenceActive === true || autoBrightStatus.paused === true) state = "temporary preference";
    else if (state === "enabled" && autoBrightStatus.mode) state = autoBrightStatus.mode;
    var source = autoBrightStatus.source_label || autoBrightStatus.sourceLabel || autoBrightStatus.entity_id || autoBrightStatus.entityId || "panel sensor";
    return state + " · " + source;
  }

  function loadAutoBrightnessData(force) {
    if (autoBrightLoading && !force) return;
    autoBrightLoading = true;
    var request = ++autoBrightRequest;
    var sensitivity = parseInt(values.auto_brightness_sensitivity, 10);
    var suffix = isFinite(sensitivity) && sensitivity >= 0 && sensitivity <= 100
      ? "&sensitivity=" + encodeURIComponent(sensitivity) : "";
    Promise.all([
      fetch("/api/v1/auto-brightness").then(function (r) { if (!r.ok) throw r.status; return r.json(); }),
      fetch("/api/v1/auto-brightness/history?hours=168" + suffix).then(function (r) { if (!r.ok) throw r.status; return r.json(); })
    ]).then(function (result) {
      if (request !== autoBrightRequest) return;
      autoBrightStatus = result[0]; autoBrightHistory = result[1]; autoBrightMessage = "";
    }).catch(function () {
      if (request !== autoBrightRequest) return;
      autoBrightStatus = { available: false, detail: "Could not load adaptive brightness." };
      autoBrightHistory = { points: [] };
    }).then(function () {
      if (request !== autoBrightRequest) return;
      autoBrightLoading = false; render();
    });
  }

  function queueAutoBrightnessHistory() {
    if (autoBrightHistoryTimer) clearTimeout(autoBrightHistoryTimer);
    autoBrightHistoryTimer = setTimeout(function () { loadAutoBrightnessData(true); }, 300);
  }

  function runAutoBrightnessAction(path, button) {
    button.disabled = true; autoBrightMessage = "Working…"; render();
    fetch(path, { method: "POST", headers: { "Accept": "application/json" } })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (body) { if (!r.ok) throw (body.error || ("HTTP " + r.status)); return body; }); })
      .then(function () { autoBrightMessage = "Updated."; loadAutoBrightnessData(true); })
      .catch(function (error) { autoBrightMessage = "Action failed (" + error + ")."; button.disabled = false; render(); });
  }

  function autoBrightnessPanel() {
    var available = autoBrightStatus && autoBrightStatus.available !== false;
    var paused = autoBrightStatus && (
      autoBrightStatus.preferenceActive === true || autoBrightStatus.paused === true || autoBrightStatus.state === "paused"
    );
    var reset = el("button", { class: "pbtn", type: "button", text: "Reset learned history" });
    var resume = el("button", { class: "pbtn", type: "button", text: "Resume full auto" });
    reset.disabled = !available || autoBrightLoading;
    resume.disabled = !available || !paused || autoBrightLoading;
    reset.onclick = function () {
      if (confirm("Delete the seven-day ambient-light history and restart learning?")) runAutoBrightnessAction("/api/v1/auto-brightness/reset", reset);
    };
    resume.onclick = function () { runAutoBrightnessAction("/api/v1/auto-brightness/resume", resume); };
    var bucket = autoBrightHistory && (autoBrightHistory.bucket_minutes || autoBrightHistory.bucketMinutes);
    var detail = bucket ? "Observed mean and min/max envelope · " + bucket + " minute buckets" : "Observed mean and min/max envelope";
    var panel = el("div", { class: "autobright-panel", id: "auto-brightness-learning" }, [
      el("div", { class: "autobright-head" }, [
        el("div", {}, [el("strong", { text: "Seven-day ambient learning" }), el("small", { text: autoBrightnessSummary() })]),
        el("div", { class: "autobright-actions" }, [reset, resume])
      ]),
      el("canvas", { id: "auto-brightness-chart", class: "autobright-chart", role: "img", "aria-label": "Seven-day ambient illuminance history" }),
      el("div", { class: "autobright-legend" }, [
        el("span", { class: "observed", text: "Observed" }),
        el("span", { class: "expected", text: "Learned baseline" }),
        el("span", { class: "proposed", text: "Proposed level" }),
        el("small", { text: detail })
      ]),
      el("div", { class: "autobright-message" + (autoBrightMessage.indexOf("failed") >= 0 ? " error" : ""), text: autoBrightMessage })
    ]);
    setTimeout(drawAutoBrightnessChart, 0);
    return panel;
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
    btn.addEventListener("click", function () { on = !on; expose[f.key] = on; render(); setDirty(f.key, true); });
    render();
    return btn;
  }

  function row(f) {
    var help = null;
    if (f.key === "dashboard_zoom") {
      var helpKids = [document.createTextNode("Browser zoom.")];
      if (f.displaySizingAvailable === true) {
        helpKids.push(document.createTextNode(" Recommend use "));
        helpKids.push(el("a", { href: "/install#cfg-display", text: "Display Sizing" }));
        helpKids.push(document.createTextNode(" for better results"));
      }
      help = el("small", {}, helpKids);
    } else if (f.help) {
      help = el("small", { text: f.help });
    }
    var protectedSetting = !!HARDENED_APPROVAL_SETTING_KEYS[f.key];
    var labelText = el("span", { text: f.label });
    if (protectedSetting) {
      labelText.setAttribute("data-hardened-approval", "conditional");
      labelText.setAttribute("aria-describedby", "hardened-approval-conditional-description");
      labelText.setAttribute("title", "Changing this setting may require physical on-panel approval when Hardened mode is enabled.");
    }
    var label = el("div", { class: "flabel" }, [
      labelText,
      help,
    ]);
    // Read-only rows (diagnostic sensors) have no editable value — just the expose-to-HA pip.
    var valueControl = f.readOnly ? null : control(f);
    if (valueControl) {
      if (!valueControl.getAttribute("aria-label")) valueControl.setAttribute("aria-label", f.label);
      if (protectedSetting) {
        valueControl.setAttribute("aria-describedby", "hardened-approval-conditional-description");
        valueControl.setAttribute("title", "Changing this setting may require physical on-panel approval when Hardened mode is enabled.");
      }
    }
    var ctl = el("div", { class: "fctl" }, f.readOnly ? [pip(f)] : [pip(f), valueControl]);
    // Anchor id so dashboard "edit" icons can deep-link straight to this setting.
    var dependencyDisabled = (f.key === "auto_brightness" || f.key === "auto_brightness_sensitivity") && !ambientLightSourceReady();
    return el("div", {
      class: "frow" + (f.available ? "" : " muted") + (dependencyDisabled ? " dependency-disabled" : ""),
      id: "cfg-" + f.key
    }, [label, ctl]);
  }

  function radioJoined() {
    return !!(radio && radio.attributes && radio.attributes.joined === true);
  }

  function requestJoin(btn) {
    if (!radio || !radio.router_enabled || radioJoined()) return;
    if (!confirm(
      "Enable Permit join in Zigbee2MQTT or ZHA first.\n\n" +
      "This will request Repeater mode and begin a new 15-minute joining period. " +
      "It will not reboot or restart the panel.\n\n" +
      "Permit join is enabled — request join?"
    )) return;
    btn.disabled = true;
    fetch("/api/v1/radio/join", { method: "POST" })
      .then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (body) {
          if (!r.ok) throw (body.status || ("HTTP " + r.status));
          return body;
        });
      })
      .then(function () {
        joinCooldownUntil = Date.now() + 60000;
        radio.state = "starting";
        if (!radio.attributes) radio.attributes = {};
        radio.attributes.joined = null;
        render();
        pollRadio(0);
      })
      .catch(function (e) {
        btn.disabled = false;
        alert("Join request failed (" + e + ").");
      });
  }

  function zigbeeJoinRow() {
    if (!radio || !radio.present) return null;
    var joined = radioJoined();
    var enabled = radio.router_enabled === true;
    var coolingDown = Date.now() < joinCooldownUntil;
    var request = el("button", { class: "pbtn", type: "button", text: "Request join" });
    request.disabled = !enabled || joined || coolingDown;
    request.title = joined ? "This Zigbee router is already joined."
      : !enabled ? "Turn on the Zigbee router switch and save first."
      : coolingDown ? "A join request was sent recently."
      : "Request Repeater mode while coordinator permit-join is open.";
    request.onclick = function () { requestJoin(request); };
    return el("div", { class: "frow", id: "cfg-zigbee_join" }, [
      el("div", { class: "flabel" }, [
        el("span", { text: "Join Zigbee network" }),
        el("small", { text: "Open permit-join on your coordinator, then request Repeater mode." }),
      ]),
      el("div", { class: "fctl" }, [request]),
    ]);
  }

  function focusHash() {
    if (hashFocused || !location.hash) return;
    var target = document.getElementById(location.hash.slice(1));
    if (!target) return;
    hashFocused = true;
    target.scrollIntoView({ block: "center" });
    target.classList.add("flash");
    setTimeout(function () { target.classList.remove("flash"); }, 1800);
  }

  // Per-card maturity badges: [text, css-modifier]. Applied to the card heading by render().
  var CARD_BADGES = { "Logging": ["experimental", "exp"], "Display": ["experimental", "exp"] };
  var CARD_NOTES = { "Sensors": "Home Assistant reporting", "Diagnostics": "Home Assistant reporting" };
  var BUILTIN_RENDERER_KEYS = {
    dashboard_entity_learning: true, dashboard_fullscreen: true, dashboard_idle_return_min: true, dashboard_zoom: true
  };
  var BUILTIN_RENDERER_ONLY_KEYS = { dashboard_idle_return_min: true };
  var HA_CONNECTION_KEYS = { ha_url: true, ha_token: true };
  function presentationGroup(f) {
    if (f.group !== "Dashboard") return f.group;
    if (BUILTIN_RENDERER_KEYS[f.key]) return "Built-in renderer";
    if (HA_CONNECTION_KEYS[f.key]) return "Home Assistant connection";
    return "Dashboard";
  }

  var scheduleConfigColumnAlignment = window.CardColumnAlignment
    ? window.CardColumnAlignment.attach("cfg-groups")
    : function () {};

  function render() {
    var root = document.getElementById("cfg-groups");
    var proximityCard = document.querySelector("#cfg-proximity-learning");
    if (haPickerCleanup) haPickerCleanup();
    root.innerHTML = "";
    var groups = [];
    schema.forEach(function (f) {
      var group = presentationGroup(f);
      if (groups.indexOf(group) < 0) groups.push(group);
    });
    var haConnectionIndex = groups.indexOf("Home Assistant connection");
    if (haConnectionIndex > 2) {
      groups.splice(haConnectionIndex, 1);
      groups.splice(2, 0, "Home Assistant connection");
    }
    var loggingIndex = groups.indexOf("Logging");
    if (loggingIndex >= 0 && loggingIndex !== groups.length - 1) {
      groups.splice(loggingIndex, 1);
      groups.push("Logging");
    }
    var shown = 0;
    groups.forEach(function (g) {
      var fields = schema.filter(function (f) {
        return presentationGroup(f) === g && f.available &&
          (!BUILTIN_RENDERER_ONLY_KEYS[f.key] || values.dashboard_package === "builtin") &&
          (advanced || f.tier === "BASIC");
      });
      if (!fields.length) return;
      shown += fields.length;
      // Maturity badges on whole cards: log shipping and display tuning remain experimental.
      var h2kids = [el("span", { text: g })];
      if (CARD_NOTES[g]) h2kids.push(el("small", { text: " · " + CARD_NOTES[g] }));
      var badge = CARD_BADGES[g];
      if (badge) h2kids.push(el("span", { class: "cardbadge " + badge[1], text: badge[0] }));
      var card = el("div", { class: "card" }, [el("h2", {}, h2kids)]);
      card.setAttribute("data-config-group", g);
      fields.forEach(function (f) {
        card.appendChild(row(f));
        if (f.key === "zigbee_router") {
          var join = zigbeeJoinRow();
          if (join) card.appendChild(join);
        }
      });
      if (g === "Display") {
        if (ambientLightSourceReady()) card.appendChild(autoBrightnessPanel());
        if (!autoBrightStatus && !autoBrightLoading) loadAutoBrightnessData(false);
      }
      // Dashboard card action: clear the built-in renderer's browsing storage — the heal for a
      // corrupted localStorage/IndexedDB that survives reloads. Never logs the panel out (auth lives
      // in ha-paneld's config, not the WebView).
      if (g === "Built-in renderer") {
        var st = el("span", { class: "muted" });
        var btn = el("button", {
          class: "pbtn", text: "🧹 Clear renderer storage",
          "aria-describedby": "hardened-approval-description",
          title: "Requires physical on-panel approval for this action when Hardened mode is enabled."
        });
        btn.onclick = function () {
          st.textContent = "Clearing…";
          fetch("/api/v1/dashboard/clear-storage", { method: "POST" })
            .then(function (r) { return approvalAwareJson(r).then(function () { return r; }); })
            .then(function (r) { st.textContent = r.ok ? "Cleared — renderer reloading." : "Failed (HTTP " + r.status + ")"; })
            .catch(function (error) { st.textContent = error && error.approvalRequired ? error.message : "Failed (network)"; });
        };
        card.appendChild(el("div", { class: "frow" }, [
          el("div", { class: "flabel" }, [
            el("span", {
              text: "Renderer storage", "data-hardened-approval": "",
              "aria-describedby": "hardened-approval-description",
              title: "Requires physical on-panel approval for this action when Hardened mode is enabled."
            }),
            el("small", { text: "Clear cached dashboard data to fix persistent renderer corruption. Keeps sign-in." }),
          ]),
          el("div", { class: "fctl" }, [btn, st]),
        ]));
      }
      root.appendChild(card);
    });
    if (proximityCard) {
      root.insertBefore(proximityCard, root.querySelector('[data-config-group="Logging"]'));
    }
    document.getElementById("cfg-status").style.display = shown ? "none" : "block";
    if (!shown) document.getElementById("cfg-status").textContent = "No settings in this view.";
    focusHash();
    scheduleConfigColumnAlignment();
  }

  window.cfgTab = function (adv) {
    advanced = adv;
    document.getElementById("tab-basic").classList.toggle("on", !adv);
    document.getElementById("tab-adv").classList.toggle("on", adv);
    render();
  };

  // The shared navigation is server-rendered, while this page saves and reloads only the form data.
  // Detect both stale directions before saving: enabling learning (or selecting the built-in renderer)
  // must expose Entities, and disabling either prerequisite must remove it.
  function entityTabNeedsShellReload() {
    var enabledInNav = !!document.querySelector('.nav a[href^="/entities"]');
    var enabledByForm = values.dashboard_entity_learning === "true" && values.dashboard_package === "builtin";
    return enabledInNav !== enabledByForm;
  }

  // A save can finish while the user is already making a newer edit. Reloading would destroy that
  // edit, so reconcile the one server-owned nav item in place to the acknowledged saved baseline.
  function reconcileEntityTabNav(enabled) {
    var link = document.querySelector('.nav a[href^="/entities"]');
    var disabled = document.querySelector('.nav span.disabled-tab');
    var current = link || disabled;
    if (!current || (!!link) === enabled || !current.parentNode) return;
    var next = document.createElement(enabled ? "a" : "span");
    next.textContent = "Entities";
    if (enabled) next.setAttribute("href", "/entities");
    else {
      next.className = "disabled-tab";
      next.setAttribute("title", "Enable Automatic dashboard entity filter with the built-in renderer");
    }
    current.parentNode.replaceChild(next, current);
  }

  function restampConfigWatchBaseline() {
    // OUR save changes the cfg fingerprint too. Re-stamp even when newer local edits prevent a form
    // reload, otherwise buildwatch.js falsely reports those acknowledged changes as external.
    setTimeout(function () {
      fetch("/health").then(function (r) { return r.text(); }).then(function (t) {
        var m = t.match(/cfg=(\S+)/); if (m) document.body.setAttribute("data-cfg", m[1]);
      }).catch(function () {});
    }, 500);
  }

  window.cfgSave = function () {
    if (!dirty || saving) return;
    saving = true;
    var submittedGeneration = editGeneration;
    updateSaveUi();
    var reloadShellAfterSave = entityTabNeedsShellReload();
    var body = new URLSearchParams();
    var submittedValues = {}, submittedExpose = {};
    schema.forEach(function (f) {
      var v = values[f.key];
      if (dirtyValues[f.key]) {
        if (f.secret) { if (v) { body.set(f.key, v); submittedValues[f.key] = v; } } // blank password = keep current
        else if (v != null) { body.set(f.key, v); submittedValues[f.key] = v; }
      }
      if (f.ha && dirtyExpose[f.key]) {
        submittedExpose[f.key] = expose[f.key] !== false;
        body.set("ha_expose_" + f.key, submittedExpose[f.key] ? "true" : "false");
      }
    });
    var msg = document.getElementById("cfg-msg");
    msg.textContent = "Saving…";
    fetch("/api/v1/config", {
      method: "POST", headers: { "Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded" },
      body: body.toString(),
    }).then(function (r) {
      return approvalAwareJson(r).then(function (body) {
        if (!r.ok) throw new Error(body.message || body.error || ("HTTP " + r.status));
        return body;
      });
    })
      .then(function () {
        Object.keys(submittedValues).forEach(function (key) { savedValues[key] = submittedValues[key]; });
        Object.keys(submittedExpose).forEach(function (key) { savedExpose[key] = submittedExpose[key]; });
        recomputeDirty();
        loadRadio();
        restampConfigWatchBaseline();
        if (editGeneration !== submittedGeneration) {
          reconcileEntityTabNav(savedValues.dashboard_entity_learning === "true" && savedValues.dashboard_package === "builtin");
          saving = false;
          msg.textContent = dirty ? "Saved; newer changes still need saving." : "Saved.";
          updateSaveUi();
          return;
        }
        saving = false;
        msg.textContent = "Saving…"; clearDirty();
        // Re-render the server-owned navigation when its eligibility changed. This is deliberately
        // symmetric so the tab cannot remain incorrectly disabled OR enabled after a successful save.
        if (reloadShellAfterSave) { location.reload(); return; }
        // Reload the form from the server, then land on a terminal message — don't leave a
        // "reconnecting…" string hanging (it reads as stuck even though the save is done).
        load(function (ok) {
          msg.textContent = ok ? "Saved." : "Saved (reload failed — refresh the page).";
          if (ok && (Object.prototype.hasOwnProperty.call(submittedValues, "auto_brightness") ||
              Object.prototype.hasOwnProperty.call(submittedValues, "auto_brightness_sensitivity") ||
              Object.prototype.hasOwnProperty.call(submittedValues, "auto_brightness_ha_entity"))) {
            loadAutoBrightnessData(true);
          }
          if (ok) setTimeout(function () { if (msg.textContent === "Saved.") msg.textContent = ""; }, 2500);
        });
      })
      .catch(function (e) {
        saving = false;
        msg.textContent = e && e.approvalRequired ? e.message : "Save failed (" + e + ")";
        updateSaveUi();
      });
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
      savedValues = Object.assign({}, values);
      savedExpose = Object.assign({}, expose);
      // A successful full reload replaces the form's local snapshot, so no previously tracked edit remains.
      clearDirty();
      render();
      if (done) done(true);
    }).catch(function (e) {
      document.getElementById("cfg-status").textContent = "Could not load settings (" + e + ").";
      if (done) done(false);
    });
  }

  function loadRadio() {
    return fetch("/api/v1/radio").then(function (r) { return r.json(); }).then(function (body) {
      radio = body && body.present ? body : null;
      render();
      return radio;
    }).catch(function () {
      radio = null;
      render();
      return null;
    });
  }

  function pollRadio(n) {
    if (joinPollTimer) clearTimeout(joinPollTimer);
    loadRadio().then(function () {
      if (radioJoined() || !radio || !radio.router_enabled || n >= 180) return;
      joinPollTimer = setTimeout(function () { pollRadio(n + 1); }, 5000);
    });
  }

  load();
  loadRadio();
})();
