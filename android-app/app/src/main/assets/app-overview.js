/* The backend owns money arithmetic and forecasting; numeric conversion here positions UI only.
   Adapted to the 4-tab index.html (#home-* ids). All lookups null-safe. */
(() => {
  const $ = selector => document.querySelector(selector);
  let epoch = 0, running = null;
  const money = value => '¥' + String(value).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  function text(selector, value) { const el = $(selector); if (el) el.textContent = value; }

  function clear(message) {
    text('#home-verified', message.includes('登录') ? '未登录' : '读取中');
    const total = $('#home-total');
    if (total) { const yuan = total.querySelector('span'); total.innerHTML = ''; if (yuan) total.append(yuan); total.append(document.createTextNode('——')); }
    text('.asset-sub', message);
    text('#home-avail', '——'); text('#home-frozen', '——'); text('#home-complete', message);
    const banks = $('#home-banks');
    if (banks) { banks.replaceChildren(); const p = document.createElement('p'); p.className = 'subtle'; p.textContent = message; banks.append(p); }
    text('#home-morning', message);
    const acts = $('#home-activities');
    if (acts) { acts.replaceChildren(); acts.textContent = message; }
    text('#plan-ticker', message);
  }

  const BANK_META = {
    A: { name: '大众储蓄银行', tag: '储蓄账户' },
    B: { name: '股份商业银联', tag: '信用账户' },
    C: { name: '互联网银行', tag: '活期账户' },
  };

  function bankCard(account) {
    const meta = BANK_META[account.bank] || { name: '银行 ' + account.bank, tag: '账户' };
    const tail = (account.accountId || '').slice(-4);
    const card = document.createElement('button'); card.type = 'button';
    card.className = 'bank-card';
    // 头部：银行标识 + 名称 + 尾号
    const head = document.createElement('div'); head.className = 'bc-head';
    const mark = document.createElement('span'); mark.className = 'bc-mark m-' + String(account.bank).toLowerCase();
    mark.textContent = account.bank;
    const name = document.createElement('div'); name.className = 'bc-name';
    const nameMain = document.createElement('strong'); nameMain.textContent = meta.name;
    const nameSub = document.createElement('small'); nameSub.textContent = meta.tag + ' ····' + tail;
    name.append(nameMain, nameSub);
    head.append(mark, name);
    // 金额
    const amount = document.createElement('div'); amount.className = 'bc-amount';
    const cny = document.createElement('small'); cny.textContent = '¥ ';
    amount.append(cny, document.createTextNode(String(account.balance).replace(/\B(?=(\d{3})+(?!\d))/g, ',')));
    // 底部：可用 + 状态胶囊
    const foot = document.createElement('div'); foot.className = 'bc-foot';
    const avail = document.createElement('span'); avail.className = 'bc-avail';
    avail.textContent = '可用 ' + money(account.available);
    const state = document.createElement('span'); state.className = 'bc-state' + (account.frozen ? ' frozen' : '');
    state.textContent = account.frozen ? '已冻结' : '正常';
    foot.append(avail, state);
    card.append(head, amount, foot);
    card.onclick = () => {
      const morning = $('#home-morning');
      if (morning) { morning.textContent = meta.name + '（' + account.bank + '）快照 ' + (account.asOf || '').slice(0, 10) + '\n账户 ' + account.accountId + '\n余额 ' + money(account.balance) + '\n可用 ' + money(account.available) + '\n状态：' + (account.frozen ? '已冻结' : '正常') + '\n来自独立模拟银行；余额与可用可能因冻结、预留不同。'; morning.style.whiteSpace = 'pre-line'; }
    };
    return card;
  }

  function render(view) {
    const accounts = view.accounts || [], totals = view.totals || {}, forecast = view.forecast || {};
    const complete = view.complete === true, timeline = forecast.timeline || [];

    text('#home-verified', complete ? '已核验' : '部分核验');
    const total = $('#home-total');
    if (total) { const yuan = total.querySelector('span'); total.innerHTML = ''; if (yuan) total.append(yuan); total.append(document.createTextNode(accounts.length ? money(totals.cash) : '——')); }
    text('.asset-sub', complete ? '三家银行实际现金快照 · 更新 ' + (view.asOf || '').slice(0, 10) : '数据不完整，仅显示已取得的账户快照');
    text('#home-avail', accounts.length ? money(totals.availableCash) : '——');
    text('#home-frozen', accounts.some(a => a.frozen) ? '存在冻结' : accounts.length ? money(totals.restrictedCash || 0) : '——');
    text('#home-complete', complete ? '完整' : '部分');

    const banks = $('#home-banks');
    if (banks) {
      banks.replaceChildren();
      if (!accounts.length) { const p = document.createElement('p'); p.className = 'subtle'; p.textContent = '没有可用账户快照，请检查绑定或银行连接。'; banks.append(p); }
      for (const account of accounts) banks.append(bankCard(account));
    }

    text('#home-morning', accounts.length
      ? '三行总资产 ' + money(totals.cash) + ' · 可用 ' + money(totals.availableCash) + (forecast.firstGap ? ' · ⚠ ' + forecast.firstGap + ' 预计出现现金缺口' : ' · 暂无缺口预警') + '。对话「晨报」可获取完整日报。'
      : '登录后查看每日资金报告和主动提醒。');

    text('#plan-ticker', timeline.length
      ? money(timeline[0].available) + ' → ' + money(timeline[timeline.length - 1].available) + '（30天后）· 最低 ' + (forecast.minimumAvailable === undefined ? '未知' : money(forecast.minimumAvailable)) + (forecast.firstGap ? ' · 缺口首现 ' + forecast.firstGap : '')
      : (accounts.length ? '暂无预测数据' : '登录后加载预测'));

    const acts = $('#home-activities');
    if (acts) {
      acts.replaceChildren();
      if (!view.pendingActions?.length) { acts.textContent = '当前会话暂无待处理资金任务。'; }
      else {
        const wrap = document.createElement('span');
        for (const task of view.pendingActions || []) {
          const row = document.createElement('button'); row.type = 'button';
          row.style.cssText = 'display:block;width:100%;text-align:left;background:var(--paper2);border:1px solid var(--line);border-radius:10px;padding:10px;margin-top:6px;color:var(--ink)';
          row.textContent = (task.actionTitle || task.id) + ' · ' + task.state + ' · ' + task.risk;
          row.onclick = () => { switchTab('assistant'); window.PocketOpenProposal?.(task.id); };
          wrap.append(row);
        }
        acts.append(wrap);
      }
    }
  }

  let hasData = false, lastDataAt = 0, inFlight = null;

  async function refresh() {
    if (!window.PocketSession?.status().online) { clear('请先登录'); hasData = false; return; }
    if (inFlight) return inFlight; // 防并发
    const own = ++epoch;
    // 不清空旧值：保留上次金额，只把徽标置"更新中"，避免读取中→横线闪烁
    if (!hasData) text('#home-verified', '读取中');
    else text('#home-verified', '更新中');
    inFlight = (async () => {
      try {
        const view = await window.PocketSession.request('/api/overview?days=30');
        if (own !== epoch) return;
        hasData = true;
        render(view);
      } catch (e) {
        if (own === epoch && !hasData) clear('读取失败：' + e.message);
      } finally { inFlight = null; }
    })();
    return inFlight;
  }

  // 聊天事件流每次 task_state/decision 都派发 pocket-data——节流 5s，避免首页反复刷新闪烁
  function onData() {
    const now = Date.now();
    if (now - lastDataAt < 5000) return;
    lastDataAt = now;
    refresh();
  }

  $('#home-verified')?.addEventListener('click', () => { lastDataAt = 0; refresh(); });
  window.addEventListener('pocket-auth', () => { lastDataAt = 0; refresh(); });
  window.addEventListener('pocket-data', onData);
  window.addEventListener('pocket-config', () => setTimeout(() => { lastDataAt = 0; refresh(); }, 800)); // 会话恢复后刷一次
  window.PocketOverviewRefresh = refresh;
  clear('请先登录');
})();
