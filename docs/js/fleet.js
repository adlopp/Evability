// Hundir la flota: dos jugadores, tablero de 10 × 10 y la flota clásica (5, 4, 3, 3 y 2). La sala vive en la nube (`frooms/{id}`) y se
// consulta cada poco. Cada jugador guarda su flota en su dispositivo y deja en la nube solo un hash; las respuestas («agua», «tocado»,
// «hundido») las da el dispositivo del defensor y, al terminar, se revela la flota para comprobar que no hubo trampa.
// Las reglas del juego están en fleet-engine.js. `api` lo pone social.js.

import { h, icon, toast, confirmDialog, modal, fill, poller, darkPref, darkToggle, darkBars } from './ui.js';
import { SIZE, LENS, NAMES, cellsOf, canPlace, packFleet, parseFleet, randomFleet, newSalt, commitOf, answers, shotCells, replay, verify, shipParts, pad2 } from './fleet-engine.js';

// ───── dibujo de los barcos (SVG) ─────
const partSvg = (p) => {
  if (p.k === 'poly') return `<polygon points="${p.pts.map((q) => q.join(',')).join(' ')}" fill="${p.f}"/>`;
  if (p.k === 'rect') return `<rect x="${p.x}" y="${p.y}" width="${p.w}" height="${p.h}" rx=".03" fill="${p.f}"/>`;
  if (p.k === 'ell') return `<ellipse cx="${p.cx}" cy="${p.cy}" rx="${p.rx}" ry="${p.ry}" fill="${p.f}"/>`;
  return `<line x1="${p.x1}" y1="${p.y1}" x2="${p.x2}" y2="${p.y2}" stroke="${p.f}" stroke-width="${p.w}" stroke-linecap="round"/>`;
};
/** El barco `i` como SVG; vertical lo gira para que ocupe 1 × largo. */
export function shipSvg(i, vertical = false) {
  const L = LENS[i], parts = shipParts(i).map(partSvg).join('');
  return `<svg viewBox="${vertical ? `0 0 1 ${L}` : `0 0 ${L} 1`}" preserveAspectRatio="none" aria-hidden="true">${vertical ? `<g transform="matrix(0 1 -1 0 1 0)">${parts}</g>` : parts}</svg>`;
}
const shipEl = (i, s, extra = '') => {
  const v = s.dir === 'v', r = Math.floor(s.cell / SIZE), c = s.cell % SIZE, L = LENS[i];
  return h('div', { class: 'fl-ship ' + extra, style: { left: c * 10 + '%', top: r * 10 + '%', width: (v ? 10 : L * 10) + '%', height: (v ? L * 10 : 10) + '%' }, html: shipSvg(i, v) });
};

// ───── sonidos sencillos (sintetizados) ─────
let actx = null;
const ac = () => { const AC = window.AudioContext || window.webkitAudioContext; if (!AC) return null; actx ||= new AC(); if (actx.state === 'suspended') actx.resume(); return actx; };
function noise(dur, { vol = 0.2, hp = 800, lp = 6000 } = {}) {
  const c = ac(); if (!c) return;
  const n = Math.floor(c.sampleRate * dur), buf = c.createBuffer(1, n, c.sampleRate), d = buf.getChannelData(0);
  for (let i = 0; i < n; i++) d[i] = (Math.random() * 2 - 1) * (1 - i / n);
  const src = c.createBufferSource(), g = c.createGain(), f1 = c.createBiquadFilter(), f2 = c.createBiquadFilter();
  f1.type = 'highpass'; f1.frequency.value = hp; f2.type = 'lowpass'; f2.frequency.value = lp; g.gain.value = vol;
  src.buffer = buf; src.connect(f1).connect(f2).connect(g).connect(c.destination); src.start();
}
function boom(f, dur, vol = 0.3) {
  const c = ac(); if (!c) return;
  const o = c.createOscillator(), g = c.createGain(), t = c.currentTime;
  o.type = 'sine'; o.frequency.setValueAtTime(f, t); o.frequency.exponentialRampToValueAtTime(30, t + dur);
  g.gain.setValueAtTime(vol, t); g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
  o.connect(g).connect(c.destination); o.start(t); o.stop(t + dur + 0.02);
}
const sound = (res) => {
  try {
    if (res === '0') noise(0.35, { vol: 0.22, hp: 500, lp: 3500 });
    else if (res === '1') { boom(150, 0.35); noise(0.2, { vol: 0.25, hp: 200, lp: 2500 }); }
    else { boom(110, 0.7, 0.4); noise(0.5, { vol: 0.3, hp: 150, lp: 2000 }); setTimeout(() => boom(70, 0.6, 0.3), 220); }
  } catch { /* sin audio */ }
};

