// Ajedrez entre amigos: tablero verde y piezas «Fresca» de sadsnake1 (CC BY-NC-SA 4.0, colección de lichess), movimientos legales, jaque, relojes, tablas y sonidos.
// Las reglas viven en chess-engine.js. La partida está en la nube (Firestore) y se consulta cada poco: `api` la lee y la escribe.

import { darkPref, darkToggle } from './ui.js';
import { h, icon, toast, confirmDialog } from './ui.js';
import { replay, applyMove, legalMoves, moveString, outcome, positionKey, colorOf, sqName } from './chess-engine.js';

// ───────── sonidos (sintetizados, sin archivos) ─────────

let audio = null;
const ac = () => { const AC = window.AudioContext || window.webkitAudioContext; if (!AC) return null; audio ||= new AC(); if (audio.state === 'suspended') audio.resume(); return audio; };
function tone(ctx, t, { f1, f2 = f1, dur, amp, type = 'sine' }) {
  const o = ctx.createOscillator(), g = ctx.createGain();
  o.type = type; o.frequency.setValueAtTime(f1, t); o.frequency.exponentialRampToValueAtTime(Math.max(20, f2), t + dur);
  g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(amp, t + 0.004); g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
  o.connect(g).connect(ctx.destination); o.start(t); o.stop(t + dur + 0.02);
}
function noise(ctx, t, { dur, amp, freq }) {
  const n = Math.floor(ctx.sampleRate * dur), buf = ctx.createBuffer(1, n, ctx.sampleRate), d = buf.getChannelData(0);
  for (let i = 0; i < n; i++) d[i] = (Math.random() * 2 - 1) * (1 - i / n);
  const s = ctx.createBufferSource(), flt = ctx.createBiquadFilter(), g = ctx.createGain();
  s.buffer = buf; flt.type = 'lowpass'; flt.frequency.value = freq; g.gain.value = amp;
  s.connect(flt).connect(g).connect(ctx.destination); s.start(t);
}
/** pick = coger una pieza · move = soltarla en su casilla (un «toc» de madera) · capture = captura · check = jaque · end = fin de la partida */
export function sound(kind) {
  const ctx = ac(); if (!ctx) return;
  const t = ctx.currentTime + 0.005;
  if (kind === 'pick') tone(ctx, t, { f1: 1900, dur: 0.03, amp: 0.07 });
  else if (kind === 'move') { tone(ctx, t, { f1: 210, f2: 130, dur: 0.09, amp: 0.45 }); noise(ctx, t, { dur: 0.018, amp: 0.22, freq: 2200 }); }
  else if (kind === 'capture') { tone(ctx, t, { f1: 160, f2: 85, dur: 0.14, amp: 0.65 }); noise(ctx, t, { dur: 0.04, amp: 0.5, freq: 1800 }); }
  else if (kind === 'castle') { sound('move'); setTimeout(() => sound('move'), 130); }
  else if (kind === 'check') { tone(ctx, t, { f1: 880, dur: 0.12, amp: 0.25 }); tone(ctx, t + 0.11, { f1: 1175, dur: 0.2, amp: 0.25 }); }
  else if (kind === 'end') [523, 659, 784, 1047].forEach((f, i) => tone(ctx, t + i * 0.14, { f1: f, dur: 0.35, amp: 0.22 }));
}

// ───────── utilidades ─────────

const IMG = (c) => `pieces/${c === c.toUpperCase() ? 'w' : 'b'}${c.toUpperCase()}.svg`;
const VALUE = { q: 9, r: 5, b: 3, n: 3, p: 1 };
const mmss = (ms) => { const s = Math.ceil(ms / 1000); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; };
const REASONS = { mate: 'Jaque mate', resign: 'Rendición', timeout: 'Tiempo agotado', stalemate: 'Rey ahogado', insufficient: 'Material insuficiente', repetition: 'Triple repetición', '50moves': 'Regla de los 50 movimientos', agreement: 'Acuerdo' };

