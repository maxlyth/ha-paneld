// Shared explicit repair interaction for Information, Configure, and Install power-safety banners.
(function () {
  function message(body) {
    if (body && body.error === 'approval-required') {
      return body.message || 'Approve this request physically on the panel, then tap Repair power safety again.';
    }
    if (body && body.status === 'repaired') return 'Power safety repaired and verified. Refreshing…';
    if (body && body.status === 'partial') return 'Repair was partial; refreshing the observed warning…';
    return body && body.message || 'Power safety repair failed; no reboot was attempted.';
  }

  document.addEventListener('submit', function (event) {
    var form = event.target;
    if (!form || !form.matches || !form.matches('form[data-power-safety-repair]')) return;
    event.preventDefault();
    var button = form.querySelector('button[type="submit"]');
    var result = form.querySelector('.power-safety-repair-result');
    if (button) button.disabled = true;
    if (result) result.textContent = 'Applying and verifying…';
    fetch(form.action, { method: 'POST', headers: { 'Accept': 'application/json' } })
      .then(function (response) {
        return response.text().then(function (text) {
          var body = {};
          try { body = text ? JSON.parse(text) : {}; } catch (_) { body = { message: text }; }
          if (result) result.textContent = message(body);
          if (response.status === 202 && body.error === 'approval-required') return;
          if (!response.ok || (body.status !== 'repaired' && body.status !== 'partial')) {
            if (button) button.disabled = false;
            return;
          }
          setTimeout(function () { location.reload(); }, 1200);
        });
      })
      .catch(function () {
        if (result) result.textContent = 'Could not reach the repair endpoint; no repair was confirmed.';
        if (button) button.disabled = false;
      })
      .finally(function () {
        if (button && result && result.textContent.indexOf('Refreshing') < 0 &&
            result.textContent.indexOf('refreshing') < 0) button.disabled = false;
      });
  });
})();