// ───── tablero ─────
/** Tablero de 10 × 10: barcos (si se dan), casillas tocables y marcas ('w' agua, 'h' tocado, 's' hundido, 'p' disparo sin responder). */
function boardEl({ ships = [], sunk = new Set(), marks = new Map(), onCell = null, cls = '' }) {
  const grid = h('div', { class: 'fl-grid' });
  for (let c = 0; c < SIZE * SIZE; c++) {
    const m = marks.get(c);
    const cell = h('button', { class: 'fl-cell' + (onCell ? ' act' : ''), 'data-cell': c, 'aria-label': `Casilla ${String.fromCharCode(65 + (c % SIZE))}${Math.floor(c / SIZE) + 1}`, disabled: !onCell, onclick: onCell ? () => onCell(c) : null },
      m ? h('i', { class: 'mk ' + m }) : null);
    grid.append(cell);
  }
  const cols = h('div', { class: 'fl-cols' }, ...Array.from({ length: SIZE }, (_, i) => h('span', {}, String.fromCharCode(65 + i))));
  const rows = h('div', { class: 'fl-rows' }, ...Array.from({ length: SIZE }, (_, i) => h('span', {}, String(i + 1))));
  const sea = h('div', { class: 'fl-sea' }, h('div', { class: 'fl-waves' }), ...ships.map((s, i) => (s ? shipEl(i, s, sunk.has(i) ? 'sunk' : '') : null)), grid);
  return h('div', { class: 'fl-board ' + cls }, cols, rows, sea);
}

