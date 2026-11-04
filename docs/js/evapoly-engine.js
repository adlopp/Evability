// Motor de reglas del Evapoly (Monopoly), puro y determinista: la partida es una lista de acciones («asiento.código») que se
// aplica sobre un estado inicial que sale de la semilla de la sala, así todos los jugadores llegan al mismo estado.
// El tablero y las cartas están en data/evapoly.json. Hay una copia idéntica en Kotlin (EvapolyEngine.kt): si cambias una,
// cambia la otra.
//
// Códigos de acción (el asiento va delante: «2.r»):
//   r tirar · p pagar multa de cárcel · c usar carta de cárcel · b comprar · n no comprar · a subastar
//   i<cantidad> pujar · f retirarse de la subasta · e terminar turno
//   h<casilla> construir · s<casilla> vender edificio · m<casilla> hipotecar · u<casilla> deshipotecar
//   x<estación> viajar en tren a otra estación tuya (regla opcional) · k usar la carta Maricarmen (te libra de un alquiler) · d pagar la deuda · q declararse en quiebra · z abandonar la partida
//   T<a>|<doy €>|<pido €>|<doy casillas>|<pido casillas>|<doy cartas>|<pido cartas> proponer un trato · Y aceptar · N rechazar/cancelar

export const GO_SALARY = 200;
export const JAIL_FINE = 50;
export const JAIL = 10;
export const MAX_HOUSES = 32, MAX_HOTELS = 12; // límite de casas y de hoteles de CADA jugador (no se comparte)
/** Tienda de La Peza (regla opcional): objetos que no salen en Suerte ni en Caja. */
export const SHOP = { 1: { n: 'la carta Maricarmen', p: 450 }, 2: { n: 'el dado triple', p: 100 }, 3: { n: 'la doble condena', p: 250 } };

export const eur = (n) => String(n).replace(/\B(?=(\d{3})+(?!\d))/g, '.') + ' €';

// ───── números aleatorios con semilla (mulberry32): idénticos en JS y en Kotlin ─────
function rnd(s) {
  s.r = (s.r + 0x6D2B79F5) | 0;
  let t = s.r;
  t = Math.imul(t ^ (t >>> 15), t | 1);
  t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}
const rint = (s, n) => Math.floor(rnd(s) * n);
function shuffled(s, n) {
  const a = Array.from({ length: n }, (_, i) => i);
  for (let i = n - 1; i > 0; i--) { const j = rint(s, i + 1); [a[i], a[j]] = [a[j], a[i]]; }
  return a;
}

// ───── estado inicial ─────
export function initGame(data, n, cfg, seed) {
  const s = {
    data, n, cfg: { money: cfg.money, goBonus: !!cfg.goBonus, auction: !!cfg.auction, pot: !!cfg.pot, dblBonus: !!cfg.dblBonus, rails: !!cfg.rails, ownerFree: !!cfg.ownerFree, shop: !!cfg.shop }, r: seed | 0,
    pos: Array(n).fill(0), cash: Array(n).fill(cfg.money), jail: Array(n).fill(false), jt: Array(n).fill(0), jf: Array(n).fill(0),
    bk: Array(n).fill(false),
    own: Array(40).fill(-1), lvl: Array(40).fill(0), mort: Array(40).fill(false),
    deck: { c: { order: [], i: 0, held: -1, held2: -1 }, h: { order: [], i: 0, held: -1, held2: -1 } }, mc: Array(n).fill(0), mcBy: -1, pot: 0,
    turn: 0, phase: 'roll', dbl: 0, again: false, d1: 0, d2: 0, buy: -1, auction: null, debts: [], pendMove: -1, trade: null,
    heldBy: { c: -1, h: -1 }, winner: -1, ev: [], acts: 0, ordCand: Array(n).fill(true), ordRolls: Array(n).fill(0), railCd: Array(n).fill(0), tri: Array(n).fill(0), dj: Array(n).fill(0), jmax: Array(n).fill(3),
  };
  s.deck.c.order = shuffled(s, data.cards.chance.length);
  s.deck.h.order = shuffled(s, data.cards.chest.length);
  // quién empieza: cada jugador tira los dados y empieza el más alto (los empates vuelven a tirar)
  s.phase = 'order';
  say(s, 'Todos tiran los dados: empieza el que saque más', 'start');
  return s;
}

