/* Shared helpers used by every page */
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const HOME = { ADMIN: 'admin.html', ORGANIZATION: 'org.html', VOLUNTEER: 'volunteer.html' };

async function api(path, { method = 'GET', body, noRedirect = false } = {}) {
  const headers = { 'X-Requested-With': 'fetch' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch('api' + path, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined, credentials: 'same-origin' });
  let data = null;
  try { data = await res.json(); } catch (_) { /* empty body */ }
  if (!res.ok) {
    if (res.status === 401 && !noRedirect) location.href = 'index.html';
    throw new Error((data && data.error) || `Request failed (${res.status})`);
  }
  return data;
}

function toast(msg, type = 'ok') {
  let box = $('#toasts');
  if (!box) { box = document.createElement('div'); box.id = 'toasts'; box.setAttribute('role', 'status'); document.body.appendChild(box); }
  const t = document.createElement('div');
  t.className = 'toast' + (type === 'error' ? ' error' : '');
  t.textContent = msg;
  box.appendChild(t);
  setTimeout(() => t.remove(), 4500);
}

/** Runs an async action and shows its message (or error) as a toast. Returns true on success. */
async function act(fn) {
  try {
    const r = await fn();
    if (r && r.message) toast(r.message);
    return true;
  } catch (e) {
    toast(e.message, 'error');
    return false;
  }
}

const parseDate = d => new Date(String(d).length <= 10 ? d + 'T00:00:00' : d);
const fmtDate = d => d ? parseDate(d).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' }) : '';
const fmtDateTime = d => d ? parseDate(d).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' }) : '';
const fmtHours = h => (Math.round(Number(h) * 100) / 100) + ' h';
const today = () => { const d = new Date(); return new Date(d - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10); };
const label = s => { const t = String(s).replace(/_/g, ' ').toLowerCase(); return t.charAt(0).toUpperCase() + t.slice(1); };
const badge = s => `<span class="badge ${esc(String(s).toLowerCase())}">${esc(label(s))}</span>`;
const emptyRow = (cols, text) => `<tr><td class="empty" colspan="${cols}">${esc(text)}</td></tr>`;
const stat = (value, text, cls = '') => `<div class="stat ${cls}"><strong>${esc(value)}</strong><span>${esc(text)}</span></div>`;

/** Checks the session and role; fills the sidebar. Returns the user or null. */
async function guard(role) {
  try {
    const me = await api('/auth/me');
    if (me.role !== role) { location.href = HOME[me.role] || 'index.html'; return null; }
    $('#meName').textContent = me.name;
    $('#logout').onclick = async () => { await act(() => api('/auth/logout', { method: 'POST' })); location.href = 'index.html'; };
    api('/auth/settings', { noRedirect: true }).then(s => {
      $$('.siteName').forEach(el => el.textContent = s.siteName);
      document.title = document.title.replace('VolunteerHub', s.siteName);
    }).catch(() => {});
    return me;
  } catch (e) { return null; }
}

function initTabs(onShow) {
  const names = $$('#nav button').map(b => b.dataset.tab);
  const show = name => {
    $$('.tab').forEach(t => t.hidden = t.id !== 'tab-' + name);
    $$('#nav button').forEach(b => b.classList.toggle('active', b.dataset.tab === name));
    history.replaceState(null, '', '#' + name);
    onShow(name);
  };
  $$('#nav button').forEach(b => b.onclick = () => show(b.dataset.tab));
  const start = location.hash.slice(1);
  show(names.includes(start) ? start : names[0]);
}

document.addEventListener('click', e => {
  const c = e.target.closest('[data-close]');
  if (c) c.closest('dialog').close();
});
