// Evapoly: Monopoly para 2 a 4 amigos. La sala vive en la nube (Firestore) y se consulta cada poco; las reglas las aplica
// evapoly-engine.js en cada dispositivo con la misma semilla, así todos ven lo mismo. `api` lo pone social.js.

import { h, icon, toast, confirmDialog, modal, fill, poller } from './ui.js';
import { initGame, act, applyText, rentOf, houseCost, groupOf, unmortgageCost, eur, JAIL_FINE } from './evapoly-engine.js';

export const COLORS = ['#E53935', '#1E88E5', '#43A047', '#FB8C00'];
const MONEY_MIN = 500, MONEY_MAX = 2500, MONEY_STEP = 50;

let dataP, toksP;
const loadData = () => (dataP ||= fetch('data/evapoly.json').then((r) => r.json()));
const loadToks = () => (toksP ||= fetch('data/evapoly-tokens.json').then((r) => r.json()));
let iconsP;
const loadIcons = () => (iconsP ||= fetch('data/evapoly-icons.json').then((r) => r.json()));
/** Icono dibujado de data/evapoly-icons.json (para el chat y la sala). */
export async function polyIcon(name) { const i = await loadIcons(); return h('span', { class: 'pico', html: svgOf({ p: i[name] }) }); }
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

const svgInner = (parts) => parts.map((p) => `<path d="${p.d}" fill="${p.f || 'none'}" stroke="${p.s || 'none'}" stroke-width="${p.w}" stroke-linecap="round" stroke-linejoin="round" opacity="${p.o}"/>`).join('');
const svgOf = (t) => `<svg viewBox="0 0 100 100" aria-hidden="true">${t.p.map((p) => `<path d="${p.d}" fill="${p.f || 'none'}" stroke="${p.s || 'none'}" stroke-width="${p.w}" stroke-linecap="round" stroke-linejoin="round" opacity="${p.o}"/>`).join('')}</svg>`;

// ───── sonidos (sintetizados) y música de fondo (más baja que los sonidos) ─────

let audio = null;
const ac = () => { const AC = window.AudioContext || window.webkitAudioContext; if (!AC) return null; audio ||= new AC(); if (audio.state === 'suspended') audio.resume(); return audio; };
function tone(f, at, dur, { type = 'sine', vol = 0.22, to = f } = {}) {
  const c = ac(); if (!c) return;
  const o = c.createOscillator(), g = c.createGain(), t0 = c.currentTime + at;
  o.type = type; o.frequency.setValueAtTime(f, t0); o.frequency.exponentialRampToValueAtTime(Math.max(30, to), t0 + dur);
  g.gain.setValueAtTime(vol, t0); g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
  o.connect(g).connect(c.destination); o.start(t0); o.stop(t0 + dur + 0.02);
}
const clips = {};
/** Sonidos grabados (los dados y la sirena): se reproducen desde el principio cada vez. */
function playClip(file, vol) {
  try { const a = (clips[file] ||= new Audio(file)); a.volume = vol; a.currentTime = 0; a.play().catch(() => {}); } catch { /* sin audio */ }
}
const SFX = {
  dice() { playClip('music/evapoly-dados.mp3', 0.55); },
  siren() { playClip('music/evapoly-policia.mp3', 0.8); },
  step() { tone(520, 0, 0.04, { type: 'triangle', vol: 0.1 }); },
  coin() { tone(988, 0, 0.09, { type: 'triangle' }); tone(1319, 0.09, 0.22, { type: 'triangle' }); },
  pay() { tone(440, 0, 0.1, { type: 'triangle' }); tone(330, 0.1, 0.18, { type: 'triangle' }); },
  buy() { tone(523, 0, 0.12, { type: 'triangle' }); tone(659, 0.1, 0.12, { type: 'triangle' }); tone(784, 0.2, 0.3, { type: 'triangle' }); },
  build() { tone(200, 0, 0.07, { type: 'square', vol: 0.15 }); tone(300, 0.09, 0.07, { type: 'square', vol: 0.15 }); },
  chest() { tone(180, 0, 0.1, { type: 'square', vol: 0.12 }); tone(520, 0.45, 0.5, { vol: 0.12, to: 900 }); tone(784, 1.1, 0.35, { type: 'triangle', vol: 0.15 }); },
  card() { tone(700, 0, 0.08); tone(1050, 0.08, 0.14); },
  turn() { tone(660, 0, 0.12); tone(880, 0.12, 0.22); },
  win() { [523, 659, 784, 1047].forEach((f, i) => tone(f, i * 0.14, 0.3, { type: 'triangle' })); },
};
let music = null;
const musicOn = () => { try { return localStorage.getItem('ev.polyMusic') !== '0'; } catch { return true; } };
function startMusic() {
  if (!musicOn()) return;
  if (!music) { music = new Audio('music/evapoly.mp3'); music.loop = true; music.volume = 0.25; }
  music.play().catch(() => {});
}
const stopMusic = () => { music?.pause(); };

// ───── tablero ─────

const K = 12, E = (100 - 2 * K) / 9;
function cellOf(i) {
  let row, col;
  if (i <= 10) { row = 10; col = 10 - i; } else if (i <= 20) { row = 10 - (i - 10); col = 0; } else if (i <= 30) { row = 0; col = i - 20; } else { row = i - 30; col = 10; }
  const pos = (n) => (n === 0 ? 0 : n === 10 ? 100 - K : K + (n - 1) * E), len = (n) => (n === 0 || n === 10 ? K : E);
  return { l: pos(col), t: pos(row), w: len(col), h: len(row) };
}
const sideOf = (i) => (i % 10 === 0 ? 'corner' : i < 10 ? 'bot' : i < 20 ? 'left' : i < 30 ? 'top' : 'right');
const centerOf = (i) => { const c = cellOf(i); return { x: c.l + c.w / 2, y: c.t + c.h / 2 }; };

const PIPS = { 1: [4], 2: [0, 8], 3: [0, 4, 8], 4: [0, 2, 6, 8], 5: [0, 2, 4, 6, 8], 6: [0, 2, 3, 5, 6, 8] };
/** Un dado que se redibuja cambiando solo los puntos (así la animación de giro no se reinicia). */
function makeDie() {
  const dots = Array.from({ length: 9 }, () => h('i', {}));
  const el = h('div', { class: 'die' }, dots);
  return { el, set: (n) => dots.forEach((d, i) => d.classList.toggle('on', PIPS[n].includes(i))) };
}