/** Abre la partida `gameId` del chat con `friend`. `api` = { me, get, join, move, end, offer, serverNow, meInfo }. */
export function openChess({ friend, gameId, api, onClose }) {
  const me = api.me();
  let g = null, moves = [], pos = null, keys = [], history = [], result = null;
  let myColor = '', sel = -1, targets = [], lastMove = null, pending = false, timer = null, ticker = null, closed = false, ending = false, promoFor = null;
  let myInfo = { username: '', photo: '' };

  const board = h('div', { class: 'cboard' });
  const squares = Array.from({ length: 64 }, () => h('div', { class: 'csq' }));
  const squareLayer = h('div', { class: 'csquares' }, ...squares);
  const pieceLayer = h('div', { class: 'cpieces' });
  const overlay = h('div', { class: 'cover' });
  board.append(squareLayer, pieceLayer, overlay);
  const topBar = h('div', { class: 'cplayer' }), botBar = h('div', { class: 'cplayer' });
  const banner = h('div', { class: 'cbanner' });
  const actions = h('div', { class: 'cactions' });
  const darkBtn = () => h('button', { class: 'icon-btn', 'aria-label': 'Fondo oscuro', title: 'Fondo oscuro', onclick: () => root.classList.toggle('dark-bg', darkToggle()) }, icon('moon'));
  const fullBtn = () => h('button', { class: 'icon-btn cfull', 'aria-label': 'Pantalla completa', title: 'Pantalla completa', onclick: () => setFull(!root.classList.contains('full')) }, icon('expand'));
  const root = h('div', { class: 'pomo chess' },
    h('div', { class: 'pomo-top' }, h('button', { class: 'icon-btn', 'aria-label': 'Volver', onclick: () => close() }, icon('back')), h('h1', { class: 'grow' }, 'Evadrez'), darkBtn(), fullBtn()),
    h('div', { class: 'chess-main' }, board, h('div', { class: 'cside' }, topBar, banner, actions, botBar)));

  const flip = () => myColor === 'b';
  const colRow = (sq) => { const r = sq >> 3, f = sq & 7; return flip() ? [7 - f, 7 - r] : [f, r]; };
  const placeAt = (el, sq) => { const [c, r] = colRow(sq); el.style.left = c * 12.5 + '%'; el.style.top = r * 12.5 + '%'; };
  const playing = () => g && g.status === 'playing' && !result;
  const myTurn = () => playing() && myColor && pos.turn === myColor;

  // ───── pintado ─────
  function paintSquares() {
    const o = pos ? outcome(pos, keys) : { check: false };
    const kingSq = pos && o.check ? pos.b.indexOf(pos.turn === 'w' ? 'K' : 'k') : -1;
    squares.forEach((el, sq) => {
      const [c, r] = colRow(sq), light = (c + r) % 2 === 0;
      let cls = 'csq ' + (light ? 'l' : 'd');
      if (lastMove && (sq === lastMove.from || sq === lastMove.to)) cls += ' last';
      if (sq === sel) cls += ' sel';
      if (sq === kingSq) cls += ' chk';
      const t = targets.find((m) => m.to === sq);
      if (t) cls += pos.b[sq] !== '' || t.flag === 'ep' ? ' ring' : ' dot';
      el.className = cls;
      el.style.left = c * 12.5 + '%'; el.style.top = r * 12.5 + '%';
      el.textContent = '';
      if (c === 0) el.append(h('i', { class: 'rk' }, String(8 - r2(sq))));
      if (r === 7) el.append(h('i', { class: 'fl' }, 'abcdefgh'[sq & 7]));
    });
  }
  const r2 = (sq) => sq >> 3;

  function paintPieces(animate) {
    pieceLayer.replaceChildren();
    if (!pos) return;
    let moved = null;
    pos.b.forEach((c, sq) => {
      if (c === '') return;
      const el = h('img', { class: 'cp', src: IMG(c), alt: '', draggable: 'false' });
      el.dataset.sq = sq;
      placeAt(el, sq);
      pieceLayer.append(el);
      if (animate && lastMove && sq === lastMove.to) moved = el;
    });
    if (moved) { // la pieza que se movió se desliza desde su casilla anterior
      const [tc, tr] = [moved.style.left, moved.style.top];
      placeAt(moved, lastMove.from); moved.style.transition = 'none';
      void moved.offsetWidth;
      moved.style.transition = ''; moved.style.left = tc; moved.style.top = tr;
    }
  }

  const captured = () => {
    const byWhite = [], byBlack = [];
    for (const hh of history) if (hh.captured) (colorOf(hh.captured) === 'b' ? byWhite : byBlack).push(hh.captured.toLowerCase());
    const sortFn = (a, b) => VALUE[b] - VALUE[a];
    byWhite.sort(sortFn); byBlack.sort(sortFn);
    const diff = byWhite.reduce((s, c) => s + VALUE[c], 0) - byBlack.reduce((s, c) => s + VALUE[c], 0);
    return { byWhite, byBlack, diff };
  };

  function clocks() {
    if (!g || !g.mins) return null;
    let w = g.wms, b = g.bms;
    if (playing()) { const el = Math.max(0, api.serverNow() - g.lastAt); if (moves.length % 2 === 0) w -= el; else b -= el; }
    return { w: Math.max(0, w), b: Math.max(0, b) };
  }

  function paintBars() {
    const cap = captured(), clk = clocks();
    const mine = myColor || 'w', theirs = mine === 'w' ? 'b' : 'w';
    const bar = (el, color, name, photo) => {
      const turn = playing() && pos && pos.turn === color;
      const lead = cap.diff * (color === 'w' ? 1 : -1);
      const took = (color === 'w' ? cap.byWhite : cap.byBlack);
      el.className = 'cplayer' + (turn ? ' turn' : '');
      el.replaceChildren(
        h('span', { class: 'cav' }, photo ? h('img', { src: 'data:image/jpeg;base64,' + photo, alt: '' }) : (name || '?').slice(0, 1).toUpperCase()),
        h('span', { class: 'cname' }, name, h('i', { class: 'cdot ' + (color === 'w' ? 'w' : 'b') })),
        h('span', { class: 'ccap' }, ...took.map((c) => h('img', { src: IMG(color === 'w' ? c.toLowerCase() : c.toUpperCase()), alt: '' })), lead > 0 ? h('b', {}, '+' + lead) : null),
        clk ? h('span', { class: 'cclock' + (turn ? ' on' : '') + (clk[color] < 10000 ? ' low' : '') }, mmss(clk[color])) : null);
    };
    bar(topBar, theirs, friend.username, friend.photo);
    topBar.append(h('button', { class: 'icon-btn cfull-exit', 'aria-label': 'Salir de pantalla completa', onclick: () => setFull(false) }, icon('shrink')));
    bar(botBar, mine, myInfo.username || 'Tú', myInfo.photo);
  }

  function paintBanner() {
    banner.replaceChildren();
    actions.replaceChildren();
    if (!g) return;
    if (g.status === 'waiting') { banner.append(h('span', { class: 'cstate' }, g.creator === me ? 'Esperando rival…' : 'Uniéndose…')); return; }
    const o = pos ? outcome(pos, keys) : { check: false };
    if (playing() && o.check) banner.append(h('span', { class: 'cstate chk' }, 'Jaque'));
    else if (playing()) banner.append(h('span', { class: 'cstate' }, myTurn() ? 'Tu turno' : `Turno de ${friend.username}`));
    if (playing() && g.drawOffer && g.drawOffer !== me) {
      banner.append(h('span', { class: 'cstate' }, 'Propone tablas'),
        h('button', { class: 'btn small-btn', onclick: () => finish('d', 'agreement') }, 'Aceptar'),
        h('button', { class: 'btn outline small-btn', onclick: () => api.offer('').then(poll) }, 'Rechazar'));
    }
    if (playing() && myColor) {
      actions.append(
        h('button', { class: 'btn outline small-btn', disabled: g.drawOffer === me, onclick: async () => { await api.offer(me); toast('Tablas propuestas'); poll(); } }, g.drawOffer === me ? 'Tablas propuestas' : 'Tablas'),
        h('button', { class: 'btn outline small-btn', onclick: async () => { if (await confirmDialog('Rendirse', '¿Seguro que quieres rendirte?', 'Rendirme')) finish(myColor === 'w' ? 'b' : 'w', 'resign'); } }, 'Rendirse'));
    }
  }

  function paintOverlay() {
    overlay.replaceChildren();
    overlay.classList.remove('show');
    if (promoFor) {
      overlay.classList.add('show');
      overlay.append(h('div', { class: 'cpromo' }, ...['q', 'r', 'b', 'n'].map((p) => h('button', { class: 'cpb', onclick: () => { const m = promoFor.find((x) => x.promo === p); promoFor = null; paintOverlay(); commit(m); } },
        h('img', { src: IMG(myColor === 'w' ? p.toUpperCase() : p), alt: p })))));
      return;
    }
    const res = result || (g && g.status === 'finished' ? { result: g.result, reason: g.reason } : null);
    if (!res) return;
    const won = res.result === myColor, draw = res.result === 'd';
    overlay.classList.add('show');
    overlay.append(h('div', { class: 'cend' },
      h('h2', {}, draw ? 'Tablas' : 'Gana ' + (won ? myInfo.username || 'Tú' : friend.username)),
      h('p', { class: 'dim' }, (REASONS[res.reason] || '') + (!draw && res.reason === 'mate' ? '' : '')),
      h('button', { class: 'btn', onclick: () => close() }, 'Cerrar')));
  }

  function render(animate = false) {
    paintSquares(); paintPieces(animate); paintBars(); paintBanner(); paintOverlay();
  }

  // ───── jugadas ─────
  function select(sq) {
    sel = sq;
    targets = legalMoves(pos).filter((m) => m.from === sq);
    sound('pick');
    paintSquares();
  }
  function deselect() { sel = -1; targets = []; paintSquares(); }

  function tryMove(from, to) {
    const cands = targets.filter((m) => m.from === from && m.to === to);
    if (!cands.length) return false;
    if (cands.length > 1) { promoFor = cands; paintOverlay(); return true; } // promoción: elegir pieza
    commit(cands[0]);
    return true;
  }

  async function commit(m) {
    if (!m || pending) return;
    const mover = pos.turn, capture = pos.b[m.to] !== '' || m.flag === 'ep';
    const str = moveString(m);
    // reloj: al que mueve se le descuenta lo que ha tardado
    let wms = g.wms, bms = g.bms;
    if (g.mins) { const el = Math.max(0, api.serverNow() - g.lastAt); if (mover === 'w') wms = Math.max(0, wms - el); else bms = Math.max(0, bms - el); }
    history.push({ m, captured: m.flag === 'ep' ? (mover === 'w' ? 'p' : 'P') : pos.b[m.to], piece: pos.b[m.from] });
    pos = applyMove(pos, m); moves.push(str); keys.push(positionKey(pos)); lastMove = { from: m.from, to: m.to };
    sel = -1; targets = [];
    const o = outcome(pos, keys);
    sound(o.over ? 'end' : o.check ? 'check' : m.flag === 'castle' ? 'castle' : capture ? 'capture' : 'move');
    g = { ...g, moves: [...moves], wms, bms, lastAt: api.serverNow(), drawOffer: '' };
    if (o.over) result = { result: o.result, reason: o.reason };
    render(true);
    pending = true;
    try { await api.move(moves, wms, bms, o.over ? { result: o.result, reason: o.reason } : null); }
    catch (e) { toast(e.message || 'No se pudo enviar la jugada'); result = null; moves = []; g = { ...g, moves: [] }; }
    pending = false;
    poll();
  }

  async function finish(res, reason) {
    if (ending) return;
    ending = true;
    try { await api.end(res, reason); result = { result: res, reason }; sound('end'); } catch (e) { toast(e.message || 'No se pudo terminar la partida'); }
    ending = false;
    poll();
  }

  // ───── sincronización con la nube ─────
  function applyRemote(remote) {
    const before = moves.length;
    g = remote;
    myColor = g.white === me ? 'w' : g.black === me ? 'b' : '';
    if (g.moves.length > moves.length || !pos) {
      const r = replay(g.moves);
      if (!r) { toast('La partida tiene una jugada no válida'); return; }
      pos = r.p; keys = r.keys; history = r.history; moves = [...g.moves];
      const last = r.history[r.history.length - 1];
      lastMove = last ? { from: last.m.from, to: last.m.to } : null;
      sel = -1; targets = [];
      if (before && last) { // jugada del rival
        const o = outcome(pos, keys);
        sound(o.over ? 'end' : o.check ? 'check' : last.m.flag === 'castle' ? 'castle' : last.captured ? 'capture' : 'move');
      }
      render(!!before);
    } else render(false);
    if (g.status === 'finished' && !result) { result = { result: g.result, reason: g.reason }; sound('end'); render(false); }
    // si alguien dejó la partida acabada en el tablero pero sin cerrar en la nube, se cierra
    if (g.status === 'playing' && pos) { const o = outcome(pos, keys); if (o.over && !ending) finish(o.result, o.reason); }
  }

  async function poll() {
    if (closed || pending) return;
    try {
      let remote = await api.get();
      if (!remote) return;
      if (remote.status === 'waiting' && remote.creator !== me) { await api.join(remote); remote = await api.get(); }
      if (remote.moves.length >= moves.length || remote.status === 'finished') applyRemote(remote);
    } catch { /* sin red: se reintenta */ }
  }

  function tickClock() {
    if (!g) return;
    paintBars();
    const clk = clocks();
    if (clk && playing() && !ending && (clk.w <= 0 || clk.b <= 0)) finish(clk.w <= 0 ? 'b' : 'w', 'timeout');
  }

  // ───── ratón y dedo: tocar una pieza, tocar su destino, o arrastrarla ─────
  const sqFromPoint = (x, y) => {
    const r = board.getBoundingClientRect(), c = Math.floor((x - r.left) / r.width * 8), rr = Math.floor((y - r.top) / r.height * 8);
    if (c < 0 || c > 7 || rr < 0 || rr > 7) return -1;
    return flip() ? (7 - rr) * 8 + (7 - c) : rr * 8 + c;
  };

  board.addEventListener('pointerdown', (e) => {
    if (promoFor || !pos) return;
    ac();
    const sq = sqFromPoint(e.clientX, e.clientY);
    if (sq < 0 || !myTurn() || pending) return;
    if (sel >= 0 && targets.some((m) => m.to === sq)) { tryMove(sel, sq); return; }
    const c = pos.b[sq];
    if (c !== '' && colorOf(c) === myColor) { select(sq); startDrag(e, sq); } else deselect();
  });

  function startDrag(e, sq) {
    const el = pieceLayer.querySelector(`[data-sq="${sq}"]`);
    if (!el) return;
    const sx = e.clientX, sy = e.clientY;
    let dragging = false;
    const move = (ev) => {
      if (!dragging && Math.hypot(ev.clientX - sx, ev.clientY - sy) > 6) { dragging = true; el.style.transition = 'none'; el.classList.add('drag'); }
      if (dragging) {
        const r = board.getBoundingClientRect();
        el.style.left = ((ev.clientX - r.left) / r.width * 100 - 6.25) + '%';
        el.style.top = ((ev.clientY - r.top) / r.height * 100 - 6.25) + '%';
      }
    };
    const up = (ev) => {
      window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up); window.removeEventListener('pointercancel', up);
      if (!dragging) return;
      el.classList.remove('drag');
      const to = sqFromPoint(ev.clientX, ev.clientY);
      if (to >= 0 && to !== sq && targets.some((m) => m.to === to) && tryMove(sq, to)) return;
      el.style.transition = ''; placeAt(el, sq); // vuelve a su casilla
    };
    window.addEventListener('pointermove', move); window.addEventListener('pointerup', up); window.addEventListener('pointercancel', up);
  }

  if (darkPref()) root.classList.add('dark-bg');

  /** Pantalla completa: se quitan la cabecera, el estado y los botones y el tablero se agranda (y, si el navegador lo permite, se oculta todo lo demás). */
  function setFull(on) {
    root.classList.toggle('full', on);
    if (on) root.requestFullscreen?.().catch(() => {});
    else if (document.fullscreenElement) document.exitFullscreen?.().catch(() => {});
  }
  const onFsChange = () => { if (!document.fullscreenElement && root.classList.contains('full') && root.dataset.nativeFs) root.classList.remove('full'); };
  document.addEventListener('fullscreenchange', onFsChange);
  root.addEventListener('fullscreenchange', () => { if (document.fullscreenElement === root) root.dataset.nativeFs = '1'; });

  function close() { closed = true; document.removeEventListener('fullscreenchange', onFsChange); if (document.fullscreenElement) document.exitFullscreen?.().catch(() => {}); clearInterval(timer); clearInterval(ticker); root.remove(); onClose?.(); }

  api.meInfo().then((i) => { myInfo = i; paintBars(); }).catch(() => {});
  render();
  poll(); timer = setInterval(poll, 1200); ticker = setInterval(tickClock, 250);
  return root;
}
