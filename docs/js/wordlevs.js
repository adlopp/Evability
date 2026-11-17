// Wordle versus: de 2 a 4 jugadores resuelven las mismas palabras (de 1 a 5 rondas) y gana quien más puntos junta.
// Cada letra amarilla vale 1 punto y cada verde 2; acertar la palabra son 10 (2 × 5). En cada ronda cuenta el mejor intento.
// La sala vive en la nube (`wrooms/{id}`): cada jugador escribe solo su avance (`g[uid]`) y los demás lo ven en directo.
// `api` lo pone social.js. Las palabras de cada ronda salen de la semilla de la sala, así que son las mismas para todos.

import { h, icon, toast, confirmDialog, modal, fill, poller, darkPref, darkToggle, darkBars } from './ui.js';
import { evaluate, wordLists } from './wordle.js';

export const COLORS = ['#E53935', '#1E88E5', '#43A047', '#FB8C00'];
const ROWS = 6;
/** Tiempos que puede elegir el anfitrión (segundos por palabra). */
export const TIMES = [30, 45, 60, 90, 120, 180, 300];
const timeLabel = (s) => (s < 60 ? `${s} s` : s % 60 === 0 ? `${s / 60} min` : `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`);
const KEYS = ['QWERTYUIOP', 'ASDFGHJKLÑ', 'ZXCVBNM'];

/** Palabra de la ronda `r` (la misma en todos los dispositivos). */
export const wordFor = (answers, seed, r) => answers[(seed + r * 104729) % answers.length];
/** Puntos de un intento: verde 2, amarilla 1, gris 0 (acertar = 10). */
export const scoreOf = (guess, answer) => evaluate(guess, answer).reduce((s, v) => s + v, 0);

// El avance de un jugador: `w` = palabras de cada ronda («a,b|c»), `rs` = puntos de las rondas terminadas («10,4») y `st` = cuándo empezó la ronda.
export const parseEntry = (e) => ({
  segs: e?.w ? e.w.split('|').map((s) => (s ? s.split(',') : [])) : [],
  rs: e?.rs ? e.rs.split(',').map(Number) : [],
  st: e?.st || 0,
});
export const packEntry = (m) => ({ w: m.segs.map((s) => s.join(',')).join('|'), rs: m.rs.join(','), st: m.st });
const total = (m) => m.rs.reduce((a, b) => a + b, 0);
const clock = (left) => `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`;