export const sq = (s, i) => s.data.squares[i];
const say = (s, m, k = 'msg', extra = {}) => s.ev.push({ k, m, ...extra });
export const alive = (s) => { const a = []; for (let i = 0; i < s.n; i++) if (!s.bk[i]) a.push(i); return a; };
const nextAlive = (s, from) => { for (let i = 1; i <= s.n; i++) { const t = (from + i) % s.n; if (!s.bk[t]) return t; } return from; };
export const groupOf = (s, g) => { const a = []; for (let i = 0; i < 40; i++) if (sq(s, i).g === g) a.push(i); return a; };
export const houseCost = (s, i) => s.data.house[sq(s, i).g] || 0;
export const ownsAll = (s, seat, g) => groupOf(s, g).every((i) => s.own[i] === seat);
export const housesOf = (s, seat) => { let n = 0; for (let i = 0; i < 40; i++) if (s.own[i] === seat && s.lvl[i] > 0 && s.lvl[i] < 5) n += s.lvl[i]; return n; };
export const hotelsOf = (s, seat) => { let n = 0; for (let i = 0; i < 40; i++) if (s.own[i] === seat && s.lvl[i] === 5) n++; return n; };
export const countOwned = (s, seat, t) => { let c = 0; for (let i = 0; i < 40; i++) if (s.own[i] === seat && sq(s, i).t === t) c++; return c; };

/** Alquiler que se paga al caer en la casilla `i` (sin contar los dados, que valen para los servicios). */
export function rentOf(s, i, diceTotal = 0) {
  const q = sq(s, i), o = s.own[i];
  if (o < 0 || s.mort[i]) return 0;
  if (q.t === 'street') {
    if (s.lvl[i] > 0) return q.r[s.lvl[i]];
    return ownsAll(s, o, q.g) ? q.r[0] * 2 : q.r[0];
  }
  if (q.t === 'station') return q.r[countOwned(s, o, 'station') - 1];
  if (q.t === 'utility') return diceTotal * (countOwned(s, o, 'utility') === 2 ? 10 : 4);
  return 0;
}

/** Patrimonio de un jugador en efectivo si lo vendiese todo (para decidir si puede salir de una deuda). */
export function worth(s, seat) {
  let w = s.cash[seat];
  for (let i = 0; i < 40; i++) if (s.own[i] === seat) { if (!s.mort[i]) w += sq(s, i).p / 2; w += s.lvl[i] * houseCost(s, i) / 2; }
  return w;
}

// ───── dinero ─────
function pay(s, seat, amt, to, why, rent = false, tax = false) {
  if (amt <= 0) return;
  if (rent && s.mc[seat] > 0) {
    // con Maricarmen en la mano el alquiler no se cobra solo: se decide si pagarlo o usar la carta
    s.debts.push({ seat, amt, to, why, rent: true });
    say(s, `@${seat} debe ${eur(amt)} a @${to}${why}`, 'debt', { seat });
  } else if (s.cash[seat] >= amt) {
    s.cash[seat] -= amt;
    if (to >= 0) s.cash[to] += amt;
    if (tax && s.cfg.pot) s.pot += amt; // los impuestos se acumulan en La Peza (si la regla está activada)
    say(s, to >= 0 ? `@${seat} paga ${eur(amt)} a @${to}${why}` : `@${seat} paga ${eur(amt)}${why}`, 'pay', { seat, to, amt });
  } else {
    s.debts.push({ seat, amt, to, why, tax });
    say(s, `@${seat} debe ${eur(amt)}${to >= 0 ? ` a @${to}` : ''}${why} y no le llega el efectivo`, 'debt', { seat });
  }
}

// ───── movimiento ─────
function goPay(s, seat, landed) {
  const amt = landed && s.cfg.goBonus ? 2 * GO_SALARY : GO_SALARY;
  s.cash[seat] += amt;
  say(s, landed ? `@${seat} cae en la Salida y cobra ${eur(amt)}` : `@${seat} pasa por la Salida y cobra ${eur(amt)}`, 'go', { seat, amt });
}

function moveTo(s, seat, to, collect) {
  const from = s.pos[seat];
  if (collect && (to < from || to === 0)) goPay(s, seat, to === 0);
  s.pos[seat] = to;
  say(s, `@${seat} avanza hasta ${sq(s, to).n}`, 'move', { seat, from, to, dir: 1 });
}

function moveDice(s, seat, total) {
  const from = s.pos[seat], raw = from + total, to = raw % 40;
  if (raw >= 40) goPay(s, seat, to === 0);
  s.pos[seat] = to;
  say(s, `@${seat} avanza ${total} y cae en ${sq(s, to).n}`, 'move', { seat, from, to, dir: 1 });
  land(s, seat, to, { dice: total });
}

function sendJail(s, seat, why) {
  const from = s.pos[seat];
  s.pos[seat] = JAIL; s.jail[seat] = true; s.jt[seat] = 0; s.jmax[seat] = 3;
  s.again = false; s.dbl = 0;
  say(s, `@${seat} va a la cárcel${why}`, 'jail', { seat, from, to: JAIL });
}