export function openFleet({ roomId, api, onClose }) {
  const me = api.me();
  let room = null, closed = false, mine = null, draft = LENS.map(() => null), sel = 0, dir = 'h', busy = false;
  let lastLog = -1, banner = '', bannerAt = 0, verifyMsg = '', verifyDone = false, revealing = false, sig = '', lobbySig = '';
  const names = {}, photos = {};
  const body = h('div', { class: 'fl-body' });
  const toggleDark = () => { const on = darkToggle(); root.classList.toggle('dark-bg', on); darkBars(on); };
  const root = h('div', { class: 'pomo fleet' },
    h('div', { class: 'pomo-top' }, h('button', { class: 'icon-btn', 'aria-label': 'Volver', onclick: () => close() }, icon('back')), h('h1', { class: 'grow' }, 'Hundir la flota'),
      h('button', { class: 'icon-btn', 'aria-label': 'Fondo oscuro', title: 'Fondo oscuro', onclick: toggleDark }, icon('moon'))),
    body);
  if (darkPref()) { root.classList.add('dark-bg'); darkBars(true); }

  const nameOf = (u) => (u === me ? 'Tú' : names[u] || '?');
  const avatarEl = (u, size = 30) => h('span', { class: 'pav', style: { width: size + 'px', height: size + 'px', fontSize: size * 0.45 + 'px' } },
    photos[u] ? h('img', { src: 'data:image/jpeg;base64,' + photos[u], alt: '' }) : (names[u] || '?').slice(0, 1).toUpperCase());
  async function loadNames() {
    const missing = [...room.players, ...room.invited].filter((u) => !(u in names));
    if (!missing.length) return;
    const p = await api.profiles(missing).catch(() => ({}));
    for (const u of missing) { names[u] = p[u]?.username || '?'; photos[u] = p[u]?.photo || ''; }
  }

  // la flota de este dispositivo (se guarda aquí; en la nube solo va su hash)
  const keyOf = () => 'ev.fleet.' + roomId;
  const loadMine = () => { try { const o = JSON.parse(localStorage.getItem(keyOf()) || 'null'); if (o?.f && o?.salt) { const ships = parseFleet(o.f); if (ships) return { ships, f: o.f, salt: o.salt }; } } catch { /* sin datos */ } return null; };
  const revealStr = () => mine.f + ':' + mine.salt;
  const oppOf = () => room.players.find((u) => u !== me) || '';
  const roomState = () => ({ players: room.players, seed: room.seed, again: room.again, s: room.s, a: room.a });

  // ───── consulta periódica ─────
  async function poll() {
    if (closed) return false;
    try {
      const r = await api.get();
      if (closed) return false;
      if (!r) { toast('La sala se ha cerrado'); close(); return false; }
      room = r;
      await loadNames();
      if (room.status === 'lobby') {
        if (!body.querySelector('.fl-lobby') || lobbySig !== lobbyKey()) renderLobby();
        return { changed: true, delay: 3500 };
      }
      if (!mine) mine = loadMine();
      await duties();
      const s2 = JSON.stringify([room.status, room.h, room.s, room.a, room.v, room.ff]);
      const changed = s2 !== sig; sig = s2;
      if (changed || !body.querySelector('.fl-main, .fl-place, .fl-wait')) render();
      return { changed, delay: 2200 };
    } catch (e) { /* sin red: se reintenta */ }
    return false;
  }

  /** Lo que le toca hacer a este dispositivo aunque el jugador no toque nada: responder al disparo del rival y revelar la flota al acabar. */
  async function duties() {
    if (!mine || !room.players.includes(me)) return;
    if (room.status === 'playing' && room.h[me] && oppOf() && room.h[oppOf()]) {
      const rp = replay(roomState());
      if (rp.pending && rp.cur !== me) {
        const mineAns = room.a[me] || '', n = rp.log.filter((e) => e.by === rp.cur).length;
        if (mineAns.length === n) {
          const res = answers(mine.ships, shotCells(room.s[rp.cur]).slice(0, n + 1))[n], next = mineAns + res;
          const lost = [...next].filter((ch) => ch >= '2').length >= LENS.length;
          await api.answer(room, next, lost ? revealStr() : null);
          room.a[me] = next; if (lost) { room.status = 'finished'; room.v[me] = revealStr(); }
        }
      }
    }
    if (room.status === 'finished' && !room.v[me] && !revealing) {
      revealing = true;
      try { await api.reveal(room, revealStr()); room.v[me] = revealStr(); } catch { /* se reintenta */ }
      revealing = false;
    }
    if (room.status === 'finished' && !verifyDone && oppOf() && room.v[oppOf()] && room.h[oppOf()]) {
      verifyDone = true;
      const why = await verify({ reveal: room.v[oppOf()], commit: room.h[oppOf()], adj: room.adj, shots: room.s[me] || '', given: room.a[oppOf()] || '' });
      verifyMsg = why ? `⚠ ${nameOf(oppOf())} ha hecho trampa: ${why}.` : `✓ La flota de ${nameOf(oppOf())} está verificada: todo fue limpio.`;
      render();
    }
  }

  // ───── sala de espera ─────
  const lobbyKey = () => JSON.stringify([room.players, room.invited, room.adj, room.again, room.status]);
  function renderLobby() {
    lobbySig = lobbyKey();
    const isHost = room.host === me, inRoom = room.players.includes(me);
    const canJoin = !inRoom && (room.invited.includes(me) || !!room.group) && room.players.length < 2;
    const seats = h('div', { class: 'plobby-seats fl-seats' }, [0, 1].map((i) => {
      const u = room.players[i];
      if (u) return h('div', { class: 'pseat', style: `--c:${i ? '#1E88E5' : '#E53935'}` }, avatarEl(u, 34), h('b', {}, nameOf(u)), u === room.host ? h('span', { class: 'vs-crown', title: 'Anfitrión' }, '♛') : null);
      const inv = room.invited.filter((x) => !room.players.includes(x))[0];
      return h('div', { class: 'pseat empty', style: `--c:#1E88E5` }, inv ? [avatarEl(inv, 34), h('b', {}, nameOf(inv)), h('small', {}, '…')] : h('b', {}, '—'));
    }));
    const toggle = (label, key, hint) => h('label', { class: 'ptoggle' }, h('span', {}, label, hint ? h('small', { class: 'dim block' }, hint) : null),
      h('input', { type: 'checkbox', checked: room[key], disabled: !isHost, onchange: (e) => {
        const v = e.target.checked, next = { adj: room.adj, again: room.again, [key]: v };
        api.setCfg(room, next).then(() => { room[key] = v; lobbySig = lobbyKey(); }).catch((err) => { e.target.checked = !v; toast(err.message); });
      } }), h('i', {}));
    const buttons = [];
    if (canJoin) buttons.push(h('button', { class: 'btn big', onclick: async () => { try { await api.join(room); await poll(); } catch (e) { toast(e.message); } } }, 'Unirse'));
    if (isHost) {
      if (!room.group) buttons.push(h('button', { class: 'btn outline', disabled: room.invited.length >= 1 || room.players.length >= 2, onclick: inviteMore }, 'Invitar'));
      buttons.push(h('button', { class: 'btn big', disabled: room.players.length < 2, onclick: async () => { try { await api.start(room, Math.floor(Math.random() * 2000000000)); await poll(); } catch (e) { toast(e.message); } } }, room.players.length < 2 ? 'Esperando rival…' : 'Empezar'));
      buttons.push(h('button', { class: 'text-btn', onclick: async () => { if (await confirmDialog('Cerrar sala', '', 'Cerrar')) { await api.remove(room).catch(() => {}); close(); } } }, 'Cerrar sala'));
    } else if (inRoom) buttons.push(h('button', { class: 'text-btn', onclick: async () => { try { await api.leave(room); close(); } catch (e) { toast(e.message); } } }, 'Salir'));
    fill(body, h('div', { class: 'poly-lobby fl-lobby' }, seats,
      h('div', { class: 'pcfg' },
        toggle('Los barcos pueden tocarse', 'adj', 'Si no, entre barcos queda siempre una casilla de agua.'),
        toggle('Repetir turno al acertar', 'again', 'Quien acierta (tocado o hundido) vuelve a disparar.'),
        h('p', { class: 'dim small vs-rules' }, 'Cada uno coloca su flota en secreto: portaaviones (5), acorazado (4), crucero (3), submarino (3) y destructor (2). Gana quien hunde antes toda la flota del rival.')),
      h('div', { class: 'pbtns' }, buttons)));
  }
  async function inviteMore() {
    let friends = [];
    try { friends = await api.friends(); } catch (e) { toast(e.message); return; }
    const free = friends.filter((f) => !room.invited.includes(f.uid) && !room.players.includes(f.uid));
    const list = h('div', { class: 'stack' }, free.length ? free.map((f) => h('button', { class: 'game-row', onclick: async () => {
      try { await api.invite(room, f.uid); names[f.uid] = f.username; photos[f.uid] = f.photo; room.invited.push(f.uid); m.close(); renderLobby(); toast('Invitación enviada'); } catch (e) { toast(e.message); }
    } }, h('span', { class: 'pav', style: { width: '30px', height: '30px' } }, f.photo ? h('img', { src: 'data:image/jpeg;base64,' + f.photo, alt: '' }) : f.username.slice(0, 1).toUpperCase()), h('div', {}, f.username))) : h('p', { class: 'dim' }, '—'));
    const m = modal({ title: 'Invitar', content: list, actions: [{ label: 'Cerrar' }] });
  }

  // ───── colocar la flota ─────
  function renderPlace() {
    const placed = draft.every((s) => s);
    const board = boardEl({ ships: draft, onCell: (c) => {
      const at = draft.findIndex((s, i) => s && cellsOf(s, i).includes(c));
      if (sel === null && at >= 0) { sel = at; dir = draft[at].dir; draft[at] = null; renderPlace(); return; }
      if (sel === null) return;
      let row = Math.floor(c / SIZE), col = c % SIZE;
      if (dir === 'h') col = Math.min(col, SIZE - LENS[sel]); else row = Math.min(row, SIZE - LENS[sel]);
      const s = { cell: row * SIZE + col, dir };
      if (canPlace(draft, sel, s, room.adj)) { draft[sel] = s; sel = draft.findIndex((x) => !x); if (sel < 0) sel = null; renderPlace(); }
      else toast(room.adj ? 'Ahí no cabe' : 'Ahí no cabe: entre barcos tiene que quedar una casilla de agua');
    } });
    const tray = h('div', { class: 'fl-tray' }, LENS.map((L, i) => h('button', {
      class: 'fl-chip' + (sel === i ? ' sel' : '') + (draft[i] ? ' placed' : ''), 'aria-label': `${NAMES[i]} (${L})`,
      onclick: () => { if (draft[i]) { dir = draft[i].dir; draft[i] = null; } sel = i; renderPlace(); },
    }, h('span', { class: 'fl-chip-ship', style: { width: L * 26 + 'px' }, html: shipSvg(i, false) }), h('small', {}, `${NAMES[i]} · ${L}`))));
    const ctrl = h('div', { class: 'pbtns fl-ctrl' },
      h('button', { class: 'btn outline', disabled: sel === null, onclick: () => { dir = dir === 'h' ? 'v' : 'h'; renderPlace(); } }, dir === 'h' ? 'Girar ↻ (horizontal)' : 'Girar ↻ (vertical)'),
      h('button', { class: 'btn outline', onclick: () => { draft = randomFleet(room.adj); sel = null; renderPlace(); } }, 'Aleatorio'),
      h('button', { class: 'text-btn', onclick: () => { draft = LENS.map(() => null); sel = 0; renderPlace(); } }, 'Limpiar'),
      h('button', { class: 'btn big', disabled: !placed || busy, onclick: ready }, 'Listo'));
    fill(body, h('div', { class: 'fl-place' }, h('p', { class: 'fl-hint' }, sel === null ? (placed ? 'Flota lista. Toca un barco para moverlo o pulsa «Listo».' : 'Elige un barco abajo.') : `Toca el mar para colocar el ${NAMES[sel].toLowerCase()} (${LENS[sel]}).`), board, tray, ctrl));
  }
  async function ready() {
    busy = true; renderPlace();
    try {
      const f = packFleet(draft), salt = newSalt();
      localStorage.setItem(keyOf(), JSON.stringify({ f, salt }));
      await api.place(room, await commitOf(f, salt));
      mine = { ships: draft, f, salt }; room.h[me] = 'x';
      sig = ''; await poll();
    } catch (e) { toast(e.message || 'No se pudo guardar'); try { localStorage.removeItem(keyOf()); } catch { /* ya */ } }
    busy = false; if (!closed && !mine) renderPlace();
  }

  // ───── batalla ─────
  function render() {
    if (!room || room.status === 'lobby') return;
    if (!room.players.includes(me)) { fill(body, h('p', { class: 'dim', style: { padding: '16px' } }, 'La partida ya ha empezado.')); return; }
    if (!room.h[me]) { renderPlaceOnce(); return; }
    if (!room.h[oppOf()] && room.status === 'playing') { renderWait(); return; }
    renderBattle();
  }
  let placeShown = false;
  function renderPlaceOnce() { if (!placeShown || !body.querySelector('.fl-place')) { placeShown = true; renderPlace(); } }

  function renderWait() {
    const board = boardEl({ ships: mine?.ships || [], cls: 'small' });
    fill(body, h('div', { class: 'fl-wait' }, h('p', { class: 'fl-hint' }, `Tu flota está lista. Esperando a que ${nameOf(oppOf())} coloque la suya…`), board,
      h('button', { class: 'text-btn', onclick: forfeit }, 'Abandonar')));
  }

  async function forfeit() {
    if (!(await confirmDialog('Rendirse', `Pierdes la partida y gana ${nameOf(oppOf())}.`, 'Rendirme'))) return;
    try { await api.forfeit(room, mine ? revealStr() : null); room.status = 'finished'; room.ff = me; sig = ''; await poll(); } catch (e) { toast(e.message || 'No se pudo'); }
  }

  function renderBattle() {
    const opp = oppOf(), rp = replay(roomState()), finished = room.status === 'finished';
    const winner = room.ff ? (room.ff === me ? opp : me) : rp.winner;
    // marcas: mis disparos en el mar enemigo y los del rival en el mío
    const eMarks = new Map(), oMarks = new Map(), eSunk = [];
    for (const e of rp.log) {
      const mk = e.res === '0' ? 'w' : e.res === '1' ? 'h' : 's';
      (e.by === me ? eMarks : oMarks).set(e.cell, mk);
      if (e.by === me && e.res >= '2') eSunk.push(Number(e.res));
    }
    const myShots = shotCells(room.s[me]);
    if (!finished && rp.cur === me && rp.pending && myShots.length) eMarks.set(myShots[myShots.length - 1], 'p');
    // mis barcos hundidos (todas sus casillas tocadas)
    const hitCells = new Set([...oMarks].filter(([, v]) => v !== 'w').map(([c]) => c));
    const mySunk = new Set((mine?.ships || []).map((s, i) => i).filter((i) => cellsOf(mine.ships[i], i).every((c) => hitCells.has(c))));
    // el barco del rival se enseña al terminar, si su flota revelada es válida
    const oppShips = finished && room.v[opp] ? parseFleet(room.v[opp].slice(0, room.v[opp].lastIndexOf(':'))) : null;

    const myTurn = !finished && rp.cur === me && !rp.pending;
    const turnText = finished
      ? (winner === me ? '¡Has ganado! 🎉' : `Gana ${nameOf(winner)}`) + (room.ff ? (room.ff === me ? ' · te has rendido' : ` · ${nameOf(room.ff)} se ha rendido`) : '')
      : rp.cur === me ? (rp.pending ? `Esperando la respuesta de ${nameOf(opp)}…` : '¡Tu turno! Dispara al mar enemigo')
        : (rp.pending ? `${nameOf(opp)} ha disparado…` : `Turno de ${nameOf(opp)}`);

    const enemy = boardEl({ ships: oppShips || [], marks: eMarks, cls: myTurn ? 'turn' : '', onCell: myTurn ? (c) => shoot(c, eMarks) : null });
    const own = boardEl({ ships: mine?.ships || [], sunk: mySunk, marks: oMarks, cls: 'small' });

    // barcos enemigos que quedan (por tamaño, según lo que he hundido)
    const left = [...LENS]; for (const L of eSunk) { const k = left.indexOf(L); if (k >= 0) left.splice(k, 1); }
    const chips = h('div', { class: 'fl-left' }, LENS.map((L, i) => { const alive = left.includes(L) && (left.splice(left.indexOf(L), 1), true); return h('span', { class: 'fl-mini' + (alive ? '' : ' dead'), title: NAMES[i], html: shipSvg(i, false), style: { width: L * 18 + 'px' } }); }));

    const lastEv = rp.log[rp.log.length - 1];
    if (lastLog < 0) lastLog = rp.log.length;
    else if (rp.log.length > lastLog) {
      lastLog = rp.log.length;
      const who = lastEv.by === me ? 'Tu disparo' : `Disparo de ${nameOf(lastEv.by)}`;
      banner = lastEv.res === '0' ? `${who}: agua` : lastEv.res === '1' ? `${who}: ¡tocado!` : `${who}: ¡hundido! (${NAMES[LENS.indexOf(Number(lastEv.res))] || 'barco'})`;
      bannerAt = Date.now(); sound(lastEv.res);
    }
    const bannerEl = banner && Date.now() - bannerAt < 2600 ? h('div', { class: 'fl-banner ' + (lastEv?.res === '0' ? 'w' : 'h') }, banner) : null;
    if (bannerEl) { enemy.append(bannerEl); setTimeout(() => bannerEl.remove(), 2600 - (Date.now() - bannerAt)); }

    const hits = rp.log.filter((e) => e.by === me && e.res !== '0').length, shots = rp.log.filter((e) => e.by === me).length;
    const result = finished ? h('div', { class: 'fl-result' },
      h('b', {}, `${shots} disparos · ${hits} aciertos`), verifyMsg ? h('p', { class: verifyMsg.startsWith('⚠') ? 'err' : 'dim' }, verifyMsg) : (room.v[opp] || room.ff ? null : h('p', { class: 'dim small' }, `Esperando a que ${nameOf(opp)} muestre su flota…`)),
      h('button', { class: 'btn big', onclick: () => close() }, 'Cerrar')) : null;

    fill(body, h('div', { class: 'fl-main' },
      h('div', { class: 'fl-status' + (myTurn ? ' mine' : '') + (finished ? ' end' : '') }, turnText),
      h('div', { class: 'fl-boards' },
        h('div', { class: 'fl-side' }, h('h3', {}, 'Flota de ', nameOf(opp), ' ', avatarEl(opp, 22)), enemy, chips),
        h('div', { class: 'fl-side own' }, h('h3', {}, 'Tu flota'), own)),
      result,
      !finished ? h('button', { class: 'text-btn fl-leave', onclick: forfeit }, 'Rendirse') : null));
  }

  async function shoot(c, marks) {
    if (marks.has(c) || busy) return;
    busy = true;
    try {
      const next = (room.s[me] || '') + pad2(c);
      await api.shoot(room, next); room.s[me] = next; sig = ''; render();
      timer.poke(900);
    } catch (e) { toast(e.message || 'No se pudo disparar'); }
    busy = false;
  }

  const timer = poller(poll, 2200, 9000);
  function close() { if (closed) return; closed = true; timer.stop(); darkBars(false); root.remove(); onClose?.(); }
  body.append(h('p', { class: 'dim', style: { padding: '16px' } }, 'Cargando…'));
  return root;
}
