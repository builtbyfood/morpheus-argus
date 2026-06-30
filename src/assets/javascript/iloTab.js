// iLO Console tab front-end. Self-contained, no framework, no globals leaked.
(function() {
    'use strict';

    var root = document.getElementById('ilo-console-tab');
    if (!root) return;

    var serverId = root.getAttribute('data-server-id');
    var basePath = root.getAttribute('data-base-path') || '/plugin/iloConsole';

    function $(sel, ctx) { return (ctx || root).querySelector(sel); }
    function $$(sel, ctx) { return Array.prototype.slice.call((ctx || root).querySelectorAll(sel)); }

    function setText(field, value) {
        var el = $('[data-field="' + field + '"]');
        if (el) el.textContent = value == null || value === '' ? '\u2014' : String(value);
    }

    function showError(msg) {
        var el = $('[data-field="error"]');
        if (!el) return;
        if (msg) {
            el.textContent = msg;
            el.hidden = false;
        } else {
            el.textContent = '';
            el.hidden = true;
        }
    }

    function flashMsg(role, msg, ok) {
        var el = $('[data-role="' + role + '"]');
        if (!el) return;
        el.textContent = msg || '';
        el.className = 'ilo-status-msg ' + (ok ? 'ok' : (msg ? 'err' : ''));
        if (msg) {
            setTimeout(function() {
                if (el.textContent === msg) {
                    el.textContent = '';
                    el.className = 'ilo-status-msg';
                }
            }, 5000);
        }
    }

    // ---- Status fetch ----

    function renderStatus(s) {
        if (!s || !s.success) {
            showError(s && s.error ? s.error : 'iLO status unavailable');
            return;
        }
        showError(null);
        setText('powerState', s.powerState);
        setText('health', s.health);
        setText('ilo', (s.iloModel || 'iLO') + (s.iloFirmware ? ' \u00B7 ' + s.iloFirmware : ''));
        setText('biosVersion', s.biosVersion);
        setText('cpu', s.cpuCount && s.cpuModel ? (s.cpuCount + '\u00D7 ' + s.cpuModel) : (s.cpuModel || s.cpuCount));
        setText('memory', s.memoryGiB != null ? (s.memoryGiB + ' GiB') : null);

        renderList('temperatures', (s.temperatures || []), function(t) { return t.name + ': ' + t.c + '\u00B0C'; });
        renderList('fans', (s.fans || []), function(f) { return f.name + ': ' + f.pct + '%'; });
    }

    function renderList(field, items, formatter) {
        var ul = $('[data-field="' + field + '"]');
        if (!ul) return;
        ul.innerHTML = '';
        if (!items.length) {
            var li = document.createElement('li');
            li.className = 'placeholder';
            li.textContent = 'none reported';
            ul.appendChild(li);
            return;
        }
        items.forEach(function(item) {
            var li = document.createElement('li');
            li.textContent = formatter(item);
            ul.appendChild(li);
        });
    }

    function loadStatus() {
        fetch(basePath + '/status?serverId=' + encodeURIComponent(serverId), {
            credentials: 'same-origin', headers: { 'Accept': 'application/json' }
        })
        .then(function(r) { return r.json(); })
        .then(renderStatus)
        .catch(function(err) { showError(String(err)); });
    }

    // ---- Config form ----

    var configForm = $('#ilo-config-form');
    if (configForm) {
        configForm.addEventListener('submit', function(ev) {
            ev.preventDefault();
            var fd = new FormData(configForm);
            var body = {
                serverId: serverId,
                iloHost: fd.get('iloHost'),
                credentialId: parseInt(fd.get('credentialId'), 10),
                verifySsl: fd.get('verifySsl') === 'on'
            };
            fetch(basePath + '/config', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
                body: JSON.stringify(body)
            })
            .then(function(r) { return r.json(); })
            .then(function(res) {
                if (res && res.success) {
                    flashMsg('config-msg', 'Saved. Reloading\u2026', true);
                    setTimeout(function() { location.reload(); }, 800);
                } else {
                    flashMsg('config-msg', (res && res.error) || 'Save failed', false);
                }
            })
            .catch(function(err) { flashMsg('config-msg', String(err), false); });
        });
    }

    // ---- Power actions ----

    $$('[data-power]').forEach(function(btn) {
        btn.addEventListener('click', function() {
            var resetType = btn.getAttribute('data-power');
            var verb = btn.textContent.trim();
            if (!confirm(verb + ' \u2014 this affects the running OS immediately. Continue?')) return;

            btn.disabled = true;
            flashMsg('power-msg', 'Sending ' + resetType + '\u2026', true);
            fetch(basePath + '/power', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
                body: JSON.stringify({ serverId: serverId, resetType: resetType })
            })
            .then(function(r) { return r.json(); })
            .then(function(res) {
                btn.disabled = false;
                if (res && res.success) {
                    flashMsg('power-msg', verb + ' sent.', true);
                    setTimeout(loadStatus, 1500);
                } else {
                    flashMsg('power-msg', (res && res.error) || (verb + ' failed'), false);
                }
            })
            .catch(function(err) {
                btn.disabled = false;
                flashMsg('power-msg', String(err), false);
            });
        });
    });

    // ---- Refresh ----

    var refreshBtn = $('[data-action="refresh"]');
    if (refreshBtn) refreshBtn.addEventListener('click', loadStatus);

    // ---- Launch ----

    var launchBtn = $('[data-action="launch"]');
    if (launchBtn) {
        launchBtn.addEventListener('click', function() {
            launchBtn.disabled = true;
            flashMsg('launch-msg', 'Preparing console\u2026', true);
            fetch(basePath + '/launch/init', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
                body: JSON.stringify({ serverId: serverId })
            })
            .then(function(r) { return r.json(); })
            .then(function(res) {
                launchBtn.disabled = false;
                if (res && res.success && res.launchUrl) {
                    // Open in a new window. The nonce in the URL is single-use,
                    // so even if it leaks via history it can't be replayed.
                    window.open(res.launchUrl, 'ilo-console-' + serverId, 'width=1280,height=900,noopener=no');
                    flashMsg('launch-msg', 'Console opened in new window.', true);
                } else {
                    flashMsg('launch-msg', (res && res.error) || 'Launch failed', false);
                }
            })
            .catch(function(err) {
                launchBtn.disabled = false;
                flashMsg('launch-msg', String(err), false);
            });
        });
    }

    // Auto-load status on mount if configured.
    if (root.querySelector('.ilo-status:not([hidden])')) {
        loadStatus();
    }
})();