function land(s, seat, i, ctx) {
  const q = sq(s, i);
  switch (q.t) {
    case 'street': case 'station': case 'utility': {
      const o = s.own[i];
      if (o < 0) {
        if (s.cash[seat] >= q.p || s.cfg.auction) s.buy = i;
        else say(s, `@${seat} no tiene dinero para comprar ${q.n} (${eur(q.p)})`);
      } else if (s.cfg.ownerFree && o !== seat && !s.mort[i] && s.pos[o] === i) {
        say(s, `@${o} está en ${q.n}: @${seat} no paga alquiler`);
      } else if (o !== seat && !s.mort[i]) {
        let rent;
        if (q.t === 'utility' && ctx.utilRoll) {
          const a = rint(s, 6) + 1, b = rint(s, 6) + 1;
          say(s, `@${seat} tira los dados: ${a} y ${b}`, 'dice', { seat, d1: a, d2: b });
          rent = (a + b) * 10;
        } else rent = rentOf(s, i, ctx.dice || 0) * (ctx.double ? 2 : 1);
        pay(s, seat, rent, o, ` de alquiler por ${q.n}`, true);
      }
      break;
    }
    case 'tax': pay(s, seat, q.p, -1, ` de ${q.n.toLowerCase()}`, false, true); break;
    case 'parking':
      if (s.cfg.pot && s.pot > 0) {
        const v = s.pot; s.pot = 0; s.cash[seat] += v;
        s.ev.push({ k: 'card', m: 'La Peza: Tito Pepe te ha soltado unos fajos de billetes', seat, text: 'Tito Pepe te ha soltado unos fajos de billetes', deck: 'p' });
        say(s, `@${seat} se lleva ${eur(v)} de los impuestos de ${q.n}`, 'gain', { seat, amt: v });
      }
      break;
    case 'chance': draw(s, seat, 'c'); break;
    case 'chest': draw(s, seat, 'h'); break;
    case 'gotojail': sendJail(s, seat, ''); break;
    default: break;
  }
}

// ───── cartas ─────
function draw(s, seat, which) {
  const d = s.deck[which], cards = which === 'c' ? s.data.cards.chance : s.data.cards.chest;
  let idx;
  do { idx = d.order[d.i % d.order.length]; d.i++; } while (idx === d.held || idx === d.held2);
  const [kind, a, b] = cards[idx].e.split(':');
  say(s, `${which === 'c' ? 'Suerte' : 'Caja'}: ${cards[idx].x}`, 'card', { seat, text: cards[idx].x, deck: which });
  switch (kind) {
    case 'to': moveTo(s, seat, Number(a), true); land(s, seat, Number(a), {}); break;
    case 'near': {
      const list = a === 'u' ? [12, 28] : [5, 15, 25, 35], p = s.pos[seat];
      const t = list.find((x) => x > p) ?? list[0];
      moveTo(s, seat, t, true);
      land(s, seat, t, a === 'u' ? { utilRoll: true } : { double: true });
      break;
    }
    case 'back': {
      const from = s.pos[seat], to = (from + 40 - Number(a)) % 40;
      s.pos[seat] = to;
      say(s, `@${seat} retrocede ${a} casillas hasta ${sq(s, to).n}`, 'move', { seat, from, to, dir: -1 });
      land(s, seat, to, {});
      break;
    }
    case 'jail': sendJail(s, seat, ''); break;
    case 'mc': d.held2 = idx; s.mc[seat]++; s.mcBy = seat; break;
    case 'free': d.held = idx; s.jf[seat]++; s.heldBy[which] = seat; break;
    case 'cash': { const v = Number(a); if (v >= 0) { s.cash[seat] += v; say(s, `@${seat} cobra ${eur(v)}`, 'gain', { seat, amt: v }); } else pay(s, seat, -v, -1, ''); break; }
    case 'repair': {
      let h = 0, t = 0;
      for (let i = 0; i < 40; i++) if (s.own[i] === seat) { if (s.lvl[i] === 5) t++; else h += s.lvl[i]; }
      pay(s, seat, h * Number(a) + t * Number(b), -1, ' de reparaciones');
      break;
    }
    case 'eachpay': for (const o of alive(s)) if (o !== seat) pay(s, seat, Number(a), o, ''); break;
    case 'eachget': for (const o of alive(s)) if (o !== seat) pay(s, o, Number(a), seat, ' de cumpleaños'); break;
    default: break;
  }
}

// ───── deudas, quiebra y fin de la partida ─────
function liquidate(s, seat) {
  for (let i = 0; i < 40; i++) {
    if (s.own[i] !== seat || !s.lvl[i]) continue;
    s.cash[seat] += s.lvl[i] * houseCost(s, i) / 2;
    s.lvl[i] = 0;
  }
}

