// Zero-configuration proximity learning journey. Idle refresh is deliberately slow; only an active
// guided/test session uses a one-second cadence, and every operation remains explicit and bounded.
(function () {
  "use strict";
  var mount = document.getElementById("proximity-learning-mount");
  if (!mount) return;
  var cardRoot = document.getElementById("cfg-groups");
  if (!cardRoot) return;
  var timer = null, active = false, cardSizeInvalid = false;

  function t(key, fallback, values) {
    return window.HaI18n ? window.HaI18n.t(key, fallback, values) : fallback;
  }

  function presented(text, localized) { return { text: text, localized: localized !== false }; }

  function paint(target, value) {
    target.textContent = value.text;
    if (value.localized) target.removeAttribute("lang");
    else target.setAttribute("lang", "en");
  }

  function node(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  var card = node("section", "card prox-learning");
  card.id = "cfg-proximity-learning";
  card.setAttribute("data-layout-key", "configure-presence-wake");
  var heading = node("h2", "", t("configure.proximity.title", "Presence & wake"));
  heading.appendChild(node("span", "cardbadge exp", t("configure.proximity.experimental", "experimental")));
  var state = node("p", "prox-learning-state", t("configure.proximity.status.waiting", "Waiting for sensor status…"));
  var detail = node("p", "note", t("configure.proximity.detail.local", "Learning runs locally. Touch-to-wake remains available while it learns."));
  var evidence = node("p", "muted prox-learning-evidence", "");
  var actions = node("div", "prox-learning-actions");
  var teach = node("button", "pbtn", t("configure.proximity.action.teach", "Teach a wave")); teach.type = "button";
  var test = node("button", "pbtn", t("configure.proximity.action.test", "Test a wave")); test.type = "button";
  var relearn = node("button", "pbtn", t("configure.proximity.action.forget", "Forget learned proximity")); relearn.type = "button";
  var result = node("p", "note", "");
  result.setAttribute("role", "status"); result.setAttribute("aria-live", "polite"); result.setAttribute("aria-atomic", "true");
  actions.appendChild(teach); actions.appendChild(test); actions.appendChild(relearn);
  card.appendChild(heading); card.appendChild(state); card.appendChild(detail); card.appendChild(evidence);
  card.appendChild(actions); card.appendChild(result);
  cardRoot.insertBefore(card, cardRoot.querySelector('[data-config-group="Logging"]'));

  function phaseText(d) {
    var raw = String(d.learning || d.phase || "waiting_for_reading");
    var p = raw.toLowerCase();
    var labels = {
      waiting_for_reading: ["configure.proximity.phase.waiting_for_reading", "Waiting for the sensor’s first reading"],
      identifying: ["configure.proximity.phase.identifying", "Identifying how this sensor reports proximity"],
      learning_baseline: ["configure.proximity.phase.learning_baseline", "Learning the room baseline"],
      learning_gestures: ["configure.proximity.phase.learning_gestures", "Learning what a deliberate wave looks like"],
      validating: ["configure.proximity.phase.validating", "Checking the learned pattern during normal use"],
      ready: ["configure.proximity.phase.ready", "Ready — deliberate wave learned"],
      rebasing: ["configure.proximity.phase.rebasing", "Adapting to a room or sensor change"],
      degraded: ["configure.proximity.phase.degraded", "Paused — proximity signal needs attention"]
    };
    return labels[p] ? presented(t(labels[p][0], labels[p][1])) : presented(raw, false);
  }

  function runtimeMessage(raw) {
    var messages = {
      "The proximity signal changed. Move clear briefly, then restart deliberate waves.": ["signal_changed_restart", "The proximity signal changed. Move clear briefly, then restart deliberate waves."],
      "Teaching complete. Deliberate wake gestures are ready.": ["teaching_complete", "Teaching complete. Deliberate wake gestures are ready."],
      "Movement seen, but it was partial, too quick, or held too long. Move clear, wait two seconds, then try again.": ["movement_partial", "Movement seen, but it was partial, too quick, or held too long. Move clear, wait two seconds, then try again."],
      "Wave detected successfully.": ["wave_detected", "Wave detected successfully."],
      "Movement seen, but it was not an armed deliberate wave. Move clear for two seconds, then try again.": ["movement_not_armed", "Movement seen, but it was not an armed deliberate wave. Move clear for two seconds, then try again."],
      "Leave the area clear briefly, then make a deliberate wave and move clear again.": ["teach_instruction", "Leave the area clear briefly, then make a deliberate wave and move clear again."],
      "Move clear for two seconds, then wave once and move clear again.": ["test_instruction", "Move clear for two seconds, then wave once and move clear again."],
      "Teaching cancelled.": ["teaching_cancelled", "Teaching cancelled."],
      "Wave test cancelled.": ["test_cancelled", "Wave test cancelled."],
      "Could not safely clear proximity history; the existing model is unchanged.": ["clear_safely_failed", "Could not safely clear proximity history; the existing model is unchanged."],
      "Could not clear proximity history; the existing model is unchanged.": ["clear_failed", "Could not clear proximity history; the existing model is unchanged."],
      "Learned proximity history cleared. Learning has restarted.": ["cleared", "Learned proximity history cleared. Learning has restarted."],
      "Wake gestures are disabled because their safety reset could not be saved.": ["safety_reset_failed", "Wake gestures are disabled because their safety reset could not be saved."],
      "Waiting for a trustworthy reading from the panel's proximity source.": ["waiting_trustworthy", "Waiting for a trustworthy reading from the panel's proximity source."],
      "No setup is required. Keep the area clear briefly so the panel can learn its baseline.": ["learning_clear_baseline", "No setup is required. Keep the area clear briefly so the panel can learn its baseline."],
      "Use the panel normally, or choose Teach a wave to finish sooner.": ["use_normally", "Use the panel normally, or choose Teach a wave to finish sooner."],
      "A previous range was found and is being checked against live clear-room readings.": ["checking_previous", "A previous range was found and is being checked against live clear-room readings."],
      "The signal changed. HA reporting and wave wake are paused while a new safe range is learned.": ["signal_changed_paused", "The signal changed. HA reporting and wave wake are paused while a new safe range is learned."],
      "Proximity is normalized for HA and wake requires a complete deliberate wave.": ["normalized_ready", "Proximity is normalized for HA and wake requires a complete deliberate wave."],
      "Presence is normalized for HA. A few more complete gestures will enable wave wake.": ["normalized_learning", "Presence is normalized for HA. A few more complete gestures will enable wave wake."],
      "Teaching timed out. Nothing unsafe was enabled; try again when convenient.": ["teaching_timed_out", "Teaching timed out. Nothing unsafe was enabled; try again when convenient."],
      "No complete wave was detected during the test.": ["test_no_wave", "No complete wave was detected during the test."],
      "No setup is required. Touch the screen to wake it until learning is ready.": ["touch_until_ready", "No setup is required. Touch the screen to wake it until learning is ready."]
    };
    var accepted = /^Wave accepted \((\d+)\/(\d+)\)\. Move clear, wait two seconds, then wave again\.$/.exec(raw || "");
    if (accepted) return presented(t("configure.proximity.message.wave_accepted", "Wave accepted ({seen}/{required}). Move clear, wait two seconds, then wave again.", { seen: accepted[1], required: accepted[2] }));
    var known = messages[raw];
    return known ? presented(t("configure.proximity.message." + known[0], known[1])) : presented(raw || "", false);
  }

  function healthText(raw) {
    var labels = {
      no_data: ["no_data", "no data"], learning: ["learning", "learning"], stale: ["stale", "stale"],
      invalid_sample: ["invalid_sample", "invalid sample"], clock_regression: ["clock_regression", "clock error"],
      model_shift: ["model_shift", "signal changed"], source_unavailable: ["source_unavailable", "source unavailable"]
    };
    return labels[raw] ? presented(t("configure.proximity.health." + labels[raw][0], labels[raw][1])) : presented(String(raw), false);
  }

  function modeText(raw) {
    var modes = { identifying: "identifying", unknown: "identifying", binary: "binary", graded: "graded" };
    return modes[raw] ? presented(t("configure.proximity.mode." + modes[raw], modes[raw])) : presented(String(raw), false);
  }

  function render(d) {
    if (d.present === false) {
      paint(state, presented(t("configure.proximity.no_source.title", "No proximity source is available on this panel")));
      paint(detail, presented(t("configure.proximity.no_source.detail", "Wake on wave is unavailable; touch-to-wake is unchanged.")));
      actions.hidden = true; return;
    }
    actions.hidden = false;
    paint(state, phaseText(d));
    var health = d.health && d.health !== "healthy" ? healthText(d.health) : null;
    var mode = d.signalMode || d.mode || "identifying";
    var detailMessage = runtimeMessage(d.message || "No setup is required. Touch the screen to wake it until learning is ready.");
    if (health) {
      detailMessage = presented(t("configure.proximity.detail.with_health", "{detail} · {health}", { detail: detailMessage.text, health: health.text }), detailMessage.localized && health.localized);
    }
    paint(detail, detailMessage);
    var seen = d.acceptedGestures == null ? 0 : d.acceptedGestures;
    var required = d.requiredGestures == null ? 3 : d.requiredGestures;
    var localizedMode = modeText(mode);
    var evidenceKey = d.normalizedLevel == null ? "configure.proximity.evidence" : "configure.proximity.evidence_level";
    var evidenceFallback = d.normalizedLevel == null ? "Observed mode: {mode} · accepted waves {seen}/{required}" : "Observed mode: {mode} · accepted waves {seen}/{required} · current level {level}%";
    paint(evidence, presented(t(evidenceKey, evidenceFallback, { mode: localizedMode.text, seen: seen, required: required, level: d.normalizedLevel }), localizedMode.localized));
    active = !!(d.session && d.session.active);
    var sessionKind = active ? d.session.kind : "";
    var ready = (d.learning || d.phase) === "ready";
    // The active operation always owns its cancel control, even if passive evidence makes the
    // background learner ready while a guided session is still open.
    teach.hidden = sessionKind === "test" || (!active && ready);
    test.hidden = sessionKind === "teach" || (!active && !ready);
    teach.disabled = d.canTeach === false && sessionKind !== "teach";
    test.disabled = d.canTest === false && sessionKind !== "test";
    if (active) {
      var s = d.session;
      paint(result, s.message ? runtimeMessage(s.message) : presented(s.kind === "teach" ? t("configure.proximity.status.waiting_deliberate", "Waiting for a deliberate wave…") : t("configure.proximity.status.waiting_one", "Waiting for one wave…")));
      teach.textContent = s.kind === "teach" ? t("configure.proximity.action.cancel_teaching", "Cancel teaching") : t("configure.proximity.action.teach", "Teach a wave");
      test.textContent = s.kind === "test" ? t("configure.proximity.action.cancel_test", "Cancel test") : t("configure.proximity.action.test", "Test a wave");
    } else {
      teach.textContent = t("configure.proximity.action.teach", "Teach a wave"); test.textContent = t("configure.proximity.action.test", "Test a wave");
      if (d.lastSessionMessage) paint(result, runtimeMessage(d.lastSessionMessage));
    }
  }

  async function refresh() {
    try {
      var response = await fetch("/api/v1/proximity", { cache: "no-store" });
      if (!response.ok) throw new Error("status " + response.status);
      render(await response.json());
      if (window.configCardSizeSourceReady) window.configCardSizeSourceReady("proximity");
      if (cardSizeInvalid && window.configCardSizeGeometryChanged) window.configCardSizeGeometryChanged();
      cardSizeInvalid = false;
    } catch (_) {
      paint(state, presented(t("configure.proximity.status.unavailable", "Proximity learning status is unavailable")));
      cardSizeInvalid = true;
      if (window.configCardSizeGeometryInvalid) window.configCardSizeGeometryInvalid();
    }
    clearTimeout(timer); timer = setTimeout(refresh, active ? 1000 : 5000);
  }

  async function post(path, body) {
    paint(result, presented(t("configure.proximity.status.working", "Working…")));
    try {
      var response = await fetch(path, {
        method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json" },
        body: new URLSearchParams(body).toString()
      });
      var data = await response.json(); render(data);
      if (!response.ok) paint(result, data.error ? presented(String(data.error), false) : presented(t("configure.proximity.error.not_available", "That action is not available yet.")));
    } catch (_) { paint(result, presented(t("configure.proximity.error.rejected", "The panel did not accept that action."))); }
  }

  teach.addEventListener("click", function () { post("/api/v1/proximity/teach", { action: active ? "cancel" : "start" }); });
  test.addEventListener("click", function () { post("/api/v1/proximity/test", { action: active ? "cancel" : "start" }); });
  relearn.addEventListener("click", function () {
    if (!window.confirm(t("configure.proximity.confirm.forget", "Move clear of the panel, then delete learned proximity history for this sensor? Touch-to-wake remains available while it relearns."))) return;
    post("/api/v1/proximity/relearn", { confirm: "true" });
  });
  document.addEventListener("visibilitychange", function () { if (!document.hidden) refresh(); });
  refresh();
})();
