// SPDX-License-Identifier: MIT
'use strict';
(() => {
  const $ = (id) => document.getElementById(id);
  const MODELS = [
    ['player_zero', '麦克（Michael）'], ['player_one', '富兰克林（Franklin）'], ['player_two', '崔佛（Trevor）'],
    ['mp_m_freemode_01', '自由模式 · 男'], ['mp_f_freemode_01', '自由模式 · 女'],
  ];
  const modelName = (model) => (MODELS.find(([id]) => id === model) || [null, model])[1];
  let token = localStorage.getItem('xhynv.token');
  let user = null;
  let registering = false;
  let info = { inviteRequired: false, maxCharacters: 3 };

  function el(tag, props = {}, ...children) {
    const node = document.createElement(tag);
    for (const [name, value] of Object.entries(props)) {
      if (name === 'class') node.className = value;
      else if (name === 'onclick') node.addEventListener('click', value);
      else node.setAttribute(name, value);
    }
    for (const child of children) node.append(child);  // strings become text nodes: no HTML injection
    return node;
  }
  const empty = (message) => el('div', { class: 'empty' }, message);
  const duration = (seconds) => seconds < 3600 ? `${Math.floor(seconds / 60)} 分钟` : `${(seconds / 3600).toFixed(1)} 小时`;
  const when = (seconds) => seconds ? new Date(seconds * 1000).toLocaleString('zh-CN', { dateStyle: 'short', timeStyle: 'short' }) : '—';
  const raceTime = (ms) => `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}.${String(ms % 1000).padStart(3, '0')}`;

  let toastTimer;
  function toast(message) {
    $('toast').textContent = message;
    $('toast').hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { $('toast').hidden = true; }, 2600);
  }

  async function api(method, url, body) {
    const headers = {};
    if (token) headers.Authorization = `Bearer ${token}`;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    let response;
    try {
      response = await fetch(`api${url}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    } catch {
      throw new Error('无法连接服务器，请检查网络');
    }
    const data = await response.json().catch(() => ({}));
    if (response.status === 401 && token && !url.startsWith('/auth/')) { signOut(); throw new Error('登录已失效，请重新登录'); }
    if (!response.ok) throw new Error((data.error && data.error.message) || `请求失败（${response.status}）`);
    return data;
  }
  /** Run an action, showing failures in the page banner and disabling the trigger meanwhile. */
  async function act(button, work) {
    $('app-error').textContent = '';
    if (button) button.disabled = true;
    try { await work(); } catch (error) { $('app-error').textContent = error.message; } finally { if (button) button.disabled = false; }
  }

  // ---- sign in / out --------------------------------------------------------
  function showAuth() {
    $('app').hidden = true; $('who').hidden = true; $('auth').hidden = false;
    $('tab-login').classList.toggle('on', !registering);
    $('tab-register').classList.toggle('on', registering);
    $('auth-submit').textContent = registering ? '注册' : '登录';
    $('auth-hint').hidden = !registering;
    $('invite-row').hidden = !(registering && info.inviteRequired);
    $('password').autocomplete = registering ? 'new-password' : 'current-password';
    $('auth-error').textContent = '';
  }
  function signOut() {
    token = null; user = null;
    localStorage.removeItem('xhynv.token');
    showAuth();
  }
  function showApp() {
    $('auth').hidden = true; $('app').hidden = false; $('who').hidden = false;
    $('who-name').textContent = user.username;
    $('admin-tab').hidden = !user.isAdmin;
    open('rooms');
  }
  $('tab-login').addEventListener('click', () => { registering = false; showAuth(); });
  $('tab-register').addEventListener('click', () => { registering = true; showAuth(); });
  $('auth-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    $('auth-error').textContent = '';
    $('auth-submit').disabled = true;
    try {
      const body = { username: $('username').value, password: $('password').value };
      if (registering && info.inviteRequired) body.inviteCode = $('invite').value;
      const data = await api('POST', registering ? '/auth/register' : '/auth/login', body);
      token = data.token; user = data.user;
      localStorage.setItem('xhynv.token', token);
      $('password').value = '';
      showApp();
    } catch (error) {
      $('auth-error').textContent = error.message;
    } finally {
      $('auth-submit').disabled = false;
    }
  });
  $('logout').addEventListener('click', async () => { try { await api('POST', '/auth/logout'); } catch { /* token is dropped locally anyway */ } signOut(); });

  // ---- views ----------------------------------------------------------------
  const loaders = { rooms: loadRooms, characters: loadCharacters, board: loadBoard, races: loadRaces, account: async () => {}, admin: loadUsers };
  function open(view) {
    for (const button of $('tabs').children) button.classList.toggle('on', button.dataset.view === view);
    for (const section of document.querySelectorAll('.view')) section.hidden = section.id !== `view-${view}`;
    act(null, loaders[view]);
  }
  $('tabs').addEventListener('click', (event) => { if (event.target.dataset.view) open(event.target.dataset.view); });

  async function loadRooms() {
    const { rooms } = await api('GET', '/rooms');
    const list = $('rooms');
    list.replaceChildren();
    if (!rooms.length) return list.append(empty('现在没有在线房间。在游戏里创建房间后，它会出现在这里。'));
    for (const room of rooms) {
      const title = el('div', { class: 'title' }, room.title);
      if (room.virtualLan) title.append(el('span', { class: 'tag warn' }, '虚拟局域网'));
      if (room.players >= room.maxPlayers) title.append(el('span', { class: 'tag bad' }, '已满'));
      if (room.relayNetwork) title.append(el('span', { class: 'tag' }, '公网联机'));
      if (room.bots) title.append(el('span', { class: 'tag warn' }, `人机 ${room.bots}${room.botMirror ? '（镜像）' : ''}`));
      const setBots = (count, mirror) => (e) => act(e.target, async () => {
        await api('POST', `/rooms/${room.id}/bots`, { count, mirror }); await loadRooms();
        toast('已设置。房主手机会在 10 秒内照做（需要新版客户端）');
      });
      const botButtons = (user.isAdmin || room.debug !== undefined) && room.relayNetwork ? [
        el('button', { class: 'ghost', onclick: setBots(Math.min(3, room.bots + 1), room.botMirror) }, '加人机'),
        el('button', { class: 'ghost', onclick: setBots(Math.max(0, room.bots - 1), room.botMirror) }, '减人机'),
        el('button', { class: 'ghost', onclick: setBots(room.bots, !room.botMirror) }, room.botMirror ? '关镜像' : '开镜像（实验）'),
      ] : [];
      const copy = el('button', { class: 'ghost', onclick: async () => {
        try { await navigator.clipboard.writeText(`${room.hostAddress} ${room.roomCode}`); toast('已复制房主 IP 和房间码'); }
        catch { toast('复制失败，请手动抄写'); }
      } }, '复制');
      list.append(el('div', { class: 'item' },
        el('div', { class: 'grow' }, title, el('div', { class: 'sub' }, `房主 ${room.hostName} · ${room.players} / ${room.maxPlayers} 人`),
          ...(room.debug ? [el('div', { class: 'sub' }, `诊断：${room.debug}`)] : [])),
        el('div', { class: 'join' },
          el('div', {}, el('small', {}, '房主 IP'), el('b', {}, room.hostAddress)),
          el('div', {}, el('small', {}, '房间码'), el('b', {}, room.roomCode))),
        el('div', { class: 'actions' }, copy, ...botButtons,
          ...(user.isAdmin ? [el('button', { class: 'danger', onclick: (e) => act(e.target, async () => { await api('DELETE', `/rooms/${room.id}`); await loadRooms(); }) }, '移除')] : []))));
    }
  }
  $('rooms-refresh').addEventListener('click', (event) => act(event.target, loadRooms));

  async function loadCharacters() {
    const { characters, activeCharacterId } = await api('GET', '/characters');
    const list = $('characters');
    list.replaceChildren();
    if (!characters.length) list.append(empty('还没有角色。在下面创建第一个角色，才能创建或加入联机房间。'));
    for (const character of characters) {
      const active = character.id === activeCharacterId;
      const title = el('div', { class: 'title' }, character.name);
      if (active) title.append(el('span', { class: 'tag on' }, '当前使用'));
      const save = character.save ? `云存档 ${(character.save.size / 1024).toFixed(0)} KB · ${when(character.save.updatedAt)}` : '无云存档';
      const actions = el('div', { class: 'actions' });
      if (!active) actions.append(el('button', { class: 'ghost', onclick: (e) => act(e.target, async () => {
        await api('POST', `/characters/${character.id}/select`); toast(`已切换到 ${character.name}`); await loadCharacters();
      }) }, '设为当前'));
      actions.append(el('button', { class: 'danger', onclick: (e) => act(e.target, async () => {
        if (!confirm(`删除角色“${character.name}”？它的在线时长和云存档会一并删除，无法恢复。`)) return;
        await api('DELETE', `/characters/${character.id}`); toast('角色已删除'); await loadCharacters();
      }) }, '删除'));
      list.append(el('div', { class: 'item' },
        el('div', { class: 'grow' }, title, el('div', { class: 'sub' }, `${modelName(character.model)} · 在线 ${duration(character.playSeconds)} · ${save}`)),
        actions));
    }
    $('character-form').hidden = characters.length >= info.maxCharacters;
  }
  for (const [id, label] of MODELS) $('character-model').append(el('option', { value: id }, label));
  $('character-form').addEventListener('submit', (event) => {
    event.preventDefault();
    act(event.submitter, async () => {
      await api('POST', '/characters', { name: $('character-name').value, model: $('character-model').value });
      $('character-name').value = '';
      toast('角色已创建');
      await loadCharacters();
    });
  });

  async function loadBoard() {
    const { leaderboard } = await api('GET', '/leaderboard');
    const body = $('board').tBodies[0];
    body.replaceChildren();
    if (!leaderboard.length) return body.append(el('tr', {}, el('td', { colspan: '6', class: 'sub' }, '还没有角色上榜。')));
    leaderboard.forEach((row, index) => body.append(el('tr', {},
      el('td', {}, String(index + 1)), el('td', {}, row.name),
      el('td', { class: 'num' }, String(row.wins)), el('td', { class: 'num' }, String(row.finishes)),
      el('td', { class: 'num' }, String(row.races)), el('td', { class: 'num' }, duration(row.playSeconds)))));
  }

  async function loadRaces() {
    const { races } = await api('GET', '/races');
    const list = $('races');
    list.replaceChildren();
    if (!races.length) return list.append(empty('还没有比赛记录。房主在游戏里办完一场街头赛车后，成绩会出现在这里。'));
    for (const race of races) {
      const results = el('ol', { class: 'results' });
      for (const entry of race.results) results.append(el('li', {},
        el('span', { class: 'pos' }, entry.finished ? `#${entry.position}` : '—'), el('span', {}, entry.name),
        el('span', { class: 'time' }, entry.finished ? raceTime(entry.timeMs) : (entry.reason || '未完赛'))));
      list.append(el('div', { class: 'item' },
        el('div', { class: 'grow' }, el('div', { class: 'title' }, race.courseName),
          el('div', { class: 'sub' }, `${race.mode === 'circuit' ? `绕圈赛 · ${race.laps} 圈` : '单程冲刺'} · ${race.entrants} 人 · ${when(race.createdAt)}`)),
        results));
    }
  }

  $('password-form').addEventListener('submit', (event) => {
    event.preventDefault();
    act(event.submitter, async () => {
      await api('POST', '/auth/password', { currentPassword: $('current-password').value, newPassword: $('new-password').value });
      event.target.reset();
      toast('密码已修改');
    });
  });

  async function loadUsers() {
    const { users } = await api('GET', '/admin/users');
    const body = $('users').tBodies[0];
    body.replaceChildren();
    for (const row of users) {
      const name = el('td', {}, row.username);
      if (row.isAdmin) name.append(el('span', { class: 'tag on' }, '管理员'));
      if (row.banned) name.append(el('span', { class: 'tag bad' }, '已封禁'));
      const action = row.id === user.id ? '' : el('button', { class: row.banned ? 'ghost' : 'danger', onclick: (e) => act(e.target, async () => {
        if (!row.banned && !confirm(`封禁用户“${row.username}”？对方会被立即登出。`)) return;
        await api('POST', `/admin/users/${row.id}/ban`, { banned: !row.banned }); await loadUsers();
      }) }, row.banned ? '解封' : '封禁');
      body.append(el('tr', {}, el('td', {}, String(row.id)), name, el('td', {}, when(row.createdAt)), el('td', {}, when(row.lastLoginAt)), el('td', {}, action)));
    }
  }

  // ---- start ----------------------------------------------------------------
  (async () => {
    try { info = await api('GET', '/info'); } catch { /* defaults are fine */ }
    if (token) {
      try { user = (await api('GET', '/me')).user; return showApp(); } catch { /* fall through to sign-in */ }
    }
    showAuth();
  })();
})();
