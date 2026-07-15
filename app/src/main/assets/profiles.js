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
    maxBytes: 128 * 1024,
  };
  var suppressEditorChange = false;

  function byId(id) { return document.getElementById(id); }
  function string(value) { return value == null ? "" : String(value); }
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
  function jsonFetch(url, options) {
    return fetch(url, options).then(function (response) {
      return response.text().then(function (text) {
        var body = {};
        try { body = text ? JSON.parse(text) : {}; } catch (_) { body = { message: text }; }
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
      setDiagnostics: function () {},
      focus: function () { fallback.focus(); },
    };
  }
  function editorChanged(value) {
    model.source = string(value);
    if (suppressEditorChange) return;
    model.preview = null;
    renderIssues([]);
    renderDiff([]);
    updateActions();
  }
  function setEditor(value, editable) {
    model.source = string(value);
    model.originalSource = string(value);
    model.editable = !!editable;
    model.preview = null;
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
  function renderBadges() {
    var root = byId("profile-badges");
    if (!root) return;
    root.textContent = "";
    var summary = reviewedSummary();
    var guidance = byId("profile-shizuku-guidance");
    if (guidance) guidance.hidden = true;
    if (!summary) return;
    root.appendChild(badge(summary.origin === "bundled" ? "Bundled" : "Local", summary.origin === "bundled" ? "bundled" : ""));
    if (summary.maturity) root.appendChild(badge(string(summary.maturity), "maturity"));
    if (summary.compatible === false) root.appendChild(badge("Incompatible", "risk"));
    if (summary.active) root.appendChild(badge("Active", "active"));
    if (summary.selected && !summary.active) root.appendChild(badge("Selected", "pending"));
    if (summary.last_known_good) root.appendChild(badge("Last known good", "lkg"));
    var shizuku = string(summary.shizuku_recommendation).toLowerCase();
    if (shizuku === "optional" || shizuku === "recommended") {
      root.appendChild(badge("Shizuku " + shizuku, "shizuku"));
    }
    if (guidance) guidance.hidden = !(shizuku === "optional" || shizuku === "recommended");
    (summary.risks || []).forEach(function (risk) { root.appendChild(badge(string(risk).replace(/_/g, " "), "risk")); });
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
    entries.forEach(function (fact) {
      var row = document.createElement("div"); row.className = "profile-diff-row";
      var key = document.createElement("div"); key.className = "profile-diff-path"; key.textContent = string(fact.key || fact.path || "fact");
      var value = document.createElement("div"); value.className = "profile-diff-value"; value.textContent = string(fact.status || "unknown") + (fact.value == null ? "" : " · " + string(fact.value));
      row.appendChild(key); row.appendChild(value); root.appendChild(row);
    });
  }
  function updateActions() {
    var summary = selectedSummary();
    var review = reviewedSummary();
    var active = model.profiles.find(function (item) { return item.active; }) || null;
    var dirty = isDirty();
    var save = byId("savebtn");
    if (save) save.disabled = !model.editable || !dirty || !(model.preview && model.preview.compatible && model.preview.source === model.source);
    var validate = byId("profile-validate"); if (validate) validate.disabled = !model.source || model.loading;
    var compare = byId("profile-compare"); if (compare) compare.disabled = !model.source || model.loading;
    var edit = byId("profile-edit"); if (edit) edit.disabled = !summary || model.editable;
    var fork = byId("profile-fork"); if (fork) fork.disabled = !summary || model.editable;
    var activate = byId("profile-activate"); if (activate) activate.disabled = !summary || summary.compatible === false || summary.active || dirty || model.loading;
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
    setStatus("Loading profiles…");
    return jsonFetch(API).then(function (data) {
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
    setStatus("Loading " + ref.id + "…");
    return yamlFetch(API + "/" + encodeURIComponent(ref.id) + "/revisions/" + encodeURIComponent(ref.revision)).then(function (yaml) {
      setEditor(yaml, false);
      var summary = selectedSummary(); renderIssues(summary && summary.issues || []); renderDiff([]);
      setStatus("Viewing immutable revision " + string(ref.revision).slice(0, 12) + ".");
    }).catch(function (error) { setStatus("Could not load profile: " + error.message, "error"); });
  }

  function loadReport() {
    return jsonFetch(API + "/report").then(renderReport).catch(function () { renderReport({}); });
  }

  function preview(openCompare) {
    if (!model.source) return Promise.resolve(null);
    model.loading = true; updateActions(); setStatus("Validating YAML…");
    return postYaml("/probe", model.source).then(function (result) {
      result.source = model.source;
      model.preview = result;
      renderIssues(result.issues || []);
      renderDiff(result.diff_from_active || []);
      renderReport(result.report || {});
      setStatus(result.compatible ? "Valid profile · sha256:" + string(result.content_sha256).slice(0, 12) : "Profile is not compatible; fix the reported issues.", result.compatible ? "ok" : "error");
      if (openCompare) openModal("Compare with active profile", diffText(result.diff_from_active || []), null);
      return result;
    }).catch(function (error) {
      var issues = error.body && error.body.issues || [{ severity: "error", message: error.message }];
      model.preview = null; renderIssues(issues); setStatus("Validation failed: " + error.message, "error"); return null;
    }).finally(function () { model.loading = false; updateActions(); });
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
    ensurePreview().then(function (result) {
      model.loading = true; updateActions(); setStatus("Saving immutable revision…");
      return postYaml("/import", model.source, result.preview_token);
    }).then(function (mutation) {
      setStatus(mutation.message || "Profile revision saved.", "ok");
      var ref = mutation.ref || mutation.imported_ref || null;
      return loadCatalog(ref);
    }).catch(function (error) {
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
    yamlFetch(API + "/template").then(function (yaml) {
      model.selected = null; setEditor(yaml, true); model.originalSource = ""; renderIssues([]); renderDiff([]); setStatus("New unsaved profile. Validate it before saving."); model.editor.focus(); updateActions();
    }).catch(function (error) { setStatus("Could not create a template: " + error.message, "error"); });
  }
  function loadDeviceDraft() {
    yamlFetch(API + "/device-draft").then(function (yaml) {
      model.selected = null; setEditor(yaml, false); renderIssues([]); renderDiff([]);
      setStatus("Passive Generic draft · read-only · unknown hardware remains marked TODO.");
      var use = byId("profile-use-draft"); if (use) use.hidden = false;
    }).catch(function (error) { setStatus("Could not build the passive draft: " + error.message, "error"); });
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
    file.text().then(function (yaml) {
      model.selected = null; setEditor(yaml, true); model.originalSource = ""; renderIssues([]); renderDiff([]); setStatus("Imported locally for preview; nothing has been saved or activated."); model.editor.focus(); return preview(false);
    }).catch(function (error) { setStatus("Could not read profile: " + error.message, "error"); });
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

  function openModal(title, detail, onConfirm, confirmLabel) {
    var modal = byId("profile-modal");
    byId("profile-modal-title").textContent = title;
    byId("profile-modal-detail").textContent = detail;
    var confirm = byId("profile-modal-confirm");
    confirm.textContent = confirmLabel || "Confirm";
    confirm.hidden = !onConfirm;
    confirm.onclick = function () { closeModal(); if (onConfirm) onConfirm(); };
    modal.hidden = false; byId("profile-modal-cancel").focus();
  }
  function closeModal() { byId("profile-modal").hidden = true; }
  function activate(ref, action) {
    var summary = ref ? summaryForRef(ref) : null;
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
        setStatus(error.status === 409 ? "The profile catalog changed in another tab. Reload and confirm again." : "Activation failed: " + error.message, "error");
      }).finally(function () { model.loading = false; updateActions(); });
    }, action === "rollback" ? "Confirm rollback" : "Confirm and restart");
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
        if (automaticHealthy || active && refKey(active.ref) === refKey(ref)) { setStatus("Profile is active and healthy.", "ok"); loadCatalog(ref); return; }
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

  initEditor(); bind(); renderCatalogIssues([]); renderIssues([]); renderDiff([]); renderReport({});
  jsonFetch(API + "/schema").then(function (schema) {
    var advertised = Number(schema.max_bytes);
    if (advertised > 0) model.maxBytes = Math.min(advertised, 256 * 1024);
  }).catch(function () {});
  loadReport(); loadCatalog();
}());