export function openWordleVs({ roomId, api, onClose }) {
  const me = api.me();
  let room = null, lists = null, closed = false, mine = null, dirty = false, current = '', msg = '', msgGood = false, lastSig = '', lobbySig = '';
  const names = {}, photos = {};
  const body = h('div', { class: 'vs-body' });
  const toggleDark = () => { const on = darkToggle(); root.classList.toggle('dark-bg', on); darkBars(on); };
  const root = h('div', { class: 'pomo wordle vs' },
    h('div', { class: 'pomo-top' }, h('button', { class: 'icon-btn', 'aria-label': 'Volver', onclick: () => close() }, icon('back')), h('h1', { class: 'grow' }, 'Wordle versus'),
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
        if (!body.querySelector('.vs-lobby') || lobbySig !== lobbyKey()) renderLobby();
        return { changed: true, delay: 3500 };
      }
      if (!lists) lists = await wordLists();
      if (!mine) mine = parseEntry(room.g[me]);
      else if (dirty) await push(false);
      const sig = JSON.stringify([room.status, room.players, room.g]);
      const changed = sig !== lastSig; lastSig = sig;
      if (changed || !body.querySelector('.vs-game')) renderGame();
      return { changed, delay: 2500 };
    } catch (e) { /* sin red: se reintenta */ }
    return false;
  }

  // ───── sala de espera ─────
  const lobbyKey = () => JSON.stringify([room.players, room.invited, room.rounds, room.timer, room.secs, room.status]);
  function renderLobby() {
    lobbySig = lobbyKey();
    const isHost = room.host === me, inRoom = room.players.includes(me);
    const canJoin = !inRoom && (room.invited.includes(me) || !!room.group) && room.players.length < 4; // en la sala de un grupo entra cualquier miembro hasta que se llene
    const seats = h('div', { class: 'plobby-seats' }, [0, 1, 2, 3].map((i) => {
      const u = room.players[i];
      if (u) return h('div', { class: 'pseat', style: `--c:${COLORS[i]}` }, avatarEl(u, 34), h('b', {}, nameOf(u)), u === room.host ? h('span', { class: 'vs-crown', title: 'Anfitrión' }, '♛') : null);
      const inv = room.invited.filter((x) => !room.players.includes(x))[i - room.players.length];
      return h('div', { class: 'pseat empty', style: `--c:${COLORS[i]}` }, inv ? [avatarEl(inv, 34), h('b', {}, nameOf(inv)), h('small', {}, '…')] : h('b', {}, '—'));
    }));
    const setCfg = async (patch) => {
      const next = { rounds: room.rounds, timer: room.timer, secs: room.secs, ...patch };
      try { await api.setCfg(room, next); Object.assign(room, next); renderLobby(); } catch (e) { toast(e.message); }
    };
    const chips = h('div', { class: 'vs-rounds', role: 'group', 'aria-label': 'Número de rondas' }, [1, 2, 3, 4, 5].map((n) => h('button', {
      class: 'vs-chip' + (room.rounds === n ? ' sel' : ''), disabled: !isHost, 'aria-pressed': room.rounds === n, onclick: () => { if (n !== room.rounds) setCfg({ rounds: n }); } }, String(n))));
    const toggle = h('label', { class: 'ptoggle' }, h('span', {}, 'Temporizador por palabra'),
      h('input', { type: 'checkbox', checked: room.timer, disabled: !isHost, onchange: (e) => { const v = e.target.checked; setCfg({ timer: v }); } }), h('i', {}));

    const buttons = [];
    if (canJoin) buttons.push(h('button', { class: 'btn big', onclick: async () => { try { await api.join(room); await poll(); } catch (e) { toast(e.message); } } }, 'Unirse'));
    if (isHost) {
      if (!room.group) buttons.push(h('button', { class: 'btn outline', onclick: inviteMore }, 'Invitar'));
      buttons.push(h('button', { class: 'btn big', disabled: room.players.length < 2, onclick: async () => { try { await api.start(room, Math.floor(Math.random() * 2000000000)); await poll(); } catch (e) { toast(e.message); } } }, room.players.length < 2 ? 'Esperando jugadores…' : 'Empezar'));
      buttons.push(h('button', { class: 'text-btn', onclick: async () => { if (await confirmDialog('Cerrar sala', '', 'Cerrar')) { await api.remove(room).catch(() => {}); close(); } } }, 'Cerrar sala'));
    } else if (inRoom) buttons.push(h('button', { class: 'text-btn', onclick: async () => { try { await api.leave(room); close(); } catch (e) { toast(e.message); } } }, 'Salir'));

    fill(body, h('div', { class: 'poly-lobby vs-lobby' }, seats,
      h('div', { class: 'pcfg' },
        h('div', { class: 'prow' }, h('span', {}, 'Rondas'), h('b', {}, String(room.rounds))), chips, toggle,
        room.timer ? h('div', { class: 'vs-times', role: 'group', 'aria-label': 'Tiempo por palabra' }, TIMES.map((s) => h('button', {
          class: 'vs-chip' + (room.secs === s ? ' sel' : ''), disabled: !isHost, 'aria-pressed': room.secs === s, onclick: () => { if (s !== room.secs) setCfg({ secs: s }); } }, timeLabel(s)))) : null,
        h('p', { class: 'dim small vs-rules' }, 'Letra amarilla: 1 punto · letra verde: 2 puntos · acertar la palabra: 10 puntos. En cada ronda cuenta tu mejor intento. Pueden jugar de 2 a 4 personas.')),
      h('div', { class: 'pbtns' }, buttons)));
  }

  async function inviteMore() {
    let friends = [];
    try { friends = await api.friends(); } catch (e) { toast(e.message); return; }
    const free = friends.filter((f) => !room.invited.includes(f.uid) && !room.players.includes(f.uid));
    const slots = 3 - room.invited.length;
    const list = h('div', { class: 'stack' }, free.length && slots > 0 ? free.map((f) => h('button', { class: 'game-row', onclick: async () => {
      try { await api.invite(room, f.uid); names[f.uid] = f.username; photos[f.uid] = f.photo; room.invited.push(f.uid); m.close(); renderLobby(); toast('Invitación enviada'); } catch (e) { toast(e.message); }
    } }, h('span', { class: 'pav', style: { width: '30px', height: '30px' } }, f.photo ? h('img', { src: 'data:image/jpeg;base64,' + f.photo, alt: '' }) : f.username.slice(0, 1).toUpperCase()), h('div', {}, f.username))) : h('p', { class: 'dim' }, '—'));
    const m = modal({ title: 'Invitar', content: list, actions: [{ label: 'Cerrar' }] });
  }

  // ───── partida ─────
  const kOf = (e) => parseEntry(e).rs.length;
  const myK = () => mine.rs.length;
  const answerOf = (r) => wordFor(lists.answers, room.seed, r);
  const behind = (need) => room.players.filter((u) => u !== me && kOf(room.g[u]) < need);
  const allDone = () => room.players.every((u) => (u === me ? myK() : kOf(room.g[u])) >= room.rounds);
  const bestOf = (words, ans) => (words.length ? Math.max(...words.map((g) => scoreOf(g, ans))) : 0);
  const sq = (s) => (s === 2 ? 's2' : s === 1 ? 's1' : 's0');

  async function push(over) {
    try { await api.progress(room, packEntry(mine), over); dirty = false; } catch (e) { dirty = true; toast(e.message || 'Sin conexión: se reintenta'); }
  }
  function setMsg(t, good = false) { msg = t; msgGood = good; }

  async function startRound() {
    const k = myK();
    while (mine.segs.length < k + 1) mine.segs.push([]);
    mine.st = Date.now(); current = ''; setMsg('');
    renderGame(); await push(false);
  }
  async function finishRound(timeout) {
    const k = myK(), words = mine.segs[k] || [], ans = answerOf(k), pts = bestOf(words, ans), solved = words[words.length - 1] === ans;
    mine.rs.push(pts); mine.st = 0; current = '';
    setMsg(solved ? `¡Acertada! +${pts} puntos` : `${timeout ? 'Se acabó el tiempo. ' : ''}Era ${ans.toUpperCase()} · +${pts} puntos`, solved);
    const over = myK() >= room.rounds && behind(room.rounds).length === 0;
    renderGame(); await push(over);
  }
  function press(k) {
    if (!lists || !mine || mine.st <= 0 || myK() >= room.rounds) return;
    setMsg('');
    const r = myK(), words = mine.segs[r];
    if (k === '⌫') current = current.slice(0, -1);
    else if (k === 'ENTER') {
      if (current.length < 5) setMsg('Faltan letras');
      else if (!lists.valid.has(current)) setMsg('No está en la lista');
      else {
        words.push(current); current = '';
        if (words[words.length - 1] === answerOf(r) || words.length >= ROWS) { finishRound(false); return; }
        renderGame(); push(false); return;
      }
    } else if (current.length < 5) current += k.toLowerCase();
    renderGame();
  }
  const onKey = (e) => {
    if (e.ctrlKey || e.metaKey || e.altKey || document.querySelector('.overlay')) return;
    if (e.key === 'Enter') press('ENTER');
    else if (e.key === 'Backspace') press('⌫');
    else if (/^[a-zñA-ZÑ]$/.test(e.key)) press(e.key.toUpperCase());
  };
  document.addEventListener('keydown', onKey);

  // temporizador: 1 minuto por palabra (la hora de inicio va en el avance del jugador, así que sigue contando si se recarga)
  let timerEl = null;
  const tickT = setInterval(() => {
    if (!room || !mine || room.status === 'lobby' || mine.st <= 0 || myK() >= room.rounds || !room.timer) return;
    const left = (room.secs || 60) - Math.floor((Date.now() - mine.st) / 1000);
    if (timerEl) { timerEl.textContent = clock(Math.max(0, left)); timerEl.classList.toggle('low', left <= 10); }
    if (left <= 0) finishRound(true);
  }, 250);

  async function forfeit() {
    if (!(await confirmDialog('Abandonar la partida', 'Tus rondas pendientes valdrán 0 puntos y los demás podrán seguir.', 'Abandonar'))) return;
    while (mine.rs.length < room.rounds) mine.rs.push(0);
    mine.st = 0;
    await push(behind(room.rounds).length === 0);
    close();
  }

  const miniGrid = (words, ans) => h('div', { class: 'vs-mini' }, Array.from({ length: ROWS }, (_, r) => {
    const ev = words[r] ? evaluate(words[r], ans) : null;
    return h('div', { class: 'vs-mrow' }, ...Array.from({ length: 5 }, (_, c) => h('i', { class: ev ? sq(ev[c]) : '' })));
  }));

  /** Tarjeta de cada jugador: puntos, intentos que lleva y si está esperando. */
  function playerCard(u) {
    const seat = room.players.indexOf(u), e = u === me ? mine : parseEntry(room.g[u]), k = e.rs.length;
    const showR = Math.min(k, myK(), room.rounds - 1), words = e.segs[showR] || [];
    let status;
    if (u === me) status = myK() >= room.rounds ? 'Terminaste' : mine.st > 0 ? `Intento ${Math.min(ROWS, (mine.segs[myK()] || []).length + 1)}/${ROWS}` : 'Entre rondas';
    else if (k >= room.rounds) status = 'Ha terminado';
    else if (k > myK()) status = `⏳ Te espera · ${words.length} int.`;
    else if (k < myK()) status = `Aún en la ronda ${k + 1}`;
    else if (e.st > 0 || words.length) status = `Intento ${Math.min(ROWS, words.length + (e.st > 0 ? 1 : 0))}/${ROWS}`;
    else status = 'Sin empezar';
    return h('div', { class: 'vs-card' + (u === me ? ' me' : '') + (k > myK() && u !== me ? ' waiting' : ''), style: `--c:${COLORS[seat] || COLORS[0]}` },
      h('div', { class: 'vs-card-top' }, avatarEl(u, 22), h('b', {}, nameOf(u))),
      h('span', { class: 'vs-pts' }, total(e) + ' pts'),
      u === me ? null : miniGrid(words, answerOf(showR)),
      h('small', { class: 'vs-status' }, status));
  }

  function renderGame() {
    if (!room || !lists || !mine) return;
    const k = myK(), over = k >= room.rounds;
    const phase = over ? 'over' : mine.st > 0 ? 'play' : 'between';
    const boardRound = phase === 'play' ? k : phase === 'between' ? Math.max(0, k - 1) : room.rounds - 1;
    const words = mine.segs[boardRound] || [], ans = answerOf(boardRound);

    const best = {};
    for (const g of words) evaluate(g, ans).forEach((s, i) => { best[g[i]] = Math.max(best[g[i]] ?? -1, s); });
    const board = h('div', { class: 'wboard' }, Array.from({ length: ROWS }, (_, r) => {
      const word = words[r] ?? (r === words.length && phase === 'play' ? current : '');
      const ev = words[r] ? evaluate(words[r], ans) : null;
      return h('div', { class: 'wrow' }, ...Array.from({ length: 5 }, (_, c) => h('div', { class: 'wcell' + (ev ? ' s' + ev[c] : word[c] ? ' filled' : '') }, (word[c] || '').toUpperCase())));
    }));
    const kb = h('div', { class: 'wkeys' }, KEYS.map((letters, ri) => h('div', { class: 'wkrow' },
      ri === 2 ? h('button', { class: 'wkey wide', onclick: () => press('ENTER') }, 'ENTER') : null,
      ...[...letters].map((ch) => h('button', { class: 'wkey' + (best[ch.toLowerCase()] !== undefined ? ' s' + best[ch.toLowerCase()] : ''), onclick: () => press(ch) }, ch)),
      ri === 2 ? h('button', { class: 'wkey wide', 'aria-label': 'Borrar', onclick: () => press('⌫') }, '⌫') : null)));

    // entre el tablero y el teclado: botón de siguiente ronda o aviso de a quién se espera
    const action = h('div', { class: 'vs-action' });
    if (phase === 'between') {
      const wait = behind(k);
      action.append(wait.length
        ? h('button', { class: 'btn big', disabled: true }, `Esperando a ${wait.map(nameOf).join(', ')}…`)
        : h('button', { class: 'btn big', onclick: startRound }, `Empezar ronda ${k + 1}`));
    } else if (phase === 'over' && !allDone()) {
      action.append(h('button', { class: 'btn big', disabled: true }, `Esperando a ${behind(room.rounds).map(nameOf).join(', ')}…`));
    }
    timerEl = room.timer && phase === 'play' ? h('span', { class: 'vs-timer' }, clock(Math.max(0, (room.secs || 60) - Math.floor((Date.now() - mine.st) / 1000)))) : null;
    const head = h('div', { class: 'vs-head' }, h('b', {}, over ? 'Partida terminada' : `Ronda ${k + 1}/${room.rounds}`), timerEl, h('span', { class: 'vs-pts' }, total(mine) + ' pts'));
    const finishedAll = over && allDone();
    // como el Wordle normal: el tablero en el centro y el teclado debajo; los jugadores, en una columna a la derecha
    const side = h('div', { class: 'vs-side' }, room.players.map(playerCard),
      room.players.length > 1 && !over ? h('button', { class: 'text-btn vs-leave', onclick: forfeit }, 'Abandonar') : null);
    const main = h('div', { class: 'vs-main' }, head, board,
      h('div', { class: 'wmsg' + (msgGood ? ' good' : '') }, msg || (phase === 'between' && k === 0 ? 'Todos jugáis las mismas palabras' : '')), action);
    fill(body, h('div', { class: 'vs-game' + (finishedAll ? ' done' : '') }, main, side, h('div', { class: 'vs-kb' }, finishedAll ? resultsPanel() : kb)));
  }

  function resultsPanel() {
    const rows = room.players.map((u) => { const e = u === me ? mine : parseEntry(room.g[u]); return { u, e, t: total(e) }; }).sort((a, b) => b.t - a.t);
    let place = 0, prev = null;
    const list = rows.map((r, i) => { if (r.t !== prev) { place = i + 1; prev = r.t; } return { ...r, place }; });
    const top = list.filter((r) => r.place === 1);
    const title = list.length === 1 ? `Has hecho ${list[0].t} puntos` : top.length > 1 ? 'Empate' : top[0].u === me ? '¡Has ganado!' : `Gana ${nameOf(top[0].u)}`;
    const answers = Array.from({ length: room.rounds }, (_, r) => answerOf(r).toUpperCase());
    return h('div', { class: 'vs-results' }, h('h2', {}, (top.length === 1 && top[0].u === me && list.length > 1 ? '🏆 ' : '') + title),
      ...list.map((r) => h('div', { class: 'vs-res', style: `--c:${COLORS[room.players.indexOf(r.u)] || COLORS[0]}` },
        h('span', { class: 'vs-place' }, list.length > 1 ? String(r.place) : ''), avatarEl(r.u, 30), h('b', { class: 'grow' }, nameOf(r.u)),
        h('small', {}, r.e.rs.map((p, i) => `R${i + 1}: ${p}`).join(' · ')), h('span', { class: 'vs-pts big' }, r.t + ' pts'))),
      h('p', { class: 'dim small' }, 'Palabras: ' + answers.join(' · ')),
      h('button', { class: 'btn big', onclick: () => close() }, 'Cerrar'));
  }

  const timer = poller(poll, 2500, 9000);
  function close() { if (closed) return; closed = true; timer.stop(); clearInterval(tickT); darkBars(false); document.removeEventListener('keydown', onKey); root.remove(); onClose?.(); }
  body.append(h('p', { class: 'dim', style: { padding: '16px' } }, 'Cargando…'));
  return root;
}