function bankrupt(s, seat, to, why) {
  liquidate(s, seat);
  for (let i = 0; i < 40; i++) if (s.own[i] === seat) { s.own[i] = to; if (to < 0) s.mort[i] = false; }
  if (to >= 0) { s.cash[to] += s.cash[seat]; s.jf[to] += s.jf[seat]; s.mc[to] += s.mc[seat]; s.tri[to] += s.tri[seat]; s.dj[to] += s.dj[seat]; if (s.mcBy === seat) s.mcBy = to; }
  else if (s.mcBy === seat) { s.deck.c.held2 = -1; s.mcBy = -1; }
  else for (const w of ['c', 'h']) if (s.deck[w].held >= 0 && s.heldBy[w] === seat) { s.deck[w].held = -1; s.heldBy[w] = -1; }
  s.cash[seat] = 0; s.jf[seat] = 0; s.mc[seat] = 0; s.tri[seat] = 0; s.dj[seat] = 0; s.bk[seat] = true; s.jail[seat] = false;
  s.debts = s.debts.filter((d) => d.seat !== seat).map((d) => (d.to === seat ? { ...d, to } : d));
  if (s.trade && (s.trade.from === seat || s.trade.to === seat)) s.trade = null;
  say(s, `@${seat} ${why}${to >= 0 ? `: todo pasa a @${to}` : ': todo vuelve al banco'}`, 'bankrupt', { seat });
  const left = alive(s);
  if (left.length === 1) { s.winner = left[0]; s.phase = 'over'; say(s, `@${left[0]} gana la partida`, 'win', { seat: left[0] }); }
}

function nextTurn(s) {
  s.turn = nextAlive(s, s.turn);
  s.phase = 'roll'; s.dbl = 0; s.again = false; s.buy = -1;
  if (s.railCd[s.turn] > 0) s.railCd[s.turn]--;
}

/** Decide en qué fase queda la partida cuando una acción termina de resolverse. */
function settle(s) {
  if (s.phase === 'over') return;
  if (s.debts.length) { s.phase = 'debt'; return; }
  if (s.auction) { s.phase = 'auction'; return; }
  if (s.bk[s.turn]) { s.pendMove = -1; nextTurn(s); return; }
  if (s.pendMove >= 0) { const t = s.pendMove; s.pendMove = -1; moveDice(s, s.turn, t); settle(s); return; }
  if (s.buy >= 0) { s.phase = 'buy'; return; }
  s.phase = s.again ? 'roll' : 'end';
}

// ───── validación de edificios ─────
function canBuild(s, seat, i) {
  const q = sq(s, i);
  if (q.t !== 'street' || s.own[i] !== seat) return 'No es tuya';
  if (!ownsAll(s, seat, q.g)) return 'Necesitas todo el grupo';
  const g = groupOf(s, q.g);
  if (g.some((j) => s.mort[j])) return 'Hay una hipotecada en el grupo';
  if (s.lvl[i] >= 5) return 'Ya tiene hotel';
  if (g.some((j) => s.lvl[j] < s.lvl[i])) return 'Hay que construir por igual en todo el grupo';
  if (s.cash[seat] < houseCost(s, i)) return 'No tienes dinero';
  if (s.lvl[i] === 4 ? hotelsOf(s, seat) >= MAX_HOTELS : housesOf(s, seat) >= MAX_HOUSES) return s.lvl[i] === 4 ? 'Ya tienes 12 hoteles' : 'Ya tienes 32 casas';
  return null;
}
function canSell(s, seat, i) {
  const q = sq(s, i);
  if (q.t !== 'street' || s.own[i] !== seat || s.lvl[i] < 1) return 'Sin edificios';
  if (groupOf(s, q.g).some((j) => s.lvl[j] > s.lvl[i])) return 'Hay que vender por igual en todo el grupo';
  if (s.lvl[i] === 5 && housesOf(s, seat) + 4 > MAX_HOUSES) return 'No te caben 4 casas más para cambiar el hotel';
  return null;
}
function canMortgage(s, seat, i) {
  const q = sq(s, i);
  if (!['street', 'station', 'utility'].includes(q.t) || s.own[i] !== seat) return 'No es tuya';
  if (s.mort[i]) return 'Ya está hipotecada';
  if (q.g && groupOf(s, q.g).some((j) => s.lvl[j] > 0)) return 'Primero vende los edificios del grupo';
  return null;
}
export const unmortgageCost = (s, i) => Math.ceil(sq(s, i).p * 0.55);
function canUnmortgage(s, seat, i) {
  if (!['street', 'station', 'utility'].includes(sq(s, i).t) || s.own[i] !== seat) return 'No es tuya';
  if (!s.mort[i]) return 'No está hipotecada';
  if (s.cash[seat] < unmortgageCost(s, i)) return 'No tienes dinero';
  return null;
}