const mine = (s, me) => { const a = []; for (let i = 0; i < 40; i++) if (s.own[i] === me) a.push(i); return a; };

/**
 * Abre la sala `roomId`. `api`: me(), get(), profiles(uids), friends(), invite(room, uid), join(room, tok), leave(room), setCfg(room, cfg),
 * setTok(room, tok), start(room, seed), act(room, acts, over), remove(room).
 */
export function openEvapoly({ roomId, api, onClose }) {
  let data = null, toks = null, icons = null;
  const me = api.me();
  let room = null, names = {}, photos = {}, closed = false, timer = null;
  let state = null, acts = [], view = null, busy = false, dice = null, log = [], seen = null;
  const q = []; let running = false;

  const body = h('div', { class: 'poly-body' });
  const musicBtn = h('button', { class: 'icon-btn', 'aria-label': 'Música', onclick: () => {
    const on = !musicOn();
    try { localStorage.setItem('ev.polyMusic', on ? '1' : '0'); } catch { /* sin almacenamiento */ }
    if (on) startMusic(); else stopMusic();
    musicBtn.replaceChildren(icon(on ? 'volume' : 'mute'));
  } }, icon(musicOn() ? 'volume' : 'mute'));
  const fsBtn = h('button', { class: 'icon-btn', 'aria-label': 'Pantalla completa', title: 'Pantalla completa', onclick: () => setFull(!root.classList.contains('full')) }, icon('expand'));
  const root = h('div', { class: 'pomo poly' },
    h('div', { class: 'pomo-top' }, h('button', { class: 'icon-btn', 'aria-label': 'Volver', onclick: () => close() }, icon('back')), h('h1', { class: 'grow' }, 'Evapoly'), fsBtn, musicBtn),
    h('button', { class: 'icon-btn pfull-exit', 'aria-label': 'Salir de pantalla completa', onclick: () => setFull(false) }, icon('shrink')),
    body);
  /** Pantalla completa: se quita la cabecera, el tablero ocupa casi todo en el centro y a un lado queda la información del turno en pequeño. */
  function setFull(on) {
    root.classList.toggle('full', on);
    fsBtn.replaceChildren(icon(on ? 'shrink' : 'expand'));
    setTimeout(paintStrip, 80);
    if (on) root.requestFullscreen?.().catch(() => {});
    else if (document.fullscreenElement) document.exitFullscreen?.().catch(() => {});
  }
  const onFs = () => { if (!document.fullscreenElement && root.classList.contains('full') && root.dataset.nativeFs) { root.classList.remove('full'); fsBtn.replaceChildren(icon('expand')); } };
  document.addEventListener('fullscreenchange', onFs);
  root.addEventListener('fullscreenchange', () => { if (document.fullscreenElement === root) root.dataset.nativeFs = '1'; });

  const nameOf = (uid) => names[uid] || '?';
  const seatOf = (uid) => (room ? room.players.indexOf(uid) : -1);
  const mySeat = () => seatOf(me);
  const seatName = (seat) => nameOf(room.players[seat]);
  const say = (m) => m.replace(/@(\d)/g, (_, n) => seatName(Number(n)));
  const tokenOf = (seat) => toks[room.tok[room.players[seat]] ?? seat] || toks[0];
  const tokEl = (seat, cls = '') => h('span', { class: 'ptk ' + cls, style: `--c:${COLORS[seat]}`, html: svgOf(tokenOf(seat)) });

  async function loadNames() {
    const missing = [...room.players, ...room.invited].filter((u) => !(u in names));
    if (!missing.length) return;
    const p = await api.profiles(missing).catch(() => ({}));
    for (const u of missing) { names[u] = p[u]?.username || '?'; photos[u] = p[u]?.photo || ''; }
  }
  const avatarEl = (uid, size = 34) => h('span', { class: 'pav', style: { width: size + 'px', height: size + 'px', fontSize: size * 0.45 + 'px' } },
    photos[uid] ? h('img', { src: 'data:image/jpeg;base64,' + photos[uid], alt: '' }) : nameOf(uid).slice(0, 1).toUpperCase());

  // ───── consulta periódica ─────
  let lastSig = '';
  async function poll() {
    if (closed) return false;
    try {
      const r = await api.get();
      if (closed) return false;
      if (!r) { toast('La sala se ha cerrado'); close(); return false; }
      const wasLobby = !room || room.status === 'lobby';
      room = r;
      await loadNames();
      if (room.status === 'lobby') { if (!body.querySelector('.poly-lobby') || lobbySig !== sigOf()) renderLobby(); }
      else {
        if (!state) { startState(); renderGame(); }
        else if (wasLobby) { startState(); renderGame(); }
        else syncActs(room.acts);
      }
      // sala de espera: cada 3 s; en la partida, cada 6 s cuando te toca a ti (nadie más va a actuar) y cada 2,2 s si esperas a otro
      const sig = room.status + room.acts.length + room.players.length + JSON.stringify(room.tok) + room.invited.length + room.money;
      const changed = sig !== lastSig; lastSig = sig;
      const myTurn = state && state.phase !== 'over' && state.turn === mySeat() && !state.trade && !['debt', 'auction', 'order'].includes(state.phase);
      return { changed, delay: room.status === 'lobby' ? 4000 : myTurn ? 10000 : 3000 };
    } catch (e) { /* sin red: se reintenta */ }
    return false;
  }

  // ───── sala de espera ─────
  let lobbySig = '';
  const sigOf = () => JSON.stringify([room.players, room.tok, room.invited, room.money, room.goBonus, room.auction, room.pot, room.dblBonus, room.status]);
  function renderLobby() {
    lobbySig = sigOf();
    const isHost = room.host === me, inRoom = room.players.includes(me), invitedOnly = !inRoom && room.invited.includes(me);
    const taken = new Set(Object.entries(room.tok).filter(([u]) => room.players.includes(u) && u !== me).map(([, t]) => t));
    const myTok = room.tok[me];

    const seats = h('div', { class: 'plobby-seats' }, [0, 1, 2, 3].map((i) => {
      const u = room.players[i];
      if (u) return h('div', { class: 'pseat', style: `--c:${COLORS[i]}` }, tokEl(i), avatarEl(u, 30), h('b', {}, nameOf(u)), u === room.host ? h('span', { class: 'pico crown', html: svgOf({ p: icons.crown }) }) : null);
      const inv = room.invited.filter((x) => !room.players.includes(x))[i - room.players.length];
      return h('div', { class: 'pseat empty', style: `--c:${COLORS[i]}` }, inv ? [h('span', { class: 'ptk' }), h('b', {}, nameOf(inv)), h('small', {}, '…')] : h('b', {}, '—'));
    }));

    const tokRow = inRoom ? h('div', { class: 'ptok-row', 'aria-label': 'Ficha' }, toks.map((t, i) => h('button', {
      class: 'ptok-pick' + (myTok === i ? ' sel' : ''), disabled: taken.has(i), 'aria-label': t.n, title: t.n,
      style: `--c:${COLORS[mySeat()]}`, html: svgOf(t),
      onclick: async () => { try { await api.setTok(room, i); room.tok[me] = i; renderLobby(); } catch (e) { toast(e.message); } },
    }))) : null;

    const moneyOut = h('b', {}, eur(room.money));
    const slider = h('input', { type: 'range', min: MONEY_MIN, max: MONEY_MAX, step: MONEY_STEP, value: room.money, disabled: !isHost,
      oninput: () => { moneyOut.textContent = eur(Number(slider.value)); },
      onchange: async () => { try { await api.setCfg(room, { ...cfgOf(), money: Number(slider.value) }); room.money = Number(slider.value); lobbySig = sigOf(); } catch (e) { toast(e.message); } } });
    const toggle = (label, key) => h('label', { class: 'ptoggle' }, h('span', {}, label),
      h('input', { type: 'checkbox', checked: room[key], disabled: !isHost, onchange: async (e) => {
        const v = e.target.checked;
        try { await api.setCfg(room, { ...cfgOf(), [key]: v }); room[key] = v; lobbySig = sigOf(); }
        catch (err) { e.target.checked = !v; toast(err.message); }
      } }), h('i', {}));

    const buttons = [];
    if (invitedOnly) buttons.push(h('button', { class: 'btn big', onclick: async () => { try { await api.join(room, firstFreeTok()); await poll(); } catch (e) { toast(e.message); } } }, 'Unirse'));
    if (isHost) {
      buttons.push(h('button', { class: 'btn outline', onclick: inviteMore }, 'Invitar'));
      buttons.push(h('button', { class: 'btn big', disabled: room.players.length < 2, onclick: async () => {
        try { await api.start(room, Math.floor(Math.random() * 2147483647)); await poll(); } catch (e) { toast(e.message); }
      } }, 'Empezar'));
      buttons.push(h('button', { class: 'text-btn', onclick: async () => { if (await confirmDialog('Cerrar sala', '', 'Cerrar')) { await api.remove(room).catch(() => {}); close(); } } }, 'Cerrar sala'));
    } else if (inRoom) buttons.push(h('button', { class: 'text-btn', onclick: async () => { try { await api.leave(room); close(); } catch (e) { toast(e.message); } } }, 'Salir'));

    fill(body, h('div', { class: 'poly-lobby' }, seats, tokRow,
      h('div', { class: 'pcfg' },
        h('div', { class: 'prow' }, h('span', {}, 'Dinero inicial'), moneyOut), slider,
        toggle('400 € al caer en la Salida', 'goBonus'), toggle('Subastas', 'auction'), toggle('Bote en La Peza', 'pot'), toggle('Premio por dobles (50 € y 100 €)', 'dblBonus')),
      h('div', { class: 'pbtns' }, buttons)));
  }
  const firstFreeTok = () => { const used = new Set(Object.values(room.tok)); return toks.findIndex((_, i) => !used.has(i)); };

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
  const cfgOf = () => ({ money: room.money, goBonus: room.goBonus, auction: room.auction, pot: room.pot, dblBonus: room.dblBonus });
  function startState() {
    state = initGame(data, room.players.length, cfgOf(), room.seed);
    acts = [];
    for (const a of room.acts) { applyText(state, a); acts.push(a); }
    view = { pos: state.pos.slice() };
    log = []; q.length = 0; dice = null; seen = state.turn; pathSet = new Set(); slowSeat = -1; lastCash = null; deltas.length = 0;
    buildBoard();
  }
  function rebuild() {
    state = initGame(data, room.players.length, cfgOf(), room.seed);
    acts = [];
    for (const a of room.acts) { applyText(state, a); acts.push(a); }
    view = { pos: state.pos.slice() };
    q.length = 0; running = false;
    renderGame();
  }
  function syncActs(serverActs) {
    if (serverActs.length < acts.length) return; // lo nuestro todavía no ha llegado al servidor
    for (let i = 0; i < acts.length; i++) if (serverActs[i] !== acts[i]) { rebuild(); return; }
    if (serverActs.length === acts.length) return;
    for (let i = acts.length; i < serverActs.length; i++) {
      applyText(state, serverActs[i]); acts.push(serverActs[i]);
      q.push(...state.ev);
    }
    play();
  }

  async function send(code) {
    if (busy || running) return;
    const seat = mySeat();
    const err = act(state, seat, code, true);
    if (err) { toast(err); return; }
    applyText(state, `${seat}.${code}`);
    const text = `${seat}.${code}`;
    acts.push(text);
    q.push(...state.ev);
    busy = true;
    timer?.poke(3000); // tras jugar, se mira pronto la respuesta de los demás
    play();
    try { await api.act(room, acts.slice(), state.phase === 'over'); }
    catch (e) {
      toast(e.message || 'No se pudo enviar');
      try { room = await api.get(); } catch { /* sin red */ }
      if (room) rebuild();
    } finally { busy = false; renderGame(); }
  }

  // reproduce los sucesos (dados, movimiento, mensajes) uno tras otro
  async function play() {
    if (running) return;
    running = true;
    actEl?.classList.add('locked'); // los botones se bloquean mientras se ve la animación; el resto se actualiza al acabar
    while (q.length && !closed) {
      const e = q.shift();
      if (e.k === 'dice') {
        clearLanding(); pathSet = new Set(); paintPath();
        SFX.dice();
        // los dados giran cambiando de cara y se van frenando hasta quedarse en el resultado
        const t0 = Date.now();
        while (Date.now() - t0 < 1400 && !closed) {
          dice = { d1: 1 + Math.floor(Math.random() * 6), d2: 1 + Math.floor(Math.random() * 6), rolling: true }; paintCenter();
          await wait(60 + (Date.now() - t0) / 9);
        }
        dice = { d1: e.d1, d2: e.d2, rolling: false }; paintCenter();
        await wait(350);
      } else if (e.k === 'move') {
        const from = view.pos[e.seat], to = e.to, dir = e.dir ?? 1;
        const steps = ((to - from) * dir + 40) % 40;
        pathSet = new Set(Array.from({ length: steps + 1 }, (_, i) => (from + dir * i + 80) % 40)); paintPath();
        let p = from;
        for (let i = 0; i < steps; i++) { p = (p + dir + 40) % 40; view.pos[e.seat] = p; SFX.step(); paintTokens(); await wait(e.seat === mySeat() || steps < 8 ? 160 : 120); }
        showLanding(to);
      } else if (e.k === 'jail') {
        clearLanding();
        // sirena, luces de policía y la ficha cruza el tablero en diagonal hasta la cárcel
        SFX.siren(); boardEl.classList.add('police'); slowSeat = e.seat; paintTokens();
        await wait(60); view.pos[e.seat] = e.to; paintTokens();
        await wait(3000);
        slowSeat = -1; boardEl.classList.remove('police'); paintTokens();
      } else if (e.k === 'pay' || e.k === 'debt') SFX.pay();
      else if (e.k === 'go' || e.k === 'gain') SFX.coin();
      else if (e.k === 'buy') SFX.buy();
      else if (e.k === 'build') SFX.build();
      else if (e.k === 'win') SFX.win();
      if (e.k === 'buy' || e.k === 'build' || e.k === 'mort') paintBoard();
      if (e.k === 'card') { clearLanding(); await showCard(e); }
      if (e.m) { log.push(say(e.m)); if (log.length > 40) log.shift(); paintLog(); }
    }
    running = false;
    view.pos = state.pos.slice();
    if (state.turn !== seen) { seen = state.turn; if (state.turn === mySeat() && state.phase !== 'over' && state.phase !== 'order') { SFX.turn(); banner('Te toca', mySeat()); } }
    renderGame();
  }

  /** Cartel que aparece un momento sobre el tablero (por ejemplo «Te toca»). */
  function banner(text, seat) {
    if (!boardEl) return;
    const b = h('div', { class: 'pbanner', style: `--c:${COLORS[seat]}` }, text);
    boardEl.append(b); setTimeout(() => b.remove(), 1800);
  }

  /** Suerte, Caja y La Peza: una animación a pantalla del tablero (la ven todos) con la carta en el centro. */
  function showCard(e) {
    return new Promise((resolve) => {
      const kind = e.deck === 'h' ? 'chest' : e.deck === 'c' ? 'chance' : 'peza';
      const title = kind === 'chest' ? 'Caja de comunidad' : kind === 'chance' ? 'Suerte' : 'La Peza';
      const cp = icons.chest;
      const art = kind === 'chest'
        ? h('div', { class: 'pfx-art', html: `<svg viewBox="0 0 100 100" aria-hidden="true"><g>${svgInner([cp[0], cp[2], cp[3], cp[4]])}</g><g class="lid">${svgInner([cp[1], cp[5]])}</g></svg>` })
        : h('div', { class: 'pfx-art', html: svgOf({ p: icons[kind === 'chance' ? 'chance' : 'tax'] }) });
      const fx = h('div', { class: 'pfx ' + kind }, art,
        h('div', { class: 'pfx-card' }, h('b', { class: 'pfx-title' }, title), h('span', { class: 'pfx-who' }, seatName(e.seat)), h('p', {}, e.text)));
      boardEl.append(fx);
      if (kind === 'chest') SFX.chest(); else SFX.card();
      let done = false;
      const end = () => { if (done) return; done = true; fx.classList.add('out'); setTimeout(() => { fx.remove(); resolve(); }, 320); };
      fx.addEventListener('click', end);
      setTimeout(end, 5600);
    });
  }

  // ───── dibujo ─────
  let stripEl = null, boardEl = null, gridEl = null, tokensEl = null, centerEl = null, sideEl = null, logEl = null, actEl = null, diceBox = null, potEl = null;
  const dieA = makeDie(), dieB = makeDie();
  let pathSet = new Set(), slowSeat = -1, lastCash = null;
  const deltas = [];

  /** Tamaño de letra (en em) con el que el nombre cabe entero en `w` × `hgt` em, partiendo en líneas. */
  function fitName(text, w, hgt) {
    const words = text.split(' '), lw = Math.max(...words.map((x) => x.length));
    for (let fs = 1.12; fs >= 0.4; fs -= 0.03) {
      const per = Math.max(1, Math.floor(w / (fs * 0.58)));
      if (lw > per) continue;
      let lines = 1, cur = 0;
      for (const wd of words) { const add = cur ? cur + 1 + wd.length : wd.length; if (add <= per) cur = add; else { lines++; cur = wd.length; } }
      if (lines * fs * 1.1 <= hgt) return fs;
    }
    return 0.4;
  }
  const GS = 0.97; // la cuadrícula ocupa el 97 % del tablero: queda un marco para que el color del dueño no toque el borde
  const lum = (hex) => { const n = parseInt(hex.slice(1), 16); return ((n >> 16) * 0.3 + ((n >> 8) & 255) * 0.59 + (n & 255) * 0.11) / 255; };

  function buildBoard() {
    boardEl = h('div', { class: 'pboard' });
    gridEl = h('div', { class: 'pgrid' });
    for (let i = 0; i < 40; i++) {
      const c = cellOf(i), q2 = data.squares[i], side = sideOf(i);
      const g = q2.g ? data.groups[q2.g] : null, special = q2.t === 'chance' || q2.t === 'chest';
      // dentro de la casilla todo se dibuja como en las de abajo; las de los lados se giran (como en el tablero de verdad)
      const cw = c.w * GS * 0.62, ch = c.h * GS * 0.62, sideways = side === 'left' || side === 'right';
      const W = sideways ? ch : cw, H = sideways ? cw : ch;
      const ownable = ['street', 'station', 'utility'].includes(q2.t);
      const body = H - (g ? 1.5 : 0) - (ownable ? 2.4 : 0) - (q2.i ? (side === 'corner' ? 4.4 : special ? 3.2 : 2.1) : 0) - (q2.p && !g && q2.t !== 'station' ? 1.1 : 0) - 0.3;
      const fs = side === 'corner' ? 1.35 : special ? 1 : fitName(q2.n, W - 0.7, Math.max(1, body));
      const pin = h('div', { class: 'pin', style: sideways ? { width: W + 'em', height: H + 'em' } : {} },
        g ? h('div', { class: 'pband', style: { background: g, color: lum(g) > 0.62 ? '#1c1b22' : '#fff' } }, h('span', { class: 'pr' }, eur(q2.p))) : null,
        h('div', { class: 'pbody' },
          q2.i ? h('span', { class: 'ico', html: svgOf({ p: icons[q2.i] }) }) : null,
          h('span', { class: 'nm', style: { fontSize: fs + 'em' } }, q2.n),
          q2.p && !g ? h('span', { class: 'pr2' + (q2.t === 'station' ? ' zoomonly' : '') }, eur(q2.p)) : null),
        ownable ? h('div', { class: 'pown' }, h('span', { class: 'bld' })) : null);
      const el = h('div', { class: `psq t-${q2.t} s-${side}`, 'data-i': i, style: { left: c.l + '%', top: c.t + '%', width: c.w + '%', height: c.h + '%' } }, pin);
      // mantener pulsado: la casilla se agranda mientras se sigue pulsando; un toque corto abre su ficha
      let timer, held = false, zoom = null;
      el.addEventListener('pointerdown', () => {
        held = false; clearTimeout(timer);
        timer = setTimeout(() => { held = true; zoom = zoomOf(el, c); document.body.append(zoom); }, 350);
      });
      const end = () => { clearTimeout(timer); zoom?.remove(); zoom = null; };
      ['pointerup', 'pointercancel', 'pointerleave'].forEach((ev) => el.addEventListener(ev, end));
      el.addEventListener('contextmenu', (e) => e.preventDefault());
      el.addEventListener('click', () => { if (held) { held = false; return; } openSquare(i); });
      gridEl.append(el);
    }
    tokensEl = h('div', { class: 'ptokens' });
    potEl = h('div', { class: 'ppot' });
    diceBox = h('div', { class: 'pdice', style: { visibility: 'hidden' } }, dieA.el, dieB.el);
    const deck = (cls, ico, label) => h('div', { class: 'pdeck ' + cls }, h('span', { class: 'dk-ico', html: svgOf({ p: icons[ico] }) }), label);
    centerEl = h('div', { class: 'pcenter' }, deck('c', 'chance', 'Suerte'), deck('h', 'chest', 'Caja'), h('div', { class: 'ptitle' }, 'EVAPOLY'), potEl, diceBox);
    gridEl.append(centerEl, tokensEl);
    boardEl.append(gridEl);
    const sideBox = h('div', { class: 'pside' });
    sideEl = h('div', { class: 'pplayers' });
    actEl = h('div', { class: 'pact' });
    logEl = h('div', { class: 'plog' });
    stripEl = h('div', { class: 'pstrip' });
    sideBox.append(sideEl, actEl, logEl, stripEl);
    fill(body, h('div', { class: 'poly-main' }, boardEl, sideBox));
    paintBoard(); paintTokens(); paintCenter(); paintLog(); paintPath();
  }

  /** Copia ampliada de una casilla (con su dueño y edificios) en el centro de la pantalla. */
  function zoomOf(el, c, at) {
    const F = at ? Math.min(30, Math.max(16, at.w / 24)) : Math.min(30, Math.max(18, window.innerWidth / 13)), z = el.cloneNode(true);
    z.classList.add('pzoom');
    z.removeAttribute('data-i');
    let w = c.w * GS * 0.62 * F, hh = c.h * GS * 0.62 * F;
    if (z.classList.contains('s-left') || z.classList.contains('s-right')) {
      // las de los lados se enderezan: se ven de pie, con la banda arriba
      z.classList.remove('s-left', 's-right'); z.classList.add('s-bot'); const pin = z.querySelector('.pin'); pin.style.width = ''; pin.style.height = ''; [w, hh] = [hh, w];
    }
    Object.assign(z.style, { position: 'fixed', left: at ? at.x + 'px' : '50%', top: at ? at.y + 'px' : '42%', transform: 'translate(-50%, -50%)', width: w + 'px', height: hh + 'px', fontSize: F + 'px' });
    return z;
  }

  /** Al caer en una casilla, se ve ampliada en el centro unos segundos (como al mantenerla pulsada); la ven todos. */
  let landEl = null, landTimer = null;
  function clearLanding() { clearTimeout(landTimer); landEl?.remove(); landEl = null; }
  function showLanding(i) {
    clearLanding();
    if (!gridEl) return;
    const br = boardEl.getBoundingClientRect(); // se ve en el centro del tablero
    landEl = zoomOf(gridEl.children[i], cellOf(i), { x: br.left + br.width / 2, y: br.top + br.height / 2, w: br.width }); landEl.classList.add('landing');
    document.body.append(landEl);
    landTimer = setTimeout(clearLanding, 2400);
  }

  function paintBoard() {
    if (!gridEl) return;
    for (let i = 0; i < 40; i++) {
      const el = gridEl.children[i], o = state.own[i];
      el.classList.toggle('owned', o >= 0); el.classList.toggle('mort', !!state.mort[i]);
      const ow = el.querySelector('.pown');
      if (ow) { ow.style.background = o >= 0 ? COLORS[o] : 'transparent'; ow.style.visibility = o >= 0 ? 'visible' : 'hidden'; }
      const bld = el.querySelector('.bld');
      if (bld) { const lvl = state.lvl[i]; bld.replaceChildren(...(lvl === 5 ? [h('u', { class: 'hotel' })] : Array.from({ length: lvl }, () => h('u', { class: 'house' })))); }
    }
  }
  /** Copia derecha de una casilla (con su dueño y edificios) de `cardW` píxeles de ancho, para la tira de posición. */
  function cardCopy(i, cardW) {
    const el = gridEl.children[i], c = cellOf(i), z = el.cloneNode(true);
    z.removeAttribute('data-i'); z.classList.remove('hl'); z.classList.add('scard');
    let w = c.w * GS * 0.62, hh = c.h * GS * 0.62;
    if (z.classList.contains('s-left') || z.classList.contains('s-right')) {
      z.classList.remove('s-left', 's-right'); z.classList.add('s-bot'); const pin = z.querySelector('.pin'); pin.style.width = ''; pin.style.height = ''; [w, hh] = [hh, w];
    }
    const F = cardW / w;
    Object.assign(z.style, { left: 'auto', top: 'auto', width: cardW + 'px', height: (hh * F) + 'px', fontSize: F + 'px' });
    return z;
  }

  /** Tira lineal de tu posición: la casilla de detrás, la tuya y las siguientes (3 en pantalla completa, 5 normal), siempre en línea aunque el tablero gire. */
  function paintStrip() {
    if (!stripEl || !state || !gridEl) return;
    const my = mySeat();
    if (my < 0 || state.bk[my]) { fill(stripEl); return; }
    const n = root.classList.contains('full') ? 3 : 5, pos = view.pos[my];
    const w = Math.max(46, ((stripEl.clientWidth || 320) - (n - 1) * 6 - 10) / n);
    fill(stripEl, Array.from({ length: n }, (_, k) => {
      const i = (pos - 1 + k + 40) % 40, now = k === 1, card = cardCopy(i, w);
      card.addEventListener('click', () => openSquare(i));
      return h('div', { class: 'pslot' + (now ? ' now' : ''), style: `--c:${COLORS[my]}` }, card, now ? tokEl(my, 'mini on-strip') : null);
    }));
  }

  /** Casillas que se iluminan en amarillo: todas las del recorrido de la última tirada. */
  function paintPath() { if (gridEl) for (let i = 0; i < 40; i++) gridEl.children[i].classList.toggle('hl', pathSet.has(i)); }
  function paintTokens() {
    if (!tokensEl) return;
    tokensEl.replaceChildren(...state.pos.map((_, s) => {
      if (state.bk[s]) return null;
      const c = centerOf(view.pos[s]), dx = (s % 2 ? 1 : -1) * 1.6, dy = (s < 2 ? -1 : 1) * 1.6;
      return h('span', { class: 'ptk on-board' + (state.turn === s && state.phase !== 'order' ? ' turn' : '') + (slowSeat === s ? ' slow' : ''), style: `--c:${COLORS[s]};left:${c.x + dx}%;top:${c.y + dy}%`, html: svgOf(tokenOf(s)) });
    }));
    paintStrip();
  }
  function paintCenter() {
    if (!centerEl) return;
    potEl.textContent = state && state.pot > 0 ? `La Peza · ${eur(state.pot)}` : '';
    potEl.style.display = state && state.pot > 0 ? '' : 'none';
    if (dice) { dieA.set(dice.d1); dieB.set(dice.d2); diceBox.style.visibility = 'visible'; diceBox.classList.toggle('rolling', !!dice.rolling); diceBox.classList.toggle('landed', !dice.rolling); }
  }
  function paintLog() { if (logEl) { fill(logEl, log.slice(-4).map((l) => h('div', {}, l))); logEl.scrollTop = logEl.scrollHeight; } }

  function renderGame() {
    if (!state || !sideEl) return;
    const s = state, my = mySeat();
    paintBoard(); paintTokens(); paintCenter();
    if (running) return; // durante la animación el panel no se adelanta: se redibuja al terminar
    actEl.classList.remove('locked');
    // variación de dinero de cada jugador (+50, −200…) que sube y se desvanece junto a su nombre
    if (lastCash) s.cash.forEach((c, i) => { const d = c - lastCash[i]; if (d) deltas.push({ seat: i, at: Date.now(), cls: d > 0 ? 'up' : 'down', text: (d > 0 ? '+' : '−') + eur(Math.abs(d)) }); });
    lastCash = s.cash.slice();
    while (deltas.length && Date.now() - deltas[0].at > 1600) deltas.shift();
    // jugadores: a la derecha, uno debajo de otro; el que tiene el turno lleva un recuadro de su color
    fill(sideEl, room.players.map((u, i) => {
      const jail = s.jail[i], bk = s.bk[i];
      const turn = s.phase === 'order' ? s.ordCand[i] && !s.ordRolls[i] : s.turn === i && s.phase !== 'over';
      const info = bk ? 'Quiebra' : s.phase === 'order' ? (s.ordRolls[i] ? `Sacó ${s.ordRolls[i]}` : '') : [jail ? 'Cárcel' : null, s.jf[i] ? `Carta ×${s.jf[i]}` : null, s.mc[i] ? 'Maricarmen' : null, `${mine(s, i).length} calles`].filter(Boolean).join(' · ');
      const row = h('div', { class: 'pp' + (turn ? ' turn' : '') + (bk ? ' bk' : ''), style: `--c:${COLORS[i]}` },
        tokEl(i, 'mini'), h('div', { class: 'who' }, h('b', {}, nameOf(u) + (i === my ? ' (tú)' : '')), h('small', {}, info),
          h('span', { class: 'pprops' }, mine(s, i).map((sqi) => h('u', { style: { background: data.squares[sqi].g ? data.groups[data.squares[sqi].g] : '#8a929b' }, class: s.mort[sqi] ? 'mort' : '' })))),
        h('span', { class: 'cash' }, eur(s.cash[i])));
      for (const d of deltas) if (d.seat === i && Date.now() - d.at < 1500) row.append(h('span', { class: 'pdelta ' + d.cls, style: `animation-delay:-${Date.now() - d.at}ms` }, d.text));
      return row;
    }));
    paintActions();
  }

  function btn(label, code, { primary = true, disabled = false, cls = '' } = {}) {
    return h('button', { class: 'btn' + (primary ? '' : ' outline') + ' ' + cls, disabled: disabled || running || busy, onclick: () => send(code) }, label);
  }

  function paintActions() {
    const s = state, my = mySeat(), cur = s.turn;
    const out = [];
    const status = (t) => out.push(h('div', { class: 'pstatus' }, t));
    if (my < 0) status('Espectador');
    else if (s.trade && s.trade.to === my) out.push(tradeBox(s.trade, true));
    else if (s.trade && s.trade.from === my) { status('Esperando respuesta'); out.push(h('div', { class: 'pbtns' }, btn('Cancelar trato', 'N', { primary: false }))); }
    else if (s.phase === 'order') {
      if (s.ordCand[my] && !s.ordRolls[my]) out.push(h('div', { class: 'pbtns' }, btn('Tirar dados', 'r', { cls: 'cta' })));
      else status(s.ordRolls[my] ? `Sacaste ${s.ordRolls[my]}` : 'Esperando');
    }
    else if (s.phase === 'over') status(`Gana ${seatName(s.winner)}`);
    else if (s.phase === 'debt') {
      const d = s.debts[0];
      if (d.seat === my) {
        status(`Debes ${eur(d.amt)}${d.to >= 0 ? ' a ' + seatName(d.to) : ''}`);
        out.push(h('div', { class: 'pbtns' }, btn('Pagar', 'd', { disabled: s.cash[my] < d.amt }), d.rent && s.mc[my] > 0 ? btn('Maricarmen', 'k') : null, btn('Quiebra', 'q', { primary: false })));
      } else status(`${seatName(d.seat)} debe ${eur(d.amt)}`);
    } else if (s.phase === 'auction') {
      const au = s.auction, qd = data.squares[au.sq];
      status(`Subasta: ${qd.n} · ${au.bidder >= 0 ? `${eur(au.bid)} (${seatName(au.bidder)})` : 'sin pujas'}`);
      if (au.cur === my) out.push(h('div', { class: 'pbtns' }, [10, 50, 100].map((inc) => btn(`+${inc}`, 'i' + (au.bid + inc), { disabled: au.bid + inc > s.cash[my] })), btn('Retirarse', 'f', { primary: false })));
      else status(`Puja ${seatName(au.cur)}`);
    } else if (cur !== my) status(`Turno de ${seatName(cur)}`);
    else if (s.phase === 'roll') {
      const row = [];
      if (s.jail[my]) { row.push(btn(`Pagar ${eur(JAIL_FINE)}`, 'p', { primary: false, disabled: s.cash[my] < JAIL_FINE })); if (s.jf[my] > 0) row.push(btn('Usar carta', 'c', { primary: false })); }
      row.push(btn('Tirar dados', 'r', { cls: 'cta' }));
      out.push(h('div', { class: 'pbtns' }, row));
    } else if (s.phase === 'buy') {
      const qd = data.squares[s.buy];
      status(`${qd.n} · ${eur(qd.p)}`);
      out.push(h('div', { class: 'pbtns' }, btn('Comprar', 'b', { cls: 'cta', disabled: s.cash[my] < qd.p })));
      out.push(h('div', { class: 'pbtns' }, s.cfg.auction ? btn('Subastar', 'a', { primary: false }) : null, btn('No comprar', 'n', { primary: false })));
    } else if (s.phase === 'end') out.push(h('div', { class: 'pbtns' }, btn('Terminar turno', 'e', { cls: 'cta' })));
    // acciones secundarias en tu turno
    if (my >= 0 && cur === my && (s.phase === 'roll' || s.phase === 'end') && !s.trade) {
      out.push(h('div', { class: 'pbtns small' },
        h('button', { class: 'text-btn', disabled: running, onclick: openTrade }, 'Negociar'),
        h('button', { class: 'text-btn', disabled: running, onclick: async () => { if (await confirmDialog('Abandonar', '', 'Abandonar')) send('z'); } }, 'Abandonar')));
    }
    fill(actEl, out);
  }

  // ───── casilla (ficha de la propiedad) ─────
  function openSquare(i) {
    const s = state, q2 = data.squares[i];
    if (!q2) return;
    const my = mySeat(), o = s.own[i];
    const content = h('div', { class: 'pdeed' });
    const draw = () => {
      const rows = [];
      if (q2.t === 'street') {
        const names = ['Solar', '1 casa', '2 casas', '3 casas', '4 casas', 'Hotel'];
        q2.r.forEach((v, k) => rows.push(h('div', { class: 'prow' + (s.lvl[i] === k ? ' now' : '') }, h('span', {}, names[k]), h('b', {}, eur(v)))));
        rows.push(h('div', { class: 'prow' }, h('span', {}, 'Casa / hotel'), h('b', {}, eur(houseCost(s, i)))));
      } else if (q2.t === 'station') q2.r.forEach((v, k) => rows.push(h('div', { class: 'prow' }, h('span', {}, `${k + 1} estación${k ? 'es' : ''}`), h('b', {}, eur(v)))));
      else if (q2.t === 'utility') rows.push(h('div', { class: 'prow' }, h('span', {}, '1 servicio'), h('b', {}, '4 × dados')), h('div', { class: 'prow' }, h('span', {}, '2 servicios'), h('b', {}, '10 × dados')));
      else if (q2.t === 'tax') rows.push(h('div', { class: 'prow' }, h('span', {}, 'Pagas'), h('b', {}, eur(q2.p))));
      if (q2.p && q2.t !== 'tax') rows.push(h('div', { class: 'prow' }, h('span', {}, 'Precio'), h('b', {}, eur(q2.p))), h('div', { class: 'prow' }, h('span', {}, 'Hipoteca'), h('b', {}, eur(q2.p / 2))));
      if (o >= 0) rows.push(h('div', { class: 'prow' }, h('span', {}, 'Dueño'), h('b', { style: { color: COLORS[o] } }, seatName(o) + (s.mort[i] ? ' · hipotecada' : ''))));
      const buttons = [];
      const can = (code) => act(s, my, code, true) === null;
      if (my >= 0 && o === my && !running) {
        if (can('h' + i)) buttons.push(h('button', { class: 'btn', onclick: async () => { await send('h' + i); draw(); } }, `Construir ${eur(houseCost(s, i))}`));
        if (can('s' + i)) buttons.push(h('button', { class: 'btn outline', onclick: async () => { await send('s' + i); draw(); } }, `Vender ${eur(houseCost(s, i) / 2)}`));
        if (can('m' + i)) buttons.push(h('button', { class: 'btn outline', onclick: async () => { await send('m' + i); draw(); } }, `Hipotecar ${eur(q2.p / 2)}`));
        if (can('u' + i)) buttons.push(h('button', { class: 'btn', onclick: async () => { await send('u' + i); draw(); } }, `Deshipotecar ${eur(unmortgageCost(s, i))}`));
      }
      fill(content, h('div', { class: 'pdeed-head', style: { background: q2.g ? data.groups[q2.g] : 'var(--surface2)', color: q2.g && q2.g !== 'yellow' && q2.g !== 'sky' ? '#fff' : '#1c1b22' } }, q2.n), rows, buttons.length ? h('div', { class: 'pbtns' }, buttons) : null);
    };
    draw();
    modal({ content, actions: [{ label: 'Cerrar' }] });
  }

  // ───── tratos ─────
  function tradeBox(t, incoming) {
    const list = (arr) => arr.map((i) => data.squares[i].n).join(', ') || '—';
    const box = h('div', { class: 'ptrade' },
      h('div', { class: 'pstatus' }, `${seatName(t.from)} propone`),
      h('div', { class: 'prow' }, h('span', {}, 'Te da'), h('b', {}, [t.gc ? eur(t.gc) : null, t.gp.length ? list(t.gp) : null, t.gj ? `${t.gj} carta` : null].filter(Boolean).join(' + ') || '—')),
      h('div', { class: 'prow' }, h('span', {}, 'Te pide'), h('b', {}, [t.rc ? eur(t.rc) : null, t.rp.length ? list(t.rp) : null, t.rj ? `${t.rj} carta` : null].filter(Boolean).join(' + ') || '—')),
      incoming ? h('div', { class: 'pbtns' }, btn('Aceptar', 'Y'), btn('Rechazar', 'N', { primary: false })) : null);
    return box;
  }

  function openTrade() {
    const s = state, my = mySeat();
    const others = [...Array(s.n).keys()].filter((i) => i !== my && !s.bk[i]);
    if (!others.length) return;
    let to = others[0];
    const give = new Set(), take = new Set();
    let gc = 0, rc = 0, gj = 0, rj = 0;
    const content = h('div', { class: 'ptrade-edit' });
    const tradable = (i) => { const g = data.squares[i].g; return !g || groupOf(s, g).every((j) => s.lvl[j] === 0); };
    const col = (seat, set, getCash, setCash, getJ, setJ) => {
      const props = mine(s, seat);
      return h('div', { class: 'pcol', style: `--c:${COLORS[seat]}` },
        h('b', {}, seat === my ? 'Doy' : 'Pido'),
        h('div', { class: 'pcash' },
          h('button', { class: 'icon-btn', onclick: () => { setCash(Math.max(0, getCash() - 10)); draw(); } }, icon('minus', 18)),
          h('input', { class: 'field', type: 'number', min: 0, step: 10, value: getCash(), onchange: (e) => { setCash(Math.max(0, Math.floor(Number(e.target.value) || 0))); draw(); } }),
          h('button', { class: 'icon-btn', onclick: () => { setCash(getCash() + 10); draw(); } }, icon('plus', 18))),
        s.jf[seat] > 0 ? h('div', { class: 'pcash' }, h('span', {}, 'Carta'),
          h('button', { class: 'icon-btn', onclick: () => { setJ(Math.max(0, getJ() - 1)); draw(); } }, icon('minus', 18)), h('b', {}, getJ()),
          h('button', { class: 'icon-btn', onclick: () => { setJ(Math.min(s.jf[seat], getJ() + 1)); draw(); } }, icon('plus', 18))) : null,
        props.map((i) => h('button', { class: 'pprop' + (set.has(i) ? ' sel' : ''), disabled: !tradable(i), onclick: () => { set.has(i) ? set.delete(i) : set.add(i); draw(); } },
          h('i', { style: { background: data.squares[i].g ? data.groups[data.squares[i].g] : '#888' } }), data.squares[i].n)));
    };
    const draw = () => {
      const code = `T${to}|${gc}|${rc}|${[...give]}|${[...take]}|${gj}|${rj}`;
      const err = act(s, my, code, true);
      fill(content,
        others.length > 1 ? h('div', { class: 'pchips' }, others.map((i) => h('button', { class: 'pchip' + (i === to ? ' sel' : ''), style: `--c:${COLORS[i]}`, onclick: () => { to = i; take.clear(); rc = 0; rj = 0; draw(); } }, seatName(i)))) : h('b', {}, seatName(to)),
        h('div', { class: 'pcols' }, col(my, give, () => gc, (v) => { gc = v; }, () => gj, (v) => { gj = v; }), col(to, take, () => rc, (v) => { rc = v; }, () => rj, (v) => { rj = v; })),
        err && (gc + rc + give.size + take.size + gj + rj) ? h('small', { class: 'perr' }, err) : null,
        h('div', { class: 'pbtns' }, h('button', { class: 'btn', disabled: !!err, onclick: () => { m.close(); send(code); } }, 'Proponer')));
    };
    const m = modal({ title: 'Negociar', content, actions: [{ label: 'Cerrar' }], wide: true });
    draw();
  }

  // ───── salir ─────
  function close() {
    closed = true; clearLanding(); timer?.stop(); stopMusic(); document.removeEventListener('fullscreenchange', onFs); if (document.fullscreenElement) document.exitFullscreen?.().catch(() => {}); root.remove(); onClose?.();
  }

  fill(body, h('div', { class: 'dim', style: { padding: '24px', textAlign: 'center' } }, '…'));
  Promise.all([loadData(), loadToks(), loadIcons()]).then(([d, t, i]) => { data = d; toks = t; icons = i; startMusic(); timer = poller(poll, 3000, 20000); }).catch(() => toast('No se pudo cargar el juego'));
  return root;
}
