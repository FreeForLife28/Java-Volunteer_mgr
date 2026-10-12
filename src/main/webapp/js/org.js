let ME, opps = [], parts = [], loadMessages;

(async () => {
  ME = await guard('ORGANIZATION');
  if (!ME) return;
  loadMessages = initMessages(ME);
  bindOpps(); bindParticipation();
  initTabs(name => ({ opportunities: loadOpps, participation: loadParts, messages: () => loadMessages() })[name]());
})();

/* ---------- opportunities ---------- */
function bindOpps() {
  $('#addOpp').onclick = () => openOpp(null);
  $('#oppBody').onclick = async e => {
    const b = e.target.closest('button[data-act]');
    if (!b) return;
    const o = opps.find(x => x.id === Number(b.dataset.id));
    if (b.dataset.act === 'edit') return openOpp(o);
    if (!confirm(`Delete "${o.title}"? Volunteers signed up for it will lose their sign-up.`)) return;
    if (await act(() => api('/org/listings/' + o.id, { method: 'DELETE' }))) loadOpps();
  };
  $('#oppForm').onsubmit = async e => {
    e.preventDefault();
    const id = $('#oId').value;
    const body = { title: $('#oTitle').value, description: $('#oDesc').value, location: $('#oLoc').value,
      eventDate: $('#oDate').value, hours: $('#oHours').value, capacity: $('#oCap').value };
    const ok = await act(() => api(id ? '/org/listings/' + id : '/org/listings', { method: id ? 'PUT' : 'POST', body }));
    if (ok) { $('#oppDlg').close(); loadOpps(); }
  };
}

function openOpp(o) {
  $('#oppDlgTitle').textContent = o ? 'Edit opportunity' : 'Post an opportunity';
  $('#oId').value = o ? o.id : '';
  $('#oTitle').value = o ? o.title : '';
  $('#oDesc').value = o ? o.description : '';
  $('#oLoc').value = o ? o.location : '';
  $('#oDate').value = o ? o.event_date : '';
  $('#oDate').min = today();
  $('#oHours').value = o ? o.hours : 2;
  $('#oCap').value = o ? o.capacity : 10;
  $('#oppNote').textContent = 'New and edited opportunities may need administrator approval before volunteers can see them.';
  $('#oppDlg').showModal();
}

async function loadOpps() {
  opps = await api('/org/listings');
  $('#oppBody').innerHTML = opps.length ? opps.map(o => `<tr>
      <td><strong>${esc(o.title)}</strong><div class="muted small">${esc(o.description.slice(0, 90))}${o.description.length > 90 ? '…' : ''}</div></td>
      <td>${fmtDate(o.event_date)}</td><td>${esc(o.location)}</td><td>${fmtHours(o.hours)}</td>
      <td>${o.signups} / ${o.capacity}</td><td>${badge(o.status)}</td>
      <td class="actions"><button class="btn sm" data-act="edit" data-id="${o.id}">Edit</button>
      <button class="btn sm danger" data-act="delete" data-id="${o.id}">Delete</button></td></tr>`).join('')
    : emptyRow(7, 'You have not posted anything yet. Use "Post an opportunity" to start.');
}

/* ---------- participation ---------- */
function bindParticipation() {
  $('#partFilter').onchange = renderParts;
  $('#partBody').onchange = async e => {
    const sel = e.target.closest('select[data-id]');
    if (!sel) return;
    if (await act(() => api('/org/participation/' + sel.dataset.id, { method: 'POST', body: { status: sel.value } }))) loadParts();
  };
}

async function loadParts() {
  parts = await api('/org/participation');
  const current = $('#partFilter').value;
  const titles = [...new Map(parts.map(p => [p.listing_id, p.title])).entries()];
  $('#partFilter').innerHTML = '<option value="">All opportunities</option>' +
    titles.map(([id, t]) => `<option value="${id}">${esc(t)}</option>`).join('');
  $('#partFilter').value = current;
  renderParts();
}

function renderParts() {
  const f = $('#partFilter').value;
  const rows = parts.filter(p => !f || String(p.listing_id) === f);
  const count = st => rows.filter(p => p.status === st).length;
  const hours = rows.filter(p => p.status === 'ATTENDED').reduce((t, p) => t + p.hours, 0);
  $('#partStats').innerHTML = stat(rows.length, 'Sign-ups') + stat(count('ATTENDED'), 'Attended') +
    stat(count('NO_SHOW'), 'No-shows') + stat(count('SIGNED_UP'), 'Not yet marked', 'warn') + stat(Math.round(hours * 100) / 100, 'Hours delivered');
  $('#partBody').innerHTML = rows.length ? rows.map(p => `<tr>
      <td>${esc(p.volunteer_name)}<div class="muted small">${esc(p.volunteer_email)}</div></td>
      <td>${esc(p.title)}</td><td>${fmtDate(p.event_date)}</td><td>${fmtHours(p.hours)}</td><td>${badge(p.status)}</td>
      <td><select data-id="${p.id}" aria-label="Status for ${esc(p.volunteer_name)}" style="width:auto">
        ${['SIGNED_UP', 'ATTENDED', 'NO_SHOW'].map(s => `<option value="${s}" ${s === p.status ? 'selected' : ''}>${esc(label(s))}</option>`).join('')}
      </select></td></tr>`).join('') : emptyRow(6, 'No volunteers have signed up yet.');
}