// ───── tratos ─────
function parseTrade(s, seat, arg) {
  const p = arg.split('|');
  if (p.length !== 7) return null;
  const list = (x) => (x ? x.split(',').map(Number) : []);
  return { from: seat, to: Number(p[0]), gc: Number(p[1]), rc: Number(p[2]), gp: list(p[3]), rp: list(p[4]), gj: Number(p[5]), rj: Number(p[6]) };
}
function tradeError(s, t) {
  if (!t || !Number.isInteger(t.to) || t.to < 0 || t.to >= s.n || t.to === t.from || s.bk[t.to] || s.bk[t.from]) return 'Trato no válido';
  for (const v of [t.gc, t.rc, t.gj, t.rj]) if (!Number.isInteger(v) || v < 0) return 'Trato no válido';
  if (t.gc + t.rc + t.gp.length + t.rp.length + t.gj + t.rj === 0) return 'Trato vacío';
  if (s.cash[t.from] < t.gc || s.cash[t.to] < t.rc) return 'No hay dinero suficiente';
  if (s.jf[t.from] < t.gj || s.jf[t.to] < t.rj) return 'Falta una carta';
  for (const [list, who] of [[t.gp, t.from], [t.rp, t.to]]) {
    if (new Set(list).size !== list.length) return 'Trato no válido';
    for (const i of list) {
      if (!Number.isInteger(i) || i < 0 || i > 39 || s.own[i] !== who) return 'Una casilla ya no es suya';
      const g = sq(s, i).g;
      if (g && groupOf(s, g).some((j) => s.lvl[j] > 0)) return 'Hay edificios en un grupo del trato';
    }
  }
  return null;
}

// ───── acciones ─────
const OK = null;

/**
 * Aplica la acción `code` del jugador `seat`. Devuelve null si ha ido bien o el motivo si no es válida (y entonces el estado
 * no cambia). Con `dry` solo comprueba. Los mensajes del turno quedan en `s.ev`.
 */
