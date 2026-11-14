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

  // ---- small pieces shared by the pages ---------------------------------------
  const PAGE = 20;
  /** "上一页 3 / 12 下一页" under a list; hidden when there is one page only. */
  function pager(id, page, total, go) {
    const box = $(id);
    const pages = Math.max(1, Math.ceil(total / PAGE));
    box.replaceChildren();
    box.hidden = pages <= 1;
    if (pages <= 1) return;
    const button = (label, to, off) => {
      const node = el('button', { class: 'ghost', onclick: () => go(to) }, label);
      node.disabled = off;
      return node;
    };
    box.append(button('« 首页', 1, page <= 1), button('‹ 上一页', page - 1, page <= 1),
      el('span', { class: 'page-no' }, `第 ${page} / ${pages} 页 · 共 ${total} 条`),
      button('下一页 ›', page + 1, page >= pages), button('末页 »', pages, page >= pages));
  }
  /** Shows one page of rows that are all in memory. */
  function pagedRows(rows, pagerId, draw) {
    const show = (page) => {
      const pages = Math.max(1, Math.ceil(rows.length / PAGE));
      page = Math.min(Math.max(1, page), pages);
      draw(rows.slice((page - 1) * PAGE, page * PAGE), (page - 1) * PAGE);
      pager(pagerId, page, rows.length, show);
    };
    show(1);
  }
  /** A row of buttons that switch between the ".sub-view" blocks of one page. */
  function subtabs(id, onOpen) {
    const bar = $(id), view = bar.parentElement;
    const open = (name) => {
      for (const button of bar.children) button.classList.toggle('on', button.dataset.sub === name);
      for (const block of view.querySelectorAll('.sub-view')) block.hidden = block.dataset.sub !== name;
      if (onOpen) onOpen(name);
    };
    bar.addEventListener('click', (event) => { if (event.target.dataset.sub) open(event.target.dataset.sub); });
    return open;
  }
  let rankNames = ['青铜', '白银', '黄金', '铂金', '钻石', '星耀', '王者'];
  const rankBadge = (name) => el('span', { class: `rank r${Math.max(0, rankNames.indexOf(name))}` }, name);
  const place = (index) => el('td', { class: `place p${index + 1}` }, String(index + 1));
  let myCharacter = '';

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
    document.body.className = 'signed-out';
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
    document.body.className = 'signed-in';
    $('who-name').textContent = user.username;
    loadMine().catch(() => {});
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
  const loaders = { rooms: loadRooms, characters: loadCharacters, board: loadBoard, races: loadRaces, account: async () => {}, admin: async () => { openAdmin('users'); } };
  function open(view) {
    for (const button of $('tabs').children) button.classList.toggle('on', button.dataset.view === view);
    for (const section of document.querySelectorAll('.view')) section.hidden = section.id !== `view-${view}`;
    act(null, loaders[view]);
  }
  $('tabs').addEventListener('click', (event) => { const button = event.target.closest('button[data-view]'); if (button) open(button.dataset.view); });

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

  /** "3 / 10" as a bar. The width is set through the style object, which the page's content policy allows. */
  function meter(have, max) {
    const fill = el('span');
    fill.style.width = `${Math.min(100, Math.round(have * 100 / Math.max(1, max)))}%`;
    return el('div', { class: 'bar small' }, fill);
  }
  const clock = (seconds) => `${Math.floor(Math.max(0, seconds) / 60)}:${String(Math.max(0, seconds) % 60).padStart(2, '0')}`;
  /** The public room's round and standing. */
  function ffaBox(ffa) {
    const out = [];
    const round = ffa.round;
    if (round) {
      out.push(el('div', { class: 'sub live' }, round.state === 'running'
        ? `第 ${round.number} 局进行中 · 先到 ${round.target} 分 · 剩余 ${clock(round.endsAt - round.now)}`
        : `第 ${round.number} 局结束 · ${round.winner} 获胜 · 即将开始下一局`));
    }
    if (ffa.players.length) {
      const list = el('ol', { class: 'results' });
      ffa.players.forEach((p, index) => list.append(el('li', {},
        el('span', { class: 'pos' }, `#${index + 1}`), el('span', { class: p.bounty ? 'gold' : '' }, p.name), ...(p.rank ? [rankBadge(p.rank)] : []),
        ...(p.bounty ? [el('span', { class: 'tag warn' }, `悬赏 · 连杀 ${p.streak}`)] : []),
        el('span', { class: 'time' }, `${p.score ?? p.kills} 分 · 击杀 ${p.kills} · 阵亡 ${p.deaths}`))));
      out.push(list);
    }
    return out;
  }

  /** The season strip of the first page and the rank next to the user's name. */
  async function loadMine() {
    const [{ season, rank }, board] = await Promise.all([api('GET', '/me/rank'), api('GET', '/season/board')]);
    rankNames = board.ranks.map((r) => r.name);
    const left = Math.max(0, Math.ceil((season.endsAt - board.season.now) / 86400));
    $('season-strip').hidden = false;
    $('strip-season').textContent = `第 ${season.id} 赛季`;
    $('strip-left').textContent = `${left} 天`;
    $('strip-rank').replaceChildren(rankBadge(rank.name), ` ${rank.points} 分`);
    const floor = board.ranks[rank.level] ? board.ranks[rank.level].points : 0;
    $('strip-next').textContent = rank.next ? `距离 ${rankNames[rank.level + 1]} 还差 ${rank.next - rank.points} 分` : '已是最高段位';
    $('strip-bar').style.width = rank.next ? `${Math.round((rank.points - floor) * 100 / (rank.next - floor))}%` : '100%';
    const badge = $('who-rank');
    badge.hidden = false;
    badge.className = `rank r${rank.level}`;
    badge.textContent = rank.name;
    try {
      const { characters, activeCharacterId } = await api('GET', '/characters');
      myCharacter = (characters.find((c) => c.id === activeCharacterId) || { name: '' }).name;
    } catch { /* highlighting the own row is optional */ }
  }

  async function loadRooms() {
    loadMine().catch(() => {});
    const { rooms, publicOpen } = await api('GET', '/rooms');
    const list = $('rooms');
    list.replaceChildren();
    if (!publicOpen) list.append(el('div', { class: 'item' }, el('div', { class: 'grow' },
      el('div', { class: 'title' }, '公共对战房 1', el('span', { class: 'tag on' }, '个人对战'), el('span', { class: 'tag' }, '常驻')),
      el('div', { class: 'sub' }, '现在里面没有人。在手机联机页点“个人对战”进入，第一个进的人自动当房主。每局先到 20 分获胜，打完自动开下一局。'))));
    if (!rooms.length) list.append(empty('现在没有其他在线房间。在游戏里创建房间后，它会出现在这里。'));
    for (const room of rooms) {
      const title = el('div', { class: 'title' }, room.title);
      if (room.public) title.append(el('span', { class: 'tag on' }, '个人对战'), el('span', { class: 'tag' }, '常驻'));
      if (!room.relayNetwork) title.append(el('span', { class: 'tag warn' }, '旧版本的局域网房间，新版不能加入'));
      if (room.players >= room.maxPlayers) title.append(el('span', { class: 'tag bad' }, '已满'));
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
      list.append(el('div', { class: 'item' },
        el('div', { class: 'grow' }, title, el('div', { class: 'sub' }, `房主 ${room.hostName} · ${room.players} / ${room.maxPlayers} 人`),
          ...(room.relayReached === false ? [el('div', { class: 'sub' }, `中转：服务器还没有收到房主手机的 UDP 数据，别人和人机都进不来。服务器累计收到 UDP 包 ${(info.relayStats || {}).received ?? '?'} 个，其中令牌不认识的 ${(info.relayStats || {}).unknown ?? '?'} 个。收到 0 个 = 包没到服务器（防火墙/安全组服务器没有放行 UDP ${info.relayPort || 27950}、域名套了 CDN、或手机网络拦 UDP）；有“不认识的” = 服务器重启过，手机上重开游戏再建房。`)] : []),
          ...(room.relayReached === true ? [el('div', { class: 'sub' }, '中转：UDP 通畅')] : []),
          ...(room.debug ? [el('div', { class: 'sub' }, `诊断：${room.debug}`)] : []),
          meter(room.players, room.maxPlayers),
          ...(room.ffa ? ffaBox(room.ffa) : []),
          ...(room.match ? [matchBox(room)] : [])),
        el('div', { class: 'actions' }, ...botButtons,
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
  const mine = (row, name) => { if (myCharacter && name === myCharacter) row.classList.add('me'); return row; };
  async function loadFfaBoard() {
    const { board } = await api('GET', `/ffa/board?range=${ffaRange}`);
    for (const button of $('ffa-ranges').children) button.classList.toggle('on', button.dataset.range === ffaRange);
    const body = $('ffa-board').tBodies[0];
    pagedRows(board, 'ffa-pager', (rows, offset) => {
      body.replaceChildren();
      if (!board.length) return body.append(el('tr', {}, el('td', { colspan: '5', class: 'sub' }, '这个时间段还没有人上榜。去公共对战房打一场吧。')));
      rows.forEach((row, index) => body.append(mine(el('tr', {}, place(offset + index), el('td', {}, row.name),
        el('td', { class: 'num' }, String(row.kills)), el('td', { class: 'num' }, String(row.deaths)),
        el('td', { class: 'num' }, (row.kills / Math.max(1, row.deaths)).toFixed(2))), row.name)));
    });
  }
  $('ffa-ranges').addEventListener('click', (event) => {
    if (!event.target.dataset.range) return;
    ffaRange = event.target.dataset.range;
    act(event.target, loadFfaBoard);
  });

  async function loadSeason() {
    const { season, ranks, points, board, last } = await api('GET', '/season/board');
    rankNames = ranks.map((r) => r.name);
    const day = (at) => new Date(at * 1000).toLocaleDateString('zh-CN');
    const left = Math.max(0, Math.ceil((season.endsAt - season.now) / 86400));
    $('rank-legend').replaceChildren(...ranks.map((r) => el('span', {}, rankBadge(r.name), ` ${r.points}+`)));
    $('season-info').textContent = `第 ${season.id} 赛季：${day(season.startedAt)} 至 ${day(season.endsAt)}，还剩 ${left} 天，结束后积分清零。`
      + `击杀 +${points.kill}（终结悬赏加倍），阵亡 ${points.death}；个人对战每局前三名 +${points.ffaPlaces.join(' / +')}；团队竞技获胜 +${points.teamWin}、平局 +${points.teamDraw}（至少 2 名真人）。`;
    const body = $('season-board').tBodies[0];
    pagedRows(board, 'season-pager', (rows, offset) => {
      body.replaceChildren();
      if (!board.length) return body.append(el('tr', {}, el('td', { colspan: '6', class: 'sub' }, '本赛季还没有人上榜。去打一场，第一个上榜的就是你。')));
      rows.forEach((row, index) => body.append(mine(el('tr', {}, place(offset + index), el('td', {}, row.name), el('td', {}, rankBadge(row.rank)),
        el('td', { class: 'num' }, String(row.points)), el('td', { class: 'num' }, String(row.kills)), el('td', { class: 'num' }, String(row.wins))), row.name)));
    });
    const podium = $('season-last');
    podium.hidden = !(last && last.top.length);
    if (last && last.top.length) podium.replaceChildren(el('small', {}, `上赛季（第 ${last.id} 赛季）前三名`),
      ...last.top.map((r, i) => el('div', { class: `p${i + 1}` }, el('b', {}, `${i + 1}. ${r.name}`), rankBadge(r.rank), ` ${r.points} 分`)));
  }

  async function loadLeaderboard() {
    const { leaderboard } = await api('GET', '/leaderboard');
    const body = $('board').tBodies[0];
    pagedRows(leaderboard, 'board-pager', (rows, offset) => {
      body.replaceChildren();
      if (!leaderboard.length) return body.append(el('tr', {}, el('td', { colspan: '12', class: 'sub' }, '还没有角色上榜。')));
      rows.forEach((row, index) => body.append(mine(el('tr', {},
        place(offset + index), el('td', {}, row.name),
        el('td', { class: 'num' }, String(row.wins)), el('td', { class: 'num' }, String(row.finishes)),
        el('td', { class: 'num' }, String(row.races)),
        el('td', { class: 'num' }, String(row.dmWins || 0)), el('td', { class: 'num' }, String(row.dmMatches || 0)),
        el('td', { class: 'num' }, String(row.dmKills || 0)), el('td', { class: 'num' }, String(row.dmDeaths || 0)),
        el('td', { class: 'num' }, String(row.ffaKills || 0)), el('td', { class: 'num' }, String(row.ffaDeaths || 0)),
        el('td', { class: 'num' }, duration(row.playSeconds))), row.name)));
    });
  }
  const boardLoaders = { season: loadSeason, ffa: loadFfaBoard, all: loadLeaderboard };
  const openBoard = subtabs('board-tabs', (name) => act(null, boardLoaders[name]));
  async function loadBoard() { openBoard('season'); }

  const TEAM_NAMES = { red: '红队', blue: '蓝队', draw: '平局' };
  async function loadMatches(page = 1) {
    const { matches, total } = await api('GET', `/matches?page=${page}&size=${PAGE}`);
    pager('matches-pager', page, total, (to) => act(null, () => loadMatches(to)));
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

  const raceLoaders = { team: () => loadMatches(1), race: () => loadRaceList(1) };
  const openRaces = subtabs('races-tabs', (name) => act(null, raceLoaders[name]));
  async function loadRaces() { openRaces('team'); }
  async function loadRaceList(page = 1) {
    const { races, total } = await api('GET', `/races?page=${page}&size=${PAGE}`);
    pager('races-pager', page, total, (to) => act(null, () => loadRaceList(to)));
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

  let usersPage = 1;
  async function loadUsers(page = usersPage) {
    usersPage = page;
    const { users, total } = await api('GET', `/admin/users?page=${page}&size=${PAGE}&q=${encodeURIComponent($('users-q').value)}`);
    pager('users-pager', page, total, (to) => act(null, () => loadUsers(to)));
    const body = $('users').tBodies[0];
    body.replaceChildren();
    if (!users.length) body.append(el('tr', {}, el('td', { colspan: '5', class: 'sub' }, '没有找到用户。')));
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

  async function loadKills(page = 1) {
    const { kills, total } = await api('GET', `/admin/ffa/kills?page=${page}&size=${PAGE}&q=${encodeURIComponent($('kills-q').value)}`);
    pager('kills-pager', page, total, (to) => act(null, () => loadKills(to)));
    const log = $('ffa-kills').tBodies[0];
    log.replaceChildren();
    if (!kills.length) log.append(el('tr', {}, el('td', { colspan: '4', class: 'sub' }, '没有记录。')));
    for (const row of kills) log.append(el('tr', {}, el('td', {}, when(row.at)), el('td', {}, `公共对战房 ${row.room}`),
      el('td', {}, row.killer || '—（无人击杀）'), el('td', {}, row.victim)));
  }
  async function loadFfaAdmin() {
    const { players, today } = await api('GET', '/admin/ffa');
    const todayKills = new Map(today.map((row) => [row.characterId, row.kills]));
    const word = $('players-q').value.trim().toLowerCase();
    const found = word ? players.filter((row) => row.name.toLowerCase().includes(word)) : players;
    const body = $('ffa-players').tBodies[0];
    pagedRows(found, 'players-pager', (rows) => {
      body.replaceChildren();
      if (!found.length) body.append(el('tr', {}, el('td', { colspan: '6', class: 'sub' }, '还没有个人对战记录。')));
      for (const row of rows) body.append(el('tr', {}, el('td', {}, row.name),
        el('td', { class: 'num' }, String(row.kills)), el('td', { class: 'num' }, String(row.deaths)),
        el('td', { class: 'num' }, String(todayKills.get(row.characterId) || 0)), el('td', {}, when(row.lastAt)),
        el('td', {}, el('button', { class: 'danger', onclick: (e) => act(e.target, async () => {
          if (!confirm(`把“${row.name}”的个人对战战绩清零？无法恢复。`)) return;
          await api('POST', '/admin/ffa/reset', { characterId: row.characterId }); toast('已清零'); await loadFfaAdmin();
        }) }, '清零'))));
    });
    await loadKills(1);
  }
  async function loadSeasonAdmin() {
    const { season, board } = await api('GET', '/season/board');
    const day = (at) => new Date(at * 1000).toLocaleDateString('zh-CN');
    $('admin-season-title').textContent = `第 ${season.id} 赛季`;
    $('admin-season-info').textContent = `${day(season.startedAt)} 至 ${day(season.endsAt)}，还剩 ${Math.max(0, Math.ceil((season.endsAt - season.now) / 86400))} 天；已有 ${board.length} 名角色上榜。`;
  }
  const adminLoaders = { users: () => loadUsers(1), ffa: loadFfaAdmin, season: loadSeasonAdmin };
  const openAdmin = subtabs('admin-tabs', (name) => act(null, adminLoaders[name]));
  for (const [form, load] of [['users-search', () => loadUsers(1)], ['players-search', loadFfaAdmin], ['kills-search', () => loadKills(1)]]) {
    $(form).addEventListener('submit', (event) => { event.preventDefault(); act(event.submitter, load); });
  }
  $('ffa-reset-all').addEventListener('click', (event) => act(event.target, async () => {
    if (!confirm('把所有人的个人对战战绩全部清零？无法恢复。')) return;
    await api('POST', '/admin/ffa/reset', { all: true }); toast('已全部清零'); await loadFfaAdmin();
  }));

  $('season-end').addEventListener('click', (event) => act(event.target, async () => {
    if (!confirm('立即结束本赛季？所有人的段位积分会从 0 重新开始，无法恢复。')) return;
    const { season } = await api('POST', '/admin/season/end'); toast(`已开始第 ${season.id} 赛季`); await loadSeasonAdmin(); loadMine().catch(() => {});
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
