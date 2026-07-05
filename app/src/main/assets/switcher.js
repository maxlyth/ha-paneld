// ha-paneld header panel-switcher — served as a static asset (CI-linted: `node --check`).
// When MORE THAN ONE ha-paneld panel is on the LAN (mDNS, via /api/v1/peers), the friendly name in the
// title becomes a dropdown of ALL panels (sorted alphabetically so the menu looks identical on every
// panel); the current panel is pre-selected. Picking another jumps THIS browser window to the SAME view
// (path) on that panel. When only this panel is present, the title stays plain text.
(function () {
  var el = document.getElementById('pswitch');
  if (!el) return;
  var selfId = el.getAttribute('data-self-id') || '';
  var selfName = el.getAttribute('data-self-name') || '';

  function isSelf(p) { return p.self === true || p.panel_id === selfId; }

  // Deterministic, locale-independent name sort — identical on every panel.
  function byName(a, b) {
    var x = (a.name || a.panel_id || '').toLowerCase();
    var y = (b.name || b.panel_id || '').toLowerCase();
    return x < y ? -1 : (x > y ? 1 : 0);
  }

  function build(all) {
    all = all.slice().sort(byName);
    var sel = document.createElement('select');
    sel.className = 'pswitch-sel';
    sel.title = 'Switch to another ha-paneld panel (keeps the current view)';
    sel.setAttribute('autocomplete', 'off');   // don't let the browser restore a stale selection
    all.forEach(function (p) {
      var o = document.createElement('option');
      o.textContent = p.name || p.panel_id;     // textContent, never innerHTML (values are peer-supplied)
      if (isSelf(p)) {
        o.value = '';                           // this panel — selecting it does nothing
        o.selected = true;
      } else {
        o.value = p.panel_id;
        if (p.ip) o.setAttribute('data-base', 'http://' + p.ip + ':' + p.port);
      }
      sel.appendChild(o);
    });
    sel.onchange = function () {
      var base = sel.options[sel.selectedIndex].getAttribute('data-base');
      sel.selectedIndex = selfIndex(sel);       // snap back to THIS panel so a later Back shows the right name
      if (!base) return;                        // "this panel" chosen — nothing to do
      // Same path/query/hash on the chosen peer: /configure on A -> /configure on B.
      window.location.href = base + window.location.pathname + window.location.search + window.location.hash;
    };
    // Back/forward can restore this page from the bfcache frozen with the last pick still selected —
    // `pageshow` fires on that restore (persisted=true) as well as normal load; reset to self either way.
    window.addEventListener('pageshow', function () { sel.selectedIndex = selfIndex(sel); });
    while (el.firstChild) el.removeChild(el.firstChild);
    el.appendChild(document.createTextNode('· '));
    el.appendChild(sel);
  }

  function selfIndex(sel) {
    for (var i = 0; i < sel.options.length; i++) if (!sel.options[i].value) return i;
    return 0;
  }

  fetch('/api/v1/peers').then(function (r) { return r.json(); }).then(function (list) {
    if (!Array.isArray(list)) return;
    if (list.filter(function (p) { return !isSelf(p); }).length === 0) return; // only this panel — plain text
    // Show ALL panels (self in its alphabetical place). Synthesize self if the roster somehow omitted it,
    // so the current panel always appears and is selectable-as-current.
    if (!list.some(isSelf) && selfName) list = list.concat([{ panel_id: selfId, name: selfName, self: true }]);
    build(list);
  }).catch(function () { /* best-effort — leave the plain title on any failure */ });
})();