export function act(s, seat, code, dry = false) {
  if (!dry) s.ev = [];
  if (s.phase === 'over') return 'La partida ha terminado';
  if (!Number.isInteger(seat) || seat < 0 || seat >= s.n || s.bk[seat]) return 'Jugador no válido';
  const op = code[0], arg = code.slice(1);
  const mine = seat === s.turn, free = s.phase === 'roll' || s.phase === 'end';
  const ok = () => { if (!dry) s.acts++; return OK; };

  // al principio todos tiran para ver quién empieza
  if (s.phase === 'order') {
    if (op !== 'r') return 'Primero hay que tirar los dados para ver quién empieza';
    if (!s.ordCand[seat] || s.ordRolls[seat] > 0) return 'Ya has tirado';
    if (dry) return OK;
    const a = rint(s, 6) + 1, b = rint(s, 6) + 1;
    s.ordRolls[seat] = a + b;
    say(s, `@${seat} saca ${a} y ${b}`, 'dice', { seat, d1: a, d2: b });
    if (!s.ordCand.some((c, i) => c && s.ordRolls[i] === 0)) {
      let best = 0;
      for (let i = 0; i < s.n; i++) if (s.ordCand[i]) best = Math.max(best, s.ordRolls[i]);
      const win = [];
      for (let i = 0; i < s.n; i++) if (s.ordCand[i] && s.ordRolls[i] === best) win.push(i);
      if (win.length === 1) {
        s.turn = win[0]; s.phase = 'roll'; s.ordCand = Array(s.n).fill(false); s.ordRolls = Array(s.n).fill(0);
        say(s, `@${win[0]} saca ${best} y empieza la partida`, 'start');
      } else {
        s.ordCand = Array.from({ length: s.n }, (_, i) => win.includes(i)); s.ordRolls = Array(s.n).fill(0);
        say(s, `Empate a ${best}: vuelven a tirar ${win.map((w) => '@' + w).join(' y ')}`, 'start');
      }
    }
    return ok();
  }

  // un trato pendiente solo admite respuesta
  if (s.trade && op !== 'Y' && op !== 'N' && op !== 'z') return 'Hay un trato pendiente';

  switch (op) {
    case 'r': {
      if (!mine || s.phase !== 'roll') return 'No puedes tirar ahora';
      if (dry) return OK;
      const a = rint(s, 6) + 1, b = rint(s, 6) + 1;
      s.d1 = a; s.d2 = b;
      say(s, `@${seat} saca ${a} y ${b}`, 'dice', { seat, d1: a, d2: b });
      if (s.jail[seat]) {
        if (a === b) {
          s.jail[seat] = false; s.jt[seat] = 0; s.again = false;
          say(s, `@${seat} saca dobles y sale de la cárcel`, 'free');
          moveDice(s, seat, a + b);
        } else if (++s.jt[seat] >= s.jmax[seat]) {
          s.jail[seat] = false; s.jt[seat] = 0; s.again = false;
          say(s, `@${seat} agota su condena: paga la multa y sale`);
          pay(s, seat, JAIL_FINE, -1, ' de multa');
          if (s.debts.length) s.pendMove = a + b; else moveDice(s, seat, a + b);
        } else { say(s, `@${seat} sigue en la cárcel (${s.jt[seat]}/${s.jmax[seat]})`); s.again = false; }
      } else if (a === b) {
        if (++s.dbl >= 3) { say(s, `@${seat} saca tres dobles seguidos`); sendJail(s, seat, ''); }
        else {
          s.again = true;
          if (s.cfg.dblBonus) { const v = s.dbl === 1 ? 50 : 100; s.cash[seat] += v; say(s, `@${seat} gana ${eur(v)} por dobles`, 'gain', { seat, amt: v }); }
          moveDice(s, seat, a + b);
        }
      } else { s.again = false; s.dbl = 0; moveDice(s, seat, a + b); }
      settle(s);
      return ok();
    }
    case 'p':
      if (!mine || s.phase !== 'roll' || !s.jail[seat]) return 'No estás en la cárcel';
      if (s.cash[seat] < JAIL_FINE) return 'No tienes dinero';
      if (dry) return OK;
      s.cash[seat] -= JAIL_FINE; s.jail[seat] = false; s.jt[seat] = 0;
      say(s, `@${seat} paga ${eur(JAIL_FINE)} y sale de la cárcel`, 'free', { seat, amt: JAIL_FINE, to: -1 });
      return ok();
    case 'c':
      if (!mine || s.phase !== 'roll' || !s.jail[seat] || s.jf[seat] < 1) return 'No puedes usar la carta';
      if (dry) return OK;
      s.jf[seat]--; s.jail[seat] = false; s.jt[seat] = 0;
      for (const w of ['c', 'h']) if (s.deck[w].held >= 0 && s.heldBy[w] === seat) { s.deck[w].held = -1; s.heldBy[w] = -1; break; }
      say(s, `@${seat} usa la carta y sale de la cárcel`, 'free');
      return ok();
    case 'b': {
      if (!mine || s.phase !== 'buy') return 'No puedes comprar ahora';
      const q = sq(s, s.buy);
      if (s.cash[seat] < q.p) return 'No tienes dinero';
      if (dry) return OK;
      s.cash[seat] -= q.p; s.own[s.buy] = seat;
      say(s, `@${seat} compra ${q.n} por ${eur(q.p)}`, 'buy', { seat, sq: s.buy, amt: q.p });
      s.buy = -1; settle(s);
      return ok();
    }
    case 'n':
      if (!mine || s.phase !== 'buy') return 'No hay nada que decidir';
      if (dry) return OK;
      say(s, `@${seat} no compra ${sq(s, s.buy).n}`);
      s.buy = -1; settle(s);
      return ok();
    case 'a': {
      if (!mine || s.phase !== 'buy' || !s.cfg.auction) return 'No hay subasta';
      if (dry) return OK;
      const order = [];
      for (let k = 1; k <= s.n; k++) { const t = (s.turn + k) % s.n; if (!s.bk[t]) order.push(t); }
      s.auction = { sq: s.buy, bid: 0, bidder: -1, active: order, cur: order[0] };
      say(s, `Subasta de ${sq(s, s.buy).n}`, 'auction');
      s.buy = -1; settle(s);
      return ok();
    }
    case 'i': case 'f': {
      const au = s.auction;
      if (s.phase !== 'auction' || !au || au.cur !== seat) return 'No es tu turno de pujar';
      if (op === 'i') {
        const amt = Number(arg);
        if (!Number.isInteger(amt) || amt <= au.bid || amt > s.cash[seat]) return 'Puja no válida';
        if (dry) return OK;
        au.bid = amt; au.bidder = seat;
        say(s, `@${seat} puja ${eur(amt)}`, 'bid', { seat, amt });
      } else {
        if (dry) return OK;
        say(s, `@${seat} se retira`);
      }
      const pos = au.active.indexOf(seat);
      if (op === 'f') au.active.splice(pos, 1);
      if (au.active.length === 0 || (au.active.length === 1 && au.bidder === au.active[0])) {
        if (au.bidder >= 0) {
          s.cash[au.bidder] -= au.bid; s.own[au.sq] = au.bidder;
          say(s, `@${au.bidder} gana la subasta de ${sq(s, au.sq).n} por ${eur(au.bid)}`, 'buy', { seat: au.bidder, sq: au.sq, amt: au.bid });
        } else say(s, `Nadie puja por ${sq(s, au.sq).n}`);
        s.auction = null; settle(s);
      } else au.cur = au.active[(op === 'f' ? pos : pos + 1) % au.active.length];
      return ok();
    }
    case 'e':
      if (!mine || s.phase !== 'end') return 'No puedes terminar el turno';
      if (dry) return OK;
      nextTurn(s);
      return ok();
    case 'h': case 's': case 'm': case 'u': {
      const i = Number(arg);
      if (!Number.isInteger(i) || i < 0 || i > 39) return 'Casilla no válida';
      const inDebt = s.phase === 'debt' && s.debts[0]?.seat === seat && (op === 's' || op === 'm');
      if (!inDebt && !(mine && free)) return 'Ahora no puedes';
      const err = op === 'h' ? canBuild(s, seat, i) : op === 's' ? canSell(s, seat, i) : op === 'm' ? canMortgage(s, seat, i) : canUnmortgage(s, seat, i);
      if (err) return err;
      if (dry) return OK;
      const q = sq(s, i), c = houseCost(s, i);
      if (op === 'h') {
        s.cash[seat] -= c;
        s.lvl[i]++;
        say(s, `@${seat} construye ${s.lvl[i] === 5 ? 'un hotel' : 'una casa'} en ${q.n}`, 'build', { seat, sq: i, amt: -c });
      } else if (op === 's') {
        s.cash[seat] += c / 2;
        s.lvl[i]--;
        say(s, `@${seat} vende un edificio de ${q.n}`, 'build', { seat, sq: i, amt: c / 2 });
      } else if (op === 'm') {
        s.mort[i] = true; s.cash[seat] += q.p / 2;
        say(s, `@${seat} hipoteca ${q.n} y cobra ${eur(q.p / 2)}`, 'mort', { seat, sq: i, amt: q.p / 2 });
      } else {
        s.cash[seat] -= unmortgageCost(s, i); s.mort[i] = false;
        say(s, `@${seat} deshipoteca ${q.n}`, 'mort', { seat, sq: i, amt: -unmortgageCost(s, i) });
      }
      return ok();
    }
    case 'R': {
      // dado triple (comprado en la tienda): se tiran tres dados y no se aplica nada de los dobles
      if (!mine || s.phase !== 'roll' || s.tri[seat] < 1 || s.jail[seat]) return 'No puedes tirar el dado triple ahora';
      if (dry) return OK;
      s.tri[seat]--;
      const a = rint(s, 6) + 1, b = rint(s, 6) + 1, c3 = rint(s, 6) + 1;
      s.d1 = a; s.d2 = b;
      say(s, `@${seat} usa el dado triple y saca ${a}, ${b} y ${c3}`, 'dice', { seat, d1: a, d2: b, d3: c3 });
      s.again = false; s.dbl = 0;
      moveDice(s, seat, a + b + c3);
      settle(s);
      return ok();
    }
    case 'w': {
      // doble condena (comprada en la tienda): en la cárcel, en vez de 3 turnos puedes estar hasta 6
      if (!mine || s.phase !== 'roll' || !s.jail[seat] || s.dj[seat] < 1 || s.jmax[seat] > 3) return 'No puedes usar la doble condena';
      if (dry) return OK;
      s.dj[seat]--; s.jmax[seat] = 6;
      say(s, `@${seat} usa la doble condena: puede estar hasta 6 turnos en la cárcel`, 'msg', { seat });
      return ok();
    }
    case 'o': {
      // tienda de La Peza (regla opcional): se compra estando en La Peza
      if (!s.cfg.shop || !(mine && free) || s.pos[seat] !== 20 || s.jail[seat]) return 'La tienda está en La Peza';
      const item = SHOP[Number(arg)];
      if (!item) return 'Objeto no válido';
      if (s.cash[seat] < item.p) return 'No tienes dinero';
      if (dry) return OK;
      s.cash[seat] -= item.p;
      if (Number(arg) === 1) s.mc[seat]++; else if (Number(arg) === 2) s.tri[seat]++; else s.dj[seat]++;
      say(s, `@${seat} compra ${item.n} en la tienda por ${eur(item.p)}`, 'buy', { seat, amt: item.p });
      return ok();
    }
    case 'x': {
      // movimiento por raíles: en vez de tirar, pagas y viajas a otra estación tuya (la siguiente 50 €, la de enfrente 150 €); solo hacia delante
      if (!s.cfg.rails || !mine || s.phase !== 'roll' || s.dbl > 0 || s.again) return 'Ahora no puedes viajar en tren';
      if (s.jail[seat]) return 'Estás en la cárcel';
      if (s.railCd[seat] > 0) return 'Tras viajar en tren, el turno siguiente toca tirar los dados';
      const st = [5, 15, 25, 35], from = s.pos[seat], k = st.indexOf(from), to = Number(arg), j = st.indexOf(to);
      if (k < 0 || s.own[from] !== seat || s.mort[from]) return 'Tienes que estar en una estación tuya';
      if (j < 0 || s.own[to] !== seat || s.mort[to]) return 'Esa estación no es tuya';
      const step = (j - k + 4) % 4, cost = step === 1 ? 50 : step === 2 ? 150 : 0;
      if (!cost) return 'Solo a la siguiente o a la de enfrente';
      if (s.cash[seat] < cost) return 'No tienes dinero';
      if (dry) return OK;
      s.cash[seat] -= cost;
      say(s, `@${seat} paga ${eur(cost)} y viaja en tren hasta ${sq(s, to).n}`, 'rail', { seat, from, to, amt: cost });
      if (to < from) goPay(s, seat, false); // cruza la Salida
      s.pos[seat] = to; s.again = false; s.dbl = 0; s.railCd[seat] = 2; // en tu siguiente turno no puedes volver a viajar
      settle(s);
      return ok();
    }
    case 'k': {
      const d = s.debts[0];
      if (s.phase !== 'debt' || !d || d.seat !== seat || !d.rent || s.mc[seat] < 1) return 'No puedes usar Maricarmen';
      if (dry) return OK;
      s.debts.shift(); s.mc[seat]--;
      if (s.mcBy === seat) { s.deck.c.held2 = -1; s.mcBy = -1; } // si era la de la baraja, vuelve a ella; la comprada simplemente se gasta
      say(s, `@${seat} usa Maricarmen y no paga el alquiler`, 'free', { seat });
      settle(s);
      return ok();
    }
    case 'd': {
      const d = s.debts[0];
      if (s.phase !== 'debt' || !d || d.seat !== seat) return 'No tienes deudas';
      if (s.cash[seat] < d.amt) return 'No tienes dinero suficiente';
      if (dry) return OK;
      s.debts.shift();
      if (d.tax && s.cfg.pot) s.pot += d.amt;
      s.cash[seat] -= d.amt;
      if (d.to >= 0) s.cash[d.to] += d.amt;
      say(s, `@${seat} paga ${eur(d.amt)}${d.to >= 0 ? ` a @${d.to}` : ''}${d.why}`, 'pay', { seat, to: d.to, amt: d.amt });
      settle(s);
      return ok();
    }
    case 'q': {
      const d = s.debts[0];
      if (s.phase !== 'debt' || !d || d.seat !== seat) return 'No tienes deudas';
      if (dry) return OK;
      bankrupt(s, seat, d.to, 'se declara en quiebra');
      settle(s);
      return ok();
    }
    case 'z':
      if (!(mine && free) && !(s.phase === 'debt' && s.debts[0]?.seat === seat)) return 'Solo puedes abandonar en tu turno';
      if (dry) return OK;
      bankrupt(s, seat, -1, 'abandona la partida');
      if (s.auction) s.auction = null;
      s.buy = -1;
      if (s.phase !== 'over') { if (s.turn === seat || s.bk[s.turn]) nextTurn(s); settle(s); }
      return ok();
    case 'T': {
      if (!(mine && free)) return 'Solo en tu turno';
      const t = parseTrade(s, seat, arg), err = tradeError(s, t);
      if (err) return err;
      if (dry) return OK;
      s.trade = t;
      say(s, `@${seat} propone un trato a @${t.to}`, 'trade');
      return ok();
    }
    case 'Y': {
      const t = s.trade;
      if (!t || t.to !== seat) return 'No hay trato para ti';
      const err = tradeError(s, t);
      if (err) return err;
      if (dry) return OK;
      s.cash[t.from] += t.rc - t.gc; s.cash[t.to] += t.gc - t.rc;
      for (const i of t.gp) s.own[i] = t.to;
      for (const i of t.rp) s.own[i] = t.from;
      s.jf[t.from] += t.rj - t.gj; s.jf[t.to] += t.gj - t.rj;
      let gj = t.gj, rj = t.rj;
      for (const w of ['c', 'h']) {
        if (s.deck[w].held < 0) continue;
        if (s.heldBy[w] === t.from && gj > 0) { s.heldBy[w] = t.to; gj--; } else if (s.heldBy[w] === t.to && rj > 0) { s.heldBy[w] = t.from; rj--; }
      }
      s.trade = null;
      say(s, `@${t.to} acepta el trato de @${t.from}`, 'trade');
      return ok();
    }
    case 'N': {
      const t = s.trade;
      if (!t || (t.to !== seat && t.from !== seat)) return 'No hay trato';
      if (dry) return OK;
      s.trade = null;
      say(s, t.from === seat ? `@${seat} cancela el trato` : `@${seat} rechaza el trato`, 'trade');
      return ok();
    }
    default: return 'Acción desconocida';
  }
}

/** Reproduce una lista de acciones («asiento.código»); las no válidas se ignoran igual en todos los dispositivos. */
export function replay(data, n, cfg, seed, acts) {
  const s = initGame(data, n, cfg, seed);
  for (const a of acts) applyText(s, a);
  return s;
}
export function applyText(s, text) {
  const k = text.indexOf('.');
  if (k < 1) return 'Acción no válida';
  return act(s, Number(text.slice(0, k)), text.slice(k + 1));
}

/** Resumen del estado, para comparar partidas entre JS y Kotlin. */
export const digest = (s) => JSON.stringify([s.pos, s.cash, s.jail, s.jt, s.jf, s.bk, s.own, s.lvl, s.mort.map(Number), s.heldBy.c, s.heldBy.h, s.turn, s.phase, s.dbl, s.again, s.buy, s.winner, s.r, s.mc, s.mcBy, s.pot, s.ordCand.map(Number), s.ordRolls, s.railCd, s.tri, s.dj, s.jmax, s.debts.map((d) => [d.seat, d.amt, d.to]), s.auction && [s.auction.sq, s.auction.bid, s.auction.bidder, s.auction.cur]]);
