(function () {
  "use strict";

  var API = "/api/v1/profiles";
  var model = {
    catalogRevision: 0,
    profiles: [],
    status: {},
    selected: null,
    source: "",
    originalSource: "",
    preview: null,
    editable: false,
    loading: false,
    editor: null,
    sourceLoaded: false,
    maxBytes: 128 * 1024,
    viewGeneration: 0,
  };
  var suppressEditorChange = false;
  var PROFILE_EDITOR_MIN_LINES = 12;
  var profileLayoutFrame = 0;

  function byId(id) { return document.getElementById(id); }
  function string(value) { return value == null ? "" : String(value); }
  function fitProfileWorkspace() {
    profileLayoutFrame = 0;
    var workspace = document.querySelector(".profile-workspace");
    var editor = byId("profile-editor");
    var editorHead = document.querySelector(".profile-editor-head");
    var inspector = document.querySelector(".profile-inspector");
    var inspectorHead = document.querySelector(".profile-inspector-head");
    var inspectorBody = document.querySelector(".profile-inspector-body");
    if (!workspace || !editor || !editorHead || !inspector || !inspectorHead || !inspectorBody) return;
    if (window.matchMedia && window.matchMedia("(max-width:857px)").matches) {
      workspace.style.removeProperty("height");
      return;
    }
    var content = editor.querySelector(".cm-content") || byId("profile-source-fallback") || editor;
    var style = window.getComputedStyle(content);
    var fontSize = parseFloat(style.fontSize) || 13;
    var lineHeight = parseFloat(style.lineHeight);
    if (!isFinite(lineHeight)) lineHeight = fontSize * 1.45;
    var minEditorHeight = Math.ceil(PROFILE_EDITOR_MIN_LINES * lineHeight);
    var rowGap = parseFloat(window.getComputedStyle(workspace).rowGap) || 0;
    var minInspectorHeight = Math.ceil(inspectorHead.getBoundingClientRect().height) +
      Math.ceil(inspectorBody.scrollHeight) + 2;
    var minWorkspaceHeight = Math.ceil(editorHead.getBoundingClientRect().height) + minEditorHeight +
      minInspectorHeight + rowGap + 2;
    var documentTop = workspace.getBoundingClientRect().top + window.scrollY;
    var wrap = workspace.closest(".wrap");
    var bottomInset = wrap ? parseFloat(window.getComputedStyle(wrap).paddingBottom) || 0 : 0;
    var availableHeight = Math.floor(window.innerHeight - documentTop - Math.max(12, bottomInset));
    workspace.style.height = Math.max(minWorkspaceHeight, availableHeight) + "px";
  }
  function scheduleProfileLayout() {
    if (profileLayoutFrame) window.cancelAnimationFrame(profileLayoutFrame);
    profileLayoutFrame = window.requestAnimationFrame(fitProfileWorkspace);
  }
  function bindProfileLayout() {
    window.addEventListener("resize", scheduleProfileLayout);
    if (window.ResizeObserver) {
      var observer = new ResizeObserver(scheduleProfileLayout);
      [".profile-toolbar", ".profile-badges", ".profile-links", ".profile-status", ".profile-inspector-body"].forEach(function (selector) {
        var node = document.querySelector(selector); if (node) observer.observe(node);
      });
    }
    scheduleProfileLayout();
  }
  function refKey(ref) { return ref ? string(ref.id) + "@" + string(ref.revision) : ""; }
  function selectedSummary() {
    var key = refKey(model.selected);
    return model.profiles.find(function (item) { return refKey(item.ref) === key; }) || null;
  }
  function reviewedSummary() {
    if (model.preview && model.preview.source === model.source && model.preview.summary) return model.preview.summary;
    if (model.editable && isDirty()) return null;
    return selectedSummary();
  }
  function summaryForRef(ref) {
    var key = refKey(ref);
    return model.profiles.find(function (item) { return refKey(item.ref) === key; }) || null;
  }
  function setStatus(message, kind) {
    var out = byId("profile-status");
    if (!out) return;
    out.textContent = message || "";
    out.className = "profile-status" + (kind ? " " + kind : "");
  }
  function httpError(response, body) {
    var error = new Error(string(body && (body.message || body.error)) || "HTTP " + response.status);
    error.status = response.status;
    error.body = body;
    return error;
  }
  function approvalMessage(body) {
    return string(body && body.message) || "Approve this request on the panel, then retry it.";
  }
  function jsonFetch(url, options) {
    return fetch(url, options).then(function (response) {
      return response.text().then(function (text) {
        var body = {};
        try { body = text ? JSON.parse(text) : {}; } catch (_) { body = { message: text }; }
        // Profile selection normally succeeds with HTTP 202 too. The structured error field is what
        // distinguishes a staged restart from a Hardened-mode physical approval challenge.
        if (response.status === 202 && body && body.error === "approval-required") throw httpError(response, body);
        if (!response.ok) throw httpError(response, body);
        return body;
      });
    });
  }
  function yamlFetch(url) {
    return fetch(url, { headers: { "Accept": "application/yaml, text/yaml, text/plain" } }).then(function (response) {
      if (!response.ok) return response.text().then(function (text) { throw httpError(response, { message: text }); });
      return response.text();
    });
  }
  function postYaml(path, yaml, token) {
    var headers = { "Accept": "application/json", "Content-Type": "application/yaml; charset=utf-8" };
    if (token) headers["X-Profile-Preview-Token"] = token;
    return jsonFetch(API + path, { method: "POST", headers: headers, body: yaml });
  }
  function postJson(path, body) {
    return jsonFetch(API + path, {
      method: "POST",
      headers: { "Accept": "application/json", "Content-Type": "application/json" },
      body: JSON.stringify(body || {}),
    });
  }

  function initEditor() {
    var host = byId("profile-editor");
    if (!host) return;
    if (window.ProfileCodeEditor && typeof window.ProfileCodeEditor.create === "function") {
      try {
        model.editor = window.ProfileCodeEditor.create(host, {
          value: "",
          readOnly: true,
          onChange: editorChanged,
        });
        return;
      } catch (_) {}
    }
    var fallback = document.createElement("textarea");
    fallback.id = "profile-source-fallback";
    fallback.setAttribute("aria-label", "Profile YAML");
    fallback.spellcheck = false;
    fallback.readOnly = true;
    fallback.addEventListener("input", function () { editorChanged(fallback.value); });
    host.appendChild(fallback);
    model.editor = {
      getValue: function () { return fallback.value; },
      setValue: function (value) { fallback.value = string(value); },
      setReadOnly: function (value) { fallback.readOnly = !!value; },
      setSchema: function () {},
      setDiagnostics: function () {},
      focus: function () { fallback.focus(); },
    };
  }
  function editorChanged(value) {
    model.source = string(value);
    if (suppressEditorChange) return;
    model.viewGeneration++;
    model.loading = false;
    model.preview = null;
    renderIssues([{ severity: "info", message: "YAML changed; validate it to refresh issues and comparison." }]);
    renderDiff(null);
    setStatus("YAML changed. Validate before saving or activating.");
    updateActions();
  }
  function setEditor(value, editable) {
    model.source = string(value);
    model.originalSource = string(value);
    model.editable = !!editable;
    model.preview = null;
    model.sourceLoaded = true;
    model.viewGeneration++;
    suppressEditorChange = true;
    model.editor.setValue(model.source);
    model.editor.setReadOnly(!model.editable);
    model.editor.setDiagnostics([]);
    suppressEditorChange = false;
    var useDraft = byId("profile-use-draft"); if (useDraft) useDraft.hidden = true;
    updateActions();
  }
  function isDirty() { return model.editable && model.source !== model.originalSource; }
  function confirmDiscard() { return !isDirty() || confirm("Discard this unsaved profile edit?"); }

  function renderCatalog() {
    var select = byId("profile-select");
    if (!select) return;
    var wanted = refKey(model.selected);
    select.textContent = "";
    model.profiles.forEach(function (profile) {
      var option = document.createElement("option");
      option.value = refKey(profile.ref);
      var origin = profile.origin === "bundled" ? "bundled" : "local";
      var suffix = profile.compatible === false ? " · incompatible" : profile.active ? " · active" : profile.selected ? " · selected" : "";
      option.textContent = string(profile.display_name || profile.ref.id) + " · " + origin + " · " + string(profile.ref.revision).slice(0, 10) + suffix;
      select.appendChild(option);
    });
    if (wanted && model.profiles.some(function (item) { return refKey(item.ref) === wanted; })) select.value = wanted;
    else {
      var active = model.profiles.find(function (item) { return item.active; }) || model.profiles[0];
      model.selected = active ? active.ref : null;
      if (active) select.value = refKey(active.ref);
    }
    renderBadges();
    updateActions();
  }
  function badge(label, kind) {
    var item = document.createElement("span");
    item.className = "profile-badge" + (kind ? " " + kind : "");
    item.textContent = label;
    return item;
  }
  function profileLinkLabelSafe(label) {
    return !/[\u00ad\u061c\u200b-\u200f\u202a-\u202e\u2060-\u206f\ufeff]/i.test(label);
  }
  function renderProfileLinks(summary) {
    var root = byId("profile-links");
    if (!root) return;
    root.textContent = "";
    root.hidden = true;
    if (!summary || summary.compatible === false) return;
    (summary.links || []).forEach(function (candidate) {
      var raw = string(candidate && candidate.url);
      var label = string(candidate && candidate.label || "Reference");
      var parsed;
      if (!raw || raw.length > 500 || !profileLinkLabelSafe(label)) return;
      try { parsed = new URL(raw); } catch (_) { return; }
      if (parsed.protocol !== "https:" || !parsed.hostname || parsed.username || parsed.password) return;
      var link = document.createElement("a");
      link.className = "profile-link";
      link.href = parsed.href;
      link.target = "_blank";
      link.rel = "noopener noreferrer";
      link.referrerPolicy = "no-referrer";
      var labelNode = document.createElement("bdi");
      labelNode.className = "profile-link-label";
      labelNode.dir = "auto";
      labelNode.textContent = label;
      var hostNode = document.createElement("bdi");
      hostNode.className = "profile-link-host";
      hostNode.dir = "ltr";
      hostNode.textContent = parsed.hostname;
      link.appendChild(labelNode);
      link.appendChild(document.createTextNode(" · "));
      link.appendChild(hostNode);
      root.appendChild(link);
    });
    root.hidden = !root.childNodes.length;
  }
  function renderBadges() {
    var root = byId("profile-badges");
    if (!root) return;
    root.textContent = "";
    var summary = reviewedSummary();
    var guidance = byId("profile-shizuku-guidance");
    if (guidance) guidance.hidden = true;
    renderProfileLinks(summary);
    if (!summary) return;
    root.appendChild(badge(summary.origin === "bundled" ? "Bundled" : "Local", summary.origin === "bundled" ? "bundled" : ""));
    if (summary.maturity) {
      var maturity = string(summary.maturity).toLowerCase();
      var trustedVerified = maturity === "verified" && summary.trusted_provenance === true;
      var maturityLabel = trustedVerified ? "✓ Verified" : (maturity === "verified" ? "Author: verified" : maturity);
      root.appendChild(badge(maturityLabel, trustedVerified ? "maturity verified" : "maturity"));
    }
    if (summary.compatible === false) root.appendChild(badge("Incompatible", "risk"));
    if (summary.matches_this_device === false) root.appendChild(badge("Does not match this device", "risk"));
    if (summary.active) root.appendChild(badge("Active", "active"));
    if (summary.selected && !summary.active) root.appendChild(badge("Selected", "pending"));
    if (summary.last_known_good) root.appendChild(badge("Last known good", "lkg"));
    var shizuku = string(summary.shizuku_recommendation).toLowerCase();
    if (shizuku === "recommended") {
      root.appendChild(badge("Shizuku: " + shizuku, "shizuku"));
    }
    // Keep exceptional access guidance out of ordinary profile summaries.
    if (guidance) guidance.hidden = shizuku !== "recommended";
    (summary.risks || []).forEach(function (risk) { root.appendChild(badge(string(risk).replace(/_/g, " "), "warning")); });
    var activation = model.status.activation || {};
    if (activation.state === "pending" || activation.state === "applying") root.appendChild(badge(activation.state, "pending"));
  }
  function renderIssues(issues) {
    var root = byId("profile-issues");
    if (!root) return;
    root.textContent = "";
    if (!issues || !issues.length) {
      var empty = document.createElement("div"); empty.className = "profile-empty"; empty.textContent = "No validation issues."; root.appendChild(empty);
      model.editor.setDiagnostics([]);
      return;
    }
    var diagnostics = [];
    issues.forEach(function (issue) {
      var severity = string(issue.severity || "error").toLowerCase();
      var row = document.createElement("div"); row.className = "profile-issue " + severity;
      var loc = document.createElement("span"); loc.className = "profile-issue-loc";
      var at = [];
      if (issue.path) at.push(string(issue.path));
      if (issue.line) at.push("line " + issue.line + (issue.column ? ":" + issue.column : ""));
      loc.textContent = at.join(" · ") || severity;
      var message = document.createElement("span"); message.textContent = string(issue.message || issue.code || "Invalid profile");
      row.appendChild(loc); row.appendChild(message); root.appendChild(row);
      if (issue.offset != null) diagnostics.push({
        from: Number(issue.offset) || 0,
        to: Number(issue.end_offset) || Number(issue.offset) || 0,
        severity: severity,
        message: message.textContent,
      });
    });
    model.editor.setDiagnostics(diagnostics);
  }
  function renderCatalogIssues(issues) {
    var root = byId("profile-catalog-issues");
    if (!root) return;
    root.textContent = "";
    if (!issues || !issues.length) {
      var empty = document.createElement("div"); empty.className = "profile-empty"; empty.textContent = "Catalog and active runtime are healthy."; root.appendChild(empty); return;
    }
    issues.forEach(function (issue) {
      var severity = string(issue.severity || "error").toLowerCase();
      var row = document.createElement("div"); row.className = "profile-issue " + severity;
      var loc = document.createElement("span"); loc.className = "profile-issue-loc"; loc.textContent = string(issue.path || severity);
      var message = document.createElement("span"); message.textContent = string(issue.message || "Profile catalog issue");
      row.appendChild(loc); row.appendChild(message); root.appendChild(row);
    });
  }
  function renderDiff(diff) {
    var root = byId("profile-diff");
    if (!root) return;
    root.textContent = "";
    if (diff == null) {
      var pending = document.createElement("div"); pending.className = "profile-empty"; pending.textContent = "Validate the current YAML to compare it with the active profile."; root.appendChild(pending); return;
    }
    if (!diff || !diff.length) {
      var empty = document.createElement("div"); empty.className = "profile-empty"; empty.textContent = "No semantic changes from the active profile."; root.appendChild(empty); return;
    }
    diff.forEach(function (change) {
      var row = document.createElement("div"); row.className = "profile-diff-row";
      var path = document.createElement("div"); path.className = "profile-diff-path"; path.textContent = string(change.path || change.field || "profile");
      var value = document.createElement("div"); value.className = "profile-diff-value";
      var before = change.before == null ? "(unset)" : string(change.before);
      var after = change.after == null ? "(unset)" : string(change.after);
      value.textContent = before + " → " + after;
      row.appendChild(path); row.appendChild(value); root.appendChild(row);
    });
  }
  function renderReport(report) {
    var root = byId("profile-report");
    if (!root) return;
    root.textContent = "";
    var entries = report && report.items || [];
    if (!entries.length) {
      var empty = document.createElement("div"); empty.className = "profile-empty"; empty.textContent = "No passive device report for this profile."; root.appendChild(empty); return;
    }
    function reportLabel(path) {
      var labels = {
        "match.any[].all[].field = model": "Match field: model",
        "match.any[].all[].field = device": "Match field: device",
        "match.any[].all[].field = product_version": "Match field: product version",
        "evidence.android_sdk": "Android SDK",
        "evidence.abis": "Supported ABIs",
        "evidence.board": "Board",
        "evidence.hardware": "Hardware",
        "evidence.display.width_px": "Display width",
        "evidence.display.height_px": "Display height",
        "evidence.display.current_density_dpi": "Current display density",
        "evidence.led.dev_ledjni_readable": "LED device access",
        "evidence.led.sysfs_rgb_readable": "LED sysfs access",
        "evidence.cpu.available_governors": "Available CPU governors",
        "sensors.light_technology": "Light sensor",
        "sensors.proximity_technology": "Proximity sensor",
        "evidence.sensors.temperature": "Temperature sensor",
        "evidence.sensors.humidity": "Humidity sensor",
      };
      return labels[path] || string(path).replace(/^evidence\./, "").replace(/[._]+/g, " ").replace(/\b\w/g, function (c) { return c.toUpperCase(); });
    }
    entries.forEach(function (fact) {
      var row = document.createElement("div"); row.className = "profile-diff-row";
      var key = document.createElement("div"); key.className = "profile-diff-path"; key.textContent = reportLabel(string(fact.key || fact.path || "fact"));
      var value = document.createElement("div"); value.className = "profile-diff-value";
      var status = string(fact.status || "unknown");
      value.textContent = fact.value == null ? status : (status === "observed" ? string(fact.value) : status + " · " + string(fact.value));
      row.appendChild(key); row.appendChild(value); root.appendChild(row);
    });
  }
  function updateActions() {
    var summary = selectedSummary();
    var review = reviewedSummary();
    var active = model.profiles.find(function (item) { return item.active; }) || null;
    var dirty = isDirty();
    var select = byId("profile-select"); if (select) select.disabled = model.loading;
    var save = byId("savebtn");
    if (save) save.disabled = !model.editable || !dirty || !(model.preview && model.preview.compatible && model.preview.source === model.source);
    var validate = byId("profile-validate"); if (validate) validate.disabled = !model.source || model.loading;
    var compare = byId("profile-compare"); if (compare) compare.disabled = !model.source || model.loading;
    var edit = byId("profile-edit"); if (edit) edit.disabled = !summary || !model.sourceLoaded || model.editable;
    var fork = byId("profile-fork"); if (fork) fork.disabled = !summary || !model.sourceLoaded || model.editable;
    var activate = byId("profile-activate"); if (activate) activate.disabled = !summary || !model.sourceLoaded || summary.compatible === false || summary.matches_this_device === false || summary.active || dirty || model.loading;
    var automatic = byId("profile-auto"); if (automatic) automatic.disabled = dirty || model.loading || model.status.selection && model.status.selection.mode === "auto";
    var remove = byId("profile-delete"); if (remove) remove.disabled = !summary || summary.origin === "bundled" || summary.active || summary.selected || summary.last_known_good || dirty || model.loading;
    var rollback = byId("profile-rollback"); if (rollback) rollback.disabled = !(model.status.rollback_ref || model.status.rollback_auto) || dirty || model.loading;
    var genericDraft = byId("profile-generic-draft"); if (genericDraft) genericDraft.hidden = !(active && active.ref && active.ref.id === "generic");
    var meta = byId("profile-editor-meta");
    if (meta) meta.textContent = review
      ? string(review.ref.id) + (review.content_version ? " · v" + string(review.content_version) : "") + (review.author ? " · " + string(review.author) : "") + " · sha256:" + string(review.ref.revision).slice(0, 16)
      : (model.editable ? "Unsaved profile" : "No profile selected");
    renderBadges();
  }

  function loadCatalog(preferredRef) {
    var generation = ++model.viewGeneration;
    setStatus("Loading profiles…");
    return jsonFetch(API).then(function (data) {
      if (generation !== model.viewGeneration) return;
      model.catalogRevision = Number(data.catalog_revision) || 0;
      model.profiles = data.profiles || [];
      model.status = data.status || data;
      renderCatalogIssues(model.status.issues || []);
      if (preferredRef) model.selected = preferredRef;
      renderCatalog();
      if (!model.selected) { setEditor("", false); setStatus("No profiles are available."); return; }
      return loadSelected();
    }).catch(function (error) {
      setStatus("Could not load profiles: " + error.message, "error");
      renderIssues([{ severity: "error", message: error.message }]);
    });
  }
  function loadSelected() {
    if (!model.selected) return Promise.resolve();
    var ref = model.selected;
    var generation = ++model.viewGeneration;
    setStatus("Loading " + ref.id + "…");
    return yamlFetch(API + "/" + encodeURIComponent(ref.id) + "/revisions/" + encodeURIComponent(ref.revision)).then(function (yaml) {
      if (generation !== model.viewGeneration || refKey(model.selected) !== refKey(ref)) return;
      setEditor(yaml, false);
      var summary = selectedSummary(); renderIssues(summary && summary.issues || []); renderDiff([]);
      setStatus("Viewing immutable revision " + string(ref.revision).slice(0, 12) + ".");
    }).catch(function (error) {
      if (generation !== model.viewGeneration || refKey(model.selected) !== refKey(ref)) return;
      setEditor("", false); model.sourceLoaded = false; renderIssues([{ severity: "error", message: error.message }]); renderDiff(null); updateActions();
      setStatus("Could not load profile: " + error.message, "error");
    });
  }

  function loadReport() {
    return jsonFetch(API + "/report").then(renderReport).catch(function () { renderReport({}); });
  }

  function preview(openCompare) {
    if (!model.source) return Promise.resolve(null);
    var source = model.source;
    var generation = model.viewGeneration;
    model.loading = true; updateActions(); setStatus("Validating YAML…");
    return postYaml("/probe", source).then(function (result) {
      if (generation !== model.viewGeneration || source !== model.source) return null;
      result.source = source;
      model.preview = result;
      renderIssues(result.issues || []);
      renderDiff(result.diff_from_active || []);
      renderReport(result.report || {});
      var matchesDevice = !result.summary || result.summary.matches_this_device !== false;
      setStatus(result.compatible
        ? (matchesDevice
          ? "Valid profile · sha256:" + string(result.content_sha256).slice(0, 12)
          : "Valid profile, but it is intended for different hardware. You can save it here, but you cannot activate it on this panel.")
        : "Profile is not compatible; fix the reported issues.",
      result.compatible && matchesDevice ? "ok" : "error");
      if (openCompare) openModal("Compare with active profile", diffText(result.diff_from_active || []), null);
      return result;
    }).catch(function (error) {
      if (generation !== model.viewGeneration || source !== model.source) return null;
      var issues = error.body && error.body.issues || [{ severity: "error", message: error.message }];
      model.preview = null; renderIssues(issues); setStatus("Validation failed: " + error.message, "error"); return null;
    }).finally(function () {
      if (generation === model.viewGeneration) { model.loading = false; updateActions(); }
    });
  }
  function diffText(diff) {
    if (!diff || !diff.length) return "No semantic changes from the active profile.";
    return diff.map(function (change) {
      return string(change.path || change.field || "profile") + "\n  " + string(change.before == null ? "(unset)" : change.before) + "\n  → " + string(change.after == null ? "(unset)" : change.after);
    }).join("\n\n");
  }
  function ensurePreview() {
    if (model.preview && model.preview.source === model.source && model.preview.compatible) return Promise.resolve(model.preview);
    return preview(false).then(function (result) { if (!result || !result.compatible) throw new Error("Profile must validate before it can be saved"); return result; });
  }
  function saveProfile() {
    var requestedSource = model.source;
    ensurePreview().then(function (result) {
      var source = model.source;
      model.loading = true; updateActions(); setStatus("Saving immutable revision…");
      return postYaml("/import", source, result.preview_token).then(function (mutation) {
        return { mutation: mutation, source: source };
      });
    }).then(function (saved) {
      if (!saved || saved.source !== model.source) return;
      var mutation = saved.mutation;
      setStatus(mutation.message || "Profile revision saved.", "ok");
      var ref = mutation.ref || mutation.imported_ref || null;
      return loadCatalog(ref);
    }).catch(function (error) {
      if (requestedSource !== model.source) return;
      // Import consumes its one-shot token even when persistence/quota checks reject the mutation.
      // Force a fresh validation so Retry can never loop on a dead token.
      model.preview = null;
      var issue = error.body && error.body.issues && error.body.issues[0];
      var reason = issue && issue.message || error.message;
      if (error.status === 409) setStatus("This preview or catalog revision is stale. Validate again before saving.", "error");
      else setStatus("Save failed: " + reason + " Resolve the issue, then Validate again.", "error");
    }).finally(function () { model.loading = false; updateActions(); });
  }

  function beginEdit(fork) {
    var summary = selectedSummary();
    if (!summary) return;
    model.editable = true;
    model.preview = null;
    model.editor.setReadOnly(false);
    setStatus((fork || summary.origin === "bundled") ? "Editing a self-contained local fork. The bundled revision will not change." : "Editing as a new immutable local revision.");
    model.editor.focus(); updateActions();
  }
  function loadTemplate() {
    var generation = ++model.viewGeneration;
    yamlFetch(API + "/template").then(function (yaml) {
      if (generation !== model.viewGeneration) return;
      model.selected = null; setEditor(yaml, true); model.originalSource = ""; renderIssues([]); renderDiff([]); setStatus("New unsaved profile. Validate it before saving."); model.editor.focus(); updateActions();
    }).catch(function (error) { if (generation === model.viewGeneration) setStatus("Could not create a template: " + error.message, "error"); });
  }
  function loadDeviceDraft() {
    var generation = ++model.viewGeneration;
    yamlFetch(API + "/device-draft").then(function (yaml) {
      if (generation !== model.viewGeneration) return;
      model.selected = null; setEditor(yaml, false); renderIssues([]); renderDiff([]);
      setStatus("Passive Generic draft · read-only · unknown hardware remains marked TODO.");
      var use = byId("profile-use-draft"); if (use) use.hidden = false;
    }).catch(function (error) { if (generation === model.viewGeneration) setStatus("Could not build the passive draft: " + error.message, "error"); });
  }
  function useDraft() {
    model.editable = true; model.editor.setReadOnly(false); model.originalSource = ""; model.preview = null;
    var use = byId("profile-use-draft"); if (use) use.hidden = true;
    setStatus("Draft copied into a new unsaved profile. Resolve every TODO before activation."); model.editor.focus(); updateActions();
  }
  function importFile(input) {
    var file = input.files && input.files[0]; if (!file) return;
    if (!confirmDiscard()) { input.value = ""; return; }
    if (file.size > model.maxBytes) {
      setStatus("Profile is too large. The limit is " + model.maxBytes + " bytes.", "error");
      input.value = "";
      return;
    }
    var generation = ++model.viewGeneration;
    file.text().then(function (yaml) {
      if (generation !== model.viewGeneration) return;
      model.selected = null; setEditor(yaml, true); model.originalSource = ""; renderIssues([]); renderDiff([]); setStatus("Imported locally for preview; nothing has been saved or activated."); model.editor.focus(); return preview(false);
    }).catch(function (error) { if (generation === model.viewGeneration) setStatus("Could not read profile: " + error.message, "error"); });
    input.value = "";
  }
  function exportSource() {
    if (!model.source) return;
    var summary = selectedSummary();
    var stem = summary ? string(summary.ref.id) : "panel-profile";
    var blob = new Blob([model.source], { type: "application/yaml;charset=utf-8" });
    var url = URL.createObjectURL(blob); var link = document.createElement("a");
    link.href = url; link.download = stem.replace(/[^a-zA-Z0-9._-]+/g, "-") + ".yaml";
    document.body.appendChild(link); link.click(); link.remove(); URL.revokeObjectURL(url);
  }

  function openModal(title, detail, onConfirm, confirmLabel, hardenedApproval) {
    var modal = byId("profile-modal");
    byId("profile-modal-title").textContent = title;
    byId("profile-modal-detail").textContent = detail + (hardenedApproval
      ? "\n\nShielded action: when Hardened mode is enabled, approve this request on the physical panel."
      : "");
    var confirm = byId("profile-modal-confirm");
    confirm.textContent = confirmLabel || "Confirm";
    confirm.hidden = !onConfirm;
    if (hardenedApproval) {
      confirm.setAttribute("data-hardened-approval", "");
      confirm.setAttribute("aria-describedby", "hardened-approval-description");
      confirm.setAttribute("title", "Requires physical on-panel approval for this action when Hardened mode is enabled.");
    } else {
      confirm.removeAttribute("data-hardened-approval");
      confirm.removeAttribute("aria-describedby");
      confirm.removeAttribute("title");
    }
    confirm.onclick = function () { closeModal(); if (onConfirm) onConfirm(); };
    modal.hidden = false; byId("profile-modal-cancel").focus();
  }
  function closeModal() { byId("profile-modal").hidden = true; }
  function activate(ref, action) {
    var summary = ref ? summaryForRef(ref) : null;
    // Imported revisions are allowed to be stored inertly for catalog transfer, but a known
    // wrong-device revision must never reach the Hardened approval endpoint from this page.
    if (summary && summary.matches_this_device === false) {
      setStatus("Activation blocked: this imported profile does not match this device's immutable build identity.", "error");
      return;
    }
    var risks = summary && summary.risks || [];
    var detail = [
      ref ? "Profile: " + string(summary && summary.display_name || ref.id) : "Profile: automatic device matching",
      ref ? "Revision: sha256:" + string(ref.revision) : "Revision: the best compatible profile selected at restart",
      risks.length ? "Privileged/risk flags: " + risks.join(", ") : "Privileged/risk flags: none declared",
      "",
      "The panel service will restart. If the revision does not become healthy, ha-paneld will return to the last-known-good profile.",
    ].join("\n");
    openModal(action === "rollback" ? "Roll back profile?" : "Activate profile?", detail, function () {
      model.loading = true; updateActions(); setStatus(action === "rollback" ? "Starting rollback…" : "Activating profile…");
      var path = action === "rollback" ? "/rollback" : "/select";
      var request = { expected_catalog_revision: model.catalogRevision, confirm: true };
      if (ref) { request.id = ref.id; request.revision = ref.revision; } else request.auto = true;
      postJson(path, request).then(function (result) {
        setStatus(result.message || "Selection saved; waiting for the panel service to restart.", "ok");
        if (result.restart_required) pollAfterRestart(ref, 0); else loadCatalog(ref);
      }).catch(function (error) {
        if (error.body && error.body.error === "approval-required") setStatus(approvalMessage(error.body));
        else {
          var issue = error.body && error.body.issues && error.body.issues[0];
          var reason = issue && (issue.message || issue.code) || error.message;
          setStatus(error.status === 409 ? "The profile catalog changed in another tab. Reload and confirm again." : "Activation failed: " + reason, "error");
        }
      }).finally(function () { model.loading = false; updateActions(); });
    }, action === "rollback" ? "Confirm rollback" : "Confirm and restart", true);
  }
  function pollAfterRestart(ref, attempt) {
    if (attempt > 60) { setStatus("The restart is taking longer than expected. Reload to check the active profile.", "error"); return; }
    setTimeout(function () {
      fetch("/health", { cache: "no-store" }).then(function (response) {
        if (!response.ok) throw new Error("not ready");
        return jsonFetch(API + "?t=" + Date.now());
      }).then(function (data) {
        model.catalogRevision = Number(data.catalog_revision) || model.catalogRevision;
        model.profiles = data.profiles || [];
        model.status = data.status || data;
        var active = model.profiles.find(function (item) { return item.active; });
        var activation = model.status.activation || {};
        var automaticHealthy = !ref && model.status.selection && model.status.selection.mode === "auto" && activation.state === "active";
        var pinnedHealthy = active && refKey(active.ref) === refKey(ref) && activation.state === "active";
        if (automaticHealthy || pinnedHealthy) { setStatus("Profile is active and healthy.", "ok"); loadCatalog(ref); return; }
        if (activation.state === "rolled_back" || activation.state === "auto_rolled_back") { setStatus("The candidate did not become healthy; ha-paneld automatically restored the last-known-good profile.", "error"); loadCatalog(active && active.ref); return; }
        pollAfterRestart(ref, attempt + 1);
      }).catch(function () { pollAfterRestart(ref, attempt + 1); });
    }, attempt < 5 ? 1000 : 2500);
  }
  function deleteSelected() {
    var summary = selectedSummary(); if (!summary) return;
    openModal("Delete inactive profile revision?", string(summary.display_name || summary.ref.id) + "\nsha256:" + string(summary.ref.revision) + "\n\nThis cannot be undone. Active, selected, bundled, and last-known-good revisions cannot be deleted.", function () {
      model.loading = true; updateActions();
      postJson("/delete", { id: summary.ref.id, revision: summary.ref.revision, expected_catalog_revision: model.catalogRevision, confirm: true }).then(function (result) {
        setStatus(result.message || "Profile deleted.", "ok"); return loadCatalog();
      }).catch(function (error) { setStatus(error.status === 409 ? "The catalog changed; reload before deleting." : "Delete failed: " + error.message, "error"); })
        .finally(function () { model.loading = false; updateActions(); });
    }, "Delete revision");
  }
  function bind() {
    byId("profile-select").addEventListener("change", function (event) {
      if (!confirmDiscard()) { event.target.value = refKey(model.selected); return; }
      var summary = model.profiles.find(function (item) { return refKey(item.ref) === event.target.value; });
      model.selected = summary && summary.ref; loadSelected();
    });
    byId("profile-new").addEventListener("click", function () { if (confirmDiscard()) loadTemplate(); });
    byId("profile-edit").addEventListener("click", function () { beginEdit(false); });
    byId("profile-fork").addEventListener("click", function () { beginEdit(true); });
    byId("profile-import").addEventListener("change", function () { importFile(this); });
    byId("profile-export").addEventListener("click", exportSource);
    byId("profile-validate").addEventListener("click", function () { preview(false); });
    byId("profile-compare").addEventListener("click", function () { preview(true); });
    byId("savebtn").addEventListener("click", saveProfile);
    byId("profile-activate").addEventListener("click", function () { var summary = selectedSummary(); if (summary) activate(summary.ref, "activate"); });
    byId("profile-auto").addEventListener("click", function () { activate(null, "activate"); });
    byId("profile-rollback").addEventListener("click", function () { if (model.status.rollback_ref || model.status.rollback_auto) activate(model.status.rollback_ref || null, "rollback"); });
    byId("profile-delete").addEventListener("click", deleteSelected);
    byId("profile-draft").addEventListener("click", function () { if (confirmDiscard()) loadDeviceDraft(); });
    byId("profile-use-draft").addEventListener("click", useDraft);
    byId("profile-modal-cancel").addEventListener("click", closeModal);
    byId("profile-modal").addEventListener("click", function (event) { if (event.target === this) closeModal(); });
    window.addEventListener("beforeunload", function (event) {
      if (!isDirty()) return;
      event.preventDefault();
      event.returnValue = "";
    });
  }

  initEditor(); bind(); bindProfileLayout(); renderCatalogIssues([]); renderIssues([]); renderDiff([]); renderReport({});
  jsonFetch(API + "/schema").then(function (schema) {
    var advertised = Number(schema.max_bytes);
    if (advertised > 0) model.maxBytes = Math.min(advertised, 256 * 1024);
    if (model.editor && typeof model.editor.setSchema === "function") model.editor.setSchema(schema.fields || []);
  }).catch(function () {});
  loadReport(); loadCatalog();
}());
