// Hundir la flota: dos jugadores, tablero de 10 × 10 y la flota clásica (5, 4, 3, 3 y 2). La sala vive en la nube (`frooms/{id}`) y se
// consulta cada poco. Cada jugador guarda su flota en su dispositivo y deja en la nube solo un hash; las respuestas («agua», «tocado»,
// «hundido») las da el dispositivo del defensor y, al terminar, se revela la flota para comprobar que no hubo trampa.
// Las reglas del juego están en fleet-engine.js. `api` lo pone social.js.

import { h, icon, toast, confirmDialog, modal, fill, poller, darkPref, darkToggle, darkBars } from './ui.js';
import { SIZE, LENS, NAMES, cellsOf, canPlace, packFleet, parseFleet, randomFleet, newSalt, commitOf, answers, shotCells, replay, sunkShips, parseRevealed, newReveals, verify, shipParts, pad2 } from './fleet-engine.js';

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
const shipBox = (i, s) => {
  const v = s.dir === 'v', r = Math.floor(s.cell / SIZE), c = s.cell % SIZE, L = LENS[i];
  return { left: c * 10 + '%', top: r * 10 + '%', width: (v ? 10 : L * 10) + '%', height: (v ? L * 10 : 10) + '%' };
};
const shipEl = (i, s, extra = '') => h('div', { class: 'fl-ship ' + extra, 'data-i': i, style: shipBox(i, s), html: shipSvg(i, s.dir === 'v') });

