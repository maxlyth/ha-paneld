// Configure tab — schema-driven form. Fetches /api/v1/config/schema (metadata) + /api/v1/config
// (current values + per-key HA-exposure flags), renders Basic/Advanced grouped fields with an inline
// "expose to HA" pip on each HA-capable row, and saves via partial-merge POST. Vanilla, no build.
(function () {
  "use strict";
  // Advanced is the DEFAULT view until the reduced Basic set is settled (user, 2026-07-01).
  var schema = [], values = {}, expose = {}, haAuth = {}, applyPending = {}, applyPendingTimer = null, advanced = true, dirty = false, saving = false, editGeneration = 0, configDiscoveryRequest = 0, apps = [], radio = null;
  var savedValues = {}, savedExpose = {};
  var dirtyValues = Object.create(null), dirtyExpose = Object.create(null);
  var joinCooldownUntil = 0, joinPollTimer = null, hashFocused = false;
  var haSourceItems = [], haSourceRequest = 0, haSourceTimer = null;
  var homeDashboardItems = [], homeDashboardRequest = 0, homeDashboardQueried = false;
  // A per-load, owner-safe seed supplied by the config response. The endpoint is still fetched every
  // render so a failed query, credential change or Home Assistant area edit can recover immediately.
  var haAreaSeed = null, haAreaSeedGeneration = 0, haAreaCatalogRequest = 0, haAreaUserOverride = false;
  var homeDashboardDefault = { explicit: false, path: "" };
  var haPickerCleanups = [];
  var haPickerCleanup = function () {
    haPickerCleanups.splice(0).forEach(function (cleanup) { cleanup(); });
  };
  var autoBrightStatus = null, autoBrightHistory = null, autoBrightLoading = false, autoBrightMessage = "";
  var autoBrightHistoryTimer = null, autoBrightRefreshTimer = null, autoBrightTransitionTimer = null, autoBrightRequest = 0;
  var autoBrightSourceTransition = false, autoBrightTransitionAttempts = 0, autoBrightTransitionSource = "";
  var AUTO_BRIGHTNESS_REFRESH_MS = 5 * 60 * 1000;
  var AUTO_BRIGHTNESS_TRANSITION_POLL_MS = 1000, AUTO_BRIGHTNESS_TRANSITION_MAX_ATTEMPTS = 10;
  var autoSleepStatus = null, autoSleepLoading = false, autoSleepRequest = 0;
  var autoSleepHistory = null, autoSleepHistoryLoading = false, autoSleepHistoryError = "", autoSleepHistoryRequest = 0;
  var autoSleepHistoryHours = 24;
  var autoSleepSourceUpdating = Object.create(null);
  var autoSleepHistoryWaiting = false, autoSleepHistoryWaitingMessage = "", autoSleepHistoryReadyTimer = null;
  var autoSleepReadinessDelayMs = 1000, autoSleepHistoryRetryDelayMs = 5000;
  var autoSleepPrerequisite = { eligible: false, phase: "checking", area_name: "" };
  var autoSleepPrerequisiteRequest = 0, autoSleepPrerequisiteTimer = null;
  var autoSleepAssignedAreaName = "", autoSleepAreaGeneration = 0;
  var haOauthButton = null, haOauthStatus = null, haOauthLinks = null;
  var haOauthAuthorizationUrl = "", haOauthTargetUrl = "";
  var haUserStatus = { phase: "unknown" }, haUserStatusRequest = 0;
  var configCardRoot = document.getElementById("cfg-groups");
  var configCardExpected = { core: true, radio: true, brightness: true };
  var configCardReady = Object.create(null), configCardMemoryReady = false;
  if (configCardRoot && configCardRoot.getAttribute("data-card-size-proximity") === "1") configCardExpected.proximity = true;
  function configCardSourceReady(source) {
    if (configCardReady[source]) return;
    configCardReady[source] = true;
    var complete = Object.keys(configCardExpected).every(function (key) { return configCardReady[key]; });
    if (!complete) return;
    configCardMemoryReady = true;
    if (window.CardSizeMemory) window.CardSizeMemory.settle("cfg-groups", 1200);
  }
  function configCardGeometryChanged() {
    if (configCardMemoryReady && window.CardSizeMemory) window.CardSizeMemory.settle("cfg-groups", 1200);
  }
  function configCardGeometryInvalid() {
    if (window.CardSizeMemory) window.CardSizeMemory.invalidate("cfg-groups");
  }
  window.configCardSizeSourceReady = configCardSourceReady;
  window.configCardSizeGeometryChanged = configCardGeometryChanged;
  window.configCardSizeGeometryInvalid = configCardGeometryInvalid;
  var HARDENED_APPROVAL_SETTING_KEYS = {
    self_update: true, update_channel: true, companion_auto_update: true,
    companion_update_channel: true, webview_auto_update: true,
    keep_awake: true, prevent_idle_dim: true
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
    syncHaOAuthAvailability();
  }
  function clearDirty() {
    dirty = false;
    dirtyValues = Object.create(null);
    dirtyExpose = Object.create(null);
    updateSaveUi();
  }

  function validHaUrlForOAuth() {
    try {
      var url = new URL(String(values.ha_url || "").trim());
      return (url.protocol === "http:" || url.protocol === "https:") && !!url.hostname &&
        !url.username && !url.password && !url.search && !url.hash;
    } catch (_) { return false; }
  }

  function syncHaOAuthAvailability() {
    if (!haOauthButton) return;
    haOauthButton.disabled = !validHaUrlForOAuth();
    haOauthButton.title = haOauthButton.disabled ? "Enter a valid Home Assistant URL first." : "";
  }

  function keepSaveMessageVisible(text) {
    return /Home Assistant sign-in|sign-in workflow/i.test(String(text || ""));
  }

  function copyText(value) {
    if (navigator.clipboard && navigator.clipboard.writeText) return navigator.clipboard.writeText(value);
    var input = el("textarea", { "aria-hidden": "true" });
    input.value = value; input.style.position = "fixed"; input.style.left = "-9999px";
    document.body.appendChild(input); input.select();
    var copied = document.execCommand("copy");
    document.body.removeChild(input);
    return copied ? Promise.resolve() : Promise.reject(new Error("copy unavailable"));
  }

  function startHaOAuth() {
    if (!haOauthButton || !validHaUrlForOAuth()) return;
    var target = String(values.ha_url || "").trim().replace(/\/+$/, "");
    // Choosing browser sign-in supersedes a manually typed token immediately. This also prevents a
    // private-window completion, which cannot signal the ordinary browser context, from being overwritten
    // by that stale form value on a later unrelated save.
    values.ha_token = "";
    savedValues.ha_token = "";
    var tokenInput = document.querySelector("#cfg-ha_token input");
    if (tokenInput) tokenInput.value = "";
    recomputeDirty(); updateSaveUi();
    haOauthButton.disabled = true;
    setHaOauthStatus("Starting sign-in…", false);
    haOauthAuthorizationUrl = "";
    haOauthTargetUrl = "";
    haOauthLinks.hidden = true;
    fetch("/api/v1/ha/oauth/start", {
      method: "POST",
      headers: { "Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ ha_url: target }).toString()
    }).then(function (response) {
      return response.json().catch(function () { return {}; }).then(function (body) {
        if (!response.ok || !body.authorization_url) throw new Error(body.message || ("HTTP " + response.status));
        return body.authorization_url;
      });
    }).then(function (authorizationUrl) {
      haOauthAuthorizationUrl = authorizationUrl;
      haOauthTargetUrl = target;
      var openLink = haOauthLinks.querySelector("a");
      openLink.href = authorizationUrl;
      haOauthLinks.hidden = false;
      setHaOauthStatus("Sign-in link ready. Open it normally or copy it into a private window.", false);
    }).catch(function (error) {
      setHaOauthStatus(error && error.message ? error.message : "Could not start sign-in.", false);
    }).then(function () { syncHaOAuthAvailability(); });
  }

  function haConnectionStatusText() {
    if (haUserStatus.phase === "connected") {
      return haUserStatus.display_name ? "Connected as " + haUserStatus.display_name : "Connected";
    }
    if (haUserStatus.phase === "rejected") return "Sign-in rejected — reconnect to Home Assistant";
    if (haUserStatus.phase === "unavailable") {
      return (haAuth.oauth ? "OAuth configured" : "Long-lived token configured") + " · status unavailable";
    }
    return haAuth.oauth ? "OAuth configured" : haAuth.configured ? "Long-lived token configured" : "Not configured";
  }

  function setHaOauthStatus(text, connected) {
    if (!haOauthStatus) return;
    haOauthStatus.textContent = text;
    haOauthStatus.classList.toggle("connected", connected === true);
  }

  function renderHaConnectionStatus() {
    setHaOauthStatus(haConnectionStatusText(), haUserStatus.phase === "connected");
  }

  function loadHaUserStatus() {
    var request = ++haUserStatusRequest;
    if (!haAuth.configured) {
      haUserStatus = { phase: "not_configured" };
      renderHaConnectionStatus();
      return;
    }
    var succeeded = false;
    fetch("/api/v1/ha/oauth/status", { headers: { "Accept": "application/json" }, cache: "no-store" })
      .then(function (response) { if (!response.ok) throw response.status; return response.json(); })
      .then(function (body) {
        if (request !== haUserStatusRequest) return;
        haUserStatus = body || { phase: "unavailable" };
        succeeded = true;
      })
      .catch(function () {
        if (request === haUserStatusRequest) { haUserStatus = { phase: "unavailable" };
          if (typeof window !== "undefined" && window.configCardSizeGeometryInvalid) window.configCardSizeGeometryInvalid(); }
      })
      .then(function () {
        if (request === haUserStatusRequest) { renderHaConnectionStatus(); if (succeeded && typeof window !== "undefined" && window.configCardSizeSourceReady) {
          window.configCardSizeSourceReady("ha");if (window.configCardSizeGeometryChanged) window.configCardSizeGeometryChanged(); } }
      });
  }

  function haOAuthRow() {
    haOauthButton = el("button", {
      class: "pbtn", type: "button", text: haAuth.configured ? "Reconnect" : "Connect"
    });
    haOauthButton.addEventListener("click", startHaOAuth);
    haOauthStatus = el("div", {
      class: "ha-oauth-status",
      text: haConnectionStatusText()
    });
    renderHaConnectionStatus();
    var openLink = el("a", {
      class: "pbtn", target: "_blank", rel: "noopener noreferrer", referrerpolicy: "no-referrer", text: "Open sign-in"
    });
    var copyButton = el("button", { class: "pbtn", type: "button", text: "Copy link" });
    copyButton.addEventListener("click", function () {
      copyText(haOauthAuthorizationUrl).then(function () {
        setHaOauthStatus("Sign-in link copied.", false);
      }).catch(function () { setHaOauthStatus("Could not copy the link.", false); });
    });
    haOauthLinks = el("div", { class: "ha-oauth-links" }, [openLink, copyButton]);
    haOauthLinks.hidden = !haOauthAuthorizationUrl;
    if (haOauthAuthorizationUrl) openLink.href = haOauthAuthorizationUrl;
    var guidance = !haAuth.configured
      ? "Enter the Home Assistant URL above, then connect this panel."
      : !haAuth.oauth ? "Browser sign-in is recommended; the long-lived token remains available as an advanced fallback." : "";
    var row = el("div", { class: "frow ha-oauth-row", id: "cfg-ha-oauth" }, [
      el("div", { class: "flabel" }, [
        el("span", { text: "Browser sign-in" }),
        haOauthStatus,
        el("small", { text: "Sign in from this computer. To sign in as another user, copy the link into a private window." }),
        guidance ? el("small", { class: "ha-oauth-guidance", text: guidance }) : null
      ]),
      el("div", { class: "fctl ha-oauth-actions" }, [haOauthButton, haOauthLinks])
    ]);
    syncHaOAuthAvailability();
    return row;
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

  function ambientLightSourceConfigured() {
    if (String(values.auto_brightness_ha_entity || "").trim()) return true;
    return !!(autoBrightStatus &&
      (autoBrightStatus.localSourcePresent === true || autoBrightStatus.local_source_present === true));
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
      var prerequisiteBlocked = f.key === "auto_sleep" && v !== "true" && autoSleepPrerequisite.eligible !== true;
      var blocked = sourceBlocked || prerequisiteBlocked;
      var t = el("div", {
        class: "toggle" + (v === "true" && !sourceBlocked ? " on" : "") + (blocked ? " blocked" : ""),
        role: "switch", tabindex: "0", "aria-label": f.label,
        "aria-checked": v === "true" && !sourceBlocked ? "true" : "false", "aria-disabled": blocked ? "true" : "false"
      });
      if (f.key === "auto_sleep") t.setAttribute("aria-describedby", "auto-sleep-prerequisite-status");
      if (sourceBlocked) t.title = "Waiting for a valid ambient light reading.";
      function toggleValue() {
        if (f.key === "auto_brightness" && !ambientLightSourceReady()) return;
        if (f.key === "auto_sleep" && values[f.key] !== "true" && autoSleepPrerequisite.eligible !== true) return;
        v = (values[f.key] === "true") ? "false" : "true";
        values[f.key] = v;
        t.classList.toggle("on", v === "true");
        t.setAttribute("aria-checked", v === "true" ? "true" : "false");
        setDirty(f.key);
        if (f.key === "auto_sleep") updateAutoSleepPrerequisiteUi();
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
      s.addEventListener("change", function () {
        values[f.key] = s.value; setDirty(f.key);
      });
      return s;
    }
    // Dashboard-app picker: the normal automatic choice is ha-paneld's built-in renderer. A foreign
    // dashboard remains available when it is already explicitly configured, but installed apps are not
    // promoted as competing renderer choices.
    if (f.picker === "renderer") {
      var cur = v == null ? "" : v;
      var sel = el("select", { class: "pkgsel" });
      sel.appendChild(el("option", { value: "", text: f.placeholder || "auto" }));
      var seen = { "": true };
      // The built-in renderer is the only normal choice. Keep a configured external selection visible
      // below so changing unrelated settings never discards it.
      var KNOWN = [
        { pkg: "builtin", label: "Built-in renderer (ha-paneld)" }
      ];
      KNOWN.forEach(function (r) {
        seen[r.pkg] = true;
        var op = el("option", { value: r.pkg, text: r.label });
        if (r.pkg === cur) op.selected = true;
        sel.appendChild(op);
      });
      // A currently-set external renderer is preserved so it isn't silently lost.
      if (cur && !seen[cur]) {
        var labels = {
          "io.homeassistant.companion.android": "Home Assistant Companion (configured)",
          "io.homeassistant.companion.android.minimal": "Home Assistant Companion (minimal, configured)"
        };
        var o2 = el("option", { value: cur, text: labels[cur] || (cur + " · configured external renderer") });
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
    // Home dashboard picker: Home Assistant provides this signed-in user's dashboards in its own order.
    // Deliberately a NATIVE select. A custom popup was tried (to carry the dashboards' icons like HA's
    // own picker) and was a bust on hardware review: it escaped the card, ran off the viewport and stole
    // wheel scrolling — the browser's own popup gets all of that right on every platform, and the
    // maintainer chose clean-over-icons. The wizard's dedicated page keeps the icon list; this form
    // keeps HA's GROUPING via native optgroups, which is the part that carries real information.
    // Auto intentionally remains first; a legacy/custom configured path is preserved rather than silently lost.
    if (f.picker === "ha_dashboard") {
      var currentDashboard = v == null ? "" : v;
      var dashboardSelect = el("select", { class: "pkgsel" });
      var autoText = !homeDashboardQueried ? "Auto — dashboard list unavailable"
        : (homeDashboardDefault.explicit
          ? "Auto — follow this account’s default" : "Auto — no default set for this account");
      var autoOption = el("option", { value: "", text: autoText });
      dashboardSelect.appendChild(autoOption);
      var dashboardPaths = { "": true };
      var groupHosts = {};
      ["panel", "dashboard"].forEach(function (group) {
        var members = homeDashboardItems.filter(function (d) { return (d.group || "dashboard") === group; });
        if (!members.length) return;
        var host = el("optgroup", { label: group === "panel" ? "Home Assistant dashboards" : "Your dashboards" });
        groupHosts[group] = host;
        dashboardSelect.appendChild(host);
        members.forEach(function (dashboard) {
          var path = String(dashboard && dashboard.path || "").trim();
          if (!path || dashboardPaths[path]) return;
          dashboardPaths[path] = true;
          var title = String(dashboard.title || "").trim() || path;
          var option = el("option", { value: path, text: title + " · " + path });
          if (path === currentDashboard) option.selected = true;
          host.appendChild(option);
        });
      });
      if (currentDashboard && !dashboardPaths[currentDashboard]) {
        dashboardSelect.appendChild(el("option", {
          value: currentDashboard, text: currentDashboard + " · configured dashboard"
        }));
        dashboardSelect.value = currentDashboard;
      }
      dashboardSelect.addEventListener("change", function () {
        values[f.key] = dashboardSelect.value;
        setDirty(f.key);
      });
      if (!homeDashboardQueried) {
        return el("div", {}, [dashboardSelect,
          el("small", { class: "hd-area-note", text:
            "Couldn’t fetch this account’s dashboard list from Home Assistant yet. Try again after the connection recovers." })]);
      }
      if (!homeDashboardItems.length) {
        dashboardSelect.disabled = true;
        return el("div", {}, [dashboardSelect,
          el("small", { class: "hd-area-note", text:
            "This account cannot access any dashboards. Create one or grant access in Home Assistant." })]);
      }
      if (!homeDashboardDefault.explicit && !currentDashboard) {
        // The demotion rule, in native terms: Auto still exists but the field says why picking a real
        // dashboard is the recommendation when the account carries no server-side default.
        var wrap = el("div", {}, [dashboardSelect,
          el("small", { class: "hd-area-note", text:
            "This account has no default dashboard set — pick the dashboard this panel should show." })]);
        return wrap;
      }
      return dashboardSelect;
    }
    // Home Assistant Area: a pop-up list of HA's REAL areas, never free text — nobody knows what to type,
    // the names live in HA. The stored value is the panel's REQUEST; Home Assistant's own value is
    // canonical, so this control shows what HA says and the note explains when an edit cannot move an
    // already-registered device (admin-only in HA).
    if (f.picker === "ha_area") {
      // Populated EAGERLY, when the field renders. It used to load on focus/pointerdown, which appended
      // options into a native dropdown the user had just opened — the picker re-laid-out on every insert
      // and visibly flickered before settling (hardware report). Nothing may mutate an open picker.
      var areaCurrent = v == null ? "" : v;
      var areaWrap = el("div", { class: "ha-area-picker" });
      var areaSelect = el("select", { class: "pkgsel", "aria-label": f.label });
      areaSelect.appendChild(el("option", { value: "", text: "No area" }));
      if (areaCurrent) {
        var cur = el("option", { value: areaCurrent, text: areaCurrent });
        cur.selected = true;
        areaSelect.appendChild(cur);
      }
      var areaNote = el("small", { class: "hd-area-note", hidden: "hidden" });
      var areaTouched = false;
      var areaHa = "";
      var areaQueried = false;
      var areaAdmin = null;
      // The note describes the VALUE, not permissions. Shown for every non-admin session it told a panel
      // whose value MATCHED Home Assistant that it had been overridden locally — untrue, and alarming on a
      // panel that had just converged correctly (reported on a fleet panel, 2026-07-26). It may appear only
      // while the local request genuinely differs from what Home Assistant holds, and it must stay honest
      // after an edit, so it is recomputed rather than decided once.
      function syncAreaNote() {
        var localArea = values[f.key] == null ? "" : String(values[f.key]);
        if (areaQueried && haAreaUserOverride && localArea !== areaHa) {
          // Name what Home Assistant actually holds, so the divergence is legible at a glance —
          // "Office while HA has Hall" is the whole story (maintainer, rc2 request 2026-07-27).
          areaNote.textContent = areaHa
            ? "Local override only — Home Assistant has \u201C" + areaHa + "\u201D"
            : "Local override only";
          areaNote.removeAttribute("hidden");
        } else {
          areaNote.setAttribute("hidden", "hidden");
        }
      }
      // ONE insertion, never one per area: appending options individually re-lays-out the control on every
      // append. And when the catalog is already known this runs BEFORE the node is attached, so returning to
      // the Config tab costs no layout change at all — the picker is complete the moment it appears.
      function fillAreaOptions(a) {
        var have = {};
        for (var i = 0; i < areaSelect.options.length; i++) have[areaSelect.options[i].value] = true;
        var areaFrag = document.createDocumentFragment();
        (a && a.areas || []).forEach(function (area) {
          if (!area || !area.name || have[area.name]) return;
          have[area.name] = true;
          areaFrag.appendChild(el("option", { value: area.name, text: area.name }));
        });
        // Home Assistant is canonical: where it holds an area for this device, that is what this control
        // reads, even if the local request has never been set. A panel whose HA device sits in Office must
        // never present itself as having no area — reported on a fleet panel whose local value was blank.
        var haArea = a && a.device && a.device.found ? a.device.area_name : "";
        if (haArea && !have[haArea]) {
          have[haArea] = true;
          areaFrag.appendChild(el("option", { value: haArea, text: haArea }));
        }
        areaSelect.appendChild(areaFrag); // the single layout-affecting mutation
        if (!areaTouched && !haAreaUserOverride && haArea && areaSelect.value !== haArea) {
          areaSelect.value = haArea;
          // Adopting HA's value is not a pending edit — the server already treats HA as the source of
          // truth — so this must not mark the form dirty and invite a redundant save.
          values[f.key] = haArea;
        }
        areaHa = haArea;
        areaQueried = !!(a && a.queried);
        areaAdmin = a && typeof a.admin === "boolean" ? a.admin : null;
        syncAreaNote();
      }
      var areaSeedGeneration = haAreaSeedGeneration;
      var areaRequest = ++haAreaCatalogRequest;
      if (haAreaSeed) fillAreaOptions(haAreaSeed);
      // The panel endpoint owns a short, owner-keyed cache. Always ask it so failed queries and config,
      // credential or identity changes can recover without a full browser reload.
      fetch("/api/v1/config/ha-area", { cache: "no-store" }).then(function (r) { return r.json(); }).then(function (a) {
        if (areaSeedGeneration !== haAreaSeedGeneration || areaRequest !== haAreaCatalogRequest) return;
        if (a && a.queried) haAreaSeed = a;
        fillAreaOptions(a);
      }).catch(function () {});
      areaSelect.addEventListener("change", function () {
        areaTouched = true;
        values[f.key] = areaSelect.value;
        setDirty(f.key);
        syncAreaNote();
      });
      areaWrap.appendChild(areaSelect);
      areaWrap.appendChild(areaNote);
      return areaWrap;
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
      var cleanup = function () {
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
      };
      haPickerCleanups.push(cleanup);
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
    if ((f.key === "auto_brightness_minimum_percent" || f.key === "auto_brightness_sensitivity") && !ambientLightSourceReady()) {
      inp.disabled = true;
      inp.title = "Select an ambient light source first.";
    }
    inp.addEventListener("input", function () {
      values[f.key] = inp.value; setDirty(f.key);
      if (f.key === "auto_brightness_minimum_percent" || f.key === "auto_brightness_sensitivity") queueAutoBrightnessHistory();
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
        localDay: point.local_day || point.localDay || "",
        minuteOfDay: pointValue(point, ["minute_of_day", "minuteOfDay"]),
        dayAge: pointValue(point, ["day_age", "dayAge"]),
        mean: pointValue(point, ["mean_lux", "observed_mean_lux", "observedMeanLux", "lux"]),
        min: pointValue(point, ["min_lux", "minLux"]),
        max: pointValue(point, ["max_lux", "maxLux"]),
        expected: pointValue(point, ["baseline_lux", "expected_lux", "expectedLux"]),
        brightness: pointValue(point, ["proposed_brightness", "proposedBrightness"])
      };
    }).filter(function (point) {
      return point.minute != null && point.mean != null && point.localDay &&
        point.minuteOfDay != null && point.dayAge != null && point.dayAge >= 0 && point.dayAge < 7;
    });
  }

  function autoBrightnessChartDays(points) {
    var grouped = {};
    points.forEach(function (point) {
      if (!grouped[point.localDay]) grouped[point.localDay] = { key: point.localDay, age: point.dayAge, points: [] };
      grouped[point.localDay].points.push(point);
    });
    return Object.keys(grouped).map(function (key) {
      grouped[key].points.sort(function (a, b) { return a.minute - b.minute; });
      return grouped[key];
    }).sort(function (a, b) { return b.age - a.age; });
  }

  function autoBrightnessDayStyle(age) {
    var boundedAge = Math.max(0, Math.min(6, age));
    return {
      alpha: [1, .28, .20, .14, .10, .07, .05][boundedAge],
      blurPx: boundedAge === 0 ? 0 : 5 * Math.pow(1.6, boundedAge - 1)
    };
  }

  function autoBrightnessSmoothing(age) {
    var boundedAge = Math.max(0, Math.min(6, Math.round(age)));
    return {
      medianWindow: [1, 3, 5, 7, 9, 11, 13][boundedAge],
      averageWindow: [1, 1, 3, 5, 7, 9, 11][boundedAge]
    };
  }

  function centeredMedian(values, windowSize) {
    if (windowSize <= 1) return values.slice();
    var radius = Math.floor(windowSize / 2);
    return values.map(function (_value, index) {
      var sample = values.slice(Math.max(0, index - radius), Math.min(values.length, index + radius + 1));
      sample.sort(function (a, b) { return a - b; });
      return sample[Math.floor(sample.length / 2)];
    });
  }

  function centeredWeightedAverage(values, windowSize) {
    if (windowSize <= 1) return values.slice();
    var radius = Math.floor(windowSize / 2);
    return values.map(function (_value, index) {
      var total = 0, weightTotal = 0;
      for (var offset = -radius; offset <= radius; offset += 1) {
        var sampleIndex = index + offset;
        if (sampleIndex < 0 || sampleIndex >= values.length) continue;
        var weight = radius + 1 - Math.abs(offset);
        total += values[sampleIndex] * weight;
        weightTotal += weight;
      }
      return weightTotal ? total / weightTotal : values[index];
    });
  }

  function smoothedRunValues(run, field, age, fallbackField) {
    var smoothing = autoBrightnessSmoothing(age);
    var values = run.map(function (point) {
      return point[field] == null && fallbackField ? point[fallbackField] : point[field];
    });
    return centeredWeightedAverage(centeredMedian(values, smoothing.medianWindow), smoothing.averageWindow);
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
    var days = autoBrightnessChartDays(points);
    var bucketMinutes = autoBrightHistory && (autoBrightHistory.bucket_minutes || autoBrightHistory.bucketMinutes) || 5;
    var pad = { left: 0, right: 0, top: 10, bottom: 24 };
    var plotW = width - pad.left - pad.right, plotH = height - pad.top - pad.bottom;
    var maxLux = 1;
    points.forEach(function (p) { maxLux = Math.max(maxLux, p.max == null ? p.mean : p.max, p.expected || 0); });
    var maxLog = Math.log(maxLux + 1);
    function x(p) { return pad.left + p.minuteOfDay / 1440 * plotW; }
    function y(value) { return pad.top + plotH - Math.log(Math.max(0, value) + 1) / maxLog * plotH; }
    function yBrightness(value) { return pad.top + plotH - Math.max(0, Math.min(255, value)) / 255 * plotH; }
    ctx.strokeStyle = "#343a44"; ctx.lineWidth = 1;
    [0, .5, 1].forEach(function (f) {
      var yy = pad.top + plotH * f; ctx.beginPath(); ctx.moveTo(pad.left, yy); ctx.lineTo(width - pad.right, yy); ctx.stroke();
    });
    [0, 360, 720, 1080, 1440].forEach(function (minute) {
      var xx = pad.left + minute / 1440 * plotW;
      ctx.beginPath(); ctx.moveTo(xx, pad.top); ctx.lineTo(xx, pad.top + plotH); ctx.stroke();
    });
    function runs(dayPoints, field) {
      var result = [], run = [];
      dayPoints.forEach(function (point) {
        var previous = run.length ? run[run.length - 1] : null;
        var discontinuous = previous && (
          point.minute - previous.minute !== bucketMinutes ||
          point.minuteOfDay - previous.minuteOfDay !== bucketMinutes
        );
        if (point[field] == null || discontinuous) {
          if (run.length) result.push(run);
          run = [];
        }
        if (point[field] != null) run.push(point);
      });
      if (run.length) result.push(run);
      return result;
    }
    function line(day, field, color, widthPx, projectY) {
      runs(day.points, field).forEach(function (run) {
        var values = smoothedRunValues(run, field, day.age);
        ctx.beginPath();
        run.forEach(function (point, index) {
          if (!index) ctx.moveTo(x(point), projectY(values[index])); else ctx.lineTo(x(point), projectY(values[index]));
        });
        ctx.strokeStyle = color; ctx.lineWidth = widthPx;
        ctx.stroke();
      });
    }
    days.forEach(function (day) {
      var style = autoBrightnessDayStyle(day.age);
      ctx.save(); ctx.globalAlpha = style.alpha;
      if ("filter" in ctx) ctx.filter = "blur(" + style.blurPx.toFixed(2) + "px)";
      runs(day.points, "mean").forEach(function (run) {
        var maxValues = smoothedRunValues(run, "max", day.age, "mean");
        var minValues = smoothedRunValues(run, "min", day.age, "mean");
        ctx.fillStyle = "rgba(74,158,255,.16)"; ctx.beginPath();
        run.forEach(function (point, index) {
          var yy = y(maxValues[index]);
          if (!index) ctx.moveTo(x(point), yy); else ctx.lineTo(x(point), yy);
        });
        run.slice().reverse().forEach(function (point, reverseIndex) {
          ctx.lineTo(x(point), y(minValues[minValues.length - reverseIndex - 1]));
        });
        ctx.closePath(); ctx.fill();
      });
      line(day, "mean", "#4a9eff", 1.6, y);
      line(day, "expected", "#f1bd52", 1.4, y);
      line(day, "brightness", "#b77cff", 1.4, yBrightness);
      ctx.restore();
    });
    ctx.fillStyle = "#888"; ctx.font = "11px sans-serif";
    ctx.textAlign = "left"; ctx.fillText(Math.round(maxLux) + " lx", pad.left + 5, pad.top + 12); ctx.fillText("0", pad.left + 5, pad.top + plotH - 4);
    ctx.textAlign = "right"; ctx.fillText("100%", width - pad.right - 5, pad.top + 12); ctx.fillText("0%", width - pad.right - 5, pad.top + plotH - 4);
    [0, 360, 720, 1080, 1440].forEach(function (minute, index) {
      var xx = pad.left + minute / 1440 * plotW;
      ctx.textAlign = index === 0 ? "left" : index === 4 ? "right" : "center";
      ctx.fillText(index === 4 ? "24:00" : ("0" + Math.floor(minute / 60)).slice(-2) + ":00", xx, height - 6);
    });
  }

  function autoBrightnessSummary() {
    if (autoBrightSourceTransition) return "Updating ambient-light source… · Source: " + autoBrightnessSelectedSource();
    if (!autoBrightStatus) return autoBrightLoading ? "Loading adaptive brightness…" : "Adaptive brightness status is unavailable.";
    if (autoBrightStatus.available === false) return autoBrightStatus.detail || "Adaptive brightness runtime is unavailable.";
    if (autoBrightStatus.sourceAvailable === false || autoBrightStatus.source_available === false) {
      return "Waiting for ambient light… · Source: " + autoBrightnessSelectedSource();
    }
    var state = autoBrightStatus.state || "learning";
    if (autoBrightStatus.preferenceActive === true || autoBrightStatus.paused === true) state = "temporary preference";
    else if (state === "enabled" && autoBrightStatus.mode) state = autoBrightStatus.mode;
    return state + " · Source: " + autoBrightnessSelectedSource();
  }

  function autoBrightnessSelectedSource() {
    if (autoBrightSourceTransition && autoBrightTransitionSource) return autoBrightTransitionSource;
    var selected = String(values.auto_brightness_ha_entity || "").trim();
    if (selected) return selected;
    return autoBrightStatus && (
      autoBrightStatus.source_label || autoBrightStatus.sourceLabel || autoBrightStatus.entity_id || autoBrightStatus.entityId
    ) || "panel sensor";
  }

  function autoBrightnessSourceRevision(body) {
    if (!body) return null;
    var revision = body.sourceRevision != null ? body.sourceRevision : body.source_revision;
    return revision == null ? null : String(revision);
  }

  function autoBrightnessStatusMatchesSelection(status) {
    var expected = autoBrightSourceTransition
      ? (autoBrightTransitionSource === "panel sensor" ? "" : autoBrightTransitionSource)
      : String(values.auto_brightness_ha_entity || "").trim();
    var actual = status && (status.entityId != null ? status.entityId : status.entity_id);
    actual = actual == null ? "" : String(actual).trim();
    return actual === expected;
  }

  function autoBrightnessLatestEpochMinute() {
    if (!autoBrightHistory) return null;
    var value = autoBrightHistory.latestEpochMinute != null
      ? autoBrightHistory.latestEpochMinute : autoBrightHistory.latest_epoch_minute;
    return typeof value === "number" && isFinite(value) ? value : null;
  }

  function autoBrightnessFreshness() {
    var latest = autoBrightnessLatestEpochMinute();
    if (latest == null) return autoBrightSourceTransition ? "Loading new source history…" : "No samples yet";
    var date = new Date(latest * 60000);
    var ageMinutes = Math.max(0, Math.floor((Date.now() - date.getTime()) / 60000));
    var relative = ageMinutes < 1 ? "just now" : ageMinutes < 60 ? ageMinutes + " min ago" :
      ageMinutes < 1440 ? Math.floor(ageMinutes / 60) + " hr ago" : Math.floor(ageMinutes / 1440) + " days ago";
    return "Updated " + date.toLocaleString() + " (" + relative + ")";
  }

  function autoBrightnessProjectionSensitivity() {
    if (!autoBrightHistory) return null;
    var value = parseInt(autoBrightHistory.sensitivity, 10);
    return isFinite(value) && value >= 0 && value <= 100 ? value : null;
  }

  function beginAutoBrightnessSourceTransition(source, preserveCurrentRequest) {
    // A save can overtake an already-running refresh. Fence that response before it can
    // make a matching pair from the previous source look current.
    if (!preserveCurrentRequest) autoBrightRequest += 1;
    autoBrightSourceTransition = true;
    autoBrightTransitionAttempts = 0;
    autoBrightTransitionSource = String(source || "").trim() || "panel sensor";
    autoBrightHistory = { points: [] };
    autoBrightMessage = "Loading history for the selected source…";
    if (autoBrightTransitionTimer) clearTimeout(autoBrightTransitionTimer);
    autoBrightTransitionTimer = null;
    if (autoBrightRefreshTimer) clearTimeout(autoBrightRefreshTimer);
    autoBrightRefreshTimer = null;
    render();
  }

  function finishAutoBrightnessSourceTransition() {
    autoBrightSourceTransition = false;
    autoBrightTransitionAttempts = 0;
    autoBrightTransitionSource = "";
    if (autoBrightTransitionTimer) clearTimeout(autoBrightTransitionTimer);
    autoBrightTransitionTimer = null;
  }

  function scheduleAutoBrightnessTransitionPoll() {
    if (autoBrightTransitionTimer) clearTimeout(autoBrightTransitionTimer);
    autoBrightTransitionTimer = null;
    if (!autoBrightSourceTransition || document.hidden) return;
    autoBrightTransitionTimer = setTimeout(function () {
      autoBrightTransitionTimer = null;
      autoBrightTransitionAttempts += 1;
      loadAutoBrightnessData(true);
    }, AUTO_BRIGHTNESS_TRANSITION_POLL_MS);
  }

  function loadAutoBrightnessData(force) {
    if (autoBrightLoading && !force) return;
    autoBrightLoading = true;
    var request = ++autoBrightRequest;
    var sensitivity = parseInt(values.auto_brightness_sensitivity, 10);
    var sensitivitySuffix = isFinite(sensitivity) && sensitivity >= 0 && sensitivity <= 100
      ? "&sensitivity=" + encodeURIComponent(sensitivity) : "";
    var minimum = parseInt(values.auto_brightness_minimum_percent, 10);
    var minimumSuffix = isFinite(minimum) && minimum >= 4 && minimum <= 95
      ? "&minimum_percent=" + encodeURIComponent(minimum) : "";
    var succeeded = false;
    Promise.all([
      fetch("/api/v1/auto-brightness", { cache: "no-store" }).then(function (r) { if (!r.ok) throw r.status; return r.json(); }),
      fetch("/api/v1/auto-brightness/history?hours=168" + sensitivitySuffix + minimumSuffix, { cache: "no-store" }).then(function (r) { if (!r.ok) throw r.status; return r.json(); })
    ]).then(function (result) {
      if (request !== autoBrightRequest) return;
      var statusRevision = autoBrightnessSourceRevision(result[0]);
      var historyRevision = autoBrightnessSourceRevision(result[1]);
      var revisionsMatch = statusRevision != null && statusRevision === historyRevision &&
        autoBrightnessStatusMatchesSelection(result[0]);
      var latest = result[1].latestEpochMinute != null ? result[1].latestEpochMinute : result[1].latest_epoch_minute;
      autoBrightStatus = result[0];
      succeeded = true;
      if (!revisionsMatch) {
        if (!autoBrightSourceTransition) beginAutoBrightnessSourceTransition(autoBrightnessSelectedSource(), true);
        autoBrightHistory = { points: [] };
        if (autoBrightTransitionAttempts >= AUTO_BRIGHTNESS_TRANSITION_MAX_ATTEMPTS) {
          finishAutoBrightnessSourceTransition();
          autoBrightMessage = "History is still preparing for the selected source; it will retry on the normal refresh.";
        } else {
          autoBrightMessage = "Loading history for the selected source…";
          scheduleAutoBrightnessTransitionPoll();
        }
      } else if (autoBrightSourceTransition && latest == null && autoBrightTransitionAttempts < AUTO_BRIGHTNESS_TRANSITION_MAX_ATTEMPTS) {
        autoBrightHistory = { points: [], sourceRevision: result[1].sourceRevision };
        autoBrightMessage = "Waiting for the first sample from the selected source…";
        scheduleAutoBrightnessTransitionPoll();
      } else {
        autoBrightHistory = result[1];
        if (autoBrightSourceTransition) {
          var timedOutEmpty = latest == null;
          finishAutoBrightnessSourceTransition();
          autoBrightMessage = timedOutEmpty ? "The selected source has no ambient-light samples yet." : "";
        } else autoBrightMessage = "";
      }
    }).catch(function () {
      if (request !== autoBrightRequest) return;
      if (typeof window !== "undefined" && window.configCardSizeGeometryInvalid) window.configCardSizeGeometryInvalid();
      if (!autoBrightSourceTransition) autoBrightStatus = { available: false, detail: "Could not load adaptive brightness." };
      autoBrightHistory = { points: [] };
      if (autoBrightSourceTransition && autoBrightTransitionAttempts < AUTO_BRIGHTNESS_TRANSITION_MAX_ATTEMPTS) {
        autoBrightMessage = "The selected source is still loading…";
        scheduleAutoBrightnessTransitionPoll();
      } else if (autoBrightSourceTransition) {
        finishAutoBrightnessSourceTransition();
        autoBrightMessage = "The selected source could not be loaded; it will retry on the normal refresh.";
      }
    }).then(function () {
      if (request !== autoBrightRequest) return;
      autoBrightLoading = false; render();
      if (succeeded && typeof window !== "undefined" && window.configCardSizeSourceReady) {
        window.configCardSizeSourceReady("brightness");
        if (window.configCardSizeGeometryChanged) window.configCardSizeGeometryChanged();
      }
      scheduleAutoBrightnessRefresh();
    });
  }

  function scheduleAutoBrightnessRefresh() {
    if (autoBrightRefreshTimer) clearTimeout(autoBrightRefreshTimer);
    autoBrightRefreshTimer = null;
    if (autoBrightSourceTransition) return;
    if (values.auto_brightness !== "true" || document.hidden) return;
    autoBrightRefreshTimer = setTimeout(function () {
      autoBrightRefreshTimer = null;
      if (document.hidden) return;
      loadAutoBrightnessData(false);
    }, AUTO_BRIGHTNESS_REFRESH_MS);
  }

  if (document.addEventListener) {
    document.addEventListener("visibilitychange", function () {
      if (document.hidden) {
        if (autoBrightRefreshTimer) clearTimeout(autoBrightRefreshTimer);
        autoBrightRefreshTimer = null;
        if (autoBrightTransitionTimer) clearTimeout(autoBrightTransitionTimer);
        autoBrightTransitionTimer = null;
      } else if (values.auto_brightness === "true") {
        loadAutoBrightnessData(false);
      }
    });
  }

  function queueAutoBrightnessHistory() {
    if (autoBrightHistoryTimer) clearTimeout(autoBrightHistoryTimer);
    autoBrightHistoryTimer = setTimeout(function () { loadAutoBrightnessData(true); }, 300);
  }

  var autoBrightnessResizeTimer = null;
  if (window.addEventListener) {
    window.addEventListener("resize", function () {
      if (autoBrightnessResizeTimer) clearTimeout(autoBrightnessResizeTimer);
      autoBrightnessResizeTimer = setTimeout(drawAutoBrightnessChart, 100);
    });
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
    var dayCount = autoBrightnessChartDays(normalizedChartPoints()).length;
    var detail = dayCount + (dayCount === 1 ? " day" : " days") + " overlaid · older days fade";
    if (bucket) detail += " · " + bucket + " minute buckets";
    var projectionSensitivity = autoBrightnessProjectionSensitivity();
    if (projectionSensitivity != null) detail += " · Sensitivity " + projectionSensitivity;
    detail += " · " + autoBrightnessFreshness();
    var panel = el("div", { class: "autobright-panel", id: "auto-brightness-learning" }, [
      el("div", { class: "autobright-head" }, [
        el("div", {}, [el("strong", { text: "Daily ambient learning" }), el("small", { text: autoBrightnessSummary() })]),
        el("div", { class: "autobright-actions" }, [reset, resume])
      ]),
      el("canvas", { id: "auto-brightness-chart", class: "autobright-chart", role: "img", "aria-label": "24-hour ambient pattern with up to seven days overlaid" }),
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

  function autoSleepHuman(value) {
    return String(value == null ? "unknown" : value).replace(/_/g, " ").replace(/^./, function (c) { return c.toUpperCase(); });
  }

  function autoSleepSummaryText() {
    var status = autoSleepStatus || {};
    var areaName = status.area_name != null ? status.area_name : status.areaName;
    var leaseMs = status.learned_lease_ms != null ? status.learned_lease_ms : status.learnedLeaseMs;
    var lease = typeof leaseMs === "number" && isFinite(leaseMs) ? Math.round(leaseMs / 60000) + " min" : "not learned";
    var count = status.source_count != null ? status.source_count : status.sourceCount;
    var suppressed = status.manual_suppression === true || status.manualSuppression === true;
    return "Home Assistant Area: " + (String(areaName || "").trim() || "not learned") +
      " · Phase: " + autoSleepHuman(status.phase) + " · Reason: " + autoSleepHuman(status.reason) +
      " · Learned delay: " + lease + " · Sources: " + (count == null ? 0 : count) +
      " · Manual screen override: " + (suppressed ? "active" : "inactive");
  }

  function autoSleepPrerequisiteText() {
    var phase = String(autoSleepPrerequisite.phase || "unavailable").toLowerCase();
    var areaName = autoSleepPrerequisite.area_name != null ? autoSleepPrerequisite.area_name : autoSleepPrerequisite.areaName;
    if (phase === "checking") return "Checking this panel’s Home Assistant Area…";
    if (autoSleepPrerequisite.eligible === true && phase === "assigned") return "Home Assistant Area: " + (String(areaName || "").trim() || "Assigned");
    if (phase === "unassigned") return "Assign this panel to a Home Assistant Area before enabling Auto sleep.";
    if (phase === "auth_failed") return "Reconnect Home Assistant before enabling Auto sleep.";
    return "Could not check this panel’s Home Assistant Area. Check the Home Assistant connection.";
  }

  function autoSleepPrerequisiteNode() {
    return el("div", {
      id: "auto-sleep-prerequisite-status",
      class: "auto-sleep-prerequisite" + (autoSleepPrerequisite.eligible === true ? " eligible" : ""),
      role: "status", "aria-live": "polite", text: autoSleepPrerequisiteText()
    });
  }

  function updateAutoSleepPrerequisiteUi() {
    var status = document.getElementById("auto-sleep-prerequisite-status");
    if (status) {
      status.textContent = autoSleepPrerequisiteText();
      status.classList.toggle("eligible", autoSleepPrerequisite.eligible === true);
    }
    var toggle = document.querySelector("#cfg-auto_sleep [role=switch]");
    if (!toggle) return;
    var blocked = values.auto_sleep !== "true" && autoSleepPrerequisite.eligible !== true;
    toggle.classList.toggle("blocked", blocked);
    toggle.setAttribute("aria-disabled", blocked ? "true" : "false");
    toggle.setAttribute("tabindex", "0");
  }

  function convergeAutoSleepOffForMissingArea() {
    if (values.auto_sleep !== "true") return;
    values.auto_sleep = "false";
    savedValues.auto_sleep = "false";
    invalidateAutoSleepData(true);
    recomputeDirty();
    updateSaveUi();
    var toggle = document.querySelector("#cfg-auto_sleep [role=switch]");
    if (toggle) {
      toggle.classList.remove("on");
      toggle.setAttribute("aria-checked", "false");
    }
    var panel = document.getElementById("auto-sleep-status");
    if (panel) panel.remove();
  }

  function loadAutoSleepPrerequisite() {
    if (!schema.some(function (field) { return field.key === "auto_sleep" && field.available; })) return;
    if (autoSleepPrerequisiteTimer) { clearTimeout(autoSleepPrerequisiteTimer); autoSleepPrerequisiteTimer = null; }
    autoSleepPrerequisite = { eligible: false, phase: "checking", area_name: "" };
    updateAutoSleepPrerequisiteUi();
    var request = ++autoSleepPrerequisiteRequest;
    fetch("/api/v1/auto-sleep/prerequisite", { cache: "no-store" })
      .then(function (response) {
        if (!response.ok) throw new Error("HTTP " + response.status);
        return response.json();
      })
      .then(function (body) {
        if (request !== autoSleepPrerequisiteRequest) return;
        autoSleepPrerequisite = body || { eligible: false, phase: "unavailable", area_name: "" };
        var phase = String(autoSleepPrerequisite.phase || "unavailable").toLowerCase();
        var nextAreaName = autoSleepPrerequisite.area_name != null ? autoSleepPrerequisite.area_name : autoSleepPrerequisite.areaName;
        nextAreaName = phase === "assigned" && autoSleepPrerequisite.eligible === true ? String(nextAreaName || "").trim() : "";
        var initialAreaMismatch = nextAreaName && !autoSleepAssignedAreaName && (
          autoSleepStatus && !autoSleepAreaMatchesName(autoSleepStatus, nextAreaName) ||
          autoSleepHistory && !autoSleepAreaMatchesName(autoSleepHistory, nextAreaName)
        );
        if (nextAreaName && (initialAreaMismatch || autoSleepAssignedAreaName && nextAreaName !== autoSleepAssignedAreaName)) {
          autoSleepAssignedAreaName = nextAreaName;
          autoSleepAreaGeneration++;
          autoSleepSourceUpdating = Object.create(null);
          // The completed replay is still useful as a stable placeholder while the new Area is
          // discovered. Fence its requests, but keep its DOM beneath the busy overlay until the
          // replacement history is complete; clearing it here makes the whole card collapse and
          // expand through an empty state before every Area refresh.
          invalidateAutoSleepData();
          autoSleepHistoryWaiting = values.auto_sleep === "true";
          autoSleepHistoryWaitingMessage = autoSleepHistoryWaiting ? "Preparing activity history…" : "";
          updateAutoSleepHistory();
          if (values.auto_sleep === "true") loadAutoSleepData();
        } else if (nextAreaName) {
          autoSleepAssignedAreaName = nextAreaName;
        }
        if (phase === "unassigned") {
          if (autoSleepAssignedAreaName) {
            autoSleepAreaGeneration++;
            autoSleepSourceUpdating = Object.create(null);
          }
          autoSleepAssignedAreaName = "";
          convergeAutoSleepOffForMissingArea();
        }
      })
      .catch(function () {
        if (request !== autoSleepPrerequisiteRequest) return;
        autoSleepPrerequisite = { eligible: false, phase: "unavailable", area_name: "" };
      })
      .then(function () {
        if (request === autoSleepPrerequisiteRequest) updateAutoSleepPrerequisiteUi();
      });
  }

  function scheduleAutoSleepPrerequisite() {
    if (autoSleepPrerequisiteTimer) clearTimeout(autoSleepPrerequisiteTimer);
    autoSleepPrerequisiteTimer = setTimeout(function () {
      autoSleepPrerequisiteTimer = null;
      loadAutoSleepPrerequisite();
    }, 150);
  }

  function autoSleepPanel() {
    if (!autoSleepStatus && !autoSleepLoading) {
      autoSleepHistoryWaiting = true;
      autoSleepHistoryWaitingMessage = "Preparing activity history…";
    }
    var windows = el("div", { class: "auto-sleep-windows", role: "group", "aria-label": "Activity history period" });
    [6, 24, 48].forEach(function (hours) {
      var button = el("button", { class: "pbtn", type: "button", text: hours + "h", "data-hours": hours, "aria-pressed": hours === autoSleepHistoryHours ? "true" : "false" });
      button.onclick = function () {
        if (autoSleepHistoryLoading || hours === autoSleepHistoryHours) return;
        autoSleepHistoryHours = hours;
        if (autoSleepHistoryReady(autoSleepStatus)) loadAutoSleepHistory();
        else updateAutoSleepHistory();
      };
      windows.appendChild(button);
    });
    var chart = el("div", { id: "auto-sleep-chart", class: "auto-sleep-history", "aria-describedby": "auto-sleep-chart-description" }, [
      el("div", { class: "auto-sleep-chart-content" }),
      el("div", { class: "auto-sleep-loading-overlay", role: "status", "aria-live": "polite", text: "Preparing activity history…" })
    ]);
    var panel = el("div", { class: "autobright-panel", id: "auto-sleep-status" }, [
      el("div", { class: "autobright-head" }, [
      el("div", {}, [
          el("strong", { text: "Auto-sleep activity" }),
          el("small", { id: "auto-sleep-summary", text: autoSleepLoading ? "Loading…" : autoSleepSummaryText() }),
          el("small", { text: "When exposed, open the Auto-sleep activity binary sensor in Home Assistant for its history timeline." })
        ]),
        el("div", { class: "autobright-actions" }, [windows])
      ]),
      chart,
      el("div", { class: "autobright-legend auto-sleep-legend" }, [
        el("span", { class: "detected", text: "Detected / Awake" }),
        el("span", { class: "clear", text: "Clear / Sleep" }),
        el("span", { class: "inhibited", text: "Unavailable" })
      ]),
      el("div", { id: "auto-sleep-chart-description", class: "sr-only", text: "Replaying activity history." }),
      el("div", {
        id: "auto-sleep-history-message", class: "autobright-message", role: "status", "aria-live": "polite",
        text: autoSleepHistoryMessage()
      })
    ]);
    drawAutoSleepChart(chart, true);
    return panel;
  }

  function updateAutoSleepSummary() {
    var summary = document.getElementById("auto-sleep-summary");
    if (!summary) return;
    // A readiness refresh must not replace a settled, wrapping summary with a much shorter loading
    // label. Apart from being less informative, that changes the card height on narrow panels. The
    // loading copy is useful only before the first status response exists.
    var next = autoSleepLoading && !autoSleepStatus ? "Loading…" : autoSleepSummaryText();
    if (summary.textContent !== next) summary.textContent = next;
  }

  function autoSleepHistoryMessage() {
    return autoSleepHistoryError;
  }

  function autoSleepHistoryBusy() {
    return autoSleepHistoryLoading || autoSleepHistoryWaiting || autoSleepLoading;
  }

  function autoSleepAreaMatchesName(value, expected) {
    var actual = value && (value.area_name != null ? value.area_name : value.areaName);
    return String(actual || "").trim().toLowerCase() === String(expected || "").trim().toLowerCase();
  }

  function autoSleepAreaMatches(value) {
    var expected = String(autoSleepAssignedAreaName || "").trim();
    if (!expected) return true;
    return autoSleepAreaMatchesName(value, expected);
  }

  function autoSleepAreaTransitioning(value) {
    var expected = String(autoSleepAssignedAreaName || "").trim();
    var actual = value && (value.area_name != null ? value.area_name : value.areaName);
    actual = String(actual || "").trim();
    // A differently named Area is a stale-but-valid result while HA registry discovery catches up.
    // A blank Area is also how terminal failures are reported, so it must not imply endless retry.
    return !!expected && !!actual && !autoSleepAreaMatchesName(value, expected);
  }

  function autoSleepHistoryReady(status) {
    var phase = String(status && status.phase || "").toLowerCase();
    var count = status && (status.source_count != null ? status.source_count : status.sourceCount);
    var discovered = status && (status.discovered_source_count != null ? status.discovered_source_count : status.discoveredSourceCount);
    return status && status.enabled === true && autoSleepAreaMatches(status) &&
      (phase === "live" && Number(count) > 0 || phase === "no_included_sources" && Number(discovered) > 0);
  }

  function autoSleepHistoryPreparing(status) {
    var phase = String(status && status.phase || "").toLowerCase();
    return ["authenticating", "discovering", "learning", "connecting", "synchronizing", "reconnecting"].indexOf(phase) >= 0;
  }

  function autoSleepStatusRetryable(status) {
    var reason = String(status && status.reason || "").toLowerCase();
    var detail = String(status && status.detail || "").toLowerCase();
    return reason === "request_failed" || detail === "registry_transport" || detail === "history_transport";
  }

  function autoSleepHistoryTerminalMessage(status) {
    var phase = String(status && status.phase || "").toLowerCase();
    var detail = String(status && status.detail || "").toLowerCase();
    if (phase === "no_area") return "Assign this panel to a Home Assistant Area to calculate activity history.";
    if (phase === "no_credible_sources") return "No credible device-backed activity source is available in this Area.";
    if (phase === "auth_failed") return "Home Assistant authentication failed. Reconnect Home Assistant to calculate activity history.";
    if (phase === "discovery_failed" && detail === "history_parse") return "Home Assistant returned activity timestamps this panel could not read.";
    if (phase === "discovery_failed" && detail === "history_limit") return "Home Assistant activity history is too large to process safely.";
    if (phase === "discovery_failed") return "Auto-sleep source discovery failed. Check the Home Assistant connection.";
    if (phase === "status_failed") return "Auto-sleep status request failed (HTTP " + Number(status && status.status_code) + ").";
    return "Auto-sleep activity history is unavailable.";
  }

  function scheduleAutoSleepReadiness(afterFailure) {
    if (autoSleepHistoryReadyTimer) clearTimeout(autoSleepHistoryReadyTimer);
    var request = autoSleepRequest;
    var delay = afterFailure ? autoSleepHistoryRetryDelayMs : autoSleepReadinessDelayMs;
    if (afterFailure) autoSleepHistoryRetryDelayMs = Math.min(60 * 1000, autoSleepHistoryRetryDelayMs * 2);
    else autoSleepReadinessDelayMs = Math.min(5000, autoSleepReadinessDelayMs + 500);
    autoSleepHistoryReadyTimer = setTimeout(function () {
      autoSleepHistoryReadyTimer = null;
      if (request === autoSleepRequest && values.auto_sleep === "true") loadAutoSleepData();
    }, delay);
  }

  function autoSleepSegments(history) {
    var rows = history && history.segments;
    return Array.isArray(rows) ? rows.filter(function (row) {
      var start = row.start_epoch_ms != null ? row.start_epoch_ms : row.startEpochMs;
      var end = row.end_epoch_ms != null ? row.end_epoch_ms : row.endEpochMs;
      return typeof start === "number" && typeof end === "number" && end > start;
    }) : [];
  }

  function autoSleepSourceLanes(history) {
    var rows = history && (history.source_lanes || history.sourceLanes);
    return Array.isArray(rows) ? rows : [];
  }

  function toggleAutoSleepSource(source) {
    var sourceKey = source && (source.source_key != null ? source.source_key : source.sourceKey);
    var areaKey = autoSleepHistory && (autoSleepHistory.area_key != null ? autoSleepHistory.area_key : autoSleepHistory.areaKey);
    sourceKey = String(sourceKey || "").trim();
    areaKey = String(areaKey || "").trim();
    if (!areaKey || !sourceKey || autoSleepSourceUpdating[sourceKey]) return;
    var included = source.included !== false;
    var updateGeneration = autoSleepAreaGeneration;
    var updateToken = {};
    autoSleepSourceUpdating[sourceKey] = updateToken;
    autoSleepHistoryWaiting = true;
    autoSleepHistoryWaitingMessage = "Preparing activity history…";
    autoSleepHistoryError = "";
    updateAutoSleepHistory(false);
    fetch("/api/v1/auto-sleep/source", {
      method: "POST",
      headers: { "Accept": "application/json", "Content-Type": "application/json" },
      body: JSON.stringify({ area_key: areaKey, source_key: sourceKey, included: !included })
    }).then(function (response) {
      if (!response.ok) { var error = new Error("HTTP " + response.status); error.status = response.status; throw error; }
      return response.json().catch(function () { return {}; });
    }).then(function () {
      if (updateGeneration !== autoSleepAreaGeneration || autoSleepSourceUpdating[sourceKey] !== updateToken) return;
      delete autoSleepSourceUpdating[sourceKey];
      invalidateAutoSleepData();
      loadAutoSleepData();
    }).catch(function (error) {
      if (updateGeneration !== autoSleepAreaGeneration || autoSleepSourceUpdating[sourceKey] !== updateToken) return;
      delete autoSleepSourceUpdating[sourceKey];
      autoSleepHistoryWaiting = false;
      autoSleepHistoryWaitingMessage = "";
      autoSleepHistoryError = "Could not update this activity source" + (error && error.status ? " (HTTP " + error.status + ")" : "") + ".";
      updateAutoSleepHistory();
    });
  }

  function autoSleepDisplayedHours(history) {
    var hours = history && history.hours;
    if (typeof hours === "number" && isFinite(hours) && hours > 0) return hours;
    var start = history && (history.window_start_epoch_ms != null ? history.window_start_epoch_ms : history.windowStartEpochMs);
    var end = history && (history.window_end_epoch_ms != null ? history.window_end_epoch_ms : history.windowEndEpochMs);
    return typeof start === "number" && typeof end === "number" && end > start ? Math.round((end - start) / 3600000) : autoSleepHistoryHours;
  }

  function autoSleepHistoryBounds(history) {
    var now = Date.now(), fallbackStart = now - autoSleepHistoryHours * 60 * 60 * 1000;
    return {
      start: history && (history.window_start_epoch_ms != null ? history.window_start_epoch_ms : history.windowStartEpochMs) || fallbackStart,
      end: history && (history.window_end_epoch_ms != null ? history.window_end_epoch_ms : history.windowEndEpochMs) || now
    };
  }

  function autoSleepHistorySummary(segments, bounds) {
    var totals = { hold_awake: 0, allow_sleep: 0, inhibited: 0 };
    segments.forEach(function (segment) {
      var output = String(segment.output || "inhibited").toLowerCase();
      var start = segment.start_epoch_ms != null ? segment.start_epoch_ms : segment.startEpochMs;
      var end = segment.end_epoch_ms != null ? segment.end_epoch_ms : segment.endEpochMs;
      if (totals[output] != null) totals[output] += Math.max(0, Math.min(bounds.end, end) - Math.max(bounds.start, start));
    });
    function duration(ms) {
      var minutes = Math.round(ms / 60000);
      return minutes >= 60 ? Math.floor(minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
    }
    return "Calculated auto-sleep: hold awake " + duration(totals.hold_awake) +
      ", allow sleep " + duration(totals.allow_sleep) + ", inhibited " + duration(totals.inhibited) + ".";
  }

  function drawAutoSleepChart(target, replaceSnapshot) {
    var chart = target && target.nodeType ? target : document.getElementById("auto-sleep-chart");
    if (!chart) return;
    var content = chart.querySelector(".auto-sleep-chart-content");
    var overlay = chart.querySelector(".auto-sleep-loading-overlay");
    if (!content) return;
    var history = autoSleepHistory;
    var shouldReplace = replaceSnapshot === true || !content.firstChild;
    if (!shouldReplace) {
      var retainedBusy = autoSleepHistoryBusy();
      Array.prototype.forEach.call(chart.querySelectorAll(".auto-sleep-lane.source[role=button]"), function (row) {
        row.setAttribute("aria-disabled", retainedBusy ? "true" : "false");
      });
      chart.classList.toggle("busy", retainedBusy);
      chart.setAttribute("aria-busy", retainedBusy ? "true" : "false");
      if (overlay) overlay.hidden = !retainedBusy;
      return;
    }
    var displayedHours = autoSleepDisplayedHours(history);
    var policySegments = autoSleepSegments(history), sourceLanes = autoSleepSourceLanes(history), bounds = autoSleepHistoryBounds(history);
    var replacement = el("div", { class: "auto-sleep-chart-snapshot" });
    if (!policySegments.length) replacement.appendChild(el("div", { class: "auto-sleep-empty", text: autoSleepHistoryError || "No replay data" }));
    function span(segment, kind) {
      var start = segment.start_epoch_ms != null ? segment.start_epoch_ms : segment.startEpochMs;
      var end = segment.end_epoch_ms != null ? segment.end_epoch_ms : segment.endEpochMs;
      var state = kind === "policy" ? String(segment.output || "inhibited").toLowerCase() : String(segment.state || "unavailable").toLowerCase();
      var left = Math.max(0, Math.min(100, (start - bounds.start) / Math.max(1, bounds.end - bounds.start) * 100));
      var right = Math.max(left, Math.min(100, (end - bounds.start) / Math.max(1, bounds.end - bounds.start) * 100));
      var names = { hold_awake: "Hold awake", allow_sleep: "Allow sleep", inhibited: "Inhibited", on: "Detected", off: "Clear", unavailable: "Unavailable" };
      var node = el("span", { class: "auto-sleep-interval " + state });
      node.style.left = left + "%"; node.style.width = Math.max(.15, right - left) + "%";
      if (right - left >= 9) node.textContent = names[state] || autoSleepHuman(state);
      return node;
    }
    function lane(label, segments, kind, source) {
      var trackAttrs = { class: "auto-sleep-track" };
      var counts = {};
      segments.forEach(function (segment) {
        var state = kind === "policy" ? String(segment.output || "inhibited").toLowerCase() : String(segment.state || "unavailable").toLowerCase();
        counts[state] = (counts[state] || 0) + 1;
      });
      var names = { hold_awake: "hold awake", allow_sleep: "allow sleep", inhibited: "inhibited", on: "detected", off: "clear", unavailable: "unavailable" };
      var detail = Object.keys(counts).map(function (state) { return (names[state] || autoSleepHuman(state)) + " " + counts[state] + " interval" + (counts[state] === 1 ? "" : "s"); }).join(", ");
      var sourceKey = source && (source.source_key != null ? source.source_key : source.sourceKey);
      sourceKey = String(sourceKey || "").trim();
      var included = !source || source.included !== false;
      var updating = !!(sourceKey && autoSleepSourceUpdating[sourceKey]);
      var interactionBlocked = updating || autoSleepHistoryBusy();
      function sourceInteractionBlocked() {
        return !!autoSleepSourceUpdating[sourceKey] || autoSleepHistoryBusy();
      }
      var stateText = source ? (included ? "included" : "suppressed") : "calculated";
      var labelText = label + (source && !included ? " · Suppressed" : "") + (updating ? " · Updating…" : "");
      var rowAttrs = {
        class: "auto-sleep-lane " + (kind === "policy" ? "policy" : "source") + (included ? "" : " suppressed") + (updating ? " updating" : ""),
        role: sourceKey ? "button" : "img",
        "aria-label": label + (source ? " activity source, " : " result, ") + stateText + ", over " + displayedHours + " hours: " + (detail || "no intervals")
      };
      if (sourceKey) {
        rowAttrs.tabindex = "0";
        rowAttrs["aria-pressed"] = included ? "true" : "false";
        rowAttrs["aria-disabled"] = interactionBlocked ? "true" : "false";
        trackAttrs.title = included ? "Click to suppress this source" : "Click to include this source";
      }
      var track = el("div", trackAttrs);
      segments.forEach(function (segment) { track.appendChild(span(segment, kind)); });
      var row = el("div", rowAttrs, [
        el("div", { class: "auto-sleep-label", text: labelText, title: label }), track
      ]);
      if (sourceKey) {
        row.onclick = function () { if (!sourceInteractionBlocked()) toggleAutoSleepSource(source); };
        row.onkeydown = function (event) {
          if (event.key !== "Enter" && event.key !== " ") return;
          event.preventDefault();
          if (!sourceInteractionBlocked()) toggleAutoSleepSource(source);
        };
      }
      return row;
    }
    if (policySegments.length) {
      replacement.appendChild(lane("Calculated auto-sleep", policySegments, "policy"));
      var sources = el("div", { class: "auto-sleep-source-scroll" });
      sourceLanes.forEach(function (source) { sources.appendChild(lane(source.label || "Activity source", Array.isArray(source.segments) ? source.segments : [], "source", source)); });
      replacement.appendChild(sources);
      var axis = el("div", { class: "auto-sleep-axis", "aria-hidden": "true" }, [el("span", { text: "" }), el("div", { class: "auto-sleep-axis-track" })]);
      var axisTrack = axis.lastChild;
      for (var tick = 0; tick <= 6; tick += 1) {
        var at = new Date(bounds.start + tick / 6 * (bounds.end - bounds.start));
        var marker = el("span", { text: ("0" + at.getHours()).slice(-2) + ":" + ("0" + at.getMinutes()).slice(-2) });
        marker.style.left = tick / 6 * 100 + "%";
        axisTrack.appendChild(marker);
      }
      replacement.appendChild(axis);
    }
    content.replaceChildren(replacement);
    var busy = autoSleepHistoryBusy();
    Array.prototype.forEach.call(chart.querySelectorAll(".auto-sleep-lane.source[role=button]"), function (row) {
      row.setAttribute("aria-disabled", busy ? "true" : "false");
    });
    chart.classList.toggle("busy", busy);
    chart.setAttribute("aria-busy", busy ? "true" : "false");
    if (overlay) overlay.hidden = !busy;
    var description = autoSleepHistorySummary(policySegments, bounds) + " " + sourceLanes.length + " source lanes are shown.";
    var accessible = document.getElementById("auto-sleep-chart-description");
    if (accessible) accessible.textContent = description;
  }

  function updateAutoSleepHistory(replaceSnapshot) {
    var message = document.getElementById("auto-sleep-history-message");
    var windowButtons = document.querySelectorAll(".auto-sleep-windows .pbtn");
    if (message) {
      message.textContent = autoSleepHistoryMessage();
      message.classList.toggle("error", !!autoSleepHistoryError);
    }
    for (var index = 0; index < windowButtons.length; index += 1) {
      var selected = Number(windowButtons[index].getAttribute("data-hours")) === autoSleepHistoryHours;
      windowButtons[index].setAttribute("aria-pressed", selected ? "true" : "false");
      windowButtons[index].disabled = autoSleepHistoryBusy();
    }
    drawAutoSleepChart(null, replaceSnapshot === true);
  }

  function loadAutoSleepHistory() {
    if (values.auto_sleep !== "true" || autoSleepHistoryLoading) return;
    autoSleepHistoryLoading = true; autoSleepHistoryWaiting = false; autoSleepHistoryWaitingMessage = "";
    autoSleepHistoryError = ""; updateAutoSleepHistory();
    var request = ++autoSleepHistoryRequest, retryAutomatically = false, receivedHistory = false, succeeded = false;
    if (autoSleepHistoryReadyTimer) { clearTimeout(autoSleepHistoryReadyTimer); autoSleepHistoryReadyTimer = null; }
    fetch("/api/v1/auto-sleep/history?hours=" + autoSleepHistoryHours, { cache: "no-store" })
      .then(function (response) {
        if (!response.ok) { var httpError = new Error("http"); httpError.status = response.status; throw httpError; }
        return response.json();
      })
      .then(function (body) {
        if (request !== autoSleepHistoryRequest) return;
        if (!body || body.available === false) {
          var unavailable = new Error("unavailable"); unavailable.detail = body && body.detail; throw unavailable;
        }
        if (!autoSleepAreaMatches(body)) {
          var staleArea = new Error("stale area"); staleArea.detail = "sources_changed"; throw staleArea;
        }
        autoSleepHistory = body;
        receivedHistory = true;
        succeeded = true;
        autoSleepReadinessDelayMs = 1000;
        autoSleepHistoryRetryDelayMs = 5000;
      }).catch(function (error) {
        if (request !== autoSleepHistoryRequest) return;
        if (error && error.status >= 400 && error.status < 500) {
          autoSleepHistoryError = "History request failed (HTTP " + error.status + ").";
        }
        else if (error && error.status) {
          retryAutomatically = true;
          autoSleepHistoryWaiting = true;
          autoSleepHistoryWaitingMessage = "Activity history is temporarily unavailable. Retrying automatically…";
        }
        else if (error && (error.detail === "runtime_unavailable" || error.detail === "sources_changed")) {
          retryAutomatically = true;
          autoSleepHistoryWaiting = true;
          autoSleepHistoryWaitingMessage = error.detail === "sources_changed" ?
            "Activity sources changed. Refreshing history…" : "Preparing activity history…";
        }
        else if (error && error.detail === "history_auth") autoSleepHistoryError = "Home Assistant rejected the history request. Reconnect Home Assistant.";
        else if (error && (error.detail === "history_transport" || error.detail === "history_unavailable")) {
          retryAutomatically = true;
          autoSleepHistoryWaiting = true;
          autoSleepHistoryWaitingMessage = "Home Assistant history is temporarily unavailable. Retrying automatically…";
        }
        else if (error && error.name === "TypeError") {
          retryAutomatically = true;
          autoSleepHistoryWaiting = true;
          autoSleepHistoryWaitingMessage = "The panel connection changed. Reconnecting activity history…";
        }
        else if (error && error.detail === "history_parse") autoSleepHistoryError = "Home Assistant returned activity history this build could not read.";
        else if (error && error.detail === "history_limit") autoSleepHistoryError = "Home Assistant returned more activity history rows than the replay safety bound allows.";
        else autoSleepHistoryError = "The activity history response could not be read.";
        if (!retryAutomatically && autoSleepHistory) autoSleepHistoryHours = autoSleepDisplayedHours(autoSleepHistory);
      }).then(function () {
        if (request !== autoSleepHistoryRequest) return;
        autoSleepHistoryLoading = false; updateAutoSleepHistory(receivedHistory);
        if ((succeeded || !retryAutomatically) && typeof window !== "undefined" && window.configCardSizeSourceReady) {
          window.configCardSizeSourceReady("autoSleep");
          if (window.configCardSizeGeometryChanged) window.configCardSizeGeometryChanged();
        }
        if (retryAutomatically) scheduleAutoSleepReadiness(true);
      });
  }

  function invalidateAutoSleepHistory(clearSnapshot) {
    autoSleepHistoryRequest++;
    autoSleepHistoryLoading = false;
    if (clearSnapshot) autoSleepHistory = null;
    autoSleepHistoryError = "";
    autoSleepHistoryWaiting = false;
    autoSleepHistoryWaitingMessage = "";
    autoSleepReadinessDelayMs = 1000;
    autoSleepHistoryRetryDelayMs = 5000;
    if (autoSleepHistoryReadyTimer) { clearTimeout(autoSleepHistoryReadyTimer); autoSleepHistoryReadyTimer = null; }
  }

  function invalidateAutoSleepData(clearSnapshot) {
    autoSleepRequest++;
    autoSleepLoading = false;
    autoSleepStatus = null;
    invalidateAutoSleepHistory(clearSnapshot === true);
  }

  // Read status before history. Transitional discovery is followed automatically with capped backoff;
  // once LIVE, steady state owns no timer and the replay is fetched exactly once.
  function loadAutoSleepData() {
    if (values.auto_sleep !== "true" || autoSleepLoading) return;
    autoSleepLoading = true;
    autoSleepHistoryWaiting = true;
    autoSleepHistoryWaitingMessage = "Preparing activity history…";
    autoSleepHistoryError = "";
    updateAutoSleepSummary();
    updateAutoSleepHistory();
    var request = ++autoSleepRequest;
    fetch("/api/v1/auto-sleep", { cache: "no-store" })
      .then(function (response) {
        if (!response.ok) { var statusError = new Error("status"); statusError.status = response.status; throw statusError; }
        return response.json();
      })
      .then(function (body) {
        if (request !== autoSleepRequest) return;
        autoSleepStatus = body || { available: false, phase: "unavailable" };
      }).catch(function (error) {
        if (request !== autoSleepRequest) return;
        autoSleepStatus = error && error.status >= 400 && error.status < 500 ?
          { available: false, phase: "status_failed", reason: "status_http", status_code: error.status, source_count: 0, manual_suppression: false } :
          { available: false, phase: "unavailable", reason: "request_failed", source_count: 0, manual_suppression: false };
      }).then(function () {
      if (request !== autoSleepRequest) return;
      autoSleepLoading = false;
      updateAutoSleepSummary();
      if (autoSleepHistoryReady(autoSleepStatus)) {
        autoSleepHistoryWaiting = false;
        autoSleepHistoryWaitingMessage = "";
        autoSleepHistoryError = "";
        loadAutoSleepHistory();
      } else if (autoSleepAreaTransitioning(autoSleepStatus) || autoSleepHistoryPreparing(autoSleepStatus) || autoSleepStatusRetryable(autoSleepStatus)) {
        var retryAfterFailure = autoSleepStatusRetryable(autoSleepStatus);
        autoSleepHistoryWaiting = true;
        autoSleepHistoryWaitingMessage = autoSleepAreaTransitioning(autoSleepStatus) || autoSleepHistoryPreparing(autoSleepStatus) ?
          "Preparing activity history…" : "Waiting for auto-sleep status…";
        autoSleepHistoryError = "";
        updateAutoSleepHistory();
        scheduleAutoSleepReadiness(retryAfterFailure);
      } else {
        autoSleepHistoryWaiting = false;
        autoSleepHistoryWaitingMessage = "";
        autoSleepHistoryError = autoSleepHistoryTerminalMessage(autoSleepStatus);
        updateAutoSleepHistory();
        if (typeof window !== "undefined" && window.configCardSizeSourceReady) {
          window.configCardSizeSourceReady("autoSleep");
          if (window.configCardSizeGeometryChanged) window.configCardSizeGeometryChanged();
        }
      }
    });
  }

  var autoSleepResizeTimer = null;
  if (window.addEventListener) {
    window.addEventListener("resize", function () {
      if (autoSleepResizeTimer) clearTimeout(autoSleepResizeTimer);
      autoSleepResizeTimer = setTimeout(drawAutoSleepChart, 100);
    });
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
    } else if (f.key === "auto_sleep") {
      help = el("small", { text: "Automatically wake the panel when activity is detected and switch the screen off after the learned delay. Manual screen control remains separate." });
    } else if (f.help) {
      help = el("small", { text: f.help });
    }
    var protectedSetting = !!HARDENED_APPROVAL_SETTING_KEYS[f.key];
    var labelText = el("span");
    if (protectedSetting) {
      // The shield is a pseudo-element, so a non-breaking space alone does not reliably bind it to
      // the label in older WebViews. Keep the final word and the shield in one non-wrapping inline run.
      var words = f.label.trim().split(/\s+/);
      if (words.length > 1) labelText.appendChild(document.createTextNode(words.slice(0, -1).join(" ") + " "));
      var shieldTail = el("span", { class: "hardened-label-tail", text: words[words.length - 1] || f.label });
      shieldTail.setAttribute("data-hardened-approval", "conditional");
      shieldTail.setAttribute("aria-describedby", "hardened-approval-conditional-description");
      shieldTail.setAttribute("title", "Changing this setting may require physical on-panel approval when Hardened mode is enabled.");
      labelText.appendChild(shieldTail);
    } else labelText.textContent = f.label;
    var label = el("div", { class: "flabel" }, [
      labelText,
      help,
      Object.prototype.hasOwnProperty.call(applyPending, f.key) ?
        el("small", { class: "apply-pending-status", text: "Saved desired value; hardware application is pending." }) : null,
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
    var dependencyDisabled = (f.key === "auto_brightness" || f.key === "auto_brightness_minimum_percent" || f.key === "auto_brightness_sensitivity") && !ambientLightSourceReady();
    return el("div", {
      class: "frow" + (f.available ? "" : " muted") + (dependencyDisabled ? " dependency-disabled" : ""),
      id: "cfg-" + f.key
    }, [label, ctl]);
  }

  function shouldRenderRow(f) {
    if ((f.key === "auto_brightness_minimum_percent" || f.key === "auto_brightness_sensitivity") && values.auto_brightness !== "true") return false;
    return true;
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
  // Logging lost its experimental badge on 2026-07-27, when log shipping was finally proven on
  // hardware: all three transports delivered marked probe records AND real shipped log lines into a
  // live collector, addressed by hostname. Display keeps its badge — that work is still unvalidated.
  var CARD_BADGES = { "Display": ["experimental", "exp"] };
  var CARD_NOTES = {
    "Sensors": "Home Assistant reporting",
    "Diagnostics": "Home Assistant reporting"
  };
  var BUILTIN_RENDERER_KEYS = {
    dashboard_entity_learning: true, dashboard_fullscreen: true, dashboard_native_kiosk: true,
    dashboard_idle_return_min: true, dashboard_zoom: true
  };
  var BUILTIN_RENDERER_ONLY_KEYS = { dashboard_idle_return_min: true };
  var HA_CONNECTION_KEYS = { ha_url: true, ha_token: true };
  var CONFIG_LAYOUT_KEYS = {
    "Identity": "configure-identity", "MQTT": "configure-mqtt", "Behaviour": "configure-behaviour",
    "Display": "configure-display", "System": "configure-system", "Sensors": "configure-sensors",
    "Diagnostics": "configure-diagnostics", "Logging": "configure-logging",
    "Home Assistant connection": "configure-ha-connection", "Dashboard": "configure-dashboard",
    "Built-in renderer": "configure-builtin-renderer"
  };
  function configLayoutKey(group) {
    return CONFIG_LAYOUT_KEYS[group] || ("configure-" + String(group).toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 50));
  }
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
    var retainedAutoSleepPanel = document.getElementById("auto-sleep-status");
    if (!retainedAutoSleepPanel || !retainedAutoSleepPanel.parentNode || typeof retainedAutoSleepPanel.contains !== "function") retainedAutoSleepPanel = null;
    var retainedAutoSleepFocus = retainedAutoSleepPanel && retainedAutoSleepPanel.contains(document.activeElement) ? document.activeElement : null;
    var retainedAutoSleepScroll = retainedAutoSleepPanel && retainedAutoSleepPanel.querySelector(".auto-sleep-source-scroll");
    var retainedAutoSleepScrollTop = retainedAutoSleepScroll ? retainedAutoSleepScroll.scrollTop : 0;
    var retainedAutoSleepPageX = retainedAutoSleepPanel ? (window.pageXOffset || 0) : 0;
    var retainedAutoSleepPageY = retainedAutoSleepPanel ? (window.pageYOffset || 0) : 0;
    var autoSleepParking = null;
    if (retainedAutoSleepPanel) {
      // render() rebuilds unrelated Configure cards after asynchronous probes. Keep the activity
      // subtree connected while that happens so its chart, scroll and focus do not flash away.
      autoSleepParking = el("div", { hidden: "", "aria-hidden": "true" });
      root.parentNode.insertBefore(autoSleepParking, root.nextSibling);
      autoSleepParking.appendChild(retainedAutoSleepPanel);
    }
    if (haPickerCleanup) haPickerCleanup();
    haOauthButton = null; haOauthStatus = null; haOauthLinks = null;
    root.innerHTML = "";
    var groups = [];
    schema.forEach(function (f) {
      var group = presentationGroup(f);
      if (groups.indexOf(group) < 0) groups.push(group);
    });
    function moveGroupTo(name, index) {
      var currentIndex = groups.indexOf(name);
      if (currentIndex < 0) return;
      groups.splice(currentIndex, 1);
      groups.splice(Math.min(index, groups.length), 0, name);
    }
    moveGroupTo("Home Assistant connection", 2);
    moveGroupTo("Dashboard", 3);
    moveGroupTo("Built-in renderer", 4);
    var loggingIndex = groups.indexOf("Logging");
    if (loggingIndex >= 0 && loggingIndex !== groups.length - 1) {
      groups.splice(loggingIndex, 1);
      groups.push("Logging");
    }
    var shown = 0;
    groups.forEach(function (g) {
      var fields = schema.filter(function (f) {
        return presentationGroup(f) === g && f.available &&
          (!BUILTIN_RENDERER_ONLY_KEYS[f.key] || !values.dashboard_package || values.dashboard_package === "builtin") &&
          (advanced || f.tier === "BASIC");
      });
      if (!fields.length) return;
      shown += fields.length;
      // Maturity badges on whole cards; Logging is intentionally no longer experimental.
      var h2kids = [el("span", { text: g })];
      if (CARD_NOTES[g]) h2kids.push(el("small", { text: " · " + CARD_NOTES[g] }));
      var badge = CARD_BADGES[g];
      if (badge) h2kids.push(el("span", { class: "cardbadge " + badge[1], text: badge[0] }));
      var card = el("div", { class: "card" }, [el("h2", {}, h2kids)]);
      card.setAttribute("data-config-group", g);
      card.setAttribute("data-layout-key", configLayoutKey(g));
      // When preserving the activity subtree, connect its destination before moving the subtree
      // out of the parking node. That avoids even a synchronous detach/blur during this rebuild.
      if (retainedAutoSleepPanel && g === "Behaviour") root.appendChild(card);
      fields.forEach(function (f) {
        if (!shouldRenderRow(f)) return;
        card.appendChild(row(f));
        if (g === "Behaviour" && f.key === "auto_sleep") card.appendChild(autoSleepPrerequisiteNode());
        if (g === "Behaviour" && f.key === "auto_sleep" && values.auto_sleep === "true") {
          card.appendChild(retainedAutoSleepPanel || autoSleepPanel());
          if (!autoSleepStatus && !autoSleepLoading) setTimeout(loadAutoSleepData, 0);
        }
        if (g === "Home Assistant connection" && f.key === "ha_url") card.appendChild(haOAuthRow());
        if (f.key === "zigbee_router") {
          var join = zigbeeJoinRow();
          if (join) card.appendChild(join);
        }
      });
      if (g === "Display") {
        if (values.auto_brightness === "true" && ambientLightSourceConfigured()) card.appendChild(autoBrightnessPanel());
        if (!autoBrightStatus && !autoBrightLoading) loadAutoBrightnessData(false);
      }
      // Dashboard card action: clear the built-in renderer's browsing storage — the heal for a
      // corrupted localStorage/IndexedDB that survives reloads. Never logs the panel out (auth lives
      // in ha-paneld's config, not the WebView).
      if (g === "Built-in renderer") {
        var st = el("span", { class: "muted" });
        var clearStatusTimer = null;
        function setClearStatus(text, transient) {
          if (clearStatusTimer) clearTimeout(clearStatusTimer);
          clearStatusTimer = null;
          st.textContent = text;
          if (transient) clearStatusTimer = setTimeout(function () {
            clearStatusTimer = null;
            st.textContent = "";
          }, 4000);
        }
        var btn = el("button", {
          class: "pbtn", text: "Clear renderer storage",
          "aria-describedby": "hardened-approval-description",
          title: "Requires physical on-panel approval for this action when Hardened mode is enabled."
        });
        btn.onclick = function () {
          setClearStatus("Clearing…", false);
          fetch("/api/v1/dashboard/clear-storage", { method: "POST" })
            .then(function (r) { return approvalAwareJson(r).then(function () { return r; }); })
            .then(function (r) { setClearStatus(r.ok ? "Clear requested." : "Failed (HTTP " + r.status + ")", r.ok); })
            .catch(function (error) { setClearStatus(error && error.approvalRequired ? error.message : "Failed (network)", false); });
        };
        card.appendChild(el("div", { class: "frow" }, [
          el("div", { class: "flabel" }, [
            el("span", {
              text: "Renderer storage", "data-hardened-approval": "",
              "aria-describedby": "hardened-approval-description",
              title: "Requires physical on-panel approval for this action when Hardened mode is enabled."
            }),
            el("small", { text: "Clear cached dashboard data. Keeps sign-in." }),
          ]),
          el("div", { class: "fctl" }, [btn, st]),
        ]));
      }
      // Logging card action: ask the panel to reach the sink from its OWN network vantage — the only
      // one that matters, and the one you cannot check from the browser. UDP has no delivery signal,
      // so its verdict deliberately stops at "sent" and hands back a marker to grep for; claiming
      // more would recreate the false confidence that made this feature look like it worked.
      if (g === "Logging") {
        var sinkStatus = el("span", { class: "muted" });
        var sinkTimer = null;
        function setSinkStatus(text, transient) {
          if (sinkTimer) clearTimeout(sinkTimer);
          sinkTimer = null;
          sinkStatus.textContent = text;
          if (transient) sinkTimer = setTimeout(function () {
            sinkTimer = null;
            sinkStatus.textContent = "";
          }, 12000);
        }
        var sinkBtn = el("button", { class: "pbtn", text: "Test sink" });
        sinkBtn.onclick = function () {
          // `values` tracks every keystroke, so this probes what is on screen rather than what was
          // last saved — the point is to catch a typo before committing it.
          var host = String(values.log_ship_host || "").trim();
          if (!host) { setSinkStatus("Set a sink host first.", true); return; }
          var query = "host=" + encodeURIComponent(host) +
            "&port=" + encodeURIComponent(values.log_ship_port || 514) +
            "&protocol=" + encodeURIComponent(values.log_ship_protocol || "");
          sinkBtn.disabled = true;
          setSinkStatus("Testing…", false);
          // POST, not GET: this transmits a record to a caller-named host, so it rides the same
          // state-changing admission as every other mutating endpoint.
          fetch("/api/v1/config/probe-log-sink", {
            method: "POST",
            cache: "no-store",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: query
          })
            .then(function (r) { return r.json(); })
            .then(function (p) {
              sinkBtn.disabled = false;
              var where = (p.host || host) + ":" + (p.port || "");
              var look = p.marker ? " Look for “" + p.marker + "” in your collector." : "";
              if (p.ok && p.protocol === "syslog-udp") {
                setSinkStatus("Sent a test record to " + where + ". UDP is unacknowledged, so the " +
                  "panel cannot tell you it arrived." + look, false);
              } else if (p.ok && p.protocol === "syslog-tcp") {
                setSinkStatus("Wrote a test record to " + where + ". A TCP socket write is not collector " +
                  "acknowledgement; verify the marker in your collector." + look, false);
              } else if (p.ok) {
                setSinkStatus("The HTTP collector at " + where + " accepted a test record." + look, false);
              } else if (String(p.error || "").indexOf("http-") === 0) {
                setSinkStatus(where + " rejected the test record with HTTP " + p.status +
                  ". It is listening, but it is not accepting this format.", false);
              } else if (p.error === "no-host") {
                setSinkStatus("Set a sink host first.", true);
              } else if (p.error === "unresolvable") {
                setSinkStatus("“" + (p.host || host) + "” doesn’t resolve on the panel’s network.", false);
              } else if (p.error === "unreachable") {
                setSinkStatus("Nothing is listening at " + where + ". A collector set up for UDP will " +
                  "refuse TCP — check the protocol as well as the port.", false);
              } else {
                setSinkStatus("Test failed (" + (p.error || "unknown") + ").", false);
              }
            })
            .catch(function () {
              sinkBtn.disabled = false;
              setSinkStatus("Test failed (network).", false);
            });
        };
        card.appendChild(el("div", { class: "frow" }, [
          el("div", { class: "flabel" }, [
            el("span", { text: "Check the sink" }),
            el("small", { text: "Reach the collector from the panel, without saving." }),
          ]),
          el("div", { class: "fctl" }, [sinkBtn, sinkStatus]),
        ]));
      }
      if (!card.parentNode) root.appendChild(card);
      if (retainedAutoSleepPanel && card.contains(retainedAutoSleepPanel)) {
        updateAutoSleepSummary();
        updateAutoSleepHistory();
      }
    });
    if (proximityCard) {
      root.insertBefore(proximityCard, root.querySelector('[data-config-group="Logging"]'));
    }
    document.getElementById("cfg-status").style.display = shown ? "none" : "block";
    if (!shown) document.getElementById("cfg-status").textContent = "No settings in this view.";
    if (retainedAutoSleepFocus && retainedAutoSleepFocus.isConnected && document.activeElement !== retainedAutoSleepFocus) {
      try { retainedAutoSleepFocus.focus({ preventScroll: true }); }
      catch (_) { retainedAutoSleepFocus.focus(); }
    }
    if (retainedAutoSleepScroll && retainedAutoSleepScroll.isConnected) retainedAutoSleepScroll.scrollTop = retainedAutoSleepScrollTop;
    if (retainedAutoSleepPanel && window.scrollTo) window.scrollTo(retainedAutoSleepPageX, retainedAutoSleepPageY);
    if (autoSleepParking) autoSleepParking.remove();
    if (window.CardSizeMemory) window.CardSizeMemory.restore("cfg-groups");
    focusHash();
    scheduleConfigColumnAlignment();
  }

  window.cfgTab = function (adv) {
    advanced = adv;
    document.getElementById("tab-basic").classList.toggle("on", !adv);
    document.getElementById("tab-adv").classList.toggle("on", adv);
    render();
  };

  function restampConfigWatchBaseline() {
    // OUR save changes the cfg fingerprint too. Re-stamp even when newer local edits prevent a form
    // reload, otherwise buildwatch.js falsely reports those acknowledged changes as external.
    setTimeout(function () {
      fetch("/health").then(function (r) { return r.text(); }).then(function (t) {
        var m = t.match(/cfg=(\S+)/); if (m) document.body.setAttribute("data-cfg", m[1]);
      }).catch(function () {});
    }, 500);
  }

  function firstInvalidDirtySetting() {
    for (var i = 0; i < schema.length; i++) {
      var field = schema[i];
      if (!dirtyValues[field.key]) continue;
      var row = document.getElementById("cfg-" + field.key);
      var control = row && row.querySelector ? row.querySelector("input,select,textarea") : null;
      if (!control || control.disabled || !control.checkValidity || control.checkValidity()) continue;
      var range = field.min != null && field.max != null ? " between " + field.min + " and " + field.max : "";
      var message = range ? field.label + " must be" + range + "." : field.label + " has an invalid value.";
      return { field: field, control: control, message: message };
    }
    return null;
  }

  window.cfgSave = function () {
    if (!dirty || saving) return;
    var msg = document.getElementById("cfg-msg");
    var invalid = firstInvalidDirtySetting();
    if (invalid) {
      msg.textContent = invalid.message;
      if (invalid.control.reportValidity) invalid.control.reportValidity();
      if (invalid.control.focus) invalid.control.focus();
      return;
    }
    saving = true;
    // Fence any mDNS suggestions computed against the pre-save broker/HA baseline.
    configDiscoveryRequest++;
    var submittedGeneration = editGeneration;
    updateSaveUi();
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
    msg.textContent = "Saving…";
    fetch("/api/v1/config", {
      method: "POST", headers: { "Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded" },
      body: body.toString(),
    }).then(function (r) {
      return approvalAwareJson(r).then(function (body) {
        if (!r.ok) {
          var error = new Error(body.message || body.error || ("HTTP " + r.status));
          error.configOutcome = body;
          throw error;
        }
        return body;
      });
    })
      .then(function (outcome) {
        var outcomeMessage = outcome && outcome.message || "Saved.";
        var autoBrightnessSourceChanged = Object.prototype.hasOwnProperty.call(submittedValues, "auto_brightness_ha_entity");
        if (autoBrightnessSourceChanged) beginAutoBrightnessSourceTransition(submittedValues.auto_brightness_ha_entity);
        Object.keys(submittedValues).forEach(function (key) { savedValues[key] = submittedValues[key]; });
        Object.keys(submittedExpose).forEach(function (key) { savedExpose[key] = submittedExpose[key]; });
        recomputeDirty();
        loadRadio();
        restampConfigWatchBaseline();
        var autoSleepInputsChanged = ["auto_sleep", "ha_url", "ha_token"].some(function (key) {
          return Object.prototype.hasOwnProperty.call(submittedValues, key);
        });
        var haConnectionInputsChanged = ["ha_url", "ha_token"].some(function (key) {
          return Object.prototype.hasOwnProperty.call(submittedValues, key);
        });
        if (autoSleepInputsChanged) {
          invalidateAutoSleepData(haConnectionInputsChanged);
        }
        if (editGeneration !== submittedGeneration) {
          (outcome && outcome.pending || []).forEach(function (key) {
            if (Object.prototype.hasOwnProperty.call(submittedValues, key)) applyPending[key] = submittedValues[key];
          });
          scheduleApplyPendingPoll();
          saving = false;
          msg.textContent = dirty ?
            ((outcome && outcome.pending && outcome.pending.length) ?
              outcomeMessage + " Newer changes still need saving." : "Saved; newer changes still need saving.") :
            outcomeMessage;
          updateSaveUi();
          if (autoSleepInputsChanged && values.auto_sleep === "true") setTimeout(loadAutoSleepData, 0);
          if (autoBrightnessSourceChanged) setTimeout(function () { loadAutoBrightnessData(true); }, 0);
          return;
        }
        saving = false;
        msg.textContent = "Saving…"; clearDirty();
        // The optional Presence & wake card is server-rendered only while Wake on wave is enabled.
        if (Object.prototype.hasOwnProperty.call(submittedValues, "wake_on_wave")) {
          window.location.reload();
          return;
        }
        // Reload the form from the server, then land on a terminal message — don't leave a
        // "reconnecting…" string hanging (it reads as stuck even though the save is done).
        // Clear before load(): its render pass is the single owner that schedules enabled status.
        load(function (ok) {
          msg.textContent = ok ? outcomeMessage : "Saved (reload failed — refresh the page).";
          var autoBrightnessSettingChanged = Object.prototype.hasOwnProperty.call(submittedValues, "auto_brightness") ||
              Object.prototype.hasOwnProperty.call(submittedValues, "auto_brightness_minimum_percent") ||
              Object.prototype.hasOwnProperty.call(submittedValues, "auto_brightness_sensitivity") ||
              autoBrightnessSourceChanged;
          if (autoBrightnessSourceChanged || (ok && autoBrightnessSettingChanged)) {
            loadAutoBrightnessData(true);
          }
          if (ok && !(outcome && outcome.pending && outcome.pending.length)) {
            if (!keepSaveMessageVisible(outcomeMessage)) {
              setTimeout(function () { if (msg.textContent === outcomeMessage) msg.textContent = ""; }, 2500);
            }
          }
        }, haConnectionInputsChanged);
      })
      .catch(function (e) {
        saving = false;
        if (e && e.configOutcome && e.configOutcome.status === "saved-partial") {
          load(function () {
            msg.textContent = e.message;
            updateSaveUi();
          }, false);
        } else {
          msg.textContent = e && e.message ? e.message : "Save failed.";
          updateSaveUi();
        }
      });
  };

  function load(done, forceHaUserStatusRefresh, refreshAutoSleepPrerequisite) {
    ++haAreaSeedGeneration;
    Promise.all([
      fetch("/api/v1/config/schema").then(function (r) { return r.json(); }),
      fetch("/api/v1/config").then(function (r) { return r.json(); }),
      // Installed launchable apps for the package pickers; tolerate failure (picker falls back to text).
      fetch("/api/v1/apps").then(function (r) { return r.json(); }).catch(function () { return { apps: [] }; }),
    ]).then(function (res) {
      var previousHaUrl = values.ha_url;
      var previousHaConfigured = haAuth.configured === true;
      var previousHaOauth = haAuth.oauth === true;
      schema = res[0];
      values = res[1].settings || {};
      expose = res[1].ha_expose || {};
      haAuth = res[1].ha_auth || {};
      applyPending = res[1].apply_pending || {};
      haAreaSeed = res[1].ha_area_catalog || null;
      haAreaUserOverride = res[1].ha_area_user_override === true;
      if (values.auto_sleep === "true") configCardExpected.autoSleep = true;
      if (haAuth.configured === true) configCardExpected.ha = true;
      var haConnectionChanged = forceHaUserStatusRefresh === true ||
        previousHaUrl !== values.ha_url ||
        previousHaConfigured !== (haAuth.configured === true) ||
        previousHaOauth !== (haAuth.oauth === true);
      // Keep an already-rendered identity stable across unrelated saves. A real HA connection edit
      // clears the old user's name and performs a fresh no-store current-user probe below.
      if (haConnectionChanged) {
        haUserStatus = { phase: "unknown" };
        autoSleepAreaGeneration++;
        autoSleepSourceUpdating = Object.create(null);
        invalidateAutoSleepData(true);
        autoSleepAssignedAreaName = "";
        autoSleepPrerequisiteRequest++;
        autoSleepPrerequisite = { eligible: false, phase: "checking", area_name: "" };
      }
      apps = (res[2] && res[2].apps) || [];
      // Normalize bool values to the "true"/"false" strings the toggle compares against.
      schema.forEach(function (f) {
        if (f.type === "BOOL" && typeof values[f.key] === "boolean") values[f.key] = values[f.key] ? "true" : "false";
        if (values[f.key] != null && typeof values[f.key] !== "string") values[f.key] = String(values[f.key]);
        if (Object.prototype.hasOwnProperty.call(applyPending, f.key)) {
          var desired = applyPending[f.key];
          if (f.type === "BOOL" && typeof desired === "boolean") desired = desired ? "true" : "false";
          values[f.key] = typeof desired === "string" ? desired : String(desired);
        }
      });
      savedValues = Object.assign({}, values);
      savedExpose = Object.assign({}, expose);
      // A successful full reload replaces the form's local snapshot, so no previously tracked edit remains.
      clearDirty();
      render();
      loadDiscoverySuggestions();
      loadHomeDashboards();
      if (!done && Object.keys(applyPending).length) {
        document.getElementById("cfg-msg").textContent =
          "Saved settings waiting to apply: " + Object.keys(applyPending).join(", ") + ".";
      }
      scheduleApplyPendingPoll();
      if (refreshAutoSleepPrerequisite !== false) scheduleAutoSleepPrerequisite();
      if (haConnectionChanged) loadHaUserStatus();
      configCardSourceReady("core");
      configCardGeometryChanged();
      if (done) done(true);
    }).catch(function (e) {
      document.getElementById("cfg-status").textContent = "Could not load settings (" + e + ").";
      if (done) done(false);
    });
  }

  function loadDiscoverySuggestions() {
    var request = ++configDiscoveryRequest;
    fetch("/api/v1/config/discovery", { cache: "no-store" }).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    }).then(function (suggestions) {
      // Never rebuild the form over an edit made while the bounded mDNS browse was in flight.
      if (request !== configDiscoveryRequest || dirty) return;
      var applied = false;
      ["mqtt_broker", "ha_url"].forEach(function (key) {
        var suggestion = suggestions && suggestions[key];
        if (!suggestion || values[key] || savedValues[key] || dirtyValues[key]) return;
        values[key] = String(suggestion);
        applied = true;
      });
      if (!applied) return;
      editGeneration++;
      recomputeDirty();
      render();
      configCardGeometryChanged();
    }).catch(function () {});
  }

  function loadHomeDashboards() {
    if (dirty || haAuth.configured !== true) return;
    var request = ++homeDashboardRequest;
    fetch("/api/v1/config/home-dashboards", { cache: "no-store" }).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    }).then(function (body) {
      if (request !== homeDashboardRequest || dirty) return;
      var next = (body && body.items || []).map(function (dashboard) {
        return {
          path: String(dashboard && dashboard.path || "").trim(),
          title: String(dashboard && dashboard.title || "").trim(),
          icon: String(dashboard && dashboard.icon || "").trim(),
          group: String(dashboard && dashboard.group || "dashboard").trim(),
        };
      }).filter(function (dashboard) { return dashboard.path; });
      var nextDefault = {
        explicit: !!(body && body.default && body.default.explicit),
        path: String(body && body.default && body.default.path || "").trim(),
      };
      var nextQueried = !!(body && body.queried);
      var changed = next.length !== homeDashboardItems.length || next.some(function (dashboard, index) {
        var previous = homeDashboardItems[index];
        return !previous || dashboard.path !== previous.path || dashboard.title !== previous.title ||
          dashboard.icon !== previous.icon || dashboard.group !== previous.group;
      }) || nextDefault.explicit !== homeDashboardDefault.explicit || nextDefault.path !== homeDashboardDefault.path ||
        nextQueried !== homeDashboardQueried;
      if (!changed) return;
      homeDashboardItems = next;
      homeDashboardDefault = nextDefault;
      homeDashboardQueried = nextQueried;
      render();
      configCardGeometryChanged();
    }).catch(function () {});
  }

  function scheduleApplyPendingPoll() {
    if (applyPendingTimer) clearTimeout(applyPendingTimer);
    applyPendingTimer = null;
    if (!Object.keys(applyPending).length) return;
    applyPendingTimer = setTimeout(function () {
      fetch("/api/v1/config", { headers: { "Accept": "application/json" }, cache: "no-store" })
        .then(function (response) { if (!response.ok) throw response.status; return response.json(); })
        .then(function (body) {
          var next = body.apply_pending || {};
          var currentKeys = Object.keys(applyPending), nextKeys = Object.keys(next);
          var changed = currentKeys.length !== nextKeys.length || nextKeys.some(function (key) {
            return !Object.prototype.hasOwnProperty.call(applyPending, key) ||
              String(applyPending[key]) !== String(next[key]);
          });
          if (!changed) return;
          var changedKeys = currentKeys.concat(nextKeys).filter(function (key, index, keys) {
            return keys.indexOf(key) === index;
          });
          if (!dirty) {
            load(function (ok) {
              if (ok && !nextKeys.length) {
                document.getElementById("cfg-msg").textContent = "Saved settings are now applied.";
              }
            }, false, false);
            return;
          }
          applyPending = next;
          changedKeys.forEach(function (key) {
            var row = document.getElementById("cfg-" + key);
            var label = row && row.querySelector(".flabel");
            if (!label) return;
            var status = label.querySelector(".apply-pending-status");
            if (Object.prototype.hasOwnProperty.call(next, key)) {
              if (!status) label.appendChild(el("small", {
                class: "apply-pending-status", text: "Saved desired value; hardware application is pending."
              }));
            } else if (status) status.remove();
          });
          if (!nextKeys.length) {
            var msg = document.getElementById("cfg-msg");
            msg.textContent = "Saved settings are now applied; newer changes still need saving.";
          }
        })
        .catch(function () {})
        .then(scheduleApplyPendingPoll);
    }, 3000);
  }

  function loadRadio() {
    return fetch("/api/v1/radio").then(function (r) { return r.json(); }).then(function (body) {
      radio = body && body.present ? body : null;
      render();
      configCardSourceReady("radio");
      configCardGeometryChanged();
      return radio;
    }).catch(function () {
      radio = null;
      render();
      configCardGeometryInvalid();
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

  function handleHaOAuthResult(status) {
      haOauthAuthorizationUrl = "";
      if (haOauthLinks) {
        haOauthLinks.hidden = true;
        var openLink = haOauthLinks.querySelector("a");
        if (openLink) openLink.removeAttribute("href");
      }
      if (status === "success") {
        haAuth = { configured: true, oauth: true };
        if (haOauthTargetUrl) {
          values.ha_url = haOauthTargetUrl;
          savedValues.ha_url = haOauthTargetUrl;
        }
        // Browser sign-in supersedes any manually typed token still present in this form.
        values.ha_token = "";
        savedValues.ha_token = "";
        haOauthTargetUrl = "";
        autoSleepAreaGeneration++;
        autoSleepSourceUpdating = Object.create(null);
        invalidateAutoSleepData(true);
        autoSleepAssignedAreaName = "";
        autoSleepPrerequisiteRequest++;
        autoSleepPrerequisite = { eligible: false, phase: "checking", area_name: "" };
        recomputeDirty(); updateSaveUi(); render();
        scheduleAutoSleepPrerequisite();
        if (values.auto_sleep === "true") setTimeout(loadAutoSleepData, 0);
        loadHaUserStatus();
        document.getElementById("cfg-msg").textContent = "Home Assistant configured.";
      } else {
        haOauthTargetUrl = "";
        setHaOauthStatus("Sign-in was not completed. Start again.", false);
        syncHaOAuthAvailability();
      }
  }

  if ("BroadcastChannel" in window) {
    var haOauthChannel = new BroadcastChannel("ha-paneld-ha-oauth");
    // Node-backed asset tests expose BroadcastChannel too; do not let its listener hold that process open.
    if (haOauthChannel.unref) haOauthChannel.unref();
    haOauthChannel.addEventListener("message", function (event) {
      if (event.data && (event.data.status === "success" || event.data.status === "failure")) {
        handleHaOAuthResult(event.data.status);
      }
    });
  }

  if (window.addEventListener) {
    window.addEventListener("focus", scheduleAutoSleepPrerequisite);
    document.addEventListener("visibilitychange", function () {
      if (document.visibilityState === "visible") scheduleAutoSleepPrerequisite();
    });
  }

  load(null, true);
  loadRadio();
})();
