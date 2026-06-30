// Console variant — left rail (Monitor / Settings / Tools / Install / Fleet). Full feature parity:
// searchable settings, interactive screenshot, bundle backup/restore, health, nav, TTS.
(function () {
  "use strict";
  var UI = window.UI, el = UI.el;
  var state = { schema: [], values: {}, expose: {}, raw: {} };
  var section = "monitor";

  function host(b) { return (b || "").replace(/^.*:\/\//, "").split(":")[0]; }
  function card(title, body) { return el("div", { class: "card" }, [el("h3", { text: title }), body]); }

  function monitor(main) {
    main.appendChild(el("h1", { text: "Monitor" }));
    var cards = el("div", { class: "cards" });
    var status = el("div", {}, [
      el("div", { text: state.raw.panel_id || "—", style: "font-size:1.4rem;font-weight:600" }),
      el("div", { class: "muted", text: "ha-paneld " + (state.raw.version || "") }),
      el("div", { class: "muted", text: "MQTT: " + (host(state.raw.mqtt_broker) || "(none)") }),
    ]);
    cards.appendChild(card("Panel", status));
    var perf = el("div", { class: "muted", text: "…" });
    cards.appendChild(card("Performance", perf));
    UI.poll("/api/v1/perf", 3000, function (d) {
      perf.innerHTML = "";
      perf.appendChild(el("div", { text: (d.cpu != null ? d.cpu : "?") + "% CPU", style: "font-size:1.4rem;font-weight:600" }));
      perf.appendChild(el("div", { class: "muted", text: "RAM " + d.memUsedMb + "/" + d.memTotalMb + " MB" + (d.tempC != null ? " · " + d.tempC + "°C" : "") }));
      if (d.render) perf.appendChild(el("div", { class: "muted", text: "dashboard " + d.render.verdict }));
    });
    var shot = el("div", { class: "card", style: "grid-column:1/-1" }, [el("h3", { text: "Panel screen" }), UI.screenshot()]);
    cards.appendChild(shot);
    main.appendChild(cards);
  }

  function settings(main) {
    main.appendChild(el("h1", { text: "Settings" }));
    main.appendChild(UI.settingsForm(state, { search: true }));
    main.appendChild(el("div", { class: "card", style: "margin-top:20px" }, [el("h3", { text: "Backup & restore" }), UI.bundle()]));
  }

  function tools(main) {
    main.appendChild(el("h1", { text: "Tools" }));
    var cards = el("div", { class: "cards" });
    cards.appendChild(el("div", { class: "card", style: "grid-column:1/-1" }, [el("h3", { text: "Screen control" }), UI.screenshot()]));
    cards.appendChild(card("On-screen actions", UI.navActions()));
    cards.appendChild(card("TTS / audio test", UI.tts()));
    main.appendChild(cards);
  }

  function install(main) {
    main.appendChild(el("h1", { text: "Install · health" }));
    main.appendChild(UI.installPanel());
  }

  function fleet(main) {
    main.appendChild(el("h1", { text: "Fleet" }));
    main.appendChild(el("p", { class: "muted", text: "Multi-panel roster coming soon — discovery hooks (mDNS + MQTT availability) are in place." }));
  }

  function render() {
    document.querySelectorAll(".side a[data-s]").forEach(function (a) { a.classList.toggle("active", a.getAttribute("data-s") === section); });
    var main = document.getElementById("console-main");
    main.innerHTML = "";
    ({ monitor: monitor, settings: settings, tools: tools, install: install, fleet: fleet }[section] || monitor)(main);
  }
  window.cnSection = function (s) { section = s; render(); };

  UI.loadConfig().then(function (s) { state = s; render(); }).catch(function () { document.getElementById("console-main").textContent = "Could not load."; });
})();
