/* Home Assistant sign-in from Panel Assistant's sidebar. Loaded only in embedded mode, where the page is
 * proxied by Home Assistant: the integration answers `_panel_assistant/sign-in` itself, creates this panel's
 * own Home Assistant user and posts the credential to the panel, so the panel's browser OAuth is not used.
 * Resolves to { ok, code, message } with the message from the panel's catalogue; it never rejects. */
(function () {
  "use strict";

  var CODES = ["internal_url_missing", "panel_unreachable", "panel_refused"];
  var FALLBACK = {
    ok: "Signed in to Home Assistant. This panel now has its own Home Assistant account.",
    internal_url_missing: "Home Assistant has no internal URL. Set one under Settings → System → Network, then try again.",
    panel_unreachable: "Home Assistant could not reach this panel. Check that the panel is on the network, then try again.",
    panel_refused: "This panel refused the sign-in from Home Assistant. Try again.",
    failed: "Sign-in from Home Assistant did not complete. Reload the page and try again."
  };

  function text(code) {
    var key = "shell.pa_sign_in_" + code;
    if (window.HaI18n && typeof window.HaI18n.t === "function") {
      try { return window.HaI18n.t(key, FALLBACK[code]); } catch (_) { /* English remains usable. */ }
    }
    return FALLBACK[code];
  }

  window.panelAssistantSignIn = function () {
    return fetch("_panel_assistant/sign-in", { method: "POST", headers: { "Accept": "application/json" }, cache: "no-store" })
      .then(function (response) {
        return response.json().catch(function () { return null; }).then(function (body) {
          if (response.ok && body && body.ok === true) return "ok";
          var code = body && typeof body.error === "string" ? body.error : "";
          return CODES.indexOf(code) >= 0 ? code : "failed";
        });
      }, function () { return "failed"; })
      .then(function (code) { return { ok: code === "ok", code: code, message: text(code) }; });
  };
})();
