// Small, dependency-free translation bridge for the :8888 pages.
//
// The server writes the request-localized catalogue projection into:
//   <script id="ha-i18n" type="application/json">...</script>
// and loads this file before any page-specific script. Callers remain the
// authority for English fallback text: HaI18n.t(key, english, values).
(function (root) {
  "use strict";

  var own = Object.prototype.hasOwnProperty;
  var payload = {};
  var strings = {};
  var source = root.document && root.document.getElementById("ha-i18n");

  if (source) {
    try {
      payload = JSON.parse(source.textContent || "{}");
      if (!payload || typeof payload !== "object" || Array.isArray(payload)) payload = {};
      if (payload.strings && typeof payload.strings === "object" && !Array.isArray(payload.strings)) {
        strings = payload.strings;
      }
    } catch (_) {
      // A missing or malformed projection must leave the English UI usable.
      payload = {};
      strings = {};
    }
  }

  function translatedText(key) {
    if (!own.call(strings, key)) return null;
    var entry = strings[key];
    if (typeof entry === "string") return entry;
    if (entry && typeof entry === "object" && typeof entry.text === "string") return entry.text;
    return null;
  }

  function interpolate(text, values) {
    if (!values || typeof values !== "object") return text;
    // Only named placeholders are substitutions. Unknown placeholders and all
    // other bytes (HA, MQTT, package ids, URLs, versions and units) pass through.
    return text.replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g, function (placeholder, name) {
      return own.call(values, name) ? String(values[name]) : placeholder;
    });
  }

  function t(key, englishFallback, values) {
    var translated = translatedText(String(key));
    var fallback = englishFallback == null ? "" : String(englishFallback);
    return interpolate(translated == null ? fallback : translated, values);
  }

  function text(node, key, englishFallback, values) {
    var value = t(key, englishFallback, values);
    if (node) node.textContent = value;
    return value;
  }

  root.HaI18n = Object.freeze({
    locale: typeof payload.locale === "string" ? payload.locale : "en",
    t: t,
    text: text
  });
})(window);
