// Configure tab — schema-driven form. Fetches /api/v1/config/schema (metadata) + /api/v1/config
// (current values + per-key HA-exposure flags), renders Basic/Advanced grouped fields with an inline
// "expose to HA" pip on each HA-capable row, and saves via partial-merge POST. Vanilla, no build.
(function () {
  "use strict";
  // Advanced is the DEFAULT view until the reduced Basic set is settled (user, 2026-07-01).
  var schema = [], values = {}, expose = {}, haAuth = {}, applyPending = {}, applyPendingTimer = null, advanced = true, dirty = false, saving = false, editGeneration = 0, configDiscoveryRequest = 0, schemaLanguageRequest = 0, apps = [], rendererChoices = [], radio = null;
  var savedValues = {}, savedExpose = {};
  var dirtyValues = Object.create(null), dirtyExpose = Object.create(null);
  var joinCooldownUntil = 0, joinPollTimer = null, hashFocused = false;
  var haSourceItems = [], haSourceRequest = 0, haSourceTimer = null;
  var homeDashboardItems = [], homeDashboardRequest = 0, homeDashboardQueried = false;
  // Assist pipeline catalogue for the voice_pipelines picker. null = not fetched yet, false = the
  // endpoint returned an error/503 (degrade to the raw JSON textarea), an array = the fetched catalogue.
  var voicePipelinesCatalog = null, voicePipelinesRequest = 0;
  // A per-load, owner-safe seed supplied by the config response. The endpoint is still fetched every
  // render so a failed query, credential change or Home Assistant area edit can recover immediately.
  var haAreaSeed = null, haAreaSeedGeneration = 0, haAreaCatalogRequest = 0, haAreaUserOverride = false;
  var homeDashboardDefault = { explicit: false, path: "" };
  // Sentinel for the "Custom…" option. Not a reachable path: a real dashboard root must match
  // ^[a-z0-9][a-z0-9_-]*$, which no leading underscore can satisfy, so it can never collide.
  var CUSTOM_DASHBOARD = "__custom__";
  // A fast client-side reject for the shapes that can never address a dashboard (a URL, a protocol-
  // relative host, a traversal, whitespace). It is deliberately NOT the authority — SettingsRegistry's
  // home_dashboard validator is, running the same rule the renderer admits routes with — so this only
  // has to be tight enough to catch a typo before the round trip, never to be trusted.
  //
  // Evaluated through new RegExp, never as an HTML `pattern` attribute: current Chromium compiles
  // `pattern` with the `v` flag, where the unescaped `/` and `?` in `[^/?#\s]` are invalid, and a
  // browser that cannot parse a pattern silently IGNORES the constraint. That made this check inert on
  // Configure while the wizard's identical expression worked, and no string-comparing contract test
  // could see the difference. A trailing slash is accepted because the server canonicalizes `/office/`
  // to `/office`; the client must never refuse what the authority would accept.
  var DASHBOARD_PATH_PATTERN = "\\s*/?[a-z0-9][a-z0-9_-]*(?:/[^/?#]*)*(?:\\?[^#]*)?(?:#.*)?\\s*";
  function wellFormedDashboardPath(path) {
    return new RegExp("^(?:" + DASHBOARD_PATH_PATTERN + ")$").test(String(path || ""));
  }
  // The dashboard root a path belongs to (/office/view?k=1 → /office), or "" when it has none.
  function dashboardRootOf(path) {
    var route = String(path || "").trim().split("?")[0].split("#")[0];
    var first = route.split("/").filter(function (s) { return s !== ""; })[0] || "";
    return /^[a-z0-9][a-z0-9_-]*$/.test(first) ? "/" + first : "";
  }
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

  function validLanguageTag(value) {
    return typeof value === "string" && value.length <= 63 &&
      /^[A-Za-z0-9]{1,8}(?:[-_][A-Za-z0-9]{1,8})*$/.test(value);
  }

  function storeBrowserLanguage(value) {
    try {
      if (value) window.localStorage.setItem("selectedLanguage", JSON.stringify(value));
      else window.localStorage.removeItem("selectedLanguage");
    } catch (_) {}
  }

  function stripLanguageQuery() {
    if (!window.history || !window.history.replaceState) return;
    var params = new URLSearchParams(window.location.search);
    if (!params.has("lang")) return;
    params.delete("lang");
    var query = params.toString();
    window.history.replaceState(null, "", window.location.pathname + (query ? "?" + query : "") + window.location.hash);
  }

  // HA uses this JSON-encoded localStorage key for its own browser language choice. The same shape
  // gives Configure a durable browser override without inventing another client-side authority.
  function browserLanguageChoice() {
    var query = new URLSearchParams(window.location.search).get("lang");
    if (query && query.toLowerCase() === "auto") {
      storeBrowserLanguage("");
      stripLanguageQuery();
      return "";
    }
    if (validLanguageTag(query)) {
      storeBrowserLanguage(query);
      return query;
    }
    try {
      var stored = JSON.parse(window.localStorage.getItem("selectedLanguage") || "null");
      return validLanguageTag(stored) ? stored : "";
    } catch (_) { return ""; }
  }

  function configSchemaUrl(haLanguage) {
    var params = new URLSearchParams();
    var explicit = browserLanguageChoice();
    if (explicit) params.set("lang", explicit);
    if (validLanguageTag(haLanguage)) params.set("ha_lang", haLanguage);
    var query = params.toString();
    return "/api/v1/config/schema" + (query ? "?" + query : "");
  }

  function readLocalizedSchema(response) {
    return response.json();
  }

  function reloadSchemaForHaLanguage() {
    if (values.ui_language !== "auto" || browserLanguageChoice() ||
        haUserStatus.phase !== "connected" || !validLanguageTag(haUserStatus.language)) return;
    var request = ++schemaLanguageRequest;
    var generation = editGeneration;
    fetch(configSchemaUrl(haUserStatus.language), { headers: { "Accept": "application/json" }, cache: "no-store" })
      .then(function (response) {
        return response.json();
      })
      .then(function (nextSchema) {
        if (request !== schemaLanguageRequest || dirty || editGeneration !== generation ||
            !Array.isArray(nextSchema)) return;
        schema = nextSchema;
        render();
        configCardGeometryChanged();
      })
      .catch(function () {});
  }
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
    syncUnsavedNavigationGuard();
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
    syncBehaviourCardSignature();
  }
  function clearDirty() {
    dirty = false;
    dirtyValues = Object.create(null);
    dirtyExpose = Object.create(null);
    updateSaveUi();
  }

  // Individual controls own their value normalization, but the shared save affordance must be the
  // final writer after an input event. Capture the event while its target still belongs to the form,
  // then reconcile after target handlers have finished (including handlers that replace that target).
  var dirtyUiReconcileQueued = false;
  function queueDirtyUiReconcile(event) {
    var groups = document.getElementById("cfg-groups");
    if (!groups || !event.target || !groups.contains(event.target) || dirtyUiReconcileQueued) return;
    dirtyUiReconcileQueued = true;
    setTimeout(function () {
      dirtyUiReconcileQueued = false;
      recomputeDirty();
      updateSaveUi();
    }, 0);
  }

  function guardUnsavedNavigation(event) {
    event.preventDefault();
    event.returnValue = "";
  }

  var unsavedNavigationGuardArmed = false;
  function syncUnsavedNavigationGuard() {
    if (typeof window.addEventListener !== "function" || typeof window.removeEventListener !== "function") return;
    var shouldArm = dirty || saving;
    if (shouldArm === unsavedNavigationGuardArmed) return;
    unsavedNavigationGuardArmed = shouldArm;
    if (shouldArm) window.addEventListener("beforeunload", guardUnsavedNavigation);
    else window.removeEventListener("beforeunload", guardUnsavedNavigation);
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
          window.configCardSizeSourceReady("ha");if (window.configCardSizeGeometryChanged) window.configCardSizeGeometryChanged(); }
          if (succeeded) reloadSchemaForHaLanguage(); }
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
    // Dashboard-app picker: Auto and ha-paneld's built-in renderer remain first. The server adds only
    // installed supported Companion variants; arbitrary launchable apps never become renderer choices.
    if (f.picker === "renderer") {
      var cur = v == null ? "" : v;
      var sel = el("select", { class: "pkgsel" });
      sel.appendChild(el("option", { value: "", text: f.placeholder || "auto" }));
      var seen = { "": true };
      var KNOWN = [
        { pkg: "builtin", label: "Built-in renderer (ha-paneld)" }
      ].concat(rendererChoices);
      KNOWN.forEach(function (r) {
        if (!r || !r.pkg || seen[r.pkg]) return;
        seen[r.pkg] = true;
        var op = el("option", { value: r.pkg, text: r.label });
        if (r.pkg === cur) op.selected = true;
        sel.appendChild(op);
      });
      // A currently-set external renderer is preserved so it isn't silently lost.
      if (cur && !seen[cur]) {
        var o2 = el("option", { value: cur, text: cur + " · configured external renderer" });
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
    // Wake-word-pipeline picker: one native select per configured wake word (from voice_wake_words),
    // offering the Home Assistant Assist pipelines fetched from /api/v1/voice/pipelines. Degrades to the
    // raw JSON textarea while that endpoint hasn't answered yet, or answered 503 (no Home Assistant
    // connection, or the voice-coordinator lane not wired up yet) — Safari-first: plain fetch/select/
    // textarea, no picker library.
    if (f.picker === "voice_pipelines") {
      if (voicePipelinesCatalog === null) loadVoicePipelines();
      var pipelinesWrap = el("div", { class: "voice-pipelines-picker" });
      // The raw textarea is focused/mid-edit exactly when it is document.activeElement — checked
      // against THIS render's about-to-be-replaced node, before reconcileConfigCards swaps it out.
      // A catalogue fetch resolving while someone is typing must not rip the control out from under
      // them: keep rendering the raw-textarea view for this pass even though the catalogue is now
      // available, so nothing they typed (committed to `values` or not) or their cursor position is
      // lost. The next render — the following keystroke's `input` event, or a blur — re-evaluates and
      // switches to the picker once it is safe to.
      var activeRawTextarea = document.activeElement &&
        document.activeElement.classList && document.activeElement.classList.contains("voice-pipelines-raw") &&
        document.activeElement.getAttribute("data-field-key") === f.key ? document.activeElement : null;
      if (voicePipelinesCatalog === null || voicePipelinesCatalog === false || activeRawTextarea) {
        var pipelinesRaw = el("textarea", {
          class: "voice-pipelines-raw", rows: "2", "data-field-key": f.key, text: v == null ? "" : v,
        });
        // `input`, not `change`: `change` only fires on blur, so a catalogue response landing mid-
        // keystroke previously re-rendered with whatever was last blurred, discarding anything typed
        // since. Committing on every keystroke means `values[f.key]` is always current, so nothing
        // typed is ever lost even if the very next render switches this field to the select picker.
        pipelinesRaw.addEventListener("input", function () {
          values[f.key] = pipelinesRaw.value; setDirty(f.key);
        });
        pipelinesWrap.appendChild(pipelinesRaw);
        if (voicePipelinesCatalog === false) {
          pipelinesWrap.appendChild(el("small", { text: "Pipeline list unavailable — edit as JSON." }));
        }
        return pipelinesWrap;
      }
      var configuredWakeWords = [];
      try {
        var parsedWakeWords = JSON.parse(values.voice_wake_words || "[]");
        if (Array.isArray(parsedWakeWords)) {
          configuredWakeWords = parsedWakeWords.filter(function (w) { return typeof w === "string" && w; });
        }
      } catch (e) { configuredWakeWords = []; }
      if (!configuredWakeWords.length) {
        pipelinesWrap.appendChild(el("small", { text: "Configure a wake word above first." }));
        return pipelinesWrap;
      }
      var pipelineMapping = {};
      try {
        var parsedMapping = JSON.parse(v || "{}");
        if (parsedMapping && typeof parsedMapping === "object" && !Array.isArray(parsedMapping)) {
          pipelineMapping = parsedMapping;
        }
      } catch (e) { pipelineMapping = {}; }
      configuredWakeWords.forEach(function (word) {
        var pipelineRow = el("div", { class: "voice-pipeline-row" });
        pipelineRow.appendChild(el("span", { class: "voice-pipeline-label", text: word }));
        var pipelineSelect = el("select");
        pipelineSelect.appendChild(el("option", { value: "", text: "Preferred pipeline" }));
        var retainedPipelineId = pipelineMapping[word];
        var matchedRetained = false;
        voicePipelinesCatalog.forEach(function (p) {
          var pid = p && p.id ? String(p.id) : "";
          if (!pid) return;
          var pname = p && p.name ? String(p.name) : pid;
          var pipelineOption = el("option", { value: pid, text: pname });
          if (retainedPipelineId === pid) { pipelineOption.selected = true; matchedRetained = true; }
          pipelineSelect.appendChild(pipelineOption);
        });
        // A retained id Home Assistant no longer offers (the pipeline was removed/renamed there) must
        // never render as if nothing were selected — that reads as "Preferred pipeline" (unset) while
        // silently keeping the stale id. Represent it honestly, mirroring the renderer/package pickers'
        // "configured external renderer"/"(not installed)" retained-value pattern, and it stays
        // clearable through the existing empty "Preferred pipeline" option.
        if (retainedPipelineId && !matchedRetained) {
          var unknownOption = el("option", {
            value: String(retainedPipelineId), text: retainedPipelineId + " · not in Home Assistant's list",
          });
          unknownOption.selected = true;
          pipelineSelect.appendChild(unknownOption);
        }
        pipelineSelect.addEventListener("change", function () {
          if (pipelineSelect.value) pipelineMapping[word] = pipelineSelect.value;
          else delete pipelineMapping[word];
          values[f.key] = JSON.stringify(pipelineMapping);
          setDirty(f.key);
        });
        pipelineRow.appendChild(pipelineSelect);
        pipelinesWrap.appendChild(pipelineRow);
      });
      return pipelinesWrap;
    }
    // Home dashboard picker: Home Assistant provides this signed-in user's dashboards in its own order.
    // Deliberately a NATIVE select. A custom popup was tried (to carry the dashboards' icons like HA's
    // own picker) and was a bust on hardware review: it escaped the card, ran off the viewport and stole
    // wheel scrolling — the browser's own popup gets all of that right on every platform, and the
    // maintainer chose clean-over-icons. The wizard's dedicated page keeps the icon list; this form
    // keeps HA's GROUPING via native optgroups, which is the part that carries real information.
    // Auto intentionally remains first; a legacy/custom configured path is preserved rather than silently lost.
    // "Custom…" reveals a plain text input UNDER the select, which is how a specific view below a
    // dashboard root (/office/kitchen) is entered — Home Assistant's list endpoint only ever
    // returns roots. A revealed input, rather than an editable combobox, is what keeps the earlier
    // hardware verdict intact: nothing floats, nothing escapes the card, the native popup still owns
    // the list. A configured path that is not in the list now lands here instead of in a dead-end
    // option, so it can finally be edited on the panel.
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
      var customActive = !!currentDashboard && !dashboardPaths[currentDashboard];
      dashboardSelect.appendChild(el("option", { value: CUSTOM_DASHBOARD, text: "Custom — enter a dashboard path…" }));
      if (customActive) dashboardSelect.value = CUSTOM_DASHBOARD;
      var customInput = el("input", {
        type: "text", class: "hd-custom-input", value: currentDashboard,
        placeholder: "/dashboard-name/tab-name", id: "cfg-home_dashboard-path",
        maxlength: f.maxLength || 2048, "aria-label": "Dashboard path",
        "aria-describedby": "cfg-home_dashboard-path-note",
      });
      // The note explains what the value will DO (including the fallback warning), so it is wired to the
      // input for assistive technology and announced politely as it changes rather than only on focus.
      var customNote = el("small", { class: "hd-area-note hd-custom-note",
        id: "cfg-home_dashboard-path-note", role: "status", "aria-live": "polite" });
      var customWrap = el("div", { class: "hd-custom" }, [customInput, customNote]);
      // The renderer resolves an explicit path against the dashboards this account can see and falls
      // back to its default when the ROOT is not one of them — silently, by design. Once a path can be
      // typed that silence becomes the likeliest failure, so say it here instead. It stays a warning
      // and never blocks the save: the list may be unfetched, and the dashboard may not exist yet.
      function refreshCustomNote() {
        var typedPath = customInput.value.trim();
        var root = dashboardRootOf(typedPath);
        var unknown = root && homeDashboardQueried && homeDashboardItems.length && !dashboardPaths[root];
        customNote.textContent = unknown
          ? root + " is not a dashboard this panel’s Home Assistant account can see — the panel will fall"
            + " back to its default until that dashboard exists."
          : "A path on this Home Assistant, starting with a dashboard from the list above.";
        customNote.classList.toggle("warn", !!unknown);
      }
      // Validity is decided here rather than by the browser's pattern engine, and is cleared entirely
      // whenever Custom is not the live control. A hidden control that stays invalid blocks Save with
      // nothing on screen to fix — reportValidity() cannot show anything on an invisible field — so an
      // abandoned malformed path would make every later Auto or listed save fail for no visible reason.
      function validateCustom() {
        var typedPath = customInput.value.trim();
        customInput.setCustomValidity(
          !typedPath || wellFormedDashboardPath(typedPath) ? ""
            : "Enter a dashboard path such as /dashboard-name/tab-name.",
        );
      }
      function syncCustom() {
        var on = dashboardSelect.value === CUSTOM_DASHBOARD;
        customWrap.hidden = !on;
        // Required only while it is the live control, so an empty box cannot be saved as a silent Auto.
        customInput.required = on;
        // Disabled when it is not: barred from constraint validation, and skipped by the row scan.
        customInput.disabled = !on;
        if (on) { validateCustom(); refreshCustomNote(); }
      }
      dashboardSelect.addEventListener("change", function () {
        syncCustom();
        values[f.key] = dashboardSelect.value === CUSTOM_DASHBOARD ? customInput.value.trim() : dashboardSelect.value;
        setDirty(f.key);
        if (dashboardSelect.value === CUSTOM_DASHBOARD) customInput.focus();
      });
      customInput.addEventListener("input", function () {
        values[f.key] = customInput.value.trim();
        setDirty(f.key);
        validateCustom();
        refreshCustomNote();
      });
      syncCustom();
      var notes = [];
      if (!homeDashboardQueried) {
        notes.push(el("small", { class: "hd-area-note", text:
          "Couldn’t fetch this account’s dashboard list from Home Assistant yet. Try again after the connection recovers." }));
      } else if (!homeDashboardItems.length) {
        notes.push(el("small", { class: "hd-area-note", text:
          "This account cannot access any dashboards. Create one or grant access in Home Assistant." }));
      } else if (!homeDashboardDefault.explicit && !currentDashboard) {
        // The demotion rule, in native terms: Auto still exists but the field says why picking a real
        // dashboard is the recommendation when the account carries no server-side default.
        notes.push(el("small", { class: "hd-area-note", text:
          "This account has no default dashboard set — pick the dashboard this panel should show." }));
      }
      // The select is never disabled now, even with no listed dashboards: Custom is still a legal
      // answer then, and it is exactly the case where someone needs to type a path by hand.
      return el("div", { class: "hd-picker" }, [dashboardSelect, customWrap].concat(notes));
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
      // panel that had just converged correctly (reported on an upgraded panel, 2026-07-26). It may appear only
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
        // never present itself as having no area — reported on an upgraded panel whose local value was blank.
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
    if ((f.key === "auto_brightness_minimum_percent" || f.key === "auto_brightness_response_percent") && !ambientLightSourceReady()) {
      inp.disabled = true;
      inp.title = "Select an ambient light source first.";
    }
    inp.addEventListener("input", function () {
      values[f.key] = inp.value; setDirty(f.key);
      if (f.key === "auto_brightness_minimum_percent" || f.key === "auto_brightness_response_percent") queueAutoBrightnessHistory();
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

  // Age hierarchy for the days that are drawn individually.
  // Every dimension uses arithmetic and core canvas state rather than an optional filter effect, so
  // the intended softening remains present when an engine omits that effect.
  //
  // The blur is required — it is what makes a previous day read as background rather than as another
  // competing line. What it must NOT use is the `filter` property on the 2D context, which is how this
  // was first written: Chromium and the panel WebView applied it while Safari showed no softening, so
  // the same history rendered as two different charts. That difference is silent, which is the real
  // damage: the radius was tuned in a browser that was showing no effect and reached 52px at day-age 6
  // without anyone ever seeing the result. A capability probe is not a fix either — skipping the call
  // leaves the day unsoftened rather than giving it a fallback. So the radius below is real and the
  // blur is genuinely drawn; see the feathered stroke in line() for how it is realised portably.
  // How many days are drawn as lines. The rest of the week survives as the painted min/max region.
  //
  // Seven days times three traces was twenty-one lines, and adjacent days could not be told apart at
  // any combination of opacity, blur and colour. That is not a tuning failure: seven ordered steps do
  // not fit between today's saturated colour and the dimmest step still visible on the card, so the
  // encoding was being asked for more levels than the space holds. Three days fit comfortably, and the
  // week's spread is better served by one region than by four more lines nobody could separate.
  function autoBrightnessLineDays() { return 3; }

  // Age hierarchy for those three days. Every dimension uses arithmetic and core canvas state, so the
  // encoding does not disappear when an engine omits an optional drawing effect.
  //
  // The blur is required — it is what makes a previous day read as background rather than as another
  // competing line. What it must NOT use is the `filter` property on the 2D context, which is how this
  // was first written: Chromium and the panel WebView applied it while Safari showed no softening, so
  // the same history rendered as two different charts. That difference is silent, which is the real
  // damage: the radius was tuned in a browser that was showing no effect and reached 52px at day-age 6
  // without anyone ever seeing the result. A capability probe is not a fix either — skipping the call
  // leaves the day unsoftened rather than giving it a fallback. So the radius below is real and the
  // blur is genuinely drawn; see the feathered stroke in line() for how it is realised portably.
  //
  // Opacity is deliberately NOT part of the ramp. Ink conservation already dims a blurred trace by
  // roughly the ratio of its spread to its width, so an extra alpha cut on top bleached the older days
  // until they read as grey. Age is carried by radius and width alone, with chroma compensating.
  function autoBrightnessDayStyle(age) {
    var boundedAge = Math.max(0, Math.min(autoBrightnessLineDays() - 1, age));
    return {
      blurPx: [0, 1.2, 3.2][boundedAge],
      widthPx: [1.8, 1.5, 1.3][boundedAge]
    };
  }

  // The blur's cross-section, as [fraction of the radius, share of the layer's opacity], widest first.
  // Held in its own function so the whole age encoding can be swapped in one place when tuning against
  // real renderers — the comparison harness overrides this and autoBrightnessDayStyle and nothing else.
  // Weighted toward the core. Ink is conserved, so a flat profile spends most of it on the widest,
  // faintest pass and the peak opacity collapses — at radius 3 the spread is over five times the line
  // width, which puts the core near 5% alpha. A saturated colour at 5% over a near-black card composites
  // to almost the background, so the trace goes grey and loses its hue entirely. Concentrating the ink
  // in the middle keeps a coloured core with a soft edge, which is what a blurred line should look like.
  function autoBrightnessFeather() {
    return [[1, .06], [.72, .12], [.4, .22], [0, .6]];
  }

  // Compensates a blurred trace's colour. A faint mark loses its hue toward whatever is behind it, and
  // ink conservation makes a heavily blurred day faint by construction — so the more a day is blurred,
  // the more chroma its source colour needs in order to still read as blue, amber or purple rather than
  // as grey. Pushes the colour away from its own luminance grey; clamping means this can only ever
  // saturate, never wash toward white, which is what made an earlier light-to-dark ramp unusable.
  // Pure arithmetic on purpose — the CSS colour-mixing functions and relative colour syntax have
  // uneven engine support, and canvas takes a plain colour string anyway.
  function autoBrightnessAgedColor(color, blurPx) {
    var amount = 1 + blurPx * .13;
    var channels = [parseInt(color.slice(1, 3), 16), parseInt(color.slice(3, 5), 16), parseInt(color.slice(5, 7), 16)];
    var grey = .299 * channels[0] + .587 * channels[1] + .114 * channels[2];
    return "rgb(" + channels.map(function (channel) {
      return Math.max(0, Math.min(255, Math.round(grey + (channel - grey) * amount)));
    }).join(",") + ")";
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
    // Draws one trace, optionally blurred. The blur is a feathered stroke: the same path is stroked
    // several times, from widthPx + 2 * blurPx down to widthPx, each pass at a fraction of the layer's
    // opacity. Those passes composite into a smooth-edged band with no hard core, which is what a
    // blurred line looks like — a stroke widened by 2r and falling off toward its edges IS the line
    // convolved with a radius-r kernel, to the accuracy this chart needs.
    //
    // It is done this way rather than with a canvas filter because stroking and globalAlpha are widely
    // implemented core 2D operations, so every target receives the same visual encoding even though
    // rasterisation details can differ. It is also cheap: a handful of extra strokes per trace, no pixel
    // readback, no offscreen buffer, nothing to allocate per frame.
    function line(day, field, color, widthPx, projectY, blurPx) {
      var featherPasses = autoBrightnessFeather();
      runs(day.points, field).forEach(function (run) {
        var values = smoothedRunValues(run, field, day.age);
        ctx.beginPath();
        run.forEach(function (point, index) {
          if (!index) ctx.moveTo(x(point), projectY(values[index])); else ctx.lineTo(x(point), projectY(values[index]));
        });
        ctx.strokeStyle = color;
        if (!blurPx) { ctx.lineWidth = widthPx; ctx.stroke(); return; }
        var layerAlpha = ctx.globalAlpha;
        // Conserve ink. A blur SPREADS a line's brightness over a wider area; it must never add more.
        // Without this the passes simply stack, so a blurred trace lights more pixels than the sharp one
        // and reads as bolder and glowing rather than smudged — the opposite of receding into the
        // background. Scaling the opacities by width/spread-width keeps the total roughly equal to the
        // unblurred stroke, so more blur automatically means more transparency.
        // Approximate on purpose: alpha compositing is not additive, so overlapping passes retain a
        // little more than this predicts. Close enough that blur reads as softening, not highlighting.
        var spreadInk = 0;
        featherPasses.forEach(function (pass) { spreadInk += (widthPx + 2 * blurPx * pass[0]) * pass[1]; });
        var conserve = spreadInk > 0 ? widthPx / spreadInk : 1;
        featherPasses.forEach(function (pass) {
          ctx.globalAlpha = layerAlpha * pass[1] * conserve;
          ctx.lineWidth = widthPx + 2 * blurPx * pass[0];
          ctx.stroke();
        });
        ctx.globalAlpha = layerAlpha;
      });
    }
    // The whole week's spread, as one painted region between the lowest and highest reading seen at each
    // time of day. Painted FIRST so the day lines sit on top of it, and tinted with the lux hue rather
    // than a neutral: the region IS the lux range, and a grey fill reads as haze over the background
    // instead of as something deliberate. It carries every day, including the ones drawn as lines, so
    // the days beyond the third are represented here rather than dropped.
    var lowest = {}, highest = {};
    points.forEach(function (point) {
      var slot = point.minuteOfDay;
      var low = point.min == null ? point.mean : point.min;
      var high = point.max == null ? point.mean : point.max;
      if (lowest[slot] == null || low < lowest[slot]) lowest[slot] = low;
      if (highest[slot] == null || high > highest[slot]) highest[slot] = high;
    });
    // Split on missing data exactly as the day traces do. A single polygon over every sampled slot
    // would bridge periods the week has no observations for, painting a filled region across a gap and
    // asserting a range that was never measured — the same defect the run-splitting in runs() exists to
    // prevent for lines, and more misleading here because a fill looks like coverage.
    var slots = Object.keys(lowest).map(Number).sort(function (a, b) { return a - b; });
    var spans = [], span = [];
    slots.forEach(function (slot) {
      if (span.length && slot - span[span.length - 1] !== bucketMinutes) { spans.push(span); span = []; }
      span.push(slot);
    });
    if (span.length) spans.push(span);
    ctx.fillStyle = "rgba(74,158,255,.15)";
    spans.forEach(function (run) {
      if (run.length < 2) return;
      ctx.beginPath();
      run.forEach(function (slot, index) {
        var yy = y(highest[slot]);
        if (!index) ctx.moveTo(pad.left + slot / 1440 * plotW, yy); else ctx.lineTo(pad.left + slot / 1440 * plotW, yy);
      });
      run.slice().reverse().forEach(function (slot) {
        ctx.lineTo(pad.left + slot / 1440 * plotW, y(lowest[slot]));
      });
      ctx.closePath(); ctx.fill();
    });
    // Oldest of the three first, so today is stroked last and stays on top of its neighbours.
    days.filter(function (day) { return day.age < autoBrightnessLineDays(); }).forEach(function (day) {
      var style = autoBrightnessDayStyle(day.age);
      ctx.save();
      line(day, "mean", autoBrightnessAgedColor("#4a9eff", style.blurPx), style.widthPx, y, style.blurPx);
      line(day, "expected", autoBrightnessAgedColor("#f1bd52", style.blurPx), style.widthPx, y, style.blurPx);
      line(day, "brightness", autoBrightnessAgedColor("#b77cff", style.blurPx), style.widthPx, yBrightness, style.blurPx);
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

  // Preview bounds come from /api/v1/config/schema, never from literals here. A copy in this file
  // disagrees with the server the moment either bound moves, and the failure is silent: an in-range
  // value outside the stale copy loses its query parameter, so the chart quietly projects the STORED
  // value while the operator believes they are previewing the one they just typed. That is exactly
  // what a hard-coded 95 did once the ceiling rose. A value outside the server's own bounds is still
  // omitted rather than sent — it would only earn a 400 — and the control is already invalid, so the
  // save gate reports it.
  function autoBrightnessPreviewSuffix(param, key) {
    var field = null;
    for (var i = 0; i < schema.length; i++) if (schema[i].key === key) { field = schema[i]; break; }
    var value = parseInt(values[key], 10);
    if (!isFinite(value) || !field || field.min == null || field.max == null) return "";
    return value < field.min || value > field.max ? "" : param + encodeURIComponent(value);
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
    var sensitivitySuffix = autoBrightnessPreviewSuffix("&sensitivity=", "auto_brightness_response_percent");
    var minimumSuffix = autoBrightnessPreviewSuffix("&minimum_percent=", "auto_brightness_minimum_percent");
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
    var lineDayCount = Math.min(dayCount, autoBrightnessLineDays());
    var detail = lineDayCount + (lineDayCount === 1 ? " day" : " days") + " drawn individually";
    if (dayCount > autoBrightnessLineDays()) detail += " · earlier history shown as weekly range";
    if (bucket) detail += " · " + bucket + " minute buckets";
    var projectionSensitivity = autoBrightnessProjectionSensitivity();
    if (projectionSensitivity != null) detail += " · Sensitivity " + projectionSensitivity + "%";
    detail += " · " + autoBrightnessFreshness();
    var panel = el("div", { class: "autobright-panel", id: "auto-brightness-learning" }, [
      el("div", { class: "autobright-head" }, [
        el("div", {}, [el("strong", { text: "Daily ambient learning" }), el("small", { text: autoBrightnessSummary() })]),
        el("div", { class: "autobright-actions" }, [reset, resume])
      ]),
      el("canvas", { id: "auto-brightness-chart", class: "autobright-chart", role: "img", "aria-label": "24-hour ambient-light pattern: the three most recent days drawn individually, with the rest of the week shown as a shaded minimum-to-maximum range" }),
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

  function autoSleepSummaryModel(status) {
    status = status || {};
    var areaName = status.area_name != null ? status.area_name : status.areaName;
    var area = String(areaName || "").trim() || "not learned";
    var leaseMs = status.learned_lease_ms != null ? status.learned_lease_ms : status.learnedLeaseMs;
    var lease = typeof leaseMs === "number" && isFinite(leaseMs) ? Math.round(leaseMs / 60000) + " min" : "not learned";
    var count = status.source_count != null ? status.source_count : status.sourceCount;
    var suppressed = status.manual_suppression === true || status.manualSuppression === true;
    var phase = autoSleepHuman(status.phase);
    var reason = autoSleepHuman(status.reason);
    var sources = count == null ? 0 : count;
    var override = suppressed ? "active" : "inactive";
    return {
      lines: [
        "Home Assistant Area: " + area,
        "Phase: " + phase,
        "Reason: " + reason,
        "Delay: " + lease + " · Sources: " + sources,
        "Manual override: " + override
      ],
      accessible: "Home Assistant Area: " + area + " · Phase: " + phase + " · Reason: " + reason +
        " · Learned delay: " + lease + " · Sources: " + sources + " · Manual screen override: " + override
    };
  }

  function autoSleepLoadingSummaryModel() {
    return { lines: ["Loading…", "", "", "", ""], accessible: "Loading…" };
  }

  function setAutoSleepSummary(summary, announcement, model) {
    var lines = summary.querySelectorAll(".auto-sleep-summary-line");
    for (var index = 0; index < lines.length; index += 1) {
      var nextLine = model.lines[index] || "";
      if (lines[index].textContent !== nextLine) lines[index].textContent = nextLine;
    }
    if (summary.getAttribute("title") !== model.accessible) summary.setAttribute("title", model.accessible);
    if (announcement.textContent !== model.accessible) announcement.textContent = model.accessible;
  }

  function autoSleepSummaryNode() {
    return el("small", { id: "auto-sleep-summary", class: "auto-sleep-summary", "aria-hidden": "true" }, [
      el("span", { class: "auto-sleep-summary-line" }),
      el("span", { class: "auto-sleep-summary-line" }),
      el("span", { class: "auto-sleep-summary-line" }),
      el("span", { class: "auto-sleep-summary-line" }),
      el("span", { class: "auto-sleep-summary-line" })
    ]);
  }

  function autoSleepSummaryAnnouncementNode() {
    return el("span", {
      id: "auto-sleep-summary-announcement", class: "sr-only", role: "status", "aria-live": "polite", "aria-atomic": "true"
    });
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
      var next = autoSleepPrerequisiteText();
      if (status.textContent !== next) status.textContent = next;
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
    // Focus/visibility refreshes are background validation. Keep a settled Area verdict visible
    // while they run; changing it to Checking… and straight back produces a conspicuous colour/text
    // flash without giving the user any useful new state.
    if (String(autoSleepPrerequisite.phase || "checking").toLowerCase() === "checking") {
      autoSleepPrerequisite = { eligible: false, phase: "checking", area_name: "" };
      updateAutoSleepPrerequisiteUi();
    }
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
        if (autoSleepHistoryBusy() || hours === autoSleepHistoryHours) return;
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
    var summary = autoSleepSummaryNode();
    var announcement = autoSleepSummaryAnnouncementNode();
    setAutoSleepSummary(summary, announcement, autoSleepStatus ? autoSleepSummaryModel(autoSleepStatus) : autoSleepLoadingSummaryModel());
    var panel = el("div", { class: "autobright-panel", id: "auto-sleep-status" }, [
      el("div", { class: "autobright-head auto-sleep-head" }, [
      el("div", {}, [
          el("strong", { text: "Auto-sleep activity" })
        ]),
        el("div", { class: "autobright-actions" }, [windows])
      ]),
      summary,
      announcement,
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
    var announcement = document.getElementById("auto-sleep-summary-announcement");
    if (!summary || !announcement) return;
    // Five fixed rows keep the chart origin stable while status values change. Loading occupies the
    // same geometry before the first response; later readiness refreshes retain the settled values.
    setAutoSleepSummary(summary, announcement, autoSleepStatus ? autoSleepSummaryModel(autoSleepStatus) : autoSleepLoadingSummaryModel());
  }

  function autoSleepHistoryMessage() {
    if (autoSleepHistoryError) return autoSleepHistoryError;
    return autoSleepHistoryBusy() && autoSleepHistory && autoSleepHistory.available !== false
      ? "Refreshing activity history…" : "";
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

  function setAutoSleepOverlayHidden(overlay, hidden) {
    if (overlay && overlay.hidden !== hidden) overlay.hidden = hidden;
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
      var retainedSettled = content.getAttribute("data-settled") === "true";
      Array.prototype.forEach.call(chart.querySelectorAll(".auto-sleep-lane.source[role=button]"), function (row) {
        row.setAttribute("aria-disabled", retainedBusy ? "true" : "false");
      });
      chart.classList.toggle("busy", retainedBusy);
      chart.setAttribute("aria-busy", retainedBusy ? "true" : "false");
      setAutoSleepOverlayHidden(overlay, !retainedBusy || retainedSettled);
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
    if (history && history.available !== false) content.setAttribute("data-settled", "true");
    else content.removeAttribute("data-settled");
    var busy = autoSleepHistoryBusy();
    Array.prototype.forEach.call(chart.querySelectorAll(".auto-sleep-lane.source[role=button]"), function (row) {
      row.setAttribute("aria-disabled", busy ? "true" : "false");
    });
    chart.classList.toggle("busy", busy);
    chart.setAttribute("aria-busy", busy ? "true" : "false");
    setAutoSleepOverlayHidden(overlay, !busy || content.getAttribute("data-settled") === "true");
    var description = autoSleepHistorySummary(policySegments, bounds) + " " + sourceLanes.length + " source lanes are shown.";
    var accessible = document.getElementById("auto-sleep-chart-description");
    if (accessible) accessible.textContent = description;
  }

  function updateAutoSleepHistory(replaceSnapshot) {
    var message = document.getElementById("auto-sleep-history-message");
    var windowButtons = document.querySelectorAll(".auto-sleep-windows .pbtn");
    var busy = autoSleepHistoryBusy();
    var chartContent = document.querySelector("#auto-sleep-chart .auto-sleep-chart-content");
    var settled = chartContent && chartContent.getAttribute("data-settled") === "true";
    if (message) {
      var nextMessage = autoSleepHistoryMessage();
      if (message.textContent !== nextMessage) message.textContent = nextMessage;
      message.classList.toggle("error", !!autoSleepHistoryError);
    }
    for (var index = 0; index < windowButtons.length; index += 1) {
      var selected = Number(windowButtons[index].getAttribute("data-hours")) === autoSleepHistoryHours;
      windowButtons[index].setAttribute("aria-pressed", selected ? "true" : "false");
      windowButtons[index].setAttribute("aria-disabled", busy ? "true" : "false");
      // Native disabled styling fades every button. Retain it only for a cold chart; a settled chart
      // remains visually unchanged while its request fencing is expressed through aria-disabled and
      // the click guard.
      windowButtons[index].disabled = busy && !settled;
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
    if (clearSnapshot === true) autoSleepStatus = null;
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

  // The RTSP port the camera transport listens on. Must equal CameraRtspServer.DEFAULT_PORT;
  // CameraSurfaceContractTest asserts the two agree so this cannot drift silently.
  var CAMERA_RTSP_PORT = 8554;

  /**
   * Turn named words inside a help string into links, leaving the wording itself in the settings
   * registry. Each pair is [word, href]; the first occurrence of each word becomes an anchor and
   * everything else stays plain text, so re-wording the help does not have to be mirrored here.
   */
  function linkifyWords(text, pairs) {
    var nodes = [document.createTextNode(text)];
    pairs.forEach(function (pair) {
      for (var i = 0; i < nodes.length; i++) {
        var node = nodes[i];
        if (node.nodeType !== 3) continue;
        var at = node.textContent.indexOf(pair[0]);
        if (at < 0) continue;
        var before = node.textContent.slice(0, at);
        var after = node.textContent.slice(at + pair[0].length);
        var link = el("a", { href: pair[1], text: pair[0] });
        if (pair[1].indexOf("rtsp:") !== 0) link.setAttribute("target", "_blank");
        link.setAttribute("title", pair[1]);
        nodes.splice(i, 1, document.createTextNode(before), link, document.createTextNode(after));
        break;
      }
    });
    return nodes;
  }

  function row(f) {
    var help = null;
    if (f.key === "dashboard_zoom") {
      var helpKids = [el("span", { lang: f.helpLanguage, text: f.help })];
      if (f.displaySizingAvailable === true) {
        helpKids.push(document.createTextNode(" Recommend use "));
        helpKids.push(el("a", { href: "/install#cfg-display", text: "Display Sizing" }));
        helpKids.push(document.createTextNode(" for better results"));
      }
      help = el("small", {}, helpKids);
    } else if (f.key === "camera_enabled") {
      // Name the two addresses the help text already talks about, so they can be opened or copied
      // rather than retyped. The RTSP link uses whatever host this page was reached on, which is the
      // address that will also work from Home Assistant.
      help = el("small", { lang: f.helpLanguage }, linkifyWords(f.help, [
        ["RTSP", "rtsp://" + location.hostname + ":" + CAMERA_RTSP_PORT + "/live"],
        ["JPEG", "/api/v1/camera/snapshot.jpg"],
      ]));
    } else if (f.key === "auto_sleep") {
      help = el("small", { lang: f.helpLanguage, text: f.help });
    } else if (f.help) {
      help = el("small", { lang: f.helpLanguage, text: f.help });
    }
    var protectedSetting = !!HARDENED_APPROVAL_SETTING_KEYS[f.key];
    var labelText = el("span", { lang: f.labelLanguage });
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
      valueControl.setAttribute("lang", f.labelLanguage);
      if (protectedSetting) {
        valueControl.setAttribute("aria-describedby", "hardened-approval-conditional-description");
        valueControl.setAttribute("title", "Changing this setting may require physical on-panel approval when Hardened mode is enabled.");
      }
    }
    var ctl = el("div", { class: "fctl" }, f.readOnly ? [pip(f)] : [pip(f), valueControl]);
    // Anchor id so dashboard "edit" icons can deep-link straight to this setting.
    var dependencyDisabled = (f.key === "auto_brightness" || f.key === "auto_brightness_minimum_percent" || f.key === "auto_brightness_response_percent") && !ambientLightSourceReady();
    return el("div", {
      class: "frow" + (f.available ? "" : " muted") + (dependencyDisabled ? " dependency-disabled" : ""),
      id: "cfg-" + f.key
    }, [label, ctl]);
  }

  function shouldRenderRow(f) {
    if ((f.key === "auto_brightness_minimum_percent" || f.key === "auto_brightness_response_percent") && values.auto_brightness !== "true") return false;
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
  // Camera is an experimental trial: it ships to earn permanent inclusion and can be withdrawn, so
  // its card says so in the heading rather than only in each setting's help text.
  var CARD_BADGES = { "Display": ["experimental", "exp"], "Camera": ["experimental", "exp"] };
  var CARD_NOTES = {
    "Sensors": "Home Assistant reporting",
    "Diagnostics": "Home Assistant reporting"
  };
  var BUILTIN_RENDERER_KEYS = {
    dashboard_entity_learning: true, dashboard_fullscreen: true, dashboard_native_kiosk: true,
    dashboard_idle_return_min: true, dashboard_zoom: true, dashboard_theme: true
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

  function fieldsForConfigGroup(group) {
    return schema.filter(function (field) {
      return presentationGroup(field) === group && field.available &&
        (!BUILTIN_RENDERER_ONLY_KEYS[field.key] || !values.dashboard_package || values.dashboard_package === "builtin") &&
        (advanced || field.tier === "BASIC");
    });
  }

  function behaviourCardSignature(fields) {
    return JSON.stringify([
      advanced,
      values.auto_sleep === "true",
      fields.map(function (field) {
        return [
          field.key,
          field.label,
          field.help,
          field.type,
          field.picker,
          field.readOnly,
          field.min,
          field.max,
          field.maxLength,
          field.options,
          shouldRenderRow(field),
          values[field.key],
          field.ha ? expose[field.key] !== false : null,
          Object.prototype.hasOwnProperty.call(applyPending, field.key)
        ];
      })
    ]);
  }

  function syncBehaviourCardSignature() {
    var card = document.querySelector('[data-config-group="Behaviour"]');
    if (card) card.setAttribute("data-render-signature", behaviourCardSignature(fieldsForConfigGroup("Behaviour")));
  }

  function reconcileConfigCards(root, cards) {
    Array.prototype.slice.call(root.children).forEach(function (child) {
      if (cards.indexOf(child) < 0) child.remove();
    });
    cards.forEach(function (card, index) {
      var current = root.children[index] || null;
      if (current !== card) root.insertBefore(card, current);
    });
  }

  function configViewportAnchor(node) {
    if (!node || !node.isConnected || typeof node.getBoundingClientRect !== "function") return null;
    if (window.matchMedia && !window.matchMedia("(max-width: 857px)").matches) return null;
    var rect = node.getBoundingClientRect();
    var viewportHeight = window.innerHeight || document.documentElement.clientHeight || 0;
    if (!isFinite(rect.top) || !isFinite(rect.bottom) || rect.height <= 0 || viewportHeight <= 0 ||
        rect.bottom <= 0 || rect.top >= viewportHeight) return null;
    return { node: node, top: rect.top };
  }

  function restoreConfigViewportAnchor(anchor) {
    if (!anchor || !anchor.node.isConnected || !window.scrollTo) return;
    // The browser may already have applied some native scroll anchoring while cards above this one
    // were reconciled. Compensate from the CURRENT scroll position so both mechanisms converge on
    // the same viewport coordinate instead of resetting scrollY to its stale pre-render value. A
    // scroll can itself change which one-column cards content-visibility considers onscreen, so
    // force and settle that layout synchronously before paint rather than correcting it later.
    for (var attempt = 0; attempt < 8; attempt += 1) {
      var top = anchor.node.getBoundingClientRect().top;
      var delta = top - anchor.top;
      if (!isFinite(delta) || Math.abs(delta) <= 0.5) return;
      var beforeY = window.pageYOffset || 0;
      window.scrollTo(window.pageXOffset || 0, beforeY + delta);
      if ((window.pageYOffset || 0) === beforeY) return;
    }
  }

  var configExactLayoutReleaseTimer = null;
  var configExactLayoutRoot = null;
  var configExactLayoutCompensationCancelled = false;
  function scheduleConfigExactLayoutRelease(root, anchor) {
    if (configExactLayoutReleaseTimer) clearTimeout(configExactLayoutReleaseTimer);
    configExactLayoutReleaseTimer = null;
    configExactLayoutRoot = anchor ? root : null;
    configExactLayoutCompensationCancelled = false;
    if (!anchor) return;
    configExactLayoutReleaseTimer = setTimeout(function () {
      configExactLayoutReleaseTimer = null;
      configExactLayoutRoot = null;
      if (!root.isConnected || !root.classList.contains("config-viewport-anchored")) return;
      root.classList.remove("config-viewport-anchored");
      if (!configExactLayoutCompensationCancelled) restoreConfigViewportAnchor(anchor);
      configExactLayoutCompensationCancelled = false;
    }, 1400);
  }

  function cancelConfigExactLayoutCompensation() {
    // Keep exact layout until the bounded release, but never let its final scroll correction compete
    // with navigation the user has started since the render.
    if (configExactLayoutReleaseTimer) configExactLayoutCompensationCancelled = true;
  }
  if (document.addEventListener) {
    ["pointerdown", "touchstart", "wheel", "keydown"].forEach(function (type) {
      document.addEventListener(type, cancelConfigExactLayoutCompensation, true);
    });
  }
  if (window.addEventListener) {
    window.addEventListener("pagehide", function () {
      if (configExactLayoutReleaseTimer) clearTimeout(configExactLayoutReleaseTimer);
      configExactLayoutReleaseTimer = null;
      configExactLayoutCompensationCancelled = true;
      var root = configExactLayoutRoot || document.getElementById("cfg-groups");
      configExactLayoutRoot = null;
      if (root) root.classList.remove("config-viewport-anchored");
    });
  }

  function render() {
    var root = document.getElementById("cfg-groups");
    var proximityCard = document.querySelector("#cfg-proximity-learning");
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
    var behaviourFields = fieldsForConfigGroup("Behaviour");
    var nextBehaviourSignature = behaviourCardSignature(behaviourFields);
    var retainedAutoSleepPanel = document.getElementById("auto-sleep-status");
    if (!retainedAutoSleepPanel || !retainedAutoSleepPanel.parentNode || typeof retainedAutoSleepPanel.contains !== "function") retainedAutoSleepPanel = null;
    var existingBehaviourCard = retainedAutoSleepPanel && retainedAutoSleepPanel.closest('[data-config-group="Behaviour"]');
    var retainedBehaviourCard = existingBehaviourCard && values.auto_sleep === "true" &&
      existingBehaviourCard.getAttribute("data-render-signature") === nextBehaviourSignature ? existingBehaviourCard : null;
    var retainedAutoSleepFocus = retainedAutoSleepPanel && retainedAutoSleepPanel.contains(document.activeElement) ? document.activeElement : null;
    var retainedAutoSleepScroll = retainedAutoSleepPanel && retainedAutoSleepPanel.querySelector(".auto-sleep-source-scroll");
    var retainedAutoSleepScrollTop = retainedAutoSleepScroll ? retainedAutoSleepScroll.scrollTop : 0;
    var retainedAutoSleepViewportAnchor = retainedBehaviourCard ? configViewportAnchor(retainedAutoSleepPanel) : null;
    // Fresh off-screen cards normally use content-visibility's intrinsic placeholder until a later
    // frame. If Behaviour is already being viewed, that delayed replacement would relocate it after
    // this render has finished. Lay out this transaction's cards exactly; the narrow-screen lazy
    // optimization remains active whenever the auto-sleep panel is outside the viewport.
    root.classList.toggle("config-viewport-anchored", !!retainedAutoSleepViewportAnchor);
    var autoSleepParking = null;
    if (retainedAutoSleepPanel && !retainedBehaviourCard) {
      // render() rebuilds unrelated Configure cards after asynchronous probes. Keep the activity
      // subtree connected while that happens so its chart, scroll and focus do not flash away.
      autoSleepParking = el("div", { hidden: "", "aria-hidden": "true" });
      root.parentNode.insertBefore(autoSleepParking, root.nextSibling);
      autoSleepParking.appendChild(retainedAutoSleepPanel);
    }
    if (haPickerCleanup) haPickerCleanup();
    haOauthButton = null; haOauthStatus = null; haOauthLinks = null;
    var shown = 0, desiredCards = [];
    groups.forEach(function (g) {
      var fields = fieldsForConfigGroup(g);
      if (!fields.length) return;
      shown += fields.length;
      if (g === "Behaviour" && retainedBehaviourCard) {
        desiredCards.push(retainedBehaviourCard);
        return;
      }
      // Maturity badges on whole cards; Logging is intentionally no longer experimental.
      var h2kids = [el("span", { text: g })];
      if (CARD_NOTES[g]) h2kids.push(el("small", { text: " · " + CARD_NOTES[g] }));
      var badge = CARD_BADGES[g];
      if (badge) h2kids.push(el("span", { class: "cardbadge " + badge[1], text: badge[0] }));
      var card = el("div", { class: "card" }, [el("h2", {}, h2kids)]);
      card.setAttribute("data-config-group", g);
      card.setAttribute("data-layout-key", configLayoutKey(g));
      if (g === "Behaviour") card.setAttribute("data-render-signature", nextBehaviourSignature);
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
      desiredCards.push(card);
    });
    if (proximityCard) {
      var loggingCardIndex = desiredCards.findIndex(function (card) {
        return card.getAttribute("data-config-group") === "Logging";
      });
      desiredCards.splice(loggingCardIndex < 0 ? desiredCards.length : loggingCardIndex, 0, proximityCard);
    }
    // Reconcile by card instead of emptying the grid. In particular, an unchanged Behaviour card
    // never leaves the rendered tree while Home-dashboard and adaptive-brightness requests finish,
    // so low-end WebViews do not discard and repaint the large Auto-sleep chart raster.
    reconcileConfigCards(root, desiredCards);
    if (retainedAutoSleepPanel && retainedAutoSleepPanel.isConnected && !retainedBehaviourCard) {
      updateAutoSleepSummary();
      updateAutoSleepHistory();
    }
    document.getElementById("cfg-status").style.display = shown ? "none" : "block";
    if (!shown) document.getElementById("cfg-status").textContent = "No settings in this view.";
    if (retainedAutoSleepFocus && retainedAutoSleepFocus.isConnected && document.activeElement !== retainedAutoSleepFocus) {
      try { retainedAutoSleepFocus.focus({ preventScroll: true }); }
      catch (_) { retainedAutoSleepFocus.focus(); }
    }
    if (retainedAutoSleepScroll && retainedAutoSleepScroll.isConnected) retainedAutoSleepScroll.scrollTop = retainedAutoSleepScrollTop;
    if (autoSleepParking) autoSleepParking.remove();
    if (window.CardSizeMemory) {
      if (retainedAutoSleepViewportAnchor) window.CardSizeMemory.invalidate("cfg-groups");
      else window.CardSizeMemory.restore("cfg-groups");
    }
    restoreConfigViewportAnchor(retainedAutoSleepViewportAnchor);
    // Keep exact layout through the card-memory settle window. The bounded release restores normal
    // lazy rendering after the latest asynchronous result; direct user input cancels compensation so
    // a late timer can never pull the viewport away from a scroll or key navigation in progress.
    scheduleConfigExactLayoutRelease(root, retainedAutoSleepViewportAnchor);
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
      // The first INVALID control in the row, not the row's first control. A row may carry more than
      // one — the dashboard picker is a select plus a revealed custom-path input — and the leading
      // control is typically the valid one while the control being edited is not. Taking the first
      // match would report the row as valid and let the bad value reach the server.
      var controls = row && row.querySelectorAll ? row.querySelectorAll("input,select,textarea") : [];
      var control = null;
      for (var c = 0; c < controls.length; c++) {
        if (controls[c].disabled || !controls[c].checkValidity || controls[c].checkValidity()) continue;
        control = controls[c];
        break;
      }
      if (!control) continue;
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
              Object.prototype.hasOwnProperty.call(submittedValues, "auto_brightness_response_percent") ||
              autoBrightnessSourceChanged;
          if (autoBrightnessSourceChanged || (ok && autoBrightnessSettingChanged)) {
            loadAutoBrightnessData(true);
          }
          // Re-enabling keeps the last completed replay visible, but it must still validate status
          // and history in the background. invalidateAutoSleepData() deliberately retains that
          // settled snapshot, so render() cannot infer the refresh from a missing status object.
          if (ok && autoSleepInputsChanged && values.auto_sleep === "true") {
            setTimeout(loadAutoSleepData, 0);
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
    ++schemaLanguageRequest;
    var schemaUrl = configSchemaUrl(haUserStatus.phase === "connected" ? haUserStatus.language : "");
    Promise.all([
      fetch(schemaUrl).then(readLocalizedSchema),
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
      rendererChoices = (res[2] && Array.isArray(res[2].renderers)) ? res[2].renderers : [];
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

  // Fetched once per page load; a 503 (no Home Assistant connection, or the coordinator lane not wired
  // up yet) is remembered as false rather than retried on every render, and the picker degrades to the
  // raw JSON textarea.
  function loadVoicePipelines() {
    if (voicePipelinesCatalog !== null) return;
    var request = ++voicePipelinesRequest;
    fetch("/api/v1/voice/pipelines", { cache: "no-store" }).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    }).then(function (body) {
      if (request !== voicePipelinesRequest) return;
      voicePipelinesCatalog = (body && Array.isArray(body.pipelines)) ? body.pipelines : [];
      render();
    }).catch(function () {
      if (request !== voicePipelinesRequest) return;
      voicePipelinesCatalog = false;
      render();
    });
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
          syncBehaviourCardSignature();
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
    document.addEventListener("input", queueDirtyUiReconcile, true);
    document.addEventListener("change", queueDirtyUiReconcile, true);
    document.addEventListener("click", queueDirtyUiReconcile, true);
    window.addEventListener("focus", scheduleAutoSleepPrerequisite);
    document.addEventListener("visibilitychange", function () {
      if (document.visibilityState === "visible") scheduleAutoSleepPrerequisite();
    });
  }

  load(null, true);
  loadRadio();
})();