// ───── sonidos sencillos (sintetizados) ─────
let actx = null;
const ac = () => { const AC = window.AudioContext || window.webkitAudioContext; if (!AC) return null; actx ||= new AC(); if (actx.state === 'suspended') actx.resume(); return actx; };
function noise(dur, { vol = 0.2, hp = 800, lp = 6000, at = 0 } = {}) {
  const c = ac(); if (!c) return;
  const n = Math.floor(c.sampleRate * dur), buf = c.createBuffer(1, n, c.sampleRate), d = buf.getChannelData(0);
  for (let i = 0; i < n; i++) d[i] = (Math.random() * 2 - 1) * (1 - i / n);
  const src = c.createBufferSource(), g = c.createGain(), f1 = c.createBiquadFilter(), f2 = c.createBiquadFilter();
  f1.type = 'highpass'; f1.frequency.value = hp; f2.type = 'lowpass'; f2.frequency.value = lp; g.gain.value = vol;
  src.buffer = buf; src.connect(f1).connect(f2).connect(g).connect(c.destination); src.start(c.currentTime + at);
}
function tone(f, at, dur, { type = 'sine', vol = 0.2, to = f } = {}) {
  const c = ac(); if (!c) return;
  const o = c.createOscillator(), g = c.createGain(), t = c.currentTime + at;
  o.type = type; o.frequency.setValueAtTime(f, t); o.frequency.exponentialRampToValueAtTime(Math.max(25, to), t + dur);
  g.gain.setValueAtTime(vol, t); g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
  o.connect(g).connect(c.destination); o.start(t); o.stop(t + dur + 0.02);
}
/** '0' agua, '1' tocado, '2'–'5' hundido; además 'shot' (disparo), 'place' (barco colocado), 'turn' (te toca), 'win' y 'lose'. */
const sound = (kind) => {
  try {
    switch (kind) {
      case '0': noise(0.4, { vol: 0.26, hp: 500, lp: 3500 }); tone(240, 0, 0.3, { vol: 0.12, to: 90 }); break; // chapuzón
      case '1': tone(160, 0, 0.4, { vol: 0.34, to: 40 }); noise(0.25, { vol: 0.3, hp: 200, lp: 2500 }); break; // impacto
      case 'shot': noise(0.22, { vol: 0.12, hp: 1200, lp: 6500 }); tone(320, 0, 0.22, { type: 'sawtooth', vol: 0.05, to: 1000 }); break;
      case 'place': tone(440, 0, 0.07, { type: 'square', vol: 0.05, to: 300 }); noise(0.05, { vol: 0.1, hp: 1500, lp: 4000 }); break;
      case 'turn': tone(660, 0, 0.12, { vol: 0.16 }); tone(880, 0.12, 0.22, { vol: 0.16 }); break;
      case 'win': [523, 659, 784, 1047].forEach((f, i) => tone(f, i * 0.14, 0.4, { vol: 0.2 })); break;
      case 'lose': [392, 330, 262, 196].forEach((f, i) => tone(f, i * 0.22, 0.45, { type: 'triangle', vol: 0.22 })); break;
      default: // hundido: explosión grande, con un segundo estallido
        tone(120, 0, 0.8, { vol: 0.45, to: 28 }); noise(0.6, { vol: 0.34, hp: 150, lp: 2200 });
        tone(80, 0.25, 0.7, { vol: 0.32, to: 25 }); noise(0.4, { vol: 0.22, hp: 200, lp: 1800, at: 0.25 });
    }
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
  let banner = '', bannerHit = false, bannerAt = 0, verifyMsg = '', verifyDone = false, revealing = false, sig = '', lobbySig = '';
  const names = {}, photos = {};
  const body = h('div', { class: 'fl-body' });
  const toggleDark = () => { const on = darkToggle(); root.classList.toggle('dark-bg', on); darkBars(on); };
  const root = h('div', { class: 'pomo fleet' },
    h('div', { class: 'pomo-top' }, h('button', { class: 'icon-btn', 'aria-label': 'Volver', onclick: () => close() }, icon('back')), h('h1', { class: 'grow' }, 'Hundir la flota'),
      h('button', { class: 'icon-btn', 'aria-label': 'Fondo oscuro', title: 'Fondo oscuro', onclick: toggleDark }, icon('moon'))),
    body);
  if (darkPref()) { root.classList.add('dark-bg'); darkBars(true); }
  root.addEventListener('pointerdown', () => { ac(); }); // el navegador solo deja sonar el audio tras un toque
  let wasMine = false, endSounded = false, firstSeen = false;
  // los disparos de una tanda se van mostrando de uno en uno (1,2 s entre cada uno): `shown` = cuántos del registro se ven ya
  let shown = -1, animStart = 0, revealTimer = null, curLog = [];
  const marked = new Set(); // objetivos marcados en mi turno (con 2 o más disparos por turno se marcan todos y se disparan juntos)

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
  const roomState = () => ({ players: room.players, seed: room.seed, again: room.again, shots: room.shots, s: room.s, a: room.a });

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
      // si un envío falla (sin red, o reglas de Firestore sin publicar) la pantalla se actualiza igual: antes el error la dejaba congelada con datos antiguos
      try { await duties(); } catch (e) { dutyFail(e); }
      const s2 = JSON.stringify([room.status, room.h, room.s, room.a, room.v, room.ff]);
      const changed = s2 !== sig; sig = s2;
      if (changed || !body.querySelector('.fl-main, .fl-place, .fl-wait')) render();
      return { changed, delay: 2200 };
    } catch (e) { /* sin red: se reintenta */ }
    return false;
  }

  let lastDutyToast = 0;
  function dutyFail(e) {
    if (Date.now() - lastDutyToast < 15000) return;
    lastDutyToast = Date.now();
    toast('No se pudo sincronizar la partida' + (e?.message ? `: ${e.message}` : '') + ' (¿faltan por publicar las reglas de Firestore?)', 5000);
  }

  /** Lo que le toca hacer a este dispositivo aunque el jugador no toque nada: responder al disparo del rival y revelar la flota al acabar. */
  async function duties() {
    if (!mine || !room.players.includes(me)) return;
    if (room.status === 'playing' && room.h[me] && oppOf() && room.h[oppOf()]) {
      const rp = replay(roomState());
      if (rp.pending && rp.cur !== me) {
        const mineAns = room.a[me] || '', n = rp.log.filter((e) => e.by === rp.cur).length;
        if (mineAns.length === n) {
          const cells = shotCells(room.s[rp.cur]), take = Math.min(5, cells.length - n); // toda la tanda de disparos a la vez
          const next = mineAns + answers(mine.ships, cells.slice(0, n + take)).slice(n);
          const lost = [...next].filter((ch) => ch >= '2').length >= LENS.length;
          const add = room.reveal ? newReveals(mine.ships, cells.slice(n, n + take), room.rv[me] || '') : '', rvNext = add ? (room.rv[me] || '') + add : null; // los barcos tocados se enseñan enteros
          await api.answer(room, next, lost ? revealStr() : null, rvNext);
          if (rvNext) room.rv[me] = rvNext;
          room.a[me] = next; if (lost) { room.status = 'finished'; room.v[me] = revealStr(); }
        }
      } else if (rp.winner === me) { // he hundido los 5 barcos: la partida termina ya, aunque el rival no tenga la pantalla abierta
        await api.finish(room); room.status = 'finished';
      }
    }
    if (room.status === 'finished' && !room.v[me] && !revealing) {
      revealing = true;
      try { await api.reveal(room, revealStr()); room.v[me] = revealStr(); } catch { /* se reintenta */ }
      revealing = false;
    }
    if (room.status === 'finished' && !verifyDone && oppOf() && room.v[oppOf()] && room.h[oppOf()]) {
      verifyDone = true;
      const why = await verify({ reveal: room.v[oppOf()], commit: room.h[oppOf()], adj: room.adj, shots: room.s[me] || '', given: room.a[oppOf()] || '', rv: room.rv[oppOf()] || '' });
      verifyMsg = why ? `⚠ ${nameOf(oppOf())} ha hecho trampa: ${why}.` : `✓ La flota de ${nameOf(oppOf())} está verificada: todo fue limpio.`;
      render();
    }
  }

  // ───── sala de espera ─────
  const lobbyKey = () => JSON.stringify([room.players, room.invited, room.adj, room.again, room.shots, room.reveal, room.status]);
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
        const v = e.target.checked, next = { adj: room.adj, again: room.again, shots: room.shots, reveal: room.reveal, [key]: v };
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
        toggle('Repetir turno al acertar', 'again', 'Quien acierta (tocado o hundido) vuelve a disparar sin gastar disparo.'),
        toggle('Al tocar un barco, se ve entero', 'reveal', 'Quien defiende enseña dónde está el barco tocado. Si no, solo se ve al hundirlo.'),
        h('div', { class: 'prow' }, h('span', {}, 'Disparos por turno'), h('b', {}, String(room.shots))),
        h('div', { class: 'vs-rounds', role: 'group', 'aria-label': 'Disparos por turno' }, [1, 2, 3, 4, 5].map((n) => h('button', {
          class: 'vs-chip' + (room.shots === n ? ' sel' : ''), disabled: !isHost, 'aria-pressed': room.shots === n,
          onclick: () => { if (n !== room.shots) api.setCfg(room, { adj: room.adj, again: room.again, shots: n, reveal: room.reveal }).then(() => { room.shots = n; renderLobby(); }).catch((err) => toast(err.message)); } }, String(n)))),
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

  // ───── colocar la flota (se arrastra con el dedo; un toque en un barco puesto lo gira) ─────
  let blockClick = 0; // justo después de un gesto con el dedo se ignora el «click» que lo sigue
  function renderPlace() {
    const placed = draft.every((s) => s);
    const next = () => { const n = draft.findIndex((x) => !x); return n < 0 ? null : n; };
    const board = boardEl({ ships: draft, onCell: (c) => {
      if (Date.now() < blockClick) return;
      if (draft.some((s, i) => s && cellsOf(s, i).includes(c))) return; // los barcos puestos se arrastran o se giran con un toque
      if (sel === null) return;
      let row = Math.floor(c / SIZE), col = c % SIZE;
      if (dir === 'h') col = Math.min(col, SIZE - LENS[sel]); else row = Math.min(row, SIZE - LENS[sel]);
      const s = { cell: row * SIZE + col, dir };
      if (canPlace(draft, sel, s, room.adj)) { draft[sel] = s; sel = next(); renderPlace(); }
      else toast(room.adj ? 'Ahí no cabe' : 'Ahí no cabe: entre barcos tiene que quedar una casilla de agua');
    } });
    board.classList.add('placing');
    const sea = board.querySelector('.fl-sea');
    const cellAt = (x, y) => { const r = sea.getBoundingClientRect(), cs = r.width / SIZE, col = Math.floor((x - r.left) / cs), row = Math.floor((y - r.top) / cs); return { col, row, inside: col >= 0 && col < SIZE && row >= 0 && row < SIZE }; };

    /** Arrastre de un barco `i` agarrado por su casilla `k` (0 = la proa) con orientación `d0`. `mode`: 'move' (barco puesto), 'tray' (ficha) o 'sea' (con un barco elegido). */
    function startDrag(e, i, k, d0, mode) {
      const L = LENS[i], x0 = e.clientX, y0 = e.clientY;
      let moved = false, target = null, ghost = null;
      const origEl = mode === 'move' ? sea.querySelector(`.fl-ship[data-i="${i}"]`) : null;
      const snap = (x, y) => {
        const { col, row } = cellAt(x, y);
        const hc = Math.max(0, Math.min(d0 === 'h' ? col - k : col, d0 === 'h' ? SIZE - L : SIZE - 1));
        const hr = Math.max(0, Math.min(d0 === 'v' ? row - k : row, d0 === 'v' ? SIZE - L : SIZE - 1));
        return { cell: hr * SIZE + hc, dir: d0 };
      };
      const show = (x, y) => {
        const { inside } = cellAt(x, y);
        if (!inside) { target = null; ghost?.remove(); ghost = null; return; }
        const s = snap(x, y), ok = canPlace(draft, i, s, room.adj);
        target = { s, ok };
        if (!ghost) { ghost = shipEl(i, s, 'ghost'); sea.append(ghost); if (origEl) origEl.style.opacity = '.25'; }
        Object.assign(ghost.style, shipBox(i, s));
        ghost.classList.toggle('bad', !ok);
      };
      const move = (ev) => {
        if (ev.pointerId !== e.pointerId) return;
        if (!moved && Math.hypot(ev.clientX - x0, ev.clientY - y0) < 6) return;
        moved = true; show(ev.clientX, ev.clientY);
      };
      const stop = () => { document.removeEventListener('pointermove', move); document.removeEventListener('pointerup', end); document.removeEventListener('pointercancel', cancel); };
      const cancel = (ev) => { if (ev.pointerId !== e.pointerId) return; stop(); renderPlace(); };
      const end = (ev) => {
        if (ev.pointerId !== e.pointerId) return;
        stop(); blockClick = Date.now() + 400;
        if (moved || mode === 'sea') {
          if (!moved) show(ev.clientX, ev.clientY); // un toque con un barco elegido: se coloca ahí
          if (target?.ok) { draft[i] = target.s; sel = next(); sound('place'); }
          else if (target) toast(room.adj ? 'Ahí no cabe' : 'Ahí no cabe: entre barcos tiene que quedar una casilla de agua');
        } else if (mode === 'move') { // toque en un barco puesto: lo gira
          const s = draft[i], nd = s.dir === 'h' ? 'v' : 'h';
          let row = Math.floor(s.cell / SIZE), col = s.cell % SIZE;
          if (nd === 'h') col = Math.min(col, SIZE - L); else row = Math.min(row, SIZE - L);
          const ns = { cell: row * SIZE + col, dir: nd };
          if (canPlace(draft, i, ns, room.adj)) draft[i] = ns; else toast('No se puede girar ahí');
        } else { // toque en la ficha: se elige ese barco (si estaba puesto, se recoge)
          if (draft[i]) { dir = draft[i].dir; draft[i] = null; }
          sel = i;
        }
        renderPlace();
      };
      document.addEventListener('pointermove', move); document.addEventListener('pointerup', end); document.addEventListener('pointercancel', cancel);
      if (mode === 'sea') show(e.clientX, e.clientY);
    }

    sea.addEventListener('pointerdown', (e) => {
      if (e.button > 0) return;
      const { col, row, inside } = cellAt(e.clientX, e.clientY);
      if (!inside) return;
      const c = row * SIZE + col, i = draft.findIndex((s, j) => s && cellsOf(s, j).includes(c));
      if (i >= 0) { e.preventDefault(); startDrag(e, i, cellsOf(draft[i], i).indexOf(c), draft[i].dir, 'move'); }
      else if (sel !== null) { e.preventDefault(); startDrag(e, sel, Math.floor(LENS[sel] / 2), dir, 'sea'); }
    });
    const tray = h('div', { class: 'fl-tray' }, LENS.map((L, i) => {
      const chip = h('button', { class: 'fl-chip' + (sel === i ? ' sel' : '') + (draft[i] ? ' placed' : ''), 'aria-label': `${NAMES[i]} (${L})` },
        h('span', { class: 'fl-chip-ship', style: { width: L * 26 + 'px' }, html: shipSvg(i, false) }), h('small', {}, `${NAMES[i]} · ${L}`));
      chip.addEventListener('pointerdown', (e) => { if (e.button > 0) return; e.preventDefault(); startDrag(e, i, Math.floor(L / 2), draft[i]?.dir || dir, 'tray'); });
      return chip;
    }));
    const ctrl = h('div', { class: 'pbtns fl-ctrl' },
      h('button', { class: 'btn outline', disabled: sel === null, onclick: () => { dir = dir === 'h' ? 'v' : 'h'; renderPlace(); } }, dir === 'h' ? 'Girar ↻ (horizontal)' : 'Girar ↻ (vertical)'),
      h('button', { class: 'btn outline', onclick: () => { draft = randomFleet(room.adj); sel = null; renderPlace(); } }, 'Aleatorio'),
      h('button', { class: 'text-btn', onclick: () => { draft = LENS.map(() => null); sel = 0; renderPlace(); } }, 'Limpiar'),
      h('button', { class: 'btn big', disabled: !placed || busy, onclick: ready }, 'Listo'));
    fill(body, h('div', { class: 'fl-place' }, h('p', { class: 'fl-hint' }, placed ? 'Flota lista. Arrastra un barco para moverlo o tócalo para girarlo; pulsa «Listo» cuando quieras.' : sel === null ? 'Arrastra un barco de abajo al mar.' : `Arrastra el ${NAMES[sel].toLowerCase()} (${LENS[sel]}) al mar o toca donde quieras ponerlo.`), board, tray, ctrl));
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
    const opp = oppOf(), rp = replay(roomState());
    curLog = rp.log;
    if (shown < 0 || shown > curLog.length) shown = curLog.length; // lo que ya había al abrir la partida se ve de golpe
    if (curLog.length - shown > 5) shown = curLog.length - 5; // si has estado fuera, solo se pasa de uno en uno la última tanda (hasta 5); lo anterior se ve de golpe
    const animating = shown < curLog.length;
    if (animating && !revealTimer) { animStart = shown; revealTimer = setTimeout(revealStep, 450); }
    const vlog = curLog.slice(0, shown); // lo que se ve ya en los tableros
    const finished = (room.status === 'finished' || !!rp.winner) && !animating; // con los 5 barcos hundidos ya no se dispara más
    const winner = room.ff ? (room.ff === me ? opp : me) : rp.winner;
    // marcas: mis disparos en el mar enemigo y los del rival en el mío
    const eMarks = new Map(), oMarks = new Map(), eSunk = [];
    for (const e of vlog) {
      const mk = e.res === '0' ? 'w' : e.res === '1' ? 'h' : 's';
      (e.by === me ? eMarks : oMarks).set(e.cell, mk);
      if (e.by === me && e.res >= '2') eSunk.push(Number(e.res));
    }
    const myShots = shotCells(room.s[me]);
    if (!finished && rp.cur === me && rp.pending) myShots.slice(curLog.filter((e) => e.by === me).length).forEach((c) => eMarks.set(c, 'p'));
    curLog.slice(shown).filter((e) => e.by === me).forEach((e) => eMarks.set(e.cell, 'p')); // mis disparos ya respondidos que aún no se han revelado
    // mis barcos hundidos (todas sus casillas tocadas)
    const hitCells = new Set([...oMarks].filter(([, v]) => v !== 'w').map(([c]) => c));
    const mySunk = new Set((mine?.ships || []).map((s, i) => i).filter((i) => cellsOf(mine.ships[i], i).every((c) => hitCells.has(c))));
    // el barco del rival se enseña al terminar, si su flota revelada es válida
    const oppShips = finished && room.v[opp] ? parseFleet(room.v[opp].slice(0, room.v[opp].lastIndexOf(':'))) : null;

    const myTurn = !finished && !animating && rp.cur === me && !rp.pending;
    const batch = room.shots > 1; // 2 o más disparos por turno: se marcan todos los objetivos y al pulsar «Disparar» se revelan juntos
    const shotAlready = new Set(eMarks.keys());
    const need = Math.min(rp.left, SIZE * SIZE - shotAlready.size);
    if (!myTurn || !batch) marked.clear(); else for (const c of [...marked]) if (shotAlready.has(c)) marked.delete(c);
    if (myTurn && batch) marked.forEach((c) => eMarks.set(c, 't'));
    const turnText = animating ? `${curLog[shown]?.by === me ? 'Tus disparos' : `Disparos de ${nameOf(curLog[shown]?.by)}`}… ${shown - animStart + 1}/${curLog.length - animStart}` : finished
      ? (winner === me ? '¡Has ganado! 🎉' : `Gana ${nameOf(winner)}`) + (room.ff ? (room.ff === me ? ' · te has rendido' : ` · ${nameOf(room.ff)} se ha rendido`) : '')
      : rp.cur === me ? (rp.pending ? `Esperando la respuesta de ${nameOf(opp)}…` : (batch ? `¡Tu turno! Marca ${need} objetivo${need === 1 ? '' : 's'} (${marked.size}/${need})` : '¡Tu turno! Dispara al mar enemigo'))
        : (rp.pending ? `${nameOf(opp)} ha disparado…` : `Turno de ${nameOf(opp)}${room.shots > 1 ? ` · ${rp.left} disparos` : ''}`);

    const eShips = LENS.map(() => null), eRed = new Set(), sunkCells = new Set();
    const visHits = new Set(vlog.filter((e) => e.by === me && e.res !== '0').map((e) => e.cell));
    if (room.reveal) for (const { idx, ship } of parseRevealed(room.rv[opp])) if (cellsOf(ship, idx).some((c) => visHits.has(c))) { eShips[idx] = ship; eRed.add(idx); } // barco tocado: se ve entero y en rojo
    for (const { idx, ship } of sunkShips(vlog, me)) {
      if (idx < 0) continue;
      if (!eShips.some((s, i) => s && s.cell === ship.cell && s.dir === ship.dir && LENS[i] === LENS[idx])) { eShips[idx] = ship; eRed.add(idx); }
      cellsOf(ship, idx).forEach((c) => sunkCells.add(c));
    }
    const enemy = boardEl({ ships: oppShips || eShips, sunk: eRed, marks: eMarks, cls: myTurn ? 'turn' : '', onCell: myTurn ? (c) => {
      if (sunkCells.has(c)) { toast('Ahí ya has hundido un barco'); return; }
      if (shotAlready.has(c)) return;
      if (!batch) { shoot([c]); return; }
      if (marked.has(c)) marked.delete(c);
      else if (marked.size < need) marked.add(c);
      else { toast(`Ya has marcado ${need}: toca uno marcado para quitarlo`); return; }
      sound('place'); render();
    } : null });
    const own = boardEl({ ships: mine?.ships || [], sunk: mySunk, marks: oMarks, cls: 'small' });

    // barcos enemigos que quedan (por tamaño, según lo que he hundido)
    const left = [...LENS]; for (const L of eSunk) { const k = left.indexOf(L); if (k >= 0) left.splice(k, 1); }
    const chips = h('div', { class: 'fl-left' }, LENS.map((L, i) => { const alive = left.includes(L) && (left.splice(left.indexOf(L), 1), true); return h('span', { class: 'fl-mini' + (alive ? '' : ' dead'), title: NAMES[i], html: shipSvg(i, false), style: { width: L * 11 + 'px' } }); }));

    const first = !firstSeen; firstSeen = true;
    if (!first && !animating) {
      if (myTurn && !wasMine) sound('turn');
      if (finished && !endSounded) { endSounded = true; sound(winner === me ? 'win' : 'lose'); }
    }
    wasMine = myTurn; if (finished && first) endSounded = true;
    const bannerEl = banner && Date.now() - bannerAt < 3200 ? h('div', { class: 'fl-banner ' + (bannerHit ? 'h' : 'w') }, banner) : null;
    if (bannerEl) { enemy.append(bannerEl); setTimeout(() => bannerEl.remove(), 3200 - (Date.now() - bannerAt)); }

    const hits = rp.log.filter((e) => e.by === me && e.res !== '0').length, shots = rp.log.filter((e) => e.by === me).length;
    const result = finished ? h('div', { class: 'fl-result' },
      h('b', {}, `${shots} disparos · ${hits} aciertos`), verifyMsg ? h('p', { class: verifyMsg.startsWith('⚠') ? 'err' : 'dim' }, verifyMsg) : (room.v[opp] || room.ff ? null : h('p', { class: 'dim small' }, `Esperando a que ${nameOf(opp)} muestre su flota…`)),
      h('button', { class: 'btn big', onclick: () => close() }, 'Cerrar')) : null;

    fill(body, h('div', { class: 'fl-main' },
      h('div', { class: 'fl-status' + (myTurn ? ' mine' : '') + (finished ? ' end' : '') }, turnText),
      myTurn && batch ? h('button', { class: 'btn big fl-fire', disabled: marked.size !== need || busy, onclick: () => shoot([...marked]) }, marked.size === need ? `¡Disparar! (${need})` : `Marca ${need - marked.size} más`) : null,
      h('div', { class: 'fl-boards' },
        h('div', { class: 'fl-side' }, h('h3', {}, 'Flota de ', nameOf(opp), ' ', avatarEl(opp, 22)), enemy, chips),
        h('div', { class: 'fl-side own' }, h('h3', {}, 'Tu flota'), own)),
      result,
      !finished ? h('button', { class: 'text-btn fl-leave', onclick: forfeit }, 'Rendirse') : null));
  }

  /** Muestra el siguiente disparo del registro (con su aviso y su sonido) y programa el siguiente 1,2 s después. */
  function revealStep() {
    revealTimer = null;
    if (closed || shown >= curLog.length) return;
    const e = curLog[shown]; shown++;
    const who = e.by === me ? 'Tu disparo' : `Disparo de ${nameOf(e.by)}`;
    banner = e.res === '0' ? `${who}: agua` : e.res === '1' ? `${who}: ¡tocado!` : `${who}: ¡hundido! (${NAMES[LENS.indexOf(Number(e.res))] || 'barco'})`;
    bannerHit = e.res !== '0'; bannerAt = Date.now(); sound(e.res);
    if (shown < curLog.length) revealTimer = setTimeout(revealStep, 1200); // antes de pintar, para que el pintado no programe otro
    render();
  }

  async function shoot(cells) {
    if (!cells.length || busy) return;
    busy = true;
    try {
      const next = (room.s[me] || '') + cells.map(pad2).join('');
      marked.clear();
      sound('shot'); await api.shoot(room, next); room.s[me] = next; sig = ''; render();
      timer.poke(900);
    } catch (e) { toast(e.message || 'No se pudo disparar'); }
    busy = false;
  }

  const timer = poller(poll, 2200, 9000);
  function close() { if (closed) return; closed = true; clearTimeout(revealTimer); timer.stop(); darkBars(false); root.remove(); onClose?.(); }
  body.append(h('p', { class: 'dim', style: { padding: '16px' } }, 'Cargando…'));
  return root;
}
