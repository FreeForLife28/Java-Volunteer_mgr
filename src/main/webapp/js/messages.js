/* Messaging panel shared by the organization and volunteer dashboards.
   Expects #msgForm, #msgTo, #msgKind, #msgBody, #msgList, #msgHint in the page. */
function initMessages(me) {
  const list = $('#msgList'), to = $('#msgTo');

  async function load() {
    const [contacts, msgs] = await Promise.all([api('/messages/contacts'), api('/messages')]);
    const keep = to.value;
    to.innerHTML = contacts.length
      ? contacts.map(c => `<option value="${c.id}">${esc(c.name)}</option>`).join('')
      : '<option value="">No one to message yet</option>';
    if (keep) to.value = keep;
    $('#msgForm button[type=submit]').disabled = !contacts.length;
    $('#msgHint').textContent = me.role === 'ORGANIZATION'
      ? 'You can write to volunteers who have signed up for your opportunities.'
      : 'You can write to any organization.';

    list.innerHTML = msgs.length ? msgs.map(m => {
      const sent = m.sender_id === me.id;
      return `<article class="msg ${sent ? 'sent' : ''}">
        <header><span>${sent ? 'To ' + esc(m.receiver_name) : 'From ' + esc(m.sender_name)} ${m.kind === 'FEEDBACK' ? badge('FEEDBACK') : ''}</span><span>${esc(fmtDateTime(m.created_at))}</span></header>
        <p>${esc(m.body)}</p>
        ${sent ? '' : `<button class="btn sm" data-reply="${m.sender_id}" type="button">Reply</button>`}
      </article>`;
    }).join('') : '<p class="muted">No messages yet.</p>';
  }

  list.onclick = e => {
    const b = e.target.closest('[data-reply]');
    if (b) { to.value = b.dataset.reply; $('#msgBody').focus(); }
  };

  $('#msgForm').onsubmit = async e => {
    e.preventDefault();
    const ok = await act(() => api('/messages', { method: 'POST', body: { receiverId: to.value, kind: $('#msgKind').value, body: $('#msgBody').value } }));
    if (ok) { $('#msgBody').value = ''; load(); }
  };
  return load;
}
