// Install tab: managed-component version pickers + install/update, WebView heal, health audit, radio.
(function () {
  var msg = function (t) { var e = document.getElementById('comp-msg'); if (e) e.textContent = t; };
  var row = function (name) { return document.querySelector('.comprow[data-name="' + name + '"]'); };

  // Populate a component's version <select> for the chosen channel, newest first. Pre-selects the option
  // matching the installed version, else the newest installable one, then syncs the notes link + button.
  window.loadVersions = function (name) {
    var r = row(name); if (!r) return;
    var chan = r.querySelector('.cchan').value, vsel = r.querySelector('.cvsel');
    var installed = (r.querySelector('.cver') || {}).textContent || '';
    vsel.innerHTML = '<option>loading…</option>';
    fetch('/api/v1/install/versions?name=' + encodeURIComponent(name) + '&channel=' + encodeURIComponent(chan))
      .then(function (res) { return res.json(); }).then(function (d) {
        var vs = (d && d.versions) || [];
        if (!vs.length) { vsel.innerHTML = '<option value="">no versions found</option>'; verChanged(name); return; }
        vsel.innerHTML = '';
        var pick = 0;
        vs.forEach(function (v, i) {
          var o = document.createElement('option');
          o.value = v.tag; o.textContent = v.version + (v.installable ? '' : ' (no APK)');
          o.setAttribute('data-notes', v.notes || ''); o.setAttribute('data-installable', v.installable ? '1' : '0');
          o.setAttribute('data-apk', v.apk || '');
          if (v.version === installed) pick = i;
          vsel.appendChild(o);
        });
        vsel.selectedIndex = pick;
        verChanged(name);
      }).catch(function () { vsel.innerHTML = '<option value="">check failed</option>'; verChanged(name); });
  };

  // Sync the release-notes link + Install button to the currently-selected version.
  window.verChanged = function (name) {
    var r = row(name); if (!r) return;
    var o = r.querySelector('.cvsel').selectedOptions[0];
    var notes = r.querySelector('.cnotes'), btn = r.querySelector('.cinstall'), dl = r.querySelector('.cdl');
    var url = o ? o.getAttribute('data-notes') : '', installable = o && o.getAttribute('data-installable') === '1';
    if (notes) { if (url) { notes.href = url; notes.style.visibility = 'visible'; } else { notes.style.visibility = 'hidden'; } }
    if (btn) btn.disabled = !(btn.getAttribute('data-root') === '1' && installable);
    // No-root panels render a Download link instead of the Install button (ha-paneld can't install
    // APKs without root): point it at the selected version's APK asset for a manual adb install -r.
    if (dl) {
      var apk = o ? o.getAttribute('data-apk') : '';
      if (installable && apk) { dl.href = apk; dl.style.display = ''; } else { dl.style.display = 'none'; }
    }
  };

  // Install the selected version of a picker component.
  window.installSel = function (name, btn) {
    var r = row(name); if (!r) return;
    var o = r.querySelector('.cvsel').selectedOptions[0];
    if (!o || !o.value) return;
    start(name, { version: o.value }, btn);
  };

  // Single-action components (WebView heal, latest update/reinstall).
  window.installComp = function (name, action, btn) { start(name, { action: action }, btn); };

  // Shared: POST the install, then poll /install/status until done and reload so versions re-render.
  function start(name, extra, btn) {
    if (btn) btn.disabled = true;
    msg('Starting ' + name + '…');
    var body = 'name=' + encodeURIComponent(name);
    if (extra.action) body += '&action=' + encodeURIComponent(extra.action);
    if (extra.version) body += '&version=' + encodeURIComponent(extra.version);
    fetch('/api/v1/install/component', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body })
      .then(function (res) { return res.json(); }).then(function (d) {
        if (d.status === 'busy') { msg('Another install is already running — try again shortly.'); if (btn) btn.disabled = false; return; }
        if (d.status !== 'started') { msg('Could not start: ' + (d.status || 'error')); if (btn) btn.disabled = false; return; }
        pollInstall(0);
      }).catch(function () { msg('Failed to start — check root/daemon.'); if (btn) btn.disabled = false; });
  }

  function pollInstall(n) {
    fetch('/api/v1/install/status').then(function (r) { return r.json(); }).then(function (d) {
      if (d.running) { msg((d.component || 'Install') + ': working…'); setTimeout(function () { pollInstall(n + 1); }, 2500); return; }
      msg((d.component || 'Install') + ': ' + (d.message || 'done') + ' — reloading…');
      setTimeout(function () { location.reload(); }, 2000);
    }).catch(function () {
      // Transient (e.g. ha-paneld restarted itself after a self-update) — keep trying a while.
      if (n < 40) setTimeout(function () { pollInstall(n + 1); }, 3000); else msg('Lost contact — reload to check.');
    });
  }

  // Top-of-tab WebView too-old heal button.
  window.healWebView = function (btn) {
    btn.disabled = true; var s = document.getElementById('wv-heal');
    if (s) s.textContent = 'Downloading + installing… this takes a minute.';
    fetch('/api/v1/webview/heal', { method: 'POST' }).then(function (r) { return r.json(); }).then(function (d) {
      if (d.status === 'busy') { if (s) s.textContent = 'Another operation is running — try again shortly.'; btn.disabled = false; return; }
      if (s) s.textContent = 'Installing WebView — reload the dashboard, then refresh this page to confirm the new version.';
    }).catch(function () { if (s) s.textContent = 'Failed to start — check root/daemon.'; btn.disabled = false; });
  };

  // Companion internal-URL repair (HA 2026.7 "Missing 'Host' header"): copy external_url into a blank
  // internal_url, then force-stop + relaunch the Companion.
  window.repairCompUrl = function (btn) {
    btn.disabled = true; var s = document.getElementById('cu-fix');
    if (s) s.textContent = 'Repairing + relaunching the Companion…';
    fetch('/api/v1/companion/repair-url', { method: 'POST' }).then(function (r) { return r.json(); }).then(function (d) {
      if (d.status === 'busy') { if (s) s.textContent = 'Another operation is running — try again shortly.'; btn.disabled = false; return; }
      if (s) s.textContent = 'Repair started — the Companion will relaunch; refresh this page in a few seconds to confirm.';
    }).catch(function () { if (s) s.textContent = 'Failed to start — check root.'; btn.disabled = false; });
  };

  // On-demand health audit: force a fresh update check + re-probe, render the warnings inline.
  window.healthAudit = function (btn) {
    btn.disabled = true; var out = document.getElementById('audit-out');
    if (out) out.innerHTML = '<p class="note">Checking…</p>';
    fetch('/api/v1/status?refresh=1').then(function (r) { return r.json(); }).then(function (d) {
      var w = (d && d.warnings) || [];
      if (!out) return;
      if (!w.length) { out.innerHTML = '<p class="note">✓ No problems detected — this panel looks ready.</p>'; }
      else { out.innerHTML = w.map(function (h) { return '<div class="setup">' + h + '</div>'; }).join(''); }
      btn.disabled = false;
    }).catch(function () { if (out) out.innerHTML = '<p class="note">Audit failed — try again.</p>'; btn.disabled = false; });
  };

  // --- APK upload (⚠ privileged-installs an arbitrary APK; parse-then-confirm) ---
  var apkMsg = function (t) { var e = document.getElementById('apk-msg'); if (e) e.textContent = t; };

  window.apkAllow = function (cb) {
    var ui = document.getElementById('apk-ui'); if (ui) ui.style.display = cb.checked ? '' : 'none';
    fetch('/api/v1/install/apk/allow', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'on=' + (cb.checked ? '1' : '0') }).catch(function () {});
  };

  // Upload the chosen APK (raw body), then render its parsed identity + a Confirm-install button.
  window.apkPick = function (input) {
    var f = input.files && input.files[0]; if (!f) return;
    var prev = document.getElementById('apk-preview');
    apkMsg(''); if (prev) prev.innerHTML = '<p class="note">Uploading + inspecting ' + esc(f.name) + '…</p>';
    fetch('/api/v1/install/apk', { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: f })
      .then(function (r) { return r.json(); }).then(function (d) {
        if (!prev) return;
        if (!d.ok) { prev.innerHTML = '<p class="note">Upload failed: ' + esc(d.error || 'error') + '</p>'; return; }
        prev.innerHTML = '<table class="dt"><tr><th>Package</th><td>' + esc(d.package) + '</td></tr>' +
          '<tr><th>Version</th><td>' + esc(d.version) + '</td></tr>' +
          '<tr><th>Signer SHA-256</th><td style="word-break:break-all">' + esc(d.signer) + '</td></tr></table>' +
          '<button class="pbtn" style="margin-top:8px" data-token="' + esc(d.token) + '" onclick="apkInstall(this)">⬇ Install ' + esc(d.package) + '</button>';
      }).catch(function () { if (prev) prev.innerHTML = '<p class="note">Upload failed.</p>'; });
  };

  window.apkInstall = function (btn) {
    btn.disabled = true; apkMsg('Installing…');
    var body = 'token=' + encodeURIComponent(btn.getAttribute('data-token') || '');
    fetch('/api/v1/install/apk/commit', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body }).then(function (r) { return r.json(); }).then(function (d) {
      if (d.status === 'busy') { apkMsg('Another install is running — try again shortly.'); btn.disabled = false; return; }
      if (d.status === 'stale-or-missing') { apkMsg('This upload was replaced or expired — choose the APK again.'); btn.disabled = false; return; }
      if (d.status !== 'started') { apkMsg('Could not start: ' + (d.status || 'error')); btn.disabled = false; return; }
      pollApk(0);
    }).catch(function () { apkMsg('Failed to start.'); btn.disabled = false; });
  };

  function pollApk(n) {
    fetch('/api/v1/install/status').then(function (r) { return r.json(); }).then(function (d) {
      if (d.running) { apkMsg('Installing…'); setTimeout(function () { pollApk(n + 1); }, 2000); return; }
      apkMsg('Result: ' + (d.message || 'done'));
    }).catch(function () { if (n < 20) setTimeout(function () { pollApk(n + 1); }, 2500); else apkMsg('Lost contact.'); });
  }

  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }

  // --- Uninstall an app ---
  function loadPackages() {
    var sel = document.getElementById('uninst-pkg'); if (!sel) return;
    fetch('/api/v1/packages').then(function (r) { return r.json(); }).then(function (d) {
      var ps = (d && d.packages) || [];
      if (!ps.length) { sel.innerHTML = '<option value="">no removable apps</option>'; return; }
      sel.innerHTML = ps.map(function (p) { return '<option value="' + esc(p.pkg) + '">' + esc(p.label) + ' (' + esc(p.pkg) + ')</option>'; }).join('');
    }).catch(function () { sel.innerHTML = '<option value="">load failed</option>'; });
  }
  window.doUninstall = function (btn) {
    var sel = document.getElementById('uninst-pkg'), msg = document.getElementById('uninst-msg');
    var pkg = sel && sel.value; if (!pkg) return;
    if (!confirm('Uninstall ' + pkg + '? This removes the app and its data.')) return;
    btn.disabled = true; if (msg) msg.textContent = 'Uninstalling ' + pkg + '…';
    fetch('/api/v1/uninstall', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'pkg=' + encodeURIComponent(pkg) })
      .then(function (r) { return r.json(); }).then(function (d) {
        if (msg) msg.textContent = d.ok ? ('Uninstalled ' + pkg) : ('Failed: ' + (d.result || d.error || 'error'));
        btn.disabled = false; if (d.ok) loadPackages();
      }).catch(function () { if (msg) msg.textContent = 'Failed.'; btn.disabled = false; });
  };
  if (document.getElementById('uninst-pkg')) loadPackages();

  // --- Encrypted backup / restore ---
  var bkMsg = function (t) { var e = document.getElementById('bk-msg'); if (e) e.textContent = t; };
  var rsFile = null;

  window.doBackup = function (btn) {
    var pw = (document.getElementById('bk-pw') || {}).value || '';
    var comp = document.getElementById('bk-comp');
    btn.disabled = true; bkMsg(pw ? 'Building encrypted backup…' : 'Building backup…');
    fetch('/api/v1/backup', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'passphrase=' + encodeURIComponent(pw) + '&include_companion=' + (comp && comp.checked ? '1' : '0') })
      .then(function (r) {
        if (r.ok) return r.blob();
        return r.json().then(function (d) { throw new Error(d.error || 'request failed'); });
      }).then(function (b) {
        var a = document.createElement('a'), url = URL.createObjectURL(b);
        var switcher = document.getElementById('pswitch'), panelName = switcher && switcher.dataset && switcher.dataset.selfName;
        a.href = url; a.download = (panelName || document.title.split('·')[0] || 'panel').trim() + '-backup.' + (pw ? 'hpb' : 'json');
        document.body.appendChild(a); a.click(); a.remove(); URL.revokeObjectURL(url);
        bkMsg('Backup downloaded' + (pw ? ' (encrypted) — keep the passphrase.' : '.')); btn.disabled = false;
      }).catch(function (e) { bkMsg('Backup failed: ' + (e.message || 'request failed')); btn.disabled = false; });
  };

  // Pick a bundle → preview (dry run, non-destructive) → offer a Confirm restore. Passphrase only needed
  // for an encrypted bundle (the server says so if it is one and the field is blank).
  window.restorePick = function (input) {
    rsFile = input.files && input.files[0]; if (!rsFile) return;
    var pw = (document.getElementById('rs-pw') || {}).value || '';
    var prev = document.getElementById('rs-preview');
    if (prev) prev.innerHTML = '<p class="note">Reading preview…</p>';
    fetch('/api/v1/restore?dry_run=1', { method: 'POST', headers: { 'X-Backup-Passphrase': pw }, body: rsFile })
      .then(function (r) { return r.json(); }).then(function (d) {
        if (!prev) return;
        if (!d.ok) { prev.innerHTML = '<p class="note">' + esc(d.error || 'could not read bundle') + '</p>'; return; }
        var comp = d.companion_files ? (esc(d.companion_pkg) + ' (' + d.companion_files + ' files)') : 'none';
        prev.innerHTML = '<table class="dt"><tr><th>Panel</th><td>' + esc(d.panel_id) + '</td></tr>' +
          '<tr><th>Config keys</th><td>' + d.config_keys + '</td></tr>' +
          '<tr><th>Companion login</th><td>' + comp + '</td></tr></table>' +
          '<button class="pbtn" style="margin-top:8px" onclick="restoreConfirm(this)">⚠ Restore this bundle now</button>';
      }).catch(function () { if (prev) prev.innerHTML = '<p class="note">Preview failed.</p>'; });
  };

  window.restoreConfirm = function (btn) {
    if (!rsFile) return;
    if (!confirm('Restore overwrites this panel\'s config and Companion login. Continue?')) return;
    var pw = (document.getElementById('rs-pw') || {}).value || '';
    btn.disabled = true; bkMsg('Restoring…');
    fetch('/api/v1/restore', { method: 'POST', headers: { 'X-Backup-Passphrase': pw }, body: rsFile })
      .then(function (r) { return r.json(); }).then(function (d) {
        if (d.status === 'busy') { bkMsg('Another operation is running — try again shortly.'); btn.disabled = false; return; }
        if (d.status !== 'started') { bkMsg('Could not start: ' + (d.status || 'error')); btn.disabled = false; return; }
        pollRestore(0);
      }).catch(function () { bkMsg('Restore failed to start.'); btn.disabled = false; });
  };

  function pollRestore(n) {
    fetch('/api/v1/install/status').then(function (r) { return r.json(); }).then(function (d) {
      if (d.running) { bkMsg('Restoring…'); setTimeout(function () { pollRestore(n + 1); }, 2500); return; }
      bkMsg('Restore: ' + (d.message || 'done'));
    }).catch(function () { if (n < 20) setTimeout(function () { pollRestore(n + 1); }, 3000); else bkMsg('Lost contact — reload to check.'); });
  }

  // Radio card: show it only when this panel actually has an EFR32 radio gateway.
  fetch('/api/v1/radio').then(function (r) { return r.json(); }).then(function (d) {
    if (!d || !d.present) return;
    var card = document.getElementById('radiocard'), st = document.getElementById('radio-status');
    if (st) st.textContent = d.status || '';
    if (card) card.style.display = '';
  }).catch(function () {});

  // Hydrate every picker component's version list on load.
  document.querySelectorAll('.comprow[data-name]').forEach(function (r) { loadVersions(r.getAttribute('data-name')); });
})();
