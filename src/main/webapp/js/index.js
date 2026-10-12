(async () => {
  // Already signed in? Go straight to the right dashboard.
  try {
    const me = await api('/auth/me', { noRedirect: true });
    location.replace(HOME[me.role]);
    return;
  } catch (_) { /* not signed in */ }

  try {
    const s = await api('/auth/settings', { noRedirect: true });
    $$('.siteName').forEach(el => el.textContent = s.siteName);
    document.title = 'Sign in · ' + s.siteName;
    if (!s.allowRegistration) $('#tabRegister').hidden = true;
  } catch (_) {
    $('#lErr').textContent = 'Cannot reach the server. Check that Tomcat and MySQL are running.';
  }

  const showTab = login => {
    $('#loginForm').hidden = !login; $('#regForm').hidden = login;
    $('#tabLogin').classList.toggle('active', login); $('#tabRegister').classList.toggle('active', !login);
  };
  $('#tabLogin').onclick = () => showTab(true);
  $('#tabRegister').onclick = () => showTab(false);

  $('#loginForm').onsubmit = async e => {
    e.preventDefault();
    $('#lErr').textContent = '';
    try {
      const me = await api('/auth/login', { method: 'POST', noRedirect: true, body: { email: $('#lEmail').value, password: $('#lPass').value } });
      location.href = HOME[me.role];
    } catch (err) { $('#lErr').textContent = err.message; }
  };

  $('#regForm').onsubmit = async e => {
    e.preventDefault();
    $('#rErr').textContent = '';
    try {
      const r = await api('/auth/register', { method: 'POST', noRedirect: true, body: {
        name: $('#rName').value, email: $('#rEmail').value, role: $('#rRole').value, password: $('#rPass').value } });
      toast(r.message);
      $('#lEmail').value = $('#rEmail').value;
      $('#regForm').reset();
      showTab(true);
      $('#lPass').focus();
    } catch (err) { $('#rErr').textContent = err.message; }
  };
})();
