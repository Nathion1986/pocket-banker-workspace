/* App protocol v0.4. No token baked into the APK; financial authority stays on the server. */
(() => {
  const $ = s => document.querySelector(s), chat = $('#chatstream');
  const offlineHandle = () => info('请先登录，消息尚未提交。');
  const tools = document.createElement('div'); tools.className = 'channel-tools';
  tools.innerHTML = '<span id="channel-status">未登录</span><button id="channel-login" type="button">账号与安全</button><button id="channel-link" type="button">连接 Telegram</button><button id="channel-unlink" type="button">解绑</button>';
  const config = document.createElement('div'); config.className = 'channel-code'; config.hidden = true;
  config.innerHTML = '<label>API 地址 <input id="api-address" type="url" autocomplete="off"></label><label>账号 <input id="api-username" autocomplete="username"></label><label>密码 <input id="api-password" type="password" autocomplete="current-password" maxlength="128"></label><label>验证器动态码（已绑定时填写）<input id="api-totp" inputmode="numeric" maxlength="6" autocomplete="one-time-code"></label><button id="api-account-login" type="button">登录</button><button id="api-logout" type="button">退出账号</button><details><summary>首次设置账号</summary><label>本机一次性初始化码 <input id="api-setup-key" type="password" autocomplete="off"></label><button id="api-setup" type="button">创建账号（密码至少12位）</button></details><details><summary>绑定验证器</summary><button id="api-enroll" type="button">用当前密码开始绑定</button><p id="api-enrollment"></p><button id="api-enroll-confirm" type="button">确认动态码</button><p id="api-recovery"></p></details><details><summary>改密或恢复验证器</summary><label>新密码 <input id="api-new-password" type="password" autocomplete="new-password" maxlength="128"></label><button id="api-change-password" type="button">验证密码和动态码后改密</button><label>一次性恢复码 <input id="api-recovery-code" type="password" autocomplete="off"></label><button id="api-recover" type="button">恢复后重新登录和绑定</button></details><details><summary>本机协议测试</summary><label>专用测试 Token（仅回环地址）<input id="api-token" type="password" autocomplete="off"></label><button id="api-connect" type="button">本地测试登录</button></details><p>账户和预测来自独立模拟银行；不连接真实银行。密码和动态码由服务器验证。</p>';
  const code = document.createElement('div'); code.className = 'channel-code'; code.hidden = true;
  const composerHost = $('.composer') || $('.chat-input');
  if (composerHost) composerHost.append(tools, config, code);
  let currentUser = '';
  let base = '', token = '', online = false, seen = 0, timer, generation = 0, pendingMessage = null;
  let accountMode = false, refreshToken = '', refreshFlight = null, loginChallenge = '', loginContext = '';
  let preserveCollector = false;
  let session = localStorage.getItem('pocket-demo-session');
  if (!session) { session = 'demo_' + crypto.randomUUID().replaceAll('-', ''); localStorage.setItem('pocket-demo-session', session); }
  window.PocketSession = { request: api, status: () => ({ online, base, userId: currentUser, preserveCollector }) };
  window.PocketOpenProposal = async id => {
    try { showProposal(await api('/api/proposals/' + encodeURIComponent(id))); }
    catch (e) { info('任务读取失败：' + e.message); }
  };
  function notifyAuth() { if (typeof Event !== 'undefined') window.dispatchEvent?.(new Event('pocket-auth')); }
  const proposals = new Map();
  function info(text, type = 'bot') {
    const e = document.createElement('div');
    // 系统性提示（网络/错误/状态）用居中小字气泡，其余按 bot/user 左右气泡
    const isNet = type === 'bot' && /^(消息|任务|执行中|✓|✅|⚠️|❌|账号|会话|服务器|上一条|请先登录)/.test(text);
    e.className = 'msg ' + (isNet ? 'net' : type);
    e.dataset.network = 'true';
    e.textContent = text;
    const stamp = document.createElement('span');
    stamp.className = 'stamp';
    const now = new Date();
    stamp.textContent = String(now.getHours()).padStart(2,'0') + ':' + String(now.getMinutes()).padStart(2,'0');
    e.append(stamp);
    chat.append(e); chat.scrollTop = chat.scrollHeight;
  }
  function saveSession(data) {
    token = data.accessToken; refreshToken = data.refreshToken; currentUser = data.userId; accountMode = true;
    if (window.PocketAccount && !window.PocketAccount.save(JSON.stringify({ apiBase: base, userId: currentUser, refreshToken, expiresAt: data.refreshExpiresAt })))
      info('账号已登录，但设备未能保存续期凭据；关闭后需重新登录。');
  }
  async function publicAuth(path, data) {
    const r = await fetch(base + '/api/auth/' + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data), cache: 'no-store' });
    const result = await r.json();
    if (!r.ok) throw Error(result.error || 'HTTP ' + r.status);
    return result;
  }
  async function renew() {
    if (refreshFlight) return refreshFlight;
    const epoch = generation;
    refreshFlight = (async () => {
      const data = await publicAuth('refresh', { refreshToken });
      if (epoch !== generation) throw Error('session_changed');
      if (currentUser && data.userId !== currentUser) throw Error('session_identity_changed');
      saveSession(data);
    })();
    try { await refreshFlight; }
    catch (e) { if (epoch === generation) { window.PocketAccount?.clear(base); offline(true); } throw e; }
    finally { refreshFlight = null; }
  }
  async function api(path, method = 'GET', data, key, retry = true) {
    const epoch = generation;
    const requestToken = token;
    const headers = { Authorization: 'Bearer ' + token, 'X-Demo-Session': session };
    if (data !== undefined) headers['Content-Type'] = 'application/json';
    if (key) headers['Idempotency-Key'] = key;
    const r = await fetch(base + path, { method, headers, body: data === undefined ? undefined : JSON.stringify(data), cache: 'no-store' });
    const result = r.status === 204 ? {} : await r.json();
    if (epoch !== generation) throw Error('session_changed');
    if (r.status === 401 && accountMode && refreshToken && retry) {
      if (token === requestToken) await renew();
      return api(path, method, data, key, false);
    }
    if (!r.ok) throw Error(result.error || 'HTTP ' + r.status);
    return result;
  }
  function resultText(p) { return p.error ? '请求未获通过：' + p.error : (p.text || '请查询任务实际状态。'); }
  function showProposal(p) {
    if (!p.id) return;
    const previous = proposals.get(p.id);
    if (previous && Number(previous.dataset.version) >= Number(p.version)) return;
    previous?.remove();
    const box = document.createElement('div'); box.className = 'approval' + (p.risk === 'red' ? ' red' : ''); box.dataset.proposal = p.id; box.dataset.version = String(p.version); box.dataset.network = 'true';
    const title = document.createElement('h3'); title.textContent = '请核对任务 · ' + (p.actionTitle || '操作');
    const detail = document.createElement('p'); detail.textContent = '收款人/账户：' + p.payee + '\n金额：' + p.amount + ' ' + p.currency + '\n备注：' + p.memo + '\n风险：' + p.risk + '，版本：' + p.version;
    detail.style.whiteSpace = 'pre-wrap';
    const nodeDetail = document.createElement('pre'); nodeDetail.textContent = JSON.stringify(p.nodes || [], null, 2); nodeDetail.style.whiteSpace = 'pre-wrap';
    const reason = document.createElement('p'); reason.textContent = p.reason;
    const actions = document.createElement('div'); actions.className = 'approval-actions';
    const yes = document.createElement('button'); yes.className = 'primary'; yes.textContent = p.risk === 'red' ? (accountMode ? '密码与验证器核验' : '本地双因子验证') : '确认模拟任务';
    const no = document.createElement('button'); no.className = 'secondary'; no.textContent = '取消'; actions.append(yes, no);
    const factors = document.createElement('div'); factors.hidden = true;
    const otp = document.createElement('input'), factor = document.createElement('input'), verify = document.createElement('button');
    otp.type = factor.type = 'password'; otp.placeholder = accountMode ? '账号密码' : '本地测试验证码'; factor.placeholder = accountMode ? '独立验证器6位动态码' : '本地测试第二因子'; verify.textContent = '提交验证';
    factors.append(accountMode ? '服务端核验密码和动态码；仅授权上述任务版本，2分钟有效。' : '仅本地测试；不是银行短信或人脸验证。', otp, factor, verify);
    box.append(title, detail, reason, nodeDetail, actions, factors); chat.append(box); proposals.set(p.id, box);
    const keys = { confirm: crypto.randomUUID(), cancel: crypto.randomUUID() }; let verifyAttempt = null, challengeId = '';
    async function decide(choice) {
      if (choice === 'confirm' && p.risk === 'red') {
        try {
          if (accountMode) {
            const challenge = await api('/api/proposals/' + encodeURIComponent(p.id) + '/auth-challenge', 'POST', { version: p.version });
            challengeId = challenge.challengeId; verifyAttempt = null;
          }
          factors.hidden = false;
        } catch (e) { info('尚未开始验证：' + e.message); }
        return;
      }
      yes.disabled = no.disabled = true;
      try { const r = await api('/api/proposals/' + encodeURIComponent(p.id) + '/decision', 'POST', { decision: choice, version: p.version }, keys[choice]); box.remove(); proposals.delete(p.id); info(resultText(r)); }
      catch (e) { yes.disabled = no.disabled = false; info('状态未确认：' + e.message + '；重试将复用同一次决定的编号。'); }
    }
    yes.onclick = () => decide('confirm'); no.onclick = () => decide('cancel');
    verify.onclick = async () => {
      const body = accountMode ? { version: p.version, challengeId, password: otp.value, totp: factor.value } : { version: p.version, code: otp.value, secondFactor: factor.value }, signature = JSON.stringify(body);
      if (!verifyAttempt || verifyAttempt.signature !== signature) verifyAttempt = { signature, key: crypto.randomUUID() };
      verify.disabled = true;
      try { const r = await api('/api/proposals/' + encodeURIComponent(p.id) + (accountMode ? '/auth-verify' : '/verify'), 'POST', body, verifyAttempt.key); info(resultText(r)); if (!r.error) { otp.value = factor.value = ''; verifyAttempt = null; box.remove(); proposals.delete(p.id); } }
      catch (e) { info('验证状态未确认：' + e.message); }
      finally { verify.disabled = false; }
    };
  }
  async function send(text) {
    text = text.trim(); if (!text) return;
    if (pendingMessage && pendingMessage.text !== text) { info('上一条消息结果未知，请先重试相同消息或核对事件流。'); return; }
    if (!pendingMessage) pendingMessage = { text, clientMessageId: crypto.randomUUID() };
    const runState = $('#run-state'); if (runState) runState.textContent = '提交中 · 尚未确认执行';
    if (!online && !accountMode) { info('请先登录：点上方「账号与安全」。', 'bot'); pendingMessage = null; return; }
    try { const r = await api('/api/messages', 'POST', pendingMessage); pendingMessage = null; if (r.kind === 'proposal') showProposal(r); if (runState) runState.textContent = '消息已受理 · 执行状态以回执为准'; }
    catch (e) { info('消息送达状态未知：' + e.message + '；相同消息重试会复用原编号。'); if (runState) runState.textContent = '网络异常 · 请核对事件流'; }
  }
  window.PocketSend = send;
  async function poll(ownGeneration) {
    if (!online || ownGeneration !== generation) return;
    let more = false;
    try {
      const data = await api('/api/events?since=' + seen + '&limit=100');
      if (!online || ownGeneration !== generation) return;
      for (const e of data.events) {
        if (!Number.isSafeInteger(e.id) || e.id <= seen) throw Error('invalid_event_cursor');
        const p = e.payload || {};
        if (e.kind === 'user_message') info((e.channel === 'telegram' ? 'IM · ' : '') + p.text, 'user');
        if (e.kind === 'proposal') showProposal(p);
        if (e.kind === 'reply') info(resultText(p));
        if (e.kind === 'decision' || e.kind === 'task_state') {
          if (p.kind === 'proposal') showProposal(p);
          else {
            const st = String(p.state || '');
            const FINAL = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'COMPENSATED', 'MANUAL_REQUIRED', 'EXPIRED', 'REJECTED'];
            if (FINAL.includes(st)) { // 终态才进聊天区，避免 RUNNING 刷屏
              proposals.get(p.id)?.remove(); proposals.delete(p.id);
              const mark = st === 'SUCCEEDED' ? '✅ ' : st === 'MANUAL_REQUIRED' ? '⚠️ ' : '❌ ';
              info(mark + resultText(p));
            } else { // 中间态只更新输入框上方小字
              const rs = document.getElementById('run-state');
              if (rs) rs.textContent = '执行中 · ' + (p.actionTitle || '任务') + '（' + st.toLowerCase() + '）';
            }
          }
        }
        if (e.kind === 'decision' || e.kind === 'task_state') {
          if (typeof Event !== 'undefined') window.dispatchEvent?.(new Event('pocket-data'));
        }
        if (e.kind === 'linked') $('#channel-status').textContent = 'Telegram 已绑定 · 本地合成环境';
        if (e.kind === 'unlinked') $('#channel-status').textContent = 'Telegram 已解绑';
        seen = e.id;
      }
      more = data.hasMore === true;
    } catch (e) { $('#channel-status').textContent = '增量同步未完成 · ' + e.message; }
    if (online && ownGeneration === generation) timer = setTimeout(() => poll(ownGeneration), more ? 0 : 3000);
  }
  async function configure() {
    online = false; generation++; clearTimeout(timer);
    base = ($('#api-address').value || window.POCKET_API_BASE || location.origin).replace(/\/$/, ''); token = $('#api-token').value;
    accountMode = false; refreshToken = '';
    const local = /^http:\/\/(localhost|127\.0\.0\.1|10\.0\.2\.2)(:\d+)?$/.test(base);
    if (!local || token.length < 32) { info('测试 Token 仅允许连接本机回环地址。公网请使用正式账号登录。'); return; }
    try {
      await connected();
    } catch (e) { window.handle = offlineHandle; info('登录失败：' + e.message); }
  }
  async function connected() {
    const status = await api('/api/link-status'); online = true; preserveCollector = false; currentUser = status.userId; seen = 0; pendingMessage = null;
    chat.querySelectorAll('[data-network]').forEach(e => e.remove()); proposals.clear();
    $('#channel-status').textContent = status.userId + ' · 独立模拟银行 · Telegram ' + (status.linked ? '已绑定' : '未绑定');
    window.handle = send; config.hidden = true; notifyAuth(); poll(generation);
  }
  function offline(keepCollector = false) {
    preserveCollector = keepCollector;
    online = false; generation++; clearTimeout(timer); token = refreshToken = ''; accountMode = false; currentUser = ''; loginChallenge = ''; pendingMessage = null;
    notifyAuth(); seen = 0; chat.querySelectorAll('[data-network]').forEach(e => e.remove()); proposals.clear();
    window.handle = offlineHandle; $('#channel-status').textContent = '已退出 · 请登录';
  }
  function selectBase() {
    const selected = ($('#api-address').value || window.POCKET_API_BASE || location.origin).replace(/\/$/, '');
    if (!/^https:\/\/[A-Za-z0-9.-]+(?::\d+)?$/.test(selected) && !/^http:\/\/(localhost|127\.0\.0\.1|10\.0\.2\.2)(:\d+)?$/.test(selected))
      throw Error('invalid_api_origin');
    if (base !== selected) loginChallenge = '';
    base = selected;
  }
  async function accountLogin(setup = false) {
    $('#api-account-login').disabled = $('#api-setup').disabled = true;
    try {
      selectBase(); online = false; generation++; clearTimeout(timer); window.handle = offlineHandle;
      token = refreshToken = ''; accountMode = false; currentUser = ''; preserveCollector = true; notifyAuth();
      const context = base + '\n' + $('#api-username').value;
      if (context !== loginContext) loginChallenge = '';
      loginContext = context;
      const credentials = { username: $('#api-username').value, password: $('#api-password').value };
      const data = setup
        ? await publicAuth('setup', { ...credentials, setupKey: $('#api-setup-key').value })
        : loginChallenge
          ? await publicAuth('login/mfa', { challengeId: loginChallenge, totp: $('#api-totp').value })
          : await publicAuth('login', credentials);
      if (data.mfaRequired) { loginChallenge = data.challengeId; info('请输入验证器动态码，再点登录；此时尚未登录。'); return; }
      saveSession(data); loginChallenge = ''; $('#api-password').value = $('#api-totp').value = $('#api-setup-key').value = '';
      config.hidden = true;
      info('✓ 登录成功，欢迎回来，' + data.userId + '。');
      await connected();
    } catch (e) { loginChallenge = ''; info('账号登录失败：' + e.message + '。请重新输入密码开始登录。'); }
    finally { $('#api-account-login').disabled = $('#api-setup').disabled = false; }
  }
  $('#channel-login').onclick = () => { config.hidden = !config.hidden; $('#api-address').value ||= window.POCKET_API_BASE || location.origin; };
  $('#api-connect').onclick = configure;
  $('#api-account-login').onclick = () => accountLogin();
  $('#api-setup').onclick = () => accountLogin(true);
  $('#api-logout').onclick = async () => {
    try { if (accountMode) await api('/api/auth/logout', 'POST', {}, undefined, false); }
    catch (e) { info('服务器退出未确认：' + e.message + '；恢复连接后需重新退出该会话。'); return; }
    window.PocketAccount?.clear(base); offline(); $('#api-token').value = $('#api-password').value = $('#api-totp').value = ''; $('#api-enrollment').textContent = $('#api-recovery').textContent = '';
  };
  $('#api-enroll').onclick = async () => {
    try {
      const data = await api('/api/auth/mfa/enroll', 'POST', { password: $('#api-password').value });
      $('#api-enrollment').textContent = '在独立验证器中添加：PocketBanker，密钥 ' + data.secret + '，6位/30秒。5分钟内填写动态码并确认；请勿分享密钥。';
      $('#api-password').value = '';
    } catch (e) { info('绑定尚未完成：' + e.message); }
  };
  $('#api-enroll-confirm').onclick = async () => {
    try {
      const data = await api('/api/auth/mfa/confirm', 'POST', { totp: $('#api-totp').value });
      $('#api-enrollment').textContent = '验证器已绑定。';
      $('#api-recovery').textContent = '恢复码仅显示本次，请离线保存：\n' + data.recoveryCodes.join('\n');
      $('#api-totp').value = '';
    } catch (e) { info('绑定尚未完成：' + e.message); }
  };
  $('#api-change-password').onclick = async () => {
    try {
      await api('/api/auth/password/change', 'POST', { password: $('#api-password').value, newPassword: $('#api-new-password').value, totp: $('#api-totp').value });
      window.PocketAccount?.clear(base); offline(); info('密码已更改，旧会话已撤销，请重新登录。');
    } catch (e) { info('改密未完成：' + e.message); }
    finally { $('#api-password').value = $('#api-new-password').value = $('#api-totp').value = ''; }
  };
  $('#api-recover').onclick = async () => {
    try {
      selectBase();
      await publicAuth('mfa/recover', { username: $('#api-username').value, password: $('#api-password').value, recoveryCode: $('#api-recovery-code').value });
      window.PocketAccount?.clear(base); offline(); info('旧验证器及会话已撤销。请重新登录，再绑定验证器。');
    } catch (e) { info('恢复未完成：' + e.message); }
    finally { $('#api-password').value = $('#api-recovery-code').value = ''; }
  };
  $('#channel-link').onclick = async () => { code.hidden = false; if (!online) { code.textContent = '请先登录；Telegram还需本人账号与Bot接通。'; return; } try { const r = await api('/api/link-code', 'POST', {}); code.textContent = '在已配置Bot的 Telegram 私聊发送：' + r.command + '\n单次绑定码，5 分钟有效。当前本人Telegram尚未接通。'; } catch (e) { code.textContent = e.message; } };
  $('#channel-unlink').onclick = async () => { if (!online) return; try { await api('/api/link', 'DELETE'); code.hidden = true; } catch (e) { info(e.message); } };
  window.handle = offlineHandle;
  let restored = false;
  window.addEventListener('pocket-config', async () => {
    $('#api-address').value = window.POCKET_API_BASE || location.origin;
    if (restored || online || !window.PocketAccount) return;
    restored = true;
    try {
      selectBase();
      const saved = JSON.parse(window.PocketAccount.read(base));
      if (!saved.refreshToken) return;
      refreshToken = saved.refreshToken; currentUser = saved.userId; accountMode = true;
      await renew(); await connected();
    } catch (e) { info('会话未恢复，请重新登录：' + e.message); }
  });
})();
