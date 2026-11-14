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
  const loaders = { rooms: loadRooms, characters: loadCharacters, board: loadBoard, races: loadRaces, account: async () => {}, admin: async () => { await loadUsers(); await loadFfaAdmin(); } };
  function open(view) {
    for (const button of $('tabs').children) button.classList.toggle('on', button.dataset.view === view);
    for (const section of document.querySelectorAll('.view')) section.hidden = section.id !== `view-${view}`;
    act(null, loaders[view]);
  }
  $('tabs').addEventListener('click', (event) => { if (event.target.dataset.view) open(event.target.dataset.view); });

  /** The room's team match as the host or an administrator sees it, with a "knock down" button per player for testing. */
  function matchBox(room) {
    const match = room.match;
    const STATES = { lobby: '选队中', running: '进行中', finished: '已结束' };
    const box = el('div', { class: 'sub' },
      `团队竞技（${STATES[match.state] || match.state}）：红队 ${match.red} : ${match.blue} 蓝队 · 目标 ${match.target} · 击倒人机需命中 ${match.botHits} 次 · ${match.record ? '记录战绩' : '本场不记录'}`);
    for (const team of ['red', 'blue']) {
      const line = el('div', {}, el('span', { class: team }, team === 'red' ? '红队' : '蓝队'), '：');
      const players = match.players.filter((p) => p.team === team);
      if (!players.length) line.append('（空）');
      for (const p of players) {
        line.append(`${p.name}（击杀 ${p.kills} / 阵亡 ${p.deaths}） `);
        if (match.state === 'running') line.append(el('button', { class: 'ghost', onclick: (e) => act(e.target, async () => {
          await api('POST', '/match/simulate', { networkId: room.relayNetwork, victim: p.id }); await loadRooms();
        }) }, '模拟阵亡'), ' ');
      }
      box.append(line);
    }
    return box;
  }

  async function loadRooms() {
    const { rooms, publicOpen } = await api('GET', '/rooms');
    const list = $('rooms');
    list.replaceChildren();
    if (!publicOpen) list.append(el('div', { class: 'item' }, el('div', { class: 'grow' },
      el('div', { class: 'title' }, '公共对战房 1', el('span', { class: 'tag on' }, '个人对战'), el('span', { class: 'tag' }, '常驻')),
      el('div', { class: 'sub' }, '现在里面没有人。在手机联机页点“公共对战房”进入，第一个进的人自动当房主。'))));
    if (!rooms.length) list.append(empty('现在没有其他在线房间。在游戏里创建房间后，它会出现在这里。'));
    for (const room of rooms) {
      const title = el('div', { class: 'title' }, room.title);
      if (room.public) title.append(el('span', { class: 'tag on' }, '个人对战'), el('span', { class: 'tag' }, '常驻'));
      if (room.virtualLan) title.append(el('span', { class: 'tag warn' }, '虚拟局域网'));
      if (room.players >= room.maxPlayers) title.append(el('span', { class: 'tag bad' }, '已满'));
      if (room.relayNetwork) title.append(el('span', { class: 'tag' }, '公网联机'));
      if (!room.bots && room.botMirror) title.append(el('span', { class: 'tag' }, '镜像已开，但还没有人机：点“加人机”'));
      if (room.bots) title.append(el('span', { class: 'tag warn' }, `人机 ${room.bots}${room.botMirror ? '（镜像）' : ''}`));
      const setBots = (count, mirror) => (e) => act(e.target, async () => {
        await api('POST', `/rooms/${room.id}/bots`, { count, mirror }); await loadRooms();
        toast('已设置。房主手机会在 10 秒内照做（需要新版客户端）');
      });
      const botButtons = (user.isAdmin || room.debug !== undefined) && room.relayNetwork && !room.public ? [
        el('button', { class: 'ghost', onclick: setBots(Math.min(9, room.bots + 1), room.botMirror) }, '加人机'),
        el('button', { class: 'ghost', onclick: (e) => act(e.target, async () => {
          await api('POST', `/rooms/${room.id}/bots`, { fill: true, mirror: room.botMirror }); await loadRooms();
          toast('已加满。房主手机会在 10 秒内照做');
        }) }, '加满人机'),
        el('button', { class: 'ghost', onclick: setBots(Math.max(0, room.bots - 1), room.botMirror) }, '减人机'),
        el('button', { class: 'ghost', onclick: setBots(room.botMirror ? room.bots : Math.max(1, room.bots), !room.botMirror) }, room.botMirror ? '关镜像' : '开镜像（实验）'),
      ] : [];
      const copy = el('button', { class: 'ghost', onclick: async () => {
        try { await navigator.clipboard.writeText(`${room.hostAddress} ${room.roomCode}`); toast('已复制房主 IP 和房间码'); }
        catch { toast('复制失败，请手动抄写'); }
      } }, '复制');
      list.append(el('div', { class: 'item' },
        el('div', { class: 'grow' }, title, el('div', { class: 'sub' }, `房主 ${room.hostName} · ${room.players} / ${room.maxPlayers} 人`),
          ...(room.relayReached === false ? [el('div', { class: 'sub' }, `中转：服务器还没有收到房主手机的 UDP 数据，别人和人机都进不来。服务器累计收到 UDP 包 ${(info.relayStats || {}).received ?? '?'} 个，其中令牌不认识的 ${(info.relayStats || {}).unknown ?? '?'} 个。收到 0 个 = 包没到服务器（防火墙/安全组没放行 UDP ${info.relayPort || 27950}、域名套了 CDN、或手机网络拦 UDP）；有“不认识的” = 服务器重启过，手机上重开游戏再建房。`)] : []),
          ...(room.relayReached === true ? [el('div', { class: 'sub' }, '中转：UDP 通畅')] : []),
          ...(room.debug ? [el('div', { class: 'sub' }, `诊断：${room.debug}`)] : []),
          ...(room.ffa && room.ffa.players.length ? [el('div', { class: 'sub' },
            `个人对战：${room.ffa.players.map((p) => `${p.name}（击杀 ${p.kills} / 阵亡 ${p.deaths}）`).join('　')}`)] : []),
          ...(room.match ? [matchBox(room)] : [])),
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

  let ffaRange = 'day';
  async function loadFfaBoard() {
    const { board } = await api('GET', `/ffa/board?range=${ffaRange}`);
    for (const button of $('ffa-ranges').children) button.classList.toggle('on', button.dataset.range === ffaRange);
    const body = $('ffa-board').tBodies[0];
    body.replaceChildren();
    if (!board.length) return body.append(el('tr', {}, el('td', { colspan: '4', class: 'sub' }, '这个时间段还没有人上榜。去公共对战房打一场吧。')));
    board.forEach((row, index) => body.append(el('tr', {}, el('td', {}, String(index + 1)), el('td', {}, row.name),
      el('td', { class: 'num' }, String(row.kills)), el('td', { class: 'num' }, String(row.deaths)))));
  }
  $('ffa-ranges').addEventListener('click', (event) => {
    if (!event.target.dataset.range) return;
    ffaRange = event.target.dataset.range;
    act(event.target, loadFfaBoard);
  });

  async function loadBoard() {
    await loadFfaBoard();
    const { leaderboard } = await api('GET', '/leaderboard');
    const body = $('board').tBodies[0];
    body.replaceChildren();
    if (!leaderboard.length) return body.append(el('tr', {}, el('td', { colspan: '12', class: 'sub' }, '还没有角色上榜。')));
    leaderboard.forEach((row, index) => body.append(el('tr', {},
      el('td', {}, String(index + 1)), el('td', {}, row.name),
      el('td', { class: 'num' }, String(row.wins)), el('td', { class: 'num' }, String(row.finishes)),
      el('td', { class: 'num' }, String(row.races)),
      el('td', { class: 'num' }, String(row.dmWins || 0)), el('td', { class: 'num' }, String(row.dmMatches || 0)),
      el('td', { class: 'num' }, String(row.dmKills || 0)), el('td', { class: 'num' }, String(row.dmDeaths || 0)),
      el('td', { class: 'num' }, String(row.ffaKills || 0)), el('td', { class: 'num' }, String(row.ffaDeaths || 0)),
      el('td', { class: 'num' }, duration(row.playSeconds)))));
  }

  const TEAM_NAMES = { red: '红队', blue: '蓝队', draw: '平局' };
  async function loadMatches() {
    const { matches } = await api('GET', '/matches');
    const list = $('matches');
    list.replaceChildren();
    if (!matches.length) return list.append(empty('还没有团队竞技记录。房主在游戏里办完一场团队竞技后，战绩会出现在这里。'));
    const REASONS = { target: '达到目标击杀数', time: '时间到', host: '房主提前结束' };
    for (const match of matches) {
      const results = el('ol', { class: 'results' });
      for (const entry of match.players) results.append(el('li', {},
        el('span', { class: `pos ${entry.team}` }, TEAM_NAMES[entry.team] || entry.team), el('span', { class: entry.team }, entry.name),
        el('span', { class: 'time' }, `击杀 ${entry.kills} · 阵亡 ${entry.deaths}`)));
      list.append(el('div', { class: 'item' },
        el('div', { class: 'grow' },
          el('div', { class: 'title' }, el('span', { class: 'red' }, `红队 ${match.red}`), ' : ', el('span', { class: 'blue' }, `${match.blue} 蓝队`),
            ` · ${match.winner === 'draw' ? '平局' : `${TEAM_NAMES[match.winner]}获胜`}`),
          el('div', { class: 'sub' }, `房主 ${match.hostName} · 目标 ${match.target} · ${REASONS[match.reason] || match.reason} · ${when(match.endedAt)}`)),
        results));
    }
  }

  async function loadRaces() {
    await loadMatches();
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
      const reset = el('button', { class: 'ghost', onclick: (e) => act(e.target, async () => {
        const next = prompt(`给用户“${row.username}”设置新密码（至少 8 位）：`);
        if (next === null) return;
        await api('POST', `/admin/users/${row.id}/password`, { newPassword: next });
        toast(`已重置“${row.username}”的密码`);
      }) }, '重置密码');
      body.append(el('tr', {}, el('td', {}, String(row.id)), name, el('td', {}, when(row.createdAt)), el('td', {}, when(row.lastLoginAt)), el('td', {}, reset, ' ', action)));
    }
  }

  async function loadFfaAdmin() {
    const { players, today, kills } = await api('GET', '/admin/ffa');
    const todayKills = new Map(today.map((row) => [row.characterId, row.kills]));
    const body = $('ffa-players').tBodies[0];
    body.replaceChildren();
    if (!players.length) body.append(el('tr', {}, el('td', { colspan: '6', class: 'sub' }, '还没有个人对战记录。')));
    for (const row of players) body.append(el('tr', {}, el('td', {}, row.name),
      el('td', { class: 'num' }, String(row.kills)), el('td', { class: 'num' }, String(row.deaths)),
      el('td', { class: 'num' }, String(todayKills.get(row.characterId) || 0)), el('td', {}, when(row.lastAt)),
      el('td', {}, el('button', { class: 'danger', onclick: (e) => act(e.target, async () => {
        if (!confirm(`把“${row.name}”的个人对战战绩清零？无法恢复。`)) return;
        await api('POST', '/admin/ffa/reset', { characterId: row.characterId }); toast('已清零'); await loadFfaAdmin();
      }) }, '清零'))));
    const log = $('ffa-kills').tBodies[0];
    log.replaceChildren();
    for (const row of kills) log.append(el('tr', {}, el('td', {}, when(row.at)), el('td', {}, `公共对战房 ${row.room}`),
      el('td', {}, row.killer || '—（无人击杀）'), el('td', {}, row.victim)));
  }
  $('ffa-reset-all').addEventListener('click', (event) => act(event.target, async () => {
    if (!confirm('把所有人的个人对战战绩全部清零？无法恢复。')) return;
    await api('POST', '/admin/ffa/reset', { all: true }); toast('已全部清零'); await loadFfaAdmin();
  }));

  // ---- start ----------------------------------------------------------------
  (async () => {
    try { info = await api('GET', '/info'); } catch { /* defaults are fine */ }
    if (token) {
      try { user = (await api('GET', '/me')).user; return showApp(); } catch { /* fall through to sign-in */ }
    }
    showAuth();
  })();
})();
