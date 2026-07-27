// Install tab: managed-component version pickers + install/update, WebView heal, health audit, radio.
(function () {
  var hardenedApprovalAttrs = ' data-hardened-approval aria-describedby="hardened-approval-description" title="Requires physical on-panel approval for this action when Hardened mode is enabled."';
  var hardenedApprovalA11yAttrs = ' aria-describedby="hardened-approval-description" title="Requires physical on-panel approval for this action when Hardened mode is enabled."';
  var scheduleInstallColumnAlignment = function () {};
  var installCardMemoryReady = false, installInitialLoadsComplete = false;
  var msg = function (t) {
    var e = document.getElementById('comp-msg');
    if (e && e.textContent !== t) { e.textContent = t; scheduleInstallColumnAlignment(); }
  };
  var row = function (name) { return document.querySelector('.comprow[data-name="' + name + '"]'); };
  function markVersionGeometryValid() {
    if (installInitialLoadsComplete && !installCardMemoryReady) { installCardMemoryReady = true; scheduleInstallColumnAlignment(); }
  }

  function approvalMessage(body) {
    return body && body.message || 'Approve this request on the panel, then retry it.';
  }

  function approvalError(body) {
    var error = new Error(approvalMessage(body));
    error.approvalRequired = true;
    error.body = body || {};
    return error;
  }

  // HTTP 202 is also used for ordinary asynchronous work, so only the structured
  // approval-required body stops the success path.
  function approvalAwareJson(response) {
    var decoded = typeof response.text === 'function'
      ? response.text().then(function (text) {
          try { return text ? JSON.parse(text) : {}; }
          catch (_) { return { message: String(text || '').trim() }; }
        })
      : response.json();
    return decoded.then(function (body) {
      if (response.status === 202 && body && body.error === 'approval-required') throw approvalError(body);
      return body;
    });
  }

  function requestFailure(error, fallback) {
    return error && error.approvalRequired ? error.message : fallback;
  }

  // Release metadata comes from the GitHub API. Keep link navigation on GitHub HTTPS even if an
  // upstream response is malformed or replaced; package/signer checks remain the install boundary.
  function safeGithubUrl(value) {
    try {
      var parsed = new URL(value);
      return parsed.protocol === 'https:' && parsed.hostname.toLowerCase() === 'github.com' ? parsed.href : '';
    } catch (_) { return ''; }
  }

  function setSafeLink(link, value, visibleProperty, visibleValue, hiddenValue) {
    if (!link) return;
    var safe = safeGithubUrl(value);
    if (safe) {
      link.href = safe;
      link.style[visibleProperty] = visibleValue;
    } else {
      link.removeAttribute('href');
      link.style[visibleProperty] = hiddenValue;
    }
  }

  // Populate a component's version <select> for the chosen channel, newest first. Pre-select the newest
  // installable release; fall back to the installed release only when the channel has no installable APK.
  window.loadVersions = function (name) {
    var r = row(name); if (!r) return;
    var chan = r.querySelector('.cchan').value, vsel = r.querySelector('.cvsel');
    var installed = (r.querySelector('.cver') || {}).textContent || '';
    vsel.innerHTML = '<option>loading…</option>';
    return fetch('/api/v1/install/versions?name=' + encodeURIComponent(name) + '&channel=' + encodeURIComponent(chan))
      .then(function (res) { return res.json(); }).then(function (d) {
        var vs = (d && d.versions) || [];
        if (!vs.length) { vsel.innerHTML = '<option value="">no versions found</option>'; verChanged(name); markVersionGeometryValid(); return true; }
        vsel.innerHTML = '';
        var firstInstallable = -1, installedIndex = -1;
        vs.forEach(function (v, i) {
          var o = document.createElement('option');
          o.value = v.tag; o.textContent = v.version + (v.installable ? '' : ' (no APK)');
          o.setAttribute('data-notes', v.notes || ''); o.setAttribute('data-installable', v.installable ? '1' : '0');
          o.setAttribute('data-action', v.action || 'Install');
          o.setAttribute('data-apk', v.apk || '');
          if (firstInstallable < 0 && v.installable) firstInstallable = i;
          if (v.version === installed) installedIndex = i;
          vsel.appendChild(o);
        });
        vsel.selectedIndex = firstInstallable >= 0 ? firstInstallable : (installedIndex >= 0 ? installedIndex : 0);
        verChanged(name);
        markVersionGeometryValid();
        return true;
      }).catch(function () { installCardMemoryReady = false;vsel.innerHTML = '<option value="">check failed</option>'; verChanged(name);
        if (window.CardSizeMemory) window.CardSizeMemory.invalidate('install-cards');return false; });
  };

  // Sync the release-notes link + Install button to the currently-selected version.
  window.verChanged = function (name) {
    var r = row(name); if (!r) return;
    var o = r.querySelector('.cvsel').selectedOptions[0];
    var notes = r.querySelector('.cnotes'), btn = r.querySelector('.cinstall'), dl = r.querySelector('.cdl');
    var url = o ? o.getAttribute('data-notes') : '', installable = o && o.getAttribute('data-installable') === '1';
    setSafeLink(notes, url, 'visibility', 'visible', 'hidden');
    if (btn) {
      btn.textContent = o ? (o.getAttribute('data-action') || 'Install') : 'Install';
      btn.disabled = !(btn.getAttribute('data-root') === '1' && installable);
    }
    // No-root panels render a Download link instead of the Install button (ha-paneld can't install
    // APKs without root): point it at the selected version's APK asset for a manual adb install -r.
    if (dl) {
      var apk = o ? o.getAttribute('data-apk') : '';
      setSafeLink(dl, installable ? apk : '', 'display', '', 'none');
    }
    scheduleInstallColumnAlignment();
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
      .then(approvalAwareJson).then(function (d) {
        if (d.status === 'busy') { msg('Another install is already running — try again shortly.'); if (btn) btn.disabled = false; return; }
        if (d.status !== 'started') { msg('Could not start: ' + (d.status || 'error')); if (btn) btn.disabled = false; return; }
        pollInstall(0);
      }).catch(function (error) { msg(requestFailure(error, 'Failed to start — check root/daemon.')); if (btn) btn.disabled = false; });
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
    fetch('/api/v1/webview/heal', { method: 'POST' }).then(approvalAwareJson).then(function (d) {
      if (d.status === 'busy') { if (s) s.textContent = 'Another operation is running — try again shortly.'; btn.disabled = false; return; }
      if (s) s.textContent = 'Installing WebView — reload the dashboard, then refresh this page to confirm the new version.';
    }).catch(function (error) { if (s) s.textContent = requestFailure(error, 'Failed to start — check root/daemon.'); btn.disabled = false; });
  };

  // Companion internal-URL repair (HA 2026.7 "Missing 'Host' header"): copy external_url into a blank
  // internal_url, then force-stop + relaunch the Companion.
  window.repairCompUrl = function (btn) {
    btn.disabled = true; var s = document.getElementById('cu-fix');
    if (s) s.textContent = 'Repairing + relaunching the Companion…';
    fetch('/api/v1/companion/repair-url', { method: 'POST' }).then(approvalAwareJson).then(function (d) {
      if (d.status === 'busy') { if (s) s.textContent = 'Another operation is running — try again shortly.'; btn.disabled = false; return; }
      if (s) s.textContent = 'Repair started — the Companion will relaunch; refresh this page in a few seconds to confirm.';
    }).catch(function (error) { if (s) s.textContent = requestFailure(error, 'Failed to start — check root.'); btn.disabled = false; });
  };

  // On-demand health audit: force a fresh update check + re-probe, render the warnings inline.
  window.healthAudit = function (btn) {
    btn.disabled = true; var out = document.getElementById('audit-out');
    if (out) { out.innerHTML = '<p class="note">Checking…</p>'; scheduleInstallColumnAlignment(); }
    fetch('/api/v1/status?refresh=1').then(function (r) { return r.json(); }).then(function (d) {
      var w = (d && d.warnings) || [];
      if (!out) return;
      if (!w.length) { out.innerHTML = '<p class="note">✓ No problems detected — this panel looks ready.</p>'; }
      else { out.innerHTML = w.map(function (h) { return '<div class="setup">' + h + '</div>'; }).join(''); }
      btn.disabled = false; scheduleInstallColumnAlignment();
    }).catch(function () {
      if (out) out.innerHTML = '<p class="note">Audit failed — try again.</p>';
      btn.disabled = false; scheduleInstallColumnAlignment();
    });
  };

  // --- APK upload (⚠ root/helper-installs an arbitrary APK; parse-then-confirm) ---
  var apkMsg = function (t) {
    var e = document.getElementById('apk-msg');
    if (e && e.textContent !== t) { e.textContent = t; scheduleInstallColumnAlignment(); }
  };

  window.apkAllow = function (cb) {
    var ui = document.getElementById('apk-ui'); if (ui) ui.style.display = cb.checked ? '' : 'none';
    scheduleInstallColumnAlignment();
    fetch('/api/v1/install/apk/allow', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'on=' + (cb.checked ? '1' : '0') }).catch(function () {});
  };

  // Upload the chosen APK (raw body), then render its parsed identity + a Confirm-install button.
  window.apkPick = function (input) {
    var f = input.files && input.files[0]; if (!f) return;
    var prev = document.getElementById('apk-preview');
    apkMsg('');
    if (prev) { prev.innerHTML = '<p class="note">Uploading + inspecting ' + esc(f.name) + '…</p>'; scheduleInstallColumnAlignment(); }
    fetch('/api/v1/install/apk', { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: f })
      .then(function (r) { return r.json(); }).then(function (d) {
        if (!prev) return;
        if (!d.ok) { prev.innerHTML = '<p class="note">Upload failed: ' + esc(d.error || 'error') + '</p>'; scheduleInstallColumnAlignment(); return; }
        prev.innerHTML = '<table class="dt"><tr><th>Package</th><td>' + esc(d.package) + '</td></tr>' +
          '<tr><th>Version</th><td>' + esc(d.version) + '</td></tr>' +
          '<tr><th>Signer SHA-256</th><td style="word-break:break-all">' + esc(d.signer) + '</td></tr></table>' +
          '<button class="pbtn"' + hardenedApprovalAttrs + ' style="margin-top:8px" data-token="' + esc(d.token) + '" onclick="apkInstall(this)">⬇ Install ' + esc(d.package) + '</button>';
        scheduleInstallColumnAlignment();
      }).catch(function () {
        if (prev) prev.innerHTML = '<p class="note">Upload failed.</p>';
        scheduleInstallColumnAlignment();
      });
  };

  window.apkInstall = function (btn) {
    btn.disabled = true; apkMsg('Installing…');
    var body = 'token=' + encodeURIComponent(btn.getAttribute('data-token') || '');
    fetch('/api/v1/install/apk/commit', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body }).then(approvalAwareJson).then(function (d) {
      if (d.status === 'busy') { apkMsg('Another install is running — try again shortly.'); btn.disabled = false; return; }
      if (d.status === 'stale-or-missing') { apkMsg('This upload was replaced or expired — choose the APK again.'); btn.disabled = false; return; }
      if (d.status !== 'started') { apkMsg('Could not start: ' + (d.status || 'error')); btn.disabled = false; return; }
      pollApk(0);
    }).catch(function (error) { apkMsg(requestFailure(error, 'Failed to start.')); btn.disabled = false; });
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
    var sel = document.getElementById('uninst-pkg'); if (!sel) return Promise.resolve();
    return fetch('/api/v1/packages').then(function (r) { return r.json(); }).then(function (d) {
      var ps = (d && d.packages) || [];
      if (!ps.length) { sel.innerHTML = '<option value="">no removable apps</option>'; return true; }
      sel.innerHTML = ps.map(function (p) { return '<option value="' + esc(p.pkg) + '">' + esc(p.label) + ' (' + esc(p.pkg) + ')</option>'; }).join('');
      return true;
    }).catch(function () { sel.innerHTML = '<option value="">load failed</option>'; return false; });
  }
  window.doUninstall = function (btn) {
    var sel = document.getElementById('uninst-pkg'), msg = document.getElementById('uninst-msg');
    var pkg = sel && sel.value; if (!pkg) return;
    if (!confirm('Uninstall ' + pkg + '? This removes the app and its data.')) return;
    btn.disabled = true; if (msg) msg.textContent = 'Uninstalling ' + pkg + '…';
    fetch('/api/v1/uninstall', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'pkg=' + encodeURIComponent(pkg) })
      .then(approvalAwareJson).then(function (d) {
        if (msg) msg.textContent = d.ok ? ('Uninstalled ' + pkg) : ('Failed: ' + (d.result || d.error || 'error'));
        btn.disabled = false; if (d.ok) loadPackages();
      }).catch(function (error) { if (msg) msg.textContent = requestFailure(error, 'Failed.'); btn.disabled = false; });
  };
  var initialPackagesLoad = loadPackages();

  // --- Encrypted backup / restore ---
  var bkMsg = function (t) {
    var e = document.getElementById('bk-msg');
    if (e && e.textContent !== t) { e.textContent = t; scheduleInstallColumnAlignment(); }
  };
  var rsFile = null;

  window.doBackup = function (btn) {
    var pw = (document.getElementById('bk-pw') || {}).value || '';
    var plain = !!((document.getElementById('bk-plain') || {}).checked);
    var comp = document.getElementById('bk-comp');
    if (!pw && !plain) {
      bkMsg('Enter a passphrase, or explicitly acknowledge the unencrypted plaintext ZIP.');
      return;
    }
    btn.disabled = true; bkMsg(pw ? 'Building encrypted backup…' : 'Building unencrypted ZIP…');
    fetch('/api/v1/backup', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'passphrase=' + encodeURIComponent(pw) + '&allow_plaintext=' + (plain ? '1' : '0') +
        '&include_companion=' + (comp && comp.checked ? '1' : '0') })
      .then(function (r) {
        // A 202 approval JSON is not an archive. Parse it before the generic 2xx/blob branch so it
        // can never be saved with an .hpb or .zip filename.
        if (r.status === 202) return approvalAwareJson(r).then(function (d) {
          throw new Error(d.message || d.error || 'Backup was accepted without an archive.');
        });
        if (r.ok) return r.blob();
        return r.json().then(function (d) { throw new Error(d.message || d.error || 'request failed'); });
      }).then(function (b) {
        var a = document.createElement('a'), url = URL.createObjectURL(b);
        var switcher = document.getElementById('pswitch'), panelName = switcher && switcher.dataset && switcher.dataset.selfName;
        a.href = url; a.download = (panelName || document.title.split('·')[0] || 'panel').trim() + '-backup.' + (pw ? 'hpb' : 'zip');
        document.body.appendChild(a); a.click(); a.remove(); URL.revokeObjectURL(url);
        bkMsg('Backup downloaded' + (pw ? ' (encrypted) — keep the passphrase.' : ' (unencrypted ZIP).')); btn.disabled = false;
      }).catch(function (e) { bkMsg(e && e.approvalRequired ? e.message : 'Backup failed: ' + (e.message || 'request failed')); btn.disabled = false; });
  };

  // Secret-inclusive config export is approval-gated in Hardened mode. Keep the request in-page so
  // an HTTP 202 challenge is rendered as guidance instead of navigating the browser to a JSON body.
  window.configExport = function (includeSecrets, btn) {
    var out = document.getElementById('cfg-export-result');
    if (btn) btn.disabled = true;
    if (out) out.textContent = includeSecrets ? 'Requesting credential-inclusive export…' : 'Building configuration export…';
    var path = '/api/v1/config/export' + (includeSecrets ? '?include_secrets=1' : '');
    fetch(path, { headers: { 'Accept': 'application/json' } }).then(function (r) {
      if (r.status === 202) return approvalAwareJson(r).then(function (d) {
        throw new Error(d.message || d.error || 'Export was accepted without a download.');
      });
      if (!r.ok) return r.json().catch(function () { return {}; }).then(function (d) {
        throw new Error(d.message || d.error || ('HTTP ' + r.status));
      });
      return r.blob();
    }).then(function (blob) {
      var switcher = document.getElementById('pswitch'), panelName = switcher && switcher.dataset && switcher.dataset.selfName;
      var stem = String(panelName || document.title.split('·')[0] || 'panel').trim().replace(/[^a-zA-Z0-9._-]+/g, '-');
      var link = document.createElement('a'), url = URL.createObjectURL(blob);
      link.href = url; link.download = stem + '-config.json';
      document.body.appendChild(link); link.click(); link.remove(); URL.revokeObjectURL(url);
      if (out) out.textContent = 'Configuration export downloaded' + (includeSecrets ? ' with stored credentials.' : '.');
      if (btn) btn.disabled = false;
    }).catch(function (error) {
      if (out) out.textContent = requestFailure(error, 'Export failed: ' + (error.message || 'request failed'));
      if (btn) btn.disabled = false;
    });
  };

  // Pick a bundle → preview (dry run, non-destructive) → offer a Confirm restore. Passphrase only needed
  // for an encrypted bundle (the server says so if it is one and the field is blank).
  window.restorePick = function (input) {
    rsFile = input.files && input.files[0]; if (!rsFile) return;
    var pw = (document.getElementById('rs-pw') || {}).value || '';
    var prev = document.getElementById('rs-preview');
    if (prev) { prev.innerHTML = '<p class="note">Reading preview…</p>'; scheduleInstallColumnAlignment(); }
    fetch('/api/v1/restore?dry_run=1', { method: 'POST', headers: { 'X-Backup-Passphrase': pw }, body: rsFile })
      .then(function (r) { return r.json(); }).then(function (d) {
        if (!prev) return;
        if (!d.ok) { prev.innerHTML = '<p class="note">' + esc(d.error || 'could not read bundle') + '</p>'; scheduleInstallColumnAlignment(); return; }
        var comp = d.companion_files ? (esc(d.companion_pkg) + ' (' + d.companion_files + ' files)') : 'none';
        prev.innerHTML = '<table class="dt"><tr><th>Panel</th><td>' + esc(d.panel_id) + '</td></tr>' +
          '<tr><th>Config keys</th><td>' + d.config_keys + '</td></tr>' +
          '<tr><th>Companion login</th><td>' + comp + '</td></tr></table>' +
          '<button class="pbtn"' + hardenedApprovalA11yAttrs + ' style="margin-top:8px" onclick="restoreConfirm(this)">⚠ Restore this bundle now</button>';
        scheduleInstallColumnAlignment();
      }).catch(function () {
        if (prev) prev.innerHTML = '<p class="note">Preview failed.</p>';
        scheduleInstallColumnAlignment();
      });
  };

  window.restoreConfirm = function (btn) {
    if (!rsFile) return;
    if (!confirm('Restore overwrites this panel\'s config and Companion login. Continue?')) return;
    var pw = (document.getElementById('rs-pw') || {}).value || '';
    btn.disabled = true; bkMsg('Restoring…');
    fetch('/api/v1/restore', { method: 'POST', headers: { 'X-Backup-Passphrase': pw }, body: rsFile })
      .then(approvalAwareJson).then(function (d) {
        if (d.status === 'busy') { bkMsg('Another operation is running — try again shortly.'); btn.disabled = false; return; }
        if (d.status !== 'started') { bkMsg('Could not start: ' + (d.status || 'error')); btn.disabled = false; return; }
        pollRestore(0);
      }).catch(function (error) { bkMsg(requestFailure(error, 'Restore failed to start.')); btn.disabled = false; });
  };

  function pollRestore(n) {
    fetch('/api/v1/install/status').then(function (r) { return r.json(); }).then(function (d) {
      if (d.running) { bkMsg('Restoring…'); setTimeout(function () { pollRestore(n + 1); }, 2500); return; }
      bkMsg('Restore: ' + (d.message || 'done'));
    }).catch(function () { if (n < 20) setTimeout(function () { pollRestore(n + 1); }, 3000); else bkMsg('Lost contact — reload to check.'); });
  }

  // Config-only bundles are intentionally separate from full device backups. Preview first and carry
  // the config fingerprint into apply so another writer cannot invalidate the reviewed changes.
  window.configImport = function (input) {
    var file = input.files && input.files[0]; if (!file) return;
    var out = document.getElementById('cfg-import-result');
    file.text().then(function (bodyText) {
      return fetch('/api/v1/config/import?dry_run=1', { method: 'POST', body: bodyText })
        .then(function (r) { return r.json().then(function (body) { if (!r.ok) throw (body.status || r.status); return body; }); })
        .then(function (dry) {
          var changes = dry.changes || [];
          var summary = changes.length ? changes.map(function (c) {
            return '  ' + c.key + ': ' + (c.from == null ? '(unset)' : c.from) + ' → ' + c.to;
          }).join('\n') : '  (no changes)';
          if (!confirm('Import will change ' + changes.length + ' setting(s):\n\n' + summary + '\n\nApply now?')) {
            if (out) out.textContent = 'Import cancelled.';
            return;
          }
          return fetch('/api/v1/config/import?expected_cfg=' + encodeURIComponent(dry.expected_cfg || ''), { method: 'POST', body: bodyText })
            .then(function (r) { return approvalAwareJson(r).then(function (body) { return { ok: r.ok, status: r.status, body: body }; }); })
            .then(function (response) {
              if (response.status === 409) {
                if (out) out.textContent = 'Import not applied: panel settings changed after the preview. Preview the file again.';
                return;
              }
              if (!response.ok) throw (response.body.status || response.status);
              var res = response.body;
              if (out) out.textContent = 'Import ' + res.status + ' · applied ' + (res.applied || []).length +
                ', skipped ' + (res.skipped || []).length +
                ((res.warnings || []).length ? '\nwarnings:\n  ' + res.warnings.join('\n  ') : '') +
                ((res.errors || []).length ? '\nerrors:\n  ' + res.errors.join('\n  ') : '') +
                '\nReloading to show the imported settings…';
              setTimeout(function () { location.reload(); }, 1800);
            });
        });
    }).catch(function (e) { if (out) out.textContent = e && e.approvalRequired ? e.message : 'Import failed: ' + e; });
    input.value = '';
  };

  // Vendor taming and display sizing are server-rendered forms. Submit them in place so a Hardened-mode
  // approval challenge stays on the current page with the same approve-then-retry guidance as the JS cards.
  if (document.addEventListener) document.addEventListener('submit', function (event) {
    var form = event.target;
    if (!form || form.tagName !== 'FORM') return;
    var path;
    try { path = new URL(form.action, location.href).pathname; } catch (_) { return; }
    if (path !== '/api/v1/tame' && path !== '/api/v1/display/density') return;
    event.preventDefault();
    var submitter = event.submitter;
    var body = new URLSearchParams(new FormData(form));
    if (submitter && submitter.name) body.set(submitter.name, submitter.value);
    var note = form.querySelector('.protected-form-result');
    if (!note) {
      note = document.createElement('p'); note.className = 'note protected-form-result'; form.appendChild(note);
    }
    note.textContent = 'Applying…';
    if (submitter) submitter.disabled = true;
    fetch(path, {
      method: 'POST',
      headers: { 'Accept': 'application/json', 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body.toString(),
    }).then(function (response) {
      return approvalAwareJson(response).then(function (result) {
        if (!response.ok || !result || result.ok !== true) {
          throw new Error(result && (result.message || result.error) || ('HTTP ' + response.status));
        }
        return result;
      });
    }).then(function (result) {
      note.textContent = (result.message || 'Applied.') + ' Returning to this card…';
      var target = path === '/api/v1/tame' ? '/install#cfg-tame' : '/install#cfg-display';
      setTimeout(function () { location.href = target; }, path === '/api/v1/tame' ? 1800 : 900);
    }).catch(function (error) {
      note.textContent = error && error.message ? error.message : 'Could not apply this change.';
      if (submitter && submitter.id === 'tame-package-submit' && typeof updateTamePackageSubmit === 'function') {
        updateTamePackageSubmit();
      } else if (submitter) submitter.disabled = false;
    });
  });

  // Server-rendered Install cards support the same scroll-and-flash deep links as Configure fields.
  if (typeof location !== 'undefined' && location.hash) {
    var target = document.getElementById(location.hash.slice(1));
    if (target) setTimeout(function () {
      target.scrollIntoView({ behavior: 'smooth', block: 'center' });
      target.classList.add('flash');
      setTimeout(function () { target.classList.remove('flash'); }, 1800);
    }, 0);
  }

  var alignInstallColumns = window.CardColumnAlignment
    ? window.CardColumnAlignment.attach('install-cards')
    : function () {};
  scheduleInstallColumnAlignment = function () {
    alignInstallColumns();
    if (installCardMemoryReady && window.CardSizeMemory) window.CardSizeMemory.settle('install-cards', 1200);
  };
  scheduleInstallColumnAlignment();

  // Radio card: show it only when this panel actually has an EFR32 radio gateway.
  var initialRadioLoad = fetch('/api/v1/radio').then(function (r) { return r.json(); }).then(function (d) {
    if (!d || !d.present) return true;
    var card = document.getElementById('radiocard'), st = document.getElementById('radio-status'),
        health = document.getElementById('radio-health');
    if (st) st.textContent = d.status || '';
    if (health) health.textContent = d.state || 'unknown';
    if (card) { card.style.display = ''; scheduleInstallColumnAlignment(); }
    return true;
  }).catch(function () { return false; });

  // Hydrate every picker component's version list on load.
  var initialVersionLoads = [];
  document.querySelectorAll('.comprow[data-name]').forEach(function (r) {
    initialVersionLoads.push(loadVersions(r.getAttribute('data-name')));
  });
  Promise.all([initialPackagesLoad, initialRadioLoad].concat(initialVersionLoads)).then(function (outcomes) {
    if (!outcomes.every(function (ok) { return ok !== false; })) return;
    installInitialLoadsComplete = true;
    installCardMemoryReady = true;
    scheduleInstallColumnAlignment();
  });
})();
