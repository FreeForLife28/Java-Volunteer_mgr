let ME, users = [], listings = [];

(async () => {
  ME = await guard('ADMIN');
  if (!ME) return;
  bindUsers(); bindListings(); bindSettings();
  initTabs(name => ({ overview: loadOverview, users: loadUsers, listings: loadListings, settings: loadSettings })[name]());
})();

/* ---------- overview ---------- */
async function loadOverview() {
  const s = await api('/admin/stats');
  const n = (arr, key, val) => ((arr.find(x => x[key] === val) || {}).count || 0);
  $('#stats').innerHTML =
    stat(n(s.usersByRole, 'role', 'VOLUNTEER'), 'Volunteers') +
    stat(n(s.usersByRole, 'role', 'ORGANIZATION'), 'Organizations') +
    stat(n(s.listingsByStatus, 'status', 'APPROVED'), 'Live listings') +
    stat(n(s.listingsByStatus, 'status', 'PENDING'), 'Pending approvals', 'warn') +
    stat(s.signups, 'Active sign-ups') +
    stat(s.attended, 'Confirmed attendances') +
    stat(Math.round(s.hours * 100) / 100, 'Hours volunteered');

  $('#pendingBody').innerHTML = s.pending.length ? s.pending.map(l => `<tr>
      <td>${esc(l.title)}</td><td>${esc(l.org_name)}</td><td>${fmtDate(l.event_date)}</td><td>${esc(l.location)}</td>
      <td class="actions"><button class="btn sm primary" data-st="APPROVED" data-id="${l.id}">Approve</button>
      <button class="btn sm danger" data-st="REJECTED" data-id="${l.id}">Reject</button></td></tr>`).join('')
    : emptyRow(5, 'Nothing is waiting for approval.');

  $('#topBody').innerHTML = s.top.length ? s.top.map(t =>
    `<tr><td>${esc(t.title)}</td><td>${esc(t.org_name)}</td><td>${t.signups}</td></tr>`).join('')
    : emptyRow(3, 'No approved opportunities yet.');
}

/* ---------- listings ---------- */
function bindListings() {
  const handler = async e => {
    const b = e.target.closest('button[data-st]');
    if (!b) return;
    const ok = await act(() => api(`/admin/listings/${b.dataset.id}/status`, { method: 'POST', body: { status: b.dataset.st } }));
    if (ok) { loadOverview(); loadListings(); }
  };
  $('#pendingBody').onclick = handler;
  $('#listingsBody').onclick = handler;
  $('#listFilter').onchange = renderListings;
}

async function loadListings() { listings = await api('/admin/listings'); renderListings(); }

function renderListings() {
  const f = $('#listFilter').value;
  const rows = listings.filter(l => !f || l.status === f);
  $('#listingsBody').innerHTML = rows.length ? rows.map(l => `<tr>
      <td><strong>${esc(l.title)}</strong><div class="muted small">${esc(l.description.slice(0, 90))}${l.description.length > 90 ? '…' : ''}</div></td>
      <td>${esc(l.org_name)}</td><td>${fmtDate(l.event_date)}</td><td>${esc(l.location)}</td>
      <td>${l.signups} / ${l.capacity}</td><td>${badge(l.status)}</td>
      <td class="actions">
        ${l.status !== 'APPROVED' ? `<button class="btn sm primary" data-st="APPROVED" data-id="${l.id}">Approve</button>` : ''}
        ${l.status !== 'REJECTED' ? `<button class="btn sm danger" data-st="REJECTED" data-id="${l.id}">Reject</button>` : ''}
      </td></tr>`).join('') : emptyRow(7, 'No listings match.');
}

/* ---------- users ---------- */
function bindUsers() {
  $('#addUser').onclick = () => openUser(null);
  $('#usersBody').onclick = async e => {
    const b = e.target.closest('button[data-act]');
    if (!b) return;
    const u = users.find(x => x.id === Number(b.dataset.id));
    if (b.dataset.act === 'edit') return openUser(u);
    if (!confirm(`Delete ${u.name}? Their listings, sign-ups and messages will be removed too.`)) return;
    if (await act(() => api('/admin/users/' + u.id, { method: 'DELETE' }))) loadUsers();
  };
  $('#userForm').onsubmit = async e => {
    e.preventDefault();
    const id = $('#uId').value;
    const body = { name: $('#uName').value, email: $('#uEmail').value, role: $('#uRole').value, status: $('#uStatus').value, password: $('#uPass').value };
    const ok = await act(() => api(id ? '/admin/users/' + id : '/admin/users', { method: id ? 'PUT' : 'POST', body }));
    if (ok) { $('#userDlg').close(); loadUsers(); }
  };
}

function openUser(u) {
  $('#userDlgTitle').textContent = u ? 'Edit user' : 'Add user';
  $('#uId').value = u ? u.id : '';
  $('#uName').value = u ? u.name : '';
  $('#uEmail').value = u ? u.email : '';
  $('#uRole').value = u ? u.role : 'VOLUNTEER';
  $('#uStatus').value = u ? u.status : 'ACTIVE';
  $('#uPass').value = '';
  $('#uPass').required = !u;
  $('#uPassHint').textContent = u ? 'Leave blank to keep the current password' : 'At least 6 characters';
  $('#userDlg').showModal();
}

async function loadUsers() {
  users = await api('/admin/users');
  $('#usersBody').innerHTML = users.length ? users.map(u => `<tr>
      <td>${esc(u.name)}</td><td>${esc(u.email)}</td><td>${badge(u.role)}</td><td>${badge(u.status)}</td><td>${fmtDate(u.created_at)}</td>
      <td class="actions"><button class="btn sm" data-act="edit" data-id="${u.id}">Edit</button>
      ${u.id === ME.id ? '' : `<button class="btn sm danger" data-act="delete" data-id="${u.id}">Delete</button>`}</td></tr>`).join('')
    : emptyRow(6, 'No users yet.');
}

/* ---------- settings ---------- */
const BOOL_KEYS = ['allow_registration', 'require_listing_approval'];
const TEXT_KEYS = ['site_name', 'contact_email', 'max_active_signups'];

async function loadSettings() {
  const s = await api('/admin/settings');
  TEXT_KEYS.forEach(k => $('#s_' + k).value = s[k] ?? '');
  BOOL_KEYS.forEach(k => $('#s_' + k).checked = s[k] === 'true');
}

function bindSettings() {
  $('#settingsForm').onsubmit = async e => {
    e.preventDefault();
    const body = {};
    TEXT_KEYS.forEach(k => body[k] = $('#s_' + k).value);
    BOOL_KEYS.forEach(k => body[k] = String($('#s_' + k).checked));
    if (await act(() => api('/admin/settings', { method: 'PUT', body }))) {
      $$('.siteName').forEach(el => el.textContent = body.site_name);
    }
  };
}
