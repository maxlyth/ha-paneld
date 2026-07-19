// Zero-configuration proximity learning journey. Idle refresh is deliberately slow; only an active
// guided/test session uses a one-second cadence, and every operation remains explicit and bounded.
(function () {
  "use strict";
  var mount = document.getElementById("proximity-learning-mount");
  if (!mount) return;
  var cardRoot = document.getElementById("cfg-groups");
  if (!cardRoot) return;
  var timer = null, active = false;

  function node(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  var card = node("section", "card prox-learning");
  card.id = "cfg-proximity-learning";
  var heading = node("h2", "", "Presence & wake");
  var state = node("p", "prox-learning-state", "Waiting for sensor status…");
  var detail = node("p", "note", "Learning runs locally. Touch-to-wake remains available while it learns.");
  var evidence = node("p", "muted prox-learning-evidence", "");
  var actions = node("div", "prox-learning-actions");
  var teach = node("button", "pbtn", "Teach a wave"); teach.type = "button";
  var test = node("button", "pbtn", "Test a wave"); test.type = "button";
  var relearn = node("button", "pbtn", "Forget learned proximity"); relearn.type = "button";
  var result = node("p", "note", "");
  result.setAttribute("role", "status"); result.setAttribute("aria-live", "polite"); result.setAttribute("aria-atomic", "true");
  actions.appendChild(teach); actions.appendChild(test); actions.appendChild(relearn);
  card.appendChild(heading); card.appendChild(state); card.appendChild(detail); card.appendChild(evidence);
  card.appendChild(actions); card.appendChild(result);
  cardRoot.insertBefore(card, cardRoot.querySelector('[data-config-group="Logging"]'));

  function phaseText(d) {
    var p = (d.learning || d.phase || "waiting_for_reading").toLowerCase();
    var labels = {
      waiting_for_reading: "Waiting for the sensor’s first reading",
      identifying: "Identifying how this sensor reports proximity",
      learning_baseline: "Learning the room baseline",
      learning_gestures: "Learning what a deliberate wave looks like",
      validating: "Checking the learned pattern during normal use",
      ready: "Ready — deliberate wave learned",
      rebasing: "Adapting to a room or sensor change",
      degraded: "Paused — proximity signal needs attention"
    };
    return labels[p] || p.replace(/_/g, " ");
  }

  function render(d) {
    if (d.present === false) {
      state.textContent = "No proximity source is available on this panel";
      detail.textContent = "Wake on wave is unavailable; touch-to-wake is unchanged.";
      actions.hidden = true; return;
    }
    actions.hidden = false;
    state.textContent = phaseText(d);
    var health = d.health && d.health !== "healthy" ? " · " + d.health.replace(/_/g, " ") : "";
    var mode = d.signalMode || d.mode || "identifying";
    detail.textContent = (d.message || "No setup is required. Touch the screen to wake it until learning is ready.") + health;
    var seen = d.acceptedGestures == null ? 0 : d.acceptedGestures;
    var required = d.requiredGestures == null ? 3 : d.requiredGestures;
    evidence.textContent = "Observed mode: " + mode.replace(/_/g, " · ") + " · accepted waves " + seen + "/" + required +
      (d.normalizedLevel == null ? "" : " · current level " + d.normalizedLevel + "%");
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
      result.textContent = s.message || (s.kind === "teach" ? "Waiting for a deliberate wave…" : "Waiting for one wave…");
      teach.textContent = s.kind === "teach" ? "Cancel teaching" : "Teach a wave";
      test.textContent = s.kind === "test" ? "Cancel test" : "Test a wave";
    } else {
      teach.textContent = "Teach a wave"; test.textContent = "Test a wave";
      if (d.lastSessionMessage) result.textContent = d.lastSessionMessage;
    }
  }

  async function refresh() {
    try {
      var response = await fetch("/api/v1/proximity", { cache: "no-store" });
      if (!response.ok) throw new Error("status " + response.status);
      render(await response.json());
    } catch (_) {
      state.textContent = "Proximity learning status is unavailable";
    }
    clearTimeout(timer); timer = setTimeout(refresh, active ? 1000 : 5000);
  }

  async function post(path, body) {
    result.textContent = "Working…";
    try {
      var response = await fetch(path, {
        method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json" },
        body: new URLSearchParams(body).toString()
      });
      var data = await response.json(); render(data);
      if (!response.ok) result.textContent = data.error || "That action is not available yet.";
    } catch (_) { result.textContent = "The panel did not accept that action."; }
  }

  teach.addEventListener("click", function () { post("/api/v1/proximity/teach", { action: active ? "cancel" : "start" }); });
  test.addEventListener("click", function () { post("/api/v1/proximity/test", { action: active ? "cancel" : "start" }); });
  relearn.addEventListener("click", function () {
    if (!window.confirm("Move clear of the panel, then delete learned proximity history for this sensor? Touch-to-wake remains available while it relearns.")) return;
    post("/api/v1/proximity/relearn", { confirm: "true" });
  });
  document.addEventListener("visibilitychange", function () { if (!document.hidden) refresh(); });
  refresh();
})();
