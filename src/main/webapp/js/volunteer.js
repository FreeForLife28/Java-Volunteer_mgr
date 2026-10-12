let ME, opps = [], loadMessages;

(async () => {
  ME = await guard('VOLUNTEER');
  if (!ME) return;
  loadMessages = initMessages(ME);
  bindOpps(); bindHours();
  initTabs(name => ({ opportunities: loadOpps, signups: loadSignups, hours: loadHours, messages: () => loadMessages() })[name]());
})();

/* ---------- opportunities ---------- */
function bindOpps() {
  $('#oppSearch').oninput = renderOpps;
  $('#oppCards').onclick = async e => {
    const b = e.target.closest('button[data-signup]');
    if (!b) return;
    b.disabled = true;
    const ok = await act(() => api('/volunteer/signups', { method: 'POST', body: { listingId: b.dataset.signup } }));
    if (ok) loadOpps(); else b.disabled = false;
  };
}

async function loadOpps() { opps = await api('/volunteer/opportunities'); renderOpps(); }

function renderOpps() {
  const q = $('#oppSearch').value.trim().toLowerCase();
  const rows = opps.filter(o => !q || [o.title, o.location, o.org_name, o.description].some(t => t.toLowerCase().includes(q)));
  $('#oppCards').innerHTML = rows.length ? rows.map(o => {
    const left = o.capacity - o.taken;
    const btn = o.mine ? '<button class="btn" disabled>Signed up</button>'
      : left <= 0 ? '<button class="btn" disabled>Full</button>'
      : `<button class="btn primary" data-signup="${o.id}">Sign up</button>`;
    return `<article class="opp">
      <h3>${esc(o.title)}</h3>
      <div class="meta"><span>${esc(o.org_name)}</span><span>${fmtDate(o.event_date)} · ${esc(o.location)}</span><span>${fmtHours(o.hours)} · ${left > 0 ? left + ' spots left' : 'No spots left'}</span></div>
      <p>${esc(o.description)}</p>
      <div class="foot">${o.mine ? badge('SIGNED_UP') : '<span></span>'}${btn}</div>
    </article>`;
  }).join('') : '<p class="muted">No open opportunities match right now. Check back soon.</p>';
}

/* ---------- my sign-ups ---------- */
async function loadSignups() {
  const rows = await api('/volunteer/signups');
  $('#signupBody').innerHTML = rows.length ? rows.map(s => `<tr>
      <td>${esc(s.title)}</td><td>${esc(s.org_name)}</td><td>${fmtDate(s.event_date)}</td><td>${esc(s.location)}</td><td>${badge(s.status)}</td>
      <td class="actions">${s.status === 'SIGNED_UP' ? `<button class="btn sm danger" data-cancel="${s.id}">Cancel</button>` : ''}</td></tr>`).join('')
    : emptyRow(6, 'You have not signed up for anything yet. Browse available opportunities to get started.');
}
$('#signupBody').onclick = async e => {
  const b = e.target.closest('button[data-cancel]');
  if (!b || !confirm('Cancel this sign-up?')) return;
  if (await act(() => api('/volunteer/signups/' + b.dataset.cancel, { method: 'DELETE' }))) loadSignups();
};

/* ---------- hours ---------- */
function bindHours() {
  $('#hDate').max = today();
  $('#hDate').value = today();
  $('#hoursForm').onsubmit = async e => {
    e.preventDefault();
    const body = { hours: $('#hHours').value, logDate: $('#hDate').value, listingId: $('#hListing').value, notes: $('#hNotes').value };
    if (await act(() => api('/volunteer/hours', { method: 'POST', body }))) {
      $('#hHours').value = ''; $('#hNotes').value = ''; loadHours();
    }
  };
  $('#hourBody').onclick = async e => {
    const b = e.target.closest('button[data-del]');
    if (!b || !confirm('Remove this entry?')) return;
    if (await act(() => api('/volunteer/hours/' + b.dataset.del, { method: 'DELETE' }))) loadHours();
  };
}

async function loadHours() {
  const [h, signups] = await Promise.all([api('/volunteer/hours'), api('/volunteer/signups')]);
  const round = n => Math.round(n * 100) / 100;
  $('#hourStats').innerHTML = stat(round(h.total), 'Total hours') + stat(round(h.verified), 'Confirmed by organizations') + stat(round(h.selfReported), 'Logged by you', 'warn');

  const keep = $('#hListing').value;
  $('#hListing').innerHTML = '<option value="">None</option>' + signups.filter(s => s.status !== 'CANCELLED')
    .map(s => `<option value="${s.listing_id}">${esc(s.title)} (${fmtDate(s.event_date)})</option>`).join('');
  $('#hListing').value = keep;

  $('#hourBody').innerHTML = h.entries.length ? h.entries.map(e => `<tr>
      <td>${fmtDate(e.log_date)}</td><td>${fmtHours(e.hours)}</td><td>${esc(e.listing_title || '—')}</td><td>${esc(e.notes || '')}</td>
      <td>${e.source === 'VERIFIED' ? badge('VERIFIED') : '<span class="badge">Self-logged</span>'}</td>
      <td class="actions">${e.source === 'SELF' ? `<button class="btn sm danger" data-del="${e.id}">Remove</button>` : ''}</td></tr>`).join('')
    : emptyRow(6, 'No hours logged yet.');
}
