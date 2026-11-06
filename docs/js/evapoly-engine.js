// Motor de reglas del Evapoly (Monopoly), puro y determinista: la partida es una lista de acciones («asiento.código») que se
// aplica sobre un estado inicial que sale de la semilla de la sala, así todos los jugadores llegan al mismo estado.
// El tablero y las cartas están en data/evapoly.json. Hay una copia idéntica en Kotlin (EvapolyEngine.kt): si cambias una,
// cambia la otra.
//
// Códigos de acción (el asiento va delante: «2.r»):
//   r tirar · p pagar multa de cárcel · c usar carta de cárcel · b comprar · n no comprar · a subastar
//   i<cantidad> pujar · f retirarse de la subasta · e terminar turno
//   h<casilla> construir · s<casilla> vender edificio · m<casilla> hipotecar · u<casilla> deshipotecar
//   B billete de tren · O<casilla> poner obras · X<jugador> intercambiar posición · x<estación> viajar en tren a otra estación tuya (regla opcional) · k usar la carta Maricarmen (te libra de un alquiler) · d pagar la deuda · q declararse en quiebra · z abandonar la partida
//   T<a>|<doy €>|<pido €>|<doy casillas>|<pido casillas>|<doy cartas>|<pido cartas> proponer un trato · Y aceptar · N rechazar/cancelar

export const GO_SALARY = 200;
export const JAIL_FINE = 50;
export const JAIL = 10;
export const MAX_HOUSES = 32, MAX_HOTELS = 12; // límite de casas y de hoteles de CADA jugador (no se comparte)
/** Tienda de La Peza (regla opcional): objetos que no salen en Suerte ni en Caja. */
export const SHOP = { 1: { n: 'la carta Maricarmen', p: 400 }, 2: { n: 'el dado triple', p: 100 }, 3: { n: 'la doble condena', p: 100 }, 4: { n: 'el billete de tren', p: 50 }, 5: { n: 'las obras', p: 100 }, 6: { n: 'el intercambio', p: 100 }, 7: { n: 'el desatascador', p: 100 }, 8: { n: 'Inténtalo otra vez', p: 150 } };
/** Calidad de los objetos: los legendarios son Maricarmen y Inténtalo otra vez; el resto son raros. Maricarmen no se vende (solo sale en una carta, una vez por partida). */
export const LEGENDARY = new Set([1, 8]), NOT_FOR_SALE = new Set([1]);
/** Premio del objetivo secreto: 250 € si se juega con 1500 € de base o menos y 350 € con más. */
export const goalPrize = (s) => (s.cfg.money <= 1500 ? 250 : 350);
/** Stock rotativo de la tienda: cada SHOP_REFRESH turnos de jugador se eligen 3 objetos a la venta y uno es el objeto del día (mitad de precio, una sola unidad). */
export const SHOP_REFRESH = 6, SELLABLE = [2, 3, 4, 5, 6, 7, 8];
export const shopPrice = (s, k) => (s.dayItem === k ? Math.floor(SHOP[k].p / 2) : SHOP[k].p);
/** Turnos de jugador que faltan para que cambie el stock. */
export const shopTurnsLeft = (s) => SHOP_REFRESH - (s.tc.reduce((a, b) => a + b, 0) % SHOP_REFRESH);
export const MAX_ITEMS = 2, RAIN_SLIP = 20, SLIP_COST = 50, CARD_ITEM_REFUND = 50;

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
    data, n, cfg: { money: cfg.money, goBonus: !!cfg.goBonus, auction: !!cfg.auction, pot: !!cfg.pot, dblBonus: !!cfg.dblBonus, rails: !!cfg.rails, ownerFree: !!cfg.ownerFree, shop: !!cfg.shop, events: !!cfg.events, goals: !!cfg.goals }, r: seed | 0,
    pos: Array(n).fill(0), cash: Array(n).fill(cfg.money), jail: Array(n).fill(false), jt: Array(n).fill(0), jf: Array(n).fill(0),
    bk: Array(n).fill(false),
    own: Array(40).fill(-1), lvl: Array(40).fill(0), mort: Array(40).fill(false),
    deck: { c: { order: [], i: 0, held: -1, held2: -1 }, h: { order: [], i: 0, held: -1, held2: -1 } }, mc: Array(n).fill(0), mcBy: -1, pot: 0,
    turn: 0, phase: 'roll', dbl: 0, again: false, d1: 0, d2: 0, buy: -1, auction: null, debts: [], pendMove: -1, trade: null,
    heldBy: { c: -1, h: -1 }, winner: -1, ev: [], acts: 0, ordCand: Array(n).fill(true), ordRolls: Array(n).fill(0), railCd: Array(n).fill(0), tri: Array(n).fill(0), dj: Array(n).fill(0), jmax: Array(n).fill(3),
    tk: Array(n).fill(0), ob: Array(n).fill(0), sw: Array(n).fill(0), sh: Array(n).fill(0), fence: Array(40).fill(-1),
    pl: Array(n).fill(0), rt: Array(n).fill(0), tr: Array(n).fill(0), dc: Array(n).fill(0), lg: 0, pend: null, sb: Array(n).fill(0), imm: -1, jc: Array(n).fill(0), gt: Array(n).fill(0), ga: Array(n).fill(0), gd: Array(n).fill(0), gx: Array(n).fill(0), snowman: -1, mer: -1, mo: [], base: data, perm: Array.from({ length: 40 }, (_, i) => i), evu: 0, frz: Array(n).fill(0), evk: '', evl: Array(n).fill(0), evf: false, evChk: false, dog: -1, tc: Array(n).fill(0),
    sx: Array(n).fill(0), sg: Array(n).fill(0), lt: Array(n).fill(0), tb: Array(n).fill(0), hb: Array(n).fill(0), xr: false, stock: [], dayItem: 0,
  };
  s.deck.c.order = shuffled(s, data.cards.chance.length);
  s.deck.h.order = shuffled(s, data.cards.chest.length);
  // quién empieza: cada jugador tira los dados y empieza el más alto (los empates vuelven a tirar)
  if (s.cfg.goals) assignGoals(s);
  if (s.cfg.shop) restock(s, false);
  s.phase = 'order';
  say(s, 'Todos tiran los dados: empieza el que saque más', 'start');
  return s;
}

/** Cambia el stock de la tienda: 3 objetos al azar a la venta y uno de ellos es el objeto del día. */
function restock(s, announce) {
  s.stock = shuffled(s, SELLABLE.length).slice(0, 3).map((i) => SELLABLE[i]);
  s.dayItem = s.stock[rint(s, 3)];
  if (announce) say(s, `La tienda de La Peza cambia de stock. Objeto del día: ${SHOP[s.dayItem].n} a mitad de precio`);
}

/** Objetivo secreto de cada jugador: conseguir una calle, servicio o estación en concreto, pasar 3 turnos en la cárcel o conseguir un objeto de la tienda. */
function assignGoals(s) {
  const props = [];
  for (let i = 0; i < 40; i++) if (['street', 'station', 'utility'].includes(sq(s, i).t)) props.push(i);
  // tipos: 1 una casilla, 2 cárcel (3 turnos), 3 un objeto, 5 un viaje en tren (solo con raíles o tienda), 6 sacar dobles seguidos en un mismo turno, 7 tacaño (5 turnos sin gastar), 8 monopolio, 9 construir 3 casas (u hoteles), 10 dar la vuelta al tablero en 4 turnos
  const types = [1, 2, 3, 6, 7, 8, 9, 10];
  const used = new Set(); // dos jugadores no reciben la misma casilla
  if (s.cfg.rails || s.cfg.shop) types.push(5);
  for (let seat = 0; seat < s.n; seat++) {
    const t = types[rint(s, types.length)];
    s.gt[seat] = t;
    if (t === 1) { const pool = props.filter((p) => !used.has(p)); s.ga[seat] = pool[rint(s, pool.length)]; used.add(s.ga[seat]); }
    else if (t === 3) s.ga[seat] = [2, 3, 4, 5, 6, 7][rint(s, 6)];
    else s.ga[seat] = t === 5 ? 1 : t === 2 ? 3 : t === 6 ? 2 : t === 7 ? 5 : t === 9 ? 3 : t === 10 ? 4 : 0; // cárcel: 3 turnos; tren: 1 viaje; dobles: 2 seguidos; tacaño: 5 turnos; casas: 3; vuelta: 4 turnos
  }
}

function goalDone(s, seat, t, a) {
  switch (t) {
    case 1: return s.own[s.perm.indexOf(a)] === seat;
    case 2: return s.jc[seat] >= a;
    case 5: return s.tr[seat] >= a;
    case 6: return s.dc[seat] >= a;
    case 7: return s.sg[seat] >= a; // turnos seguidos sin gastar dinero
    case 8: for (let i = 0; i < 40; i++) if (sq(s, i).t === 'street' && ownsAll(s, seat, sq(s, i).g)) return true; return false; // algún grupo de color completo
    case 9: return s.hb[seat] >= a; // casas u hoteles construidos
    case 10: return s.tb[seat] > 0;
    default: return ((s.gx[seat] >> a) & 1) === 1;
  }
}

/** Comprueba si alguien ha cumplido su objetivo secreto: cobra 250 € (350 € si la partida empezó con más de 1500 €). */
function checkGoals(s) {
  if (!s.cfg.goals) return;
  for (let seat = 0; seat < s.n; seat++) {
    if (s.bk[seat] || s.gd[seat] || !s.gt[seat]) continue;
    const t = s.gt[seat], a = s.ga[seat];
    if (!goalDone(s, seat, t, a)) continue;
    const prize = goalPrize(s);
    s.gd[seat] = 1; s.cash[seat] += prize;
    say(s, `@${seat} cumple su objetivo secreto y cobra ${eur(prize)}`, 'gain', { seat, amt: prize });
  }
}

export const sq = (s, i) => s.data.squares[i];
const say = (s, m, k = 'msg', extra = {}) => s.ev.push({ k, m, ...extra });
/** Objetos que cuentan para el límite de 2 (la doble condena no cuenta). */
export const heldItems = (s, seat) => s.mc[seat] + s.tri[seat] + s.tk[seat] + s.ob[seat] + s.sw[seat] + s.pl[seat] + s.rt[seat];
/** Cuántos de ese objeto de la tienda lleva (1 Maricarmen … 7 desatascador). */
export const itemHeld = (s, k, seat) => [0, s.mc, s.tri, s.dj, s.tk, s.ob, s.sw, s.pl, s.rt][k][seat];
/** Marca que el jugador ha gastado dinero (rompe la racha del objetivo «tacaño»). */
const spent = (s, seat) => { s.sx[seat] = 1; };
const shielded = (s, seat) => s.evk === 'shield' && s.imm === seat && s.evl[seat] > 0;
export const alive = (s) => { const a = []; for (let i = 0; i < s.n; i++) if (!s.bk[i]) a.push(i); return a; };
const nextAlive = (s, from) => { for (let i = 1; i <= s.n; i++) { const t = (from + i) % s.n; if (!s.bk[t]) return t; } return from; };
export const groupOf = (s, g) => { const a = []; for (let i = 0; i < 40; i++) if (sq(s, i).g === g) a.push(i); return a; };
/** Coste base de una casa u hotel (lo que se recupera a medias al venderlo). */
export const houseBase = (s, i) => s.data.house[sq(s, i).g] || 0;
/** Coste de construir ahora mismo: con el boom inmobiliario cuesta la mitad. */
export const houseCost = (s, i) => (s.evk === 'boom' ? Math.floor(houseBase(s, i) / 2) : s.evk === 'crisis' ? houseBase(s, i) * 2 : houseBase(s, i));
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
  if (q.t === 'station') return q.r[countOwned(s, o, 'station') - 1] * (s.evk === 'rush' ? 2 : 1);
  if (q.t === 'utility') return diceTotal * (countOwned(s, o, 'utility') === 2 ? 10 : 4);
  return 0;
}

/** Patrimonio de un jugador en efectivo si lo vendiese todo (para decidir si puede salir de una deuda). */
export function worth(s, seat) {
  let w = s.cash[seat];
  for (let i = 0; i < 40; i++) if (s.own[i] === seat) { if (!s.mort[i]) w += sq(s, i).p / 2; w += s.lvl[i] * houseBase(s, i) / 2; }
  return w;
}

// ───── dinero ─────
function pay(s, seat, amt, to, why, rent = false, tax = false) {
  if (amt <= 0) return;
  if (shielded(s, seat)) { say(s, `@${seat} está protegido por el escudo y no paga ${eur(amt)}`, 'shield', { seat, amt }); return; }
  if (rent && s.mc[seat] > 0) {
    // con Maricarmen en la mano el alquiler no se cobra solo: se decide si pagarlo o usar la carta
    s.debts.push({ seat, amt, to, why, rent: true });
    say(s, `@${seat} debe ${eur(amt)} a @${to}${why}`, 'debt', { seat });
  } else if (s.cash[seat] >= amt) {
    s.cash[seat] -= amt; spent(s, seat);
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
  if (s.gt[seat] === 10 && s.lt[seat] + 1 <= s.ga[seat]) s.tb[seat] = 1; // vuelta completada a tiempo (este turno cuenta)
  s.lt[seat] = 0;
  say(s, landed ? `@${seat} cae en la Salida y cobra ${eur(amt)}` : `@${seat} pasa por la Salida y cobra ${eur(amt)}`, 'go', { seat, amt });
}

function moveTo(s, seat, to, collect) {
  const from = s.pos[seat];
  if (collect && (to < from || to === 0)) goPay(s, seat, to === 0);
  s.pos[seat] = to;
  say(s, `@${seat} avanza hasta ${sq(s, to).n}`, 'move', { seat, from, to, dir: 1 });
}

function moveDice(s, seat, total) {
  // se avanza casilla a casilla: al llegar a unas obras (valla) se da la vuelta; la Salida se cobra una vez si se cruza hacia delante
  const from = s.pos[seat], segs = [];
  const rev = s.evk === 'reverse' && s.evl[seat] > 0, gale = s.evk === 'gale' && s.evl[seat] > 0;
  let pos = from, dir = rev ? -1 : 1, seg = from, pass = false;
  for (let k = 1; k <= total; k++) {
    let nxt = (pos + dir + 40) % 40;
    if (s.fence[nxt] >= 0) {
      // las obras son una pared: no se entra en su casilla; se levantan y se sigue hacia atrás desde donde se está
      const wall = nxt;
      s.fence[wall] = -1;
      segs.push({ from: seg, to: pos, dir, sq: wall });
      seg = pos; dir = -dir;
      nxt = (pos + dir + 40) % 40;
      if (s.fence[nxt] >= 0) continue; // también hay obras detrás: este paso se queda quieto
    }
    pos = nxt;
    if (!rev && dir === 1 && pos === 0) pass = true; // con la dirección contraria no se cobra la Salida
  }
  if (pass) goPay(s, seat, pos === 0);
  s.pos[seat] = pos;
  for (const g of segs) say(s, `@${seat} choca con las obras de ${sq(s, g.sq).n} y da la vuelta`, 'move', { seat, from: g.from, to: g.to, dir: g.dir, sq: g.sq });
  say(s, `@${seat} avanza ${total} y cae en ${sq(s, pos).n}`, 'move', { seat, from: seg, to: pos, dir });
  if (gale && pos >= 2) {
    // vendaval: te echa hacia atrás de 1 a 3 casillas, pero nunca más allá de la primera calle
    const np = Math.max(1, pos - (rint(s, 3) + 1));
    say(s, `Un vendaval echa a @${seat} ${pos - np} casilla${pos - np > 1 ? 's' : ''} atrás hasta ${sq(s, np).n}`, 'move', { seat, from: pos, to: np, dir: -1 });
    s.pos[seat] = pos = np;
  }
  land(s, seat, pos, { dice: total });
}

function sendJail(s, seat, why) {
  if (s.evk === 'amnesty' && s.evl[seat] > 0) { s.again = false; s.dbl = 0; say(s, `Amnistía: @${seat} se libra de ir a la cárcel`, 'msg', { seat }); return; }
  const from = s.pos[seat];
  s.pos[seat] = JAIL; s.jail[seat] = true; s.jt[seat] = 0; s.jmax[seat] = 3;
  s.again = false; s.dbl = 0;
  say(s, `@${seat} va a la cárcel${why}`, 'jail', { seat, from, to: JAIL });
}

function land(s, seat, i, ctx) {
  if (s.evk === 'snow' && s.snowman === i && !s.bk[seat] && !s.frz[seat]) { s.frz[seat] = 1; say(s, `@${seat} choca con el muñeco de nieve y se queda congelado: perderá su próximo turno`, 'freeze', { seat, sq: i }); }
  if (s.evk === 'dog' && s.dog === i && !s.bk[seat]) dogBite(s, seat);
  const q = sq(s, i);
  switch (q.t) {
    case 'street': case 'station': case 'utility': {
      const o = s.own[i];
      if (s.evk === 'merchant' && s.mer === i && !s.bk[seat]) { merchant(s, seat, i, o); break; }
      if (o < 0) {
        s.buy = i; // aunque falte dinero se ofrece: se puede hipotecar o vender casas para reunirlo antes de decidir
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
        s.ev.push({ k: 'card', m: 'La Peza: Tito Pepe te ha soltado unos fajos de billetes', seat, text: 'Tito Pepe te ha soltado unos fajos de billetes', deck: 'p', amt: 1 });
        say(s, `@${seat} se lleva ${eur(v)} de los impuestos de ${q.n}`, 'gain', { seat, amt: v });
      }
      break;
    case 'chance': case 'chest': {
      const w = q.t === 'chance' ? 'c' : 'h';
      draw(s, seat, w);
      // sorpresas y cajas: durante el evento se cogen dos cartas (si la primera no te ha movido de sitio ni te ha mandado a la cárcel)
      if (s.evk === 'twice' && s.evl[seat] > 0 && !s.bk[seat] && !s.jail[seat] && s.pos[seat] === i && !s.debts.length && s.phase !== 'over') draw(s, seat, w);
      break;
    }
    case 'gotojail': sendJail(s, seat, ''); break;
    default: break;
  }
}

/** Mercader Mohamed: quien cae en su estación no paga alquiler y puede comprarle una de 3 propiedades libres al azar (la propia estación, si está libre, es una de ellas). */
function merchant(s, seat, i, o) {
  say(s, `El mercader Mohamed recibe a @${seat}`, 'merchant', { seat, sq: i });
  if (o >= 0 && o !== seat) say(s, `El mercader Mohamed libra a @${seat} de pagar el alquiler`);
  const cand = [];
  for (let k = 0; k < 40; k++) if (k !== i && s.own[k] < 0 && ['street', 'station', 'utility'].includes(sq(s, k).t)) cand.push(k);
  const offers = o < 0 ? [i] : [];
  for (const k of shuffled(s, cand.length)) { if (offers.length >= 3) break; offers.push(cand[k]); }
  s.mo = offers;
  if (offers.length) say(s, `El mercader Mohamed ofrece: ${offers.map((k) => sq(s, k).n).join(', ')}`);
}

// ───── cartas ─────
function draw(s, seat, which) {
  const d = s.deck[which], cards = which === 'c' ? s.data.cards.chance : s.data.cards.chest;
  let idx;
  const legend = (e) => (e === 'mc' ? 1 : e === 'item:8' ? 2 : 0); // objetos legendarios: cada uno sale una sola vez por partida
  do { idx = d.order[d.i % d.order.length]; d.i++; } while (idx === d.held || idx === d.held2 || (s.lg & legend(cards[idx].e)));
  s.lg |= legend(cards[idx].e);
  const [kind, a, b] = cards[idx].e.split(':');
  // ánimo de la carta (amt): 1 buena, -1 mala, 0 neutra; solo sirve para la animación
  const mood = ({ cash: Number(a) >= 0 ? 1 : -1, eachget: 1, mc: 1, item: 1, free: 1, jail: -1, repair: -1, eachpay: -1 })[kind] || 0;
  say(s, `${which === 'c' ? 'Suerte' : 'Caja'}: ${cards[idx].x}`, 'card', { seat, text: cards[idx].x, deck: which, amt: mood });
  switch (kind) {
    case 'to': { const t = s.perm.indexOf(Number(a)); moveTo(s, seat, t, true); land(s, seat, t, {}); break; }
    case 'near': {
      const list = [], p = s.pos[seat];
      for (let k = 0; k < 40; k++) if (sq(s, k).t === (a === 'u' ? 'utility' : 'station')) list.push(k);
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
    case 'mc':
      if (s.mc[seat] > 0 || heldItems(s, seat) >= MAX_ITEMS) { s.cash[seat] += CARD_ITEM_REFUND; say(s, `@${seat} no puede llevar más objetos: se destruye y cobra ${eur(CARD_ITEM_REFUND)}`, 'gain', { seat, amt: CARD_ITEM_REFUND }); }
      else { d.held2 = idx; s.mc[seat]++; s.mcBy = seat; s.gx[seat] |= 1 << 1; }
      break;
    case 'item': {
      // objeto de la tienda encontrado en una carta: si no cabe (ya lo lleva o lleva dos), se destruye y cobra 50 €
      const k = Number(a);
      if (itemHeld(s, k, seat) > 0 || (k !== 3 && heldItems(s, seat) >= MAX_ITEMS)) { s.cash[seat] += CARD_ITEM_REFUND; say(s, `@${seat} no puede llevar ${SHOP[k].n}: se destruye y cobra ${eur(CARD_ITEM_REFUND)}`, 'gain', { seat, amt: CARD_ITEM_REFUND }); break; }
      [null, null, s.tri, s.dj, s.tk, s.ob, s.sw, s.pl, s.rt][k][seat]++; s.gx[seat] |= 1 << k;
      say(s, `@${seat} se queda con ${SHOP[k].n}`, 'msg', { seat });
      break;
    }
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
    s.cash[seat] += s.lvl[i] * houseBase(s, i) / 2;
    s.lvl[i] = 0;
  }
}

/** Solo se pueden llevar dos objetos (la doble condena aparte): si sobran, se quedan los primeros. */
function trimItems(s, seat) {
  let kept = 0;
  [s.mc, s.tri, s.dj, s.tk, s.ob, s.sw, s.pl, s.rt].forEach((a, k) => {
    if (a[seat] <= 0) return;
    if (k !== 2 && kept >= MAX_ITEMS) { a[seat] = 0; if (k === 0 && s.mcBy === seat) { s.deck.c.held2 = -1; s.mcBy = -1; } } else { a[seat] = 1; if (k !== 2) kept++; }
  });
}

function bankrupt(s, seat, to, why) {
  liquidate(s, seat);
  for (let i = 0; i < 40; i++) if (s.own[i] === seat) { s.own[i] = to; if (to < 0) s.mort[i] = false; }
  if (to >= 0) { s.cash[to] += s.cash[seat]; s.jf[to] += s.jf[seat]; s.mc[to] += s.mc[seat]; s.tri[to] = Math.min(1, s.tri[to] + s.tri[seat]); s.dj[to] = Math.min(1, s.dj[to] + s.dj[seat]); s.tk[to] = Math.min(1, s.tk[to] + s.tk[seat]); s.ob[to] = Math.min(1, s.ob[to] + s.ob[seat]); s.sw[to] = Math.min(1, s.sw[to] + s.sw[seat]); s.pl[to] = Math.min(1, s.pl[to] + s.pl[seat]); s.rt[to] = Math.min(1, s.rt[to] + s.rt[seat]); if (s.mcBy === seat) s.mcBy = to; }
  else if (s.mcBy === seat) { s.deck.c.held2 = -1; s.mcBy = -1; }
  else for (const w of ['c', 'h']) if (s.deck[w].held >= 0 && s.heldBy[w] === seat) { s.deck[w].held = -1; s.heldBy[w] = -1; }
  if (to >= 0) trimItems(s, to);
  s.cash[seat] = 0; s.jf[seat] = 0; s.mc[seat] = 0; s.tri[seat] = 0; s.dj[seat] = 0; s.tk[seat] = 0; s.ob[seat] = 0; s.sw[seat] = 0; s.pl[seat] = 0; s.rt[seat] = 0; s.evl[seat] = 0; s.bk[seat] = true; s.jail[seat] = false;
  for (let i = 0; i < 40; i++) if (s.fence[i] === seat) s.fence[i] = -1;
  s.debts = s.debts.filter((d) => d.seat !== seat).map((d) => (d.to === seat ? { ...d, to } : d));
  if (s.trade && (s.trade.from === seat || s.trade.to === seat)) s.trade = null;
  say(s, `@${seat} ${why}${to >= 0 ? `: todo pasa a @${to}` : ': todo vuelve al banco'}`, 'bankrupt', { seat });
  const left = alive(s);
  if (left.length === 1) { s.winner = left[0]; s.phase = 'over'; say(s, `@${left[0]} gana la partida`, 'win', { seat: left[0] }); }
}

// ───── eventos aleatorios (regla opcional) ─────
const EV_NAMES = ['dog', 'snow', 'rain', 'quake', 'crisis', 'balance', 'hacienda', 'thief', 'boom', 'rush', 'shield', 'reverse', 'chaos', 'twice', 'gale', 'amnesty', 'merchant', 'lottery', 'pot', 'swap', 'dturn', 'police'], EV_DUR = [5, 4, 4, 5, 2, 0, 0, 0, 2, 2, 2, 2, 2, 2, 2, 1, 4, 0, 0, 0, 1, 2], EV_W = [2, 20, 20, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], EV_TURNS = 5, SHIELD_TURNS = 2, RUSH_STEPS = 2, BALANCE_PRIZE = 500, DOG_BITE = 150, DOG_STEP = 4, QUAKE_PCT = 20, EVENT_PCT = 20, CRISIS_PRICE = 50, CRISIS_RENT = 25, LOTTERY_UNIT = 25;
/** Precio al que se compra una propiedad ahora mismo (con la crisis económica sube). */
export const buyPrice = (s, i) => sq(s, i).p;
const EV_TEXT = ['Evento del perro sarnoso: un perro avanza 4 casillas por turno y muerde a quien esté en su casilla o caiga en ella', 'Evento de nieve: está nevando, solo se tira un dado y un muñeco de nieve congela un turno a quien caiga en su casilla', 'Evento de lluvia: el suelo está mojado y al tirar hay un 20 % de resbalar y perder 50 €', 'Evento del terremoto: al acabar cada turno hay un 20 % de que tiemble la tierra y se derrumbe un edificio al azar de cada jugador (un hotel se queda en 3 casas)', 'Evento de crisis económica: durante 2 turnos construir casas y hoteles cuesta el doble', 'Evento de equilibrio: el jugador con menos patrimonio (dinero, propiedades y construcciones) recibe 500 €; si hay empate, cobran todos los empatados', 'Evento de Hacienda: cada jugador paga el 10 % de su dinero, que va al bote de La Peza (o a la banca si no hay bote)', 'Evento del ladrón: el jugador con más dinero pierde 100 € y se los lleva el que menos tiene', 'Evento de boom inmobiliario: durante 2 turnos construir casas y hoteles cuesta la mitad', 'Evento de hora punta: durante 2 turnos los dados suman 2 casillas más y las estaciones cobran el doble de alquiler', 'Evento de inmunidad: el jugador con menos patrimonio tiene un escudo durante 2 turnos y no pierde dinero por alquileres, impuestos, cartas ni eventos', 'Evento de dirección contraria: durante 2 turnos de cada jugador se avanza hacia atrás y no se cobra la Salida', 'Evento de caos: durante 2 turnos de cada jugador todas las casillas, menos las esquinas, cambian de sitio al azar y los trenes no funcionan', 'Evento de sorpresas y cajas: durante 2 turnos de cada jugador, al caer en Suerte o Caja se cogen dos cartas', 'Evento de vendaval: durante 2 turnos de cada jugador, al tirar un vendaval te echa de 1 a 3 casillas hacia atrás, sin pasar de la primera calle', 'Amnistía Internacional: salen todos de la cárcel y durante un turno de cada jugador nadie puede ir a la cárcel', 'Evento del mercader Mohamed: durante 4 turnos de cada jugador se instala en una estación al azar; quien caiga en ella no paga alquiler y puede comprarle una de 3 propiedades libres al azar', 'Evento de lotería: el jugador que activa el evento tira un dado y cobra 25 € por cada punto que saque', 'Evento de reparto del bote: el bote de La Peza se reparte a partes iguales entre todos los jugadores', 'Evento de intercambio de sitio: dos jugadores al azar intercambian su posición, sin sufrir el efecto de la casilla en la que quedan', 'Evento de turno doble: durante 1 turno de cada jugador se tira dos veces; los dobles no dan más tiradas ni llevan a la cárcel, pero siguen cobrando su premio si está activado', 'Evento de policía: durante 2 turnos de cada jugador, quien saque dobles va directo a la cárcel'];
const EV_END = ['El perro sarnoso ha desaparecido', 'Ha salido el sol: se acabó la nieve', 'Ha salido el sol: se acabó la lluvia', 'La tierra ha dejado de temblar', 'La crisis económica ha terminado', '', '', '', 'El boom inmobiliario ha terminado', 'Ha pasado la hora punta', 'El escudo se ha desvanecido', 'Se acabó la dirección contraria', 'El caos ha terminado: cada casilla vuelve a su sitio', 'Se acabaron las cartas dobles', 'El viento se ha calmado', 'La amnistía ha terminado', 'El mercader Mohamed se ha ido', '', '', '', 'Se acabó el turno doble', 'La policía se ha ido'];

// el perro solo muerde a quien está en su casilla: cuando él llega a ella o cuando alguien cae en ella
function dogBite(s, i) {
  if (shielded(s, i)) { say(s, `El escudo protege a @${i} del perro`, 'shield', { seat: i, amt: 0 }); return; }
  const v = Math.min(DOG_BITE, s.cash[i]);
  s.cash[i] -= v; if (v > 0) spent(s, i);
  say(s, `El perro muerde a @${i} y le quita ${eur(v)}`, 'bite', { seat: i, amt: v, sq: s.pos[i] });
}
function dogBites(s, squares) {
  for (let i = 0; i < s.n; i++) if (!s.bk[i] && squares.includes(s.pos[i])) dogBite(s, i);
}

/** Patrimonio: dinero + precio de las propiedades (las hipotecadas, a la mitad) + coste de las casas y hoteles. */
function fortune(s, seat) {
  let w = s.cash[seat];
  for (let i = 0; i < 40; i++) if (s.own[i] === seat) { w += s.mort[i] ? sq(s, i).p / 2 : sq(s, i).p; w += s.lvl[i] * houseBase(s, i); }
  return w;
}

/** Equilibrio (evento instantáneo): quien tenga menos patrimonio cobra 500 €; si hay empate, cobran todos los empatados. */
function balance(s, seat) {
  say(s, EV_TEXT[5], 'evt', { seat, amt: 1, sq: 5 });
  const left = alive(s), f = left.map((i) => fortune(s, i)), low = Math.min(...f);
  left.forEach((i, k) => {
    if (f[k] !== low) return;
    s.cash[i] += BALANCE_PRIZE;
    say(s, `Equilibrio: @${i} recibe ${eur(BALANCE_PRIZE)}`, 'gain', { seat: i, amt: BALANCE_PRIZE });
  });
}

/** Hacienda (evento instantáneo): cada jugador paga el 10 % de su dinero al bote de La Peza (o a la banca). */
function hacienda(s, seat) {
  say(s, EV_TEXT[6], 'evt', { seat, amt: 1, sq: 6 });
  for (const i of alive(s)) {
    const v = Math.floor(s.cash[i] * 0.1);
    if (v <= 0 || shielded(s, i)) continue;
    s.cash[i] -= v; spent(s, i); if (s.cfg.pot) s.pot += v;
    say(s, `Hacienda: @${i} paga ${eur(v)}`, 'pay', { seat: i, to: -1, amt: v });
  }
}

/** Ladrón (evento instantáneo): el jugador con más dinero pierde 100 € y se los lleva el que menos tiene. */
function thief(s, seat) {
  say(s, EV_TEXT[7], 'evt', { seat, amt: 1, sq: 7 });
  const left = alive(s);
  let rich = left[0], poor = left[0];
  for (const i of left) { if (s.cash[i] > s.cash[rich]) rich = i; if (s.cash[i] < s.cash[poor]) poor = i; }
  if (rich === poor || shielded(s, rich)) { say(s, 'El ladrón no encuentra a quién robar'); return; }
  const v = Math.min(100, s.cash[rich]);
  if (v <= 0) return;
  s.cash[rich] -= v; spent(s, rich); s.cash[poor] += v;
  say(s, `El ladrón le quita ${eur(v)} a @${rich} y se los da a @${poor}`, 'pay', { seat: rich, to: poor, amt: v });
}

/** Lotería (evento instantáneo): el jugador que activa el evento tira un dado y cobra 25 € por cada punto. */
function lottery(s, seat) {
  say(s, EV_TEXT[17], 'evt', { seat, amt: 1, sq: 17 });
  const d = rint(s, 6) + 1, v = LOTTERY_UNIT * d;
  s.cash[seat] += v;
  say(s, `Lotería: @${seat} saca un ${d} y cobra ${eur(v)}`, 'gain', { seat, amt: v });
}

/** Reparto del bote (evento instantáneo): el bote de La Peza se divide a partes iguales entre los jugadores. */
function potSplit(s, seat) {
  say(s, EV_TEXT[18], 'evt', { seat, amt: 1, sq: 18 });
  const left = alive(s), each = Math.floor(s.pot / left.length);
  s.pot -= each * left.length;
  for (const i of left) { s.cash[i] += each; say(s, `Reparto del bote: @${i} recibe ${eur(each)}`, 'gain', { seat: i, amt: each }); }
}

/** Jugadores que pueden cambiar de sitio con el evento (los presos no). */
const swappable = (s) => alive(s).filter((i) => !s.jail[i]);

/** Intercambio de sitio (evento instantáneo): dos jugadores al azar cambian de posición, sin que les afecte la casilla en la que quedan. */
function swapPlaces(s, seat) {
  say(s, EV_TEXT[19], 'evt', { seat, amt: 1, sq: 19 });
  const c = swappable(s), o = shuffled(s, c.length), a = c[o[0]], b = c[o[1]], pa = s.pos[a], pb = s.pos[b];
  s.pos[a] = pb; s.pos[b] = pa;
  say(s, `@${a} y @${b} cambian de sitio`, 'swap', { seat: a, to: b, from: pa, sq: pb });
}

/** Construcciones en el tablero (un hotel cuenta como una). */
function constructions(s) {
  let n = 0;
  for (let i = 0; i < 40; i++) n += s.lvl[i] === 5 ? 1 : s.lvl[i];
  return n;
}

/** Terremoto: derrumba un edificio al azar de cada jugador: una casa pierde un piso y un hotel se queda en 3 casas; sin edificios, se libra. */
function quake(s) {
  say(s, '¡Terremoto! La tierra tiembla', 'quake', { sq: -1 });
  for (let seat = 0; seat < s.n; seat++) {
    if (s.bk[seat]) continue;
    const houses = [];
    for (let i = 0; i < 40; i++) if (s.own[i] === seat && s.lvl[i] >= 1) houses.push(i);
    if (!houses.length) continue;
    const i = houses[rint(s, houses.length)];
    const hotel = s.lvl[i] === 5;
    s.lvl[i] = hotel ? Math.max(0, Math.min(3, MAX_HOUSES - housesOf(s, seat))) : s.lvl[i] - 1; // el hotel pasa a 3 casas si caben en el límite del jugador
    say(s, hotel ? `El terremoto derrumba el hotel de @${seat} en ${sq(s, i).n}: se queda en 3 casas` : `El terremoto derrumba una casa de @${seat} en ${sq(s, i).n}`, 'crumble', { seat, sq: i, amt: hotel ? 1 : 0 });
  }
}

/** Recoloca las casillas (menos las esquinas): `perm[i]` es la casilla original que queda en la posición i. Dueños, casas, hipotecas y obras viajan con la casilla. */
export function setPerm(s, perm) {
  const inv = Array(40);
  s.perm.forEach((o, p) => { inv[o] = p; });
  for (const a of [s.own, s.lvl, s.mort, s.fence]) { const old = a.slice(); for (let i = 0; i < 40; i++) a[i] = old[inv[perm[i]]]; }
  s.perm = perm;
  s.data = { ...s.base, squares: perm.map((o) => s.base.squares[o]) };
  s.trade = null;
}

/** Elige el evento: nieve y lluvia pesan 20 y el resto 2 (25 % + 25 % + 50 % repartido); nieve y lluvia solo salen una vez por partida. */
function pickEvent(s) {
  const w = EV_W.map((v, k) => (((k === 1 || k === 2) && (s.evu >> k) & 1)) || (k === 3 && constructions(s) < 3) || (k === 12 && (s.buy >= 0 || s.auction || s.debts.length)) || (k === 18 && (!s.cfg.pot || s.pot < alive(s).length)) || (k === 19 && (swappable(s).length < 2 || s.buy >= 0 || s.auction || s.debts.length || s.mo.length)) ? 0 : v);
  const tot = w.reduce((x, y) => x + y, 0);
  if (!tot) return -1;
  let r = rint(s, tot);
  for (let k = 0; k < w.length; k++) { r -= w[k]; if (r < 0) return k; }
  return -1;
}

/** Una vez por turno, tras tirar: un 20 % de que se active un evento (si no hay otro activo ni son los dos primeros turnos del jugador). */
function maybeEvent(s, seat) {
  if (!s.cfg.events || s.evChk || s.bk[seat] || s.phase === 'over') return;
  s.evChk = true;
  if (s.evk || s.tc[seat] < 2 || rint(s, 100) >= EVENT_PCT) return;
  const kind = pickEvent(s);
  if (kind < 0) return;
  if (kind === 1 || kind === 2) s.evu |= 1 << kind; // nieve y lluvia solo salen una vez por partida; el resto se puede repetir
  if (kind >= 5 && kind <= 7) { [balance, hacienda, thief][kind - 5](s, seat); return; }
  if (kind >= 17 && kind <= 19) { [lottery, potSplit, swapPlaces][kind - 17](s, seat); return; }
  s.evk = EV_NAMES[kind]; s.evf = true;
  for (let i = 0; i < s.n; i++) s.evl[i] = s.bk[i] ? 0 : EV_DUR[kind];
  if (kind === 1) {
    // el muñeco de nieve aparece en una casilla en la que no haya nadie
    const cand = [];
    for (let i = 0; i < 40; i++) if (!s.pos.some((p, j) => !s.bk[j] && p === i)) cand.push(i);
    s.snowman = cand[rint(s, cand.length)];
  }
  if (kind === 10) {
    // inmunidad: escudo de 3 turnos para el jugador que va peor (menos patrimonio)
    const left = alive(s), f = left.map((i) => fortune(s, i)), w = left[f.indexOf(Math.min(...f))];
    s.imm = w; s.evl = Array(s.n).fill(0); s.evl[w] = SHIELD_TURNS;
  }
  say(s, EV_TEXT[kind], 'evt', kind === 10 ? { seat: s.imm, amt: 1, sq: kind } : { seat, amt: 1, sq: kind });
  if (kind === 0) { s.dog = 0; dogBites(s, [0]); }
  if (kind === 12) {
    // caos: las casillas que no son esquina se mezclan
    const ids = [];
    for (let i = 1; i < 40; i++) if (i % 10) ids.push(i);
    const mix = shuffled(s, ids.length).map((k) => ids[k]), perm = Array.from({ length: 40 }, (_, i) => i);
    ids.forEach((pos, k) => { perm[pos] = mix[k]; });
    setPerm(s, perm);
    say(s, 'Las casillas se mezclan', 'chaos', { seat });
  }
  if (kind === 16) {
    // mercader Mohamed: se instala en una estación al azar
    const st = [];
    for (let i = 0; i < 40; i++) if (sq(s, i).t === 'station') st.push(i);
    s.mer = st[rint(s, st.length)];
  }
  if (kind === 15) {
    // amnistía: los presos salen y durante un turno nadie va a la cárcel
    for (const i of alive(s)) if (s.jail[i]) { s.jail[i] = false; s.jt[i] = 0; say(s, `Amnistía: @${i} sale de la cárcel`, 'free', { seat: i }); }
  }
}

function nextTurn(s) {
  const prev = s.turn;
  s.tc[prev]++;
  if (s.cfg.shop && s.tc.reduce((a, b) => a + b, 0) % SHOP_REFRESH === 0) restock(s, true);
  if (s.sx[prev]) s.sg[prev] = 0; else s.sg[prev]++; // objetivo «tacaño»: turnos seguidos sin gastar
  s.sx[prev] = 0; s.lt[prev]++; s.xr = false; // lt: turnos desde la última Salida
  if (s.evk) {
    if (s.evf) s.evf = false;
    else {
      if (s.evk === 'dog') {
        const from = s.dog, path = Array.from({ length: DOG_STEP }, (_, i) => (from + i + 1) % 40);
        s.dog = path[DOG_STEP - 1];
        say(s, 'El perro avanza 4 casillas', 'dog', { from, to: s.dog });
        dogBites(s, [s.dog]);
      } else if (s.evk === 'quake' && rint(s, 100) < QUAKE_PCT) quake(s);
      s.evl[prev] = Math.max(0, s.evl[prev] - 1);
    }
    if (s.evl.every((v, i) => s.bk[i] || v <= 0)) {
      const kind = EV_NAMES.indexOf(s.evk);
      say(s, EV_END[kind], 'evt', { amt: 0, sq: kind });
      if (s.evk === 'chaos') setPerm(s, Array.from({ length: 40 }, (_, i) => i));
      s.evk = ''; s.dog = -1; s.imm = -1; s.snowman = -1; s.mer = -1; s.evl = Array(s.n).fill(0);
    }
  }
  s.evChk = false;
  s.turn = nextAlive(s, s.turn);
  s.phase = 'roll'; s.dbl = 0; s.again = false; s.buy = -1; s.mo = [];
  if (s.railCd[s.turn] > 0) s.railCd[s.turn]--;
  s.sh[s.turn] = 0; // una nueva visita a La Peza: otra compra en la tienda
  if (s.frz[s.turn] > 0) { s.frz[s.turn]--; say(s, `@${s.turn} está congelado y pierde el turno`, 'freezeskip', { seat: s.turn }); nextTurn(s); }
}

/** Tira los dados del jugador (con la nieve solo uno; con la lluvia puede resbalar; con la hora punta suma casillas). Devuelve [dado1, dado2, extra, resbala]. */
function throwDice(s, seat) {
  const snow = s.evk === 'snow' && s.evl[seat] > 0 && !s.jail[seat];
  const a = rint(s, 6) + 1, b = snow ? 0 : rint(s, 6) + 1;
  const slip = s.evk === 'rain' && s.evl[seat] > 0 && rint(s, 100) < RAIN_SLIP;
  s.d1 = a; s.d2 = b;
  const rush = s.evk === 'rush' && s.evl[seat] > 0 ? RUSH_STEPS : 0; // hora punta: los dados llevan más lejos
  if (rush) say(s, `@${seat} avanza ${rush} casillas más por la hora punta`);
  say(s, b ? `@${seat} saca ${a} y ${b}` : `@${seat} saca ${a}`, 'dice', { seat, d1: a, d2: b });
  return [a, b, rush, slip ? 1 : 0];
}

/** Aplica una tirada ya hecha: dobles, cárcel, movimiento, resbalón y posible evento. */
function finishRoll(s, seat, [a, b, rush, slipN]) {
  const slip = slipN === 1;
  if (a === b && !s.jail[seat] && !(s.evk === 'police' && s.evl[seat] > 0)) s.dc[seat] = Math.max(s.dc[seat], s.dbl + 1); // objetivo secreto: dobles seguidos en un mismo turno
  if (s.jail[seat]) {
    if (a === b) {
      s.jail[seat] = false; s.jt[seat] = 0; s.again = false;
      say(s, `@${seat} saca dobles y sale de la cárcel`, 'free');
      moveDice(s, seat, a + b + rush);
    } else if (s.jt[seat] >= s.jmax[seat]) {
      // ya ha pasado todos sus turnos en la cárcel: en este intento sale sin pagar multa
      s.jail[seat] = false; s.jt[seat] = 0; s.again = false;
      say(s, `@${seat} cumple su condena y sale de la cárcel`, 'free');
      moveDice(s, seat, a + b + rush);
    } else { s.jc[seat]++; s.jt[seat]++; say(s, `@${seat} sigue en la cárcel (${s.jt[seat]}/${s.jmax[seat]})`); s.again = false; } // jc: turnos que ha pasado en la cárcel (objetivo secreto)
  } else if (a === b) {
    if (s.evk === 'police' && s.evl[seat] > 0) sendJail(s, seat, ': la policía lo pilla sacando dobles');
    else if (++s.dbl >= 3) { say(s, `@${seat} saca tres dobles seguidos`); sendJail(s, seat, ''); }
    else {
      s.again = true;
      if (s.cfg.dblBonus) { const v = s.dbl === 1 ? 50 : 100; s.cash[seat] += v; say(s, `@${seat} gana ${eur(v)} por dobles`, 'gain', { seat, amt: v }); }
      moveDice(s, seat, a + b + rush);
    }
  } else { s.again = false; s.dbl = 0; moveDice(s, seat, a + b + rush); }
  if (s.evk === 'dturn' && s.evl[seat] > 0 && !s.bk[seat] && !s.jail[seat] && s.phase !== 'over') {
    // turno doble: se tira dos veces; los dobles no dan una tirada más
    if (!s.xr) { s.xr = true; s.again = true; } else s.again = false;
  }
  if (slip && !s.bk[seat] && s.phase !== 'over') { say(s, `@${seat} resbala en el suelo mojado`, 'slip', { seat }); pay(s, seat, SLIP_COST, -1, ' por resbalar'); }
  maybeEvent(s, seat);
  settle(s);
}

/** Decide en qué fase queda la partida cuando una acción termina de resolverse. */
function settle(s) {
  if (s.phase === 'over') return;
  if (s.debts.length) { s.phase = 'debt'; return; }
  if (s.auction) { s.phase = 'auction'; return; }
  if (s.bk[s.turn]) { s.pendMove = -1; nextTurn(s); return; }
  if (s.pendMove >= 0) { const t = s.pendMove; s.pendMove = -1; moveDice(s, s.turn, t); settle(s); return; }
  if (s.mo.length) { s.phase = 'merchant'; return; }
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
  const ok = () => { if (!dry) { s.acts++; checkGoals(s); } return OK; };

  // emoticonos: no cambian el juego, los puede enviar cualquiera en cualquier momento
  if (op === 'E') {
    if (!/^[0-2]$/.test(arg)) return 'Emoticono no válido';
    if (dry) return OK;
    say(s, '', 'emote', { seat, sq: Number(arg) });
    return ok();
  }

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
      const t = throwDice(s, seat);
      if (s.rt[seat] > 0 && !s.jail[seat]) { s.pend = t; s.phase = 'retry'; return ok(); } // con «Inténtalo otra vez» se decide si se queda con la tirada
      finishRoll(s, seat, t);
      return ok();
    }
    case 'G': {
      // se queda con la tirada (con el objeto «Inténtalo otra vez»)
      if (!mine || s.phase !== 'retry' || !s.pend) return 'No hay nada que decidir';
      if (dry) return OK;
      const t = s.pend; s.pend = null; s.phase = 'roll';
      finishRoll(s, seat, t);
      return ok();
    }
    case 'J': {
      // «Inténtalo otra vez»: gasta el objeto y vuelve a tirar los dados
      if (!mine || s.phase !== 'retry' || !s.pend || s.rt[seat] < 1) return 'No puedes volver a tirar';
      if (dry) return OK;
      s.rt[seat]--; s.pend = null; s.phase = 'roll';
      say(s, `@${seat} usa «Inténtalo otra vez» y vuelve a tirar`, 'msg', { seat });
      finishRoll(s, seat, throwDice(s, seat));
      return ok();
    }
    case 'p':
      if (!mine || s.phase !== 'roll' || !s.jail[seat]) return 'No estás en la cárcel';
      if (s.cash[seat] < JAIL_FINE) return 'No tienes dinero';
      if (dry) return OK;
      s.cash[seat] -= JAIL_FINE; spent(s, seat); s.jail[seat] = false; s.jt[seat] = 0;
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
      const q = sq(s, s.buy), price = buyPrice(s, s.buy);
      if (s.cash[seat] < price) return 'No tienes dinero';
      if (dry) return OK;
      s.cash[seat] -= price; spent(s, seat); s.own[s.buy] = seat;
      say(s, `@${seat} compra ${q.n} por ${eur(price)}`, 'buy', { seat, sq: s.buy, amt: price });
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
          s.cash[au.bidder] -= au.bid; spent(s, au.bidder); s.own[au.sq] = au.bidder;
          say(s, `@${au.bidder} gana la subasta de ${sq(s, au.sq).n} por ${eur(au.bid)}`, 'buy', { seat: au.bidder, sq: au.sq, amt: au.bid, dir: 0 });
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
      const inBuy = (s.phase === 'buy' || s.phase === 'merchant') && mine && (op === 's' || op === 'm'); // al decidir una compra se puede reunir dinero
      if (!inDebt && !inBuy && !(mine && free)) return 'Ahora no puedes';
      const err = op === 'h' ? canBuild(s, seat, i) : op === 's' ? canSell(s, seat, i) : op === 'm' ? canMortgage(s, seat, i) : canUnmortgage(s, seat, i);
      if (err) return err;
      if (dry) return OK;
      const q = sq(s, i), c = op === 's' ? houseBase(s, i) : houseCost(s, i);
      if (op === 'h') {
        s.cash[seat] -= c; spent(s, seat);
        s.lvl[i]++;
        s.hb[seat]++; // objetivo secreto: edificios construidos
        say(s, `@${seat} construye ${s.lvl[i] === 5 ? 'un hotel' : 'una casa'} en ${q.n}`, 'build', { seat, sq: i, amt: -c });
      } else if (op === 's') {
        s.cash[seat] += c / 2;
        s.lvl[i]--;
        say(s, `@${seat} vende un edificio de ${q.n}`, 'build', { seat, sq: i, amt: c / 2 });
      } else if (op === 'm') {
        s.mort[i] = true; s.cash[seat] += q.p / 2;
        say(s, `@${seat} hipoteca ${q.n} y cobra ${eur(q.p / 2)}`, 'mort', { seat, sq: i, amt: q.p / 2 });
      } else {
        s.cash[seat] -= unmortgageCost(s, i); spent(s, seat); s.mort[i] = false;
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
      maybeEvent(s, seat);
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
      const k = Number(arg), item = SHOP[k];
      if (!item || NOT_FOR_SALE.has(k)) return 'Objeto no válido';
      if (!s.stock.includes(k)) return 'Ese objeto no está en la tienda ahora';
      if (s.sh[seat]) return 'Solo puedes comprar un objeto por visita';
      const have = [0, s.mc, s.tri, s.dj, s.tk, s.ob, s.sw, s.pl, s.rt][k][seat];
      if (have > 0 || (s.sb[seat] >> k) & 1) return 'Ya has comprado ese objeto';
      if (k !== 3 && heldItems(s, seat) >= MAX_ITEMS) return 'Solo puedes llevar dos objetos a la vez';
      const price = shopPrice(s, k), daily = s.dayItem === k;
      if (s.cash[seat] < price) return 'No tienes dinero';
      if (dry) return OK;
      s.cash[seat] -= price; spent(s, seat); s.sh[seat] = 1; s.sb[seat] |= 1 << k; s.gx[seat] |= 1 << k;
      if (k === 1) s.mc[seat]++; else if (k === 2) s.tri[seat]++; else if (k === 3) s.dj[seat]++; else if (k === 4) s.tk[seat]++; else if (k === 5) s.ob[seat]++; else if (k === 6) s.sw[seat]++; else if (k === 7) s.pl[seat]++; else s.rt[seat]++;
      say(s, `@${seat} compra ${item.n} en la tienda por ${eur(price)}${daily ? ' (objeto del día)' : ''}`, 'buy', { seat, amt: price });
      if (daily) { s.dayItem = 0; s.stock = s.stock.filter((x) => x !== k); } // el objeto del día es una sola unidad
      return ok();
    }
    case 'B': {
      // billete de tren (tienda): desde una estación (tuya o no) viajas a la siguiente, en vez de tirar
      if (!mine || s.phase !== 'roll' || s.dbl > 0 || s.again || s.tk[seat] < 1) return 'Ahora no puedes usar el billete';
      if (s.jail[seat]) return 'Estás en la cárcel';
      if (s.evk === 'chaos') return 'Los trenes no funcionan durante el caos';
      const st = [5, 15, 25, 35], from = s.pos[seat], k = st.indexOf(from);
      if (k < 0) return 'Tienes que estar en una estación';
      if (dry) return OK;
      const to = st[(k + 1) % 4];
      s.tk[seat]--; s.tr[seat]++;
      say(s, `@${seat} usa el billete y viaja en tren hasta ${sq(s, to).n}`, 'rail', { seat, from, to, amt: 0 });
      if (to < from) goPay(s, seat, false);
      s.pos[seat] = to; s.again = false; s.dbl = 0;
      land(s, seat, to, {});
      settle(s);
      return ok();
    }
    case 'O': {
      // obras (tienda): una valla en cualquier casilla; quien llega a ella da la vuelta. Cada jugador tiene como mucho una valla puesta
      if (!(mine && free) || s.ob[seat] < 1) return 'No puedes poner obras ahora';
      if (!/^\d+$/.test(arg) || Number(arg) > 39) return 'Casilla no válida';
      const i = Number(arg);
      if (s.fence[i] >= 0) return 'Ya hay obras ahí';
      if (dry) return OK;
      s.ob[seat]--;
      const old = s.fence.indexOf(seat); if (old >= 0) s.fence[old] = -1;
      s.fence[i] = seat;
      say(s, `@${seat} pone obras en ${sq(s, i).n}`, 'fence', { seat, sq: i });
      return ok();
    }
    case 'X': {
      // intercambio (tienda): cambias tu casilla con la de otro jugador en vez de tirar; si llegas a la Salida cobras 200
      if (!mine || s.phase !== 'roll' || s.dbl > 0 || s.again || s.sw[seat] < 1 || s.jail[seat]) return 'Ahora no puedes intercambiar';
      if (!/^\d+$/.test(arg)) return 'Jugador no válido';
      const t = Number(arg);
      if (t >= s.n || t === seat || s.bk[t]) return 'Jugador no válido';
      if (s.jail[t]) return 'Está en la cárcel';
      if (s.pos[t] === s.pos[seat]) return 'Estáis en la misma casilla';
      if (dry) return OK;
      s.sw[seat]--;
      const a = s.pos[seat], b = s.pos[t];
      s.pos[seat] = b; s.pos[t] = a;
      say(s, `@${seat} cambia de sitio con @${t}`, 'swap', { seat, to: t, from: a, sq: b });
      if (b === 0) { s.cash[seat] += GO_SALARY; say(s, `@${seat} cae en la Salida por el intercambio y cobra ${eur(GO_SALARY)}`, 'go', { seat, amt: GO_SALARY }); }
      s.again = false; s.dbl = 0;
      land(s, seat, b, {});
      settle(s);
      return ok();
    }
    case 'V': {
      // desatascador (tienda): chupa a otro jugador y lo deja en tu casilla; no sustituye la tirada
      if (!mine || s.phase !== 'roll' || s.pl[seat] < 1 || s.jail[seat]) return 'Ahora no puedes usar el desatascador';
      if (!/^\d+$/.test(arg)) return 'Jugador no válido';
      const t = Number(arg);
      if (t >= s.n || t === seat || s.bk[t]) return 'Jugador no válido';
      if (s.jail[t]) return 'Está en la cárcel';
      if (s.pos[t] === s.pos[seat]) return 'Ya está en tu casilla';
      if (dry) return OK;
      s.pl[seat]--;
      const from = s.pos[t], a = s.pos[seat];
      s.pos[t] = a;
      say(s, `@${seat} chupa a @${t} con el desatascador y lo deja a su lado`, 'pull', { seat, to: t, from, sq: a });
      if (s.evk === 'dog' && s.dog === a) dogBite(s, t);
      return ok();
    }
    case 'x': {
      // movimiento por raíles: en vez de tirar, pagas y viajas a otra estación tuya (la siguiente 50 €, la de enfrente 150 €); solo hacia delante
      if (!s.cfg.rails || !mine || s.phase !== 'roll' || s.dbl > 0 || s.again) return 'Ahora no puedes viajar en tren';
      if (s.jail[seat]) return 'Estás en la cárcel';
      if (s.evk === 'chaos') return 'Los trenes no funcionan durante el caos';
      if (s.railCd[seat] > 0) return 'Tras viajar en tren, el turno siguiente toca tirar los dados';
      const st = [5, 15, 25, 35], from = s.pos[seat], k = st.indexOf(from), to = Number(arg), j = st.indexOf(to);
      if (k < 0 || s.own[from] !== seat || s.mort[from]) return 'Tienes que estar en una estación tuya';
      if (j < 0 || s.own[to] !== seat || s.mort[to]) return 'Esa estación no es tuya';
      const step = (j - k + 4) % 4, cost = step === 1 ? 50 : step === 2 ? 150 : 0;
      if (!cost) return 'Solo a la siguiente o a la de enfrente';
      if (s.cash[seat] < cost) return 'No tienes dinero';
      if (dry) return OK;
      s.cash[seat] -= cost; spent(s, seat);
      say(s, `@${seat} paga ${eur(cost)} y viaja en tren hasta ${sq(s, to).n}`, 'rail', { seat, from, to, amt: cost });
      if (to < from) goPay(s, seat, false); // cruza la Salida
      s.pos[seat] = to; s.again = false; s.dbl = 0; s.railCd[seat] = 2; s.tr[seat]++; // en tu siguiente turno no puedes volver a viajar
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
      s.cash[seat] -= d.amt; spent(s, seat);
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
    case 'W': {
      // mercader Mohamed: se compra una de las propiedades que ofrece
      if (!mine || s.phase !== 'merchant') return 'No hay ofertas';
      const i = Number(arg);
      if (!s.mo.includes(i) || s.own[i] >= 0) return 'Esa propiedad no está en oferta';
      const price = buyPrice(s, i);
      if (s.cash[seat] < price) return 'No tienes dinero';
      if (dry) return OK;
      s.cash[seat] -= price; spent(s, seat); s.own[i] = seat;
      say(s, `@${seat} compra ${sq(s, i).n} al mercader Mohamed por ${eur(price)}`, 'buy', { seat, sq: i, amt: price });
      s.mo = []; settle(s);
      return ok();
    }
    case 'L':
      if (!mine || s.phase !== 'merchant') return 'No hay ofertas';
      if (dry) return OK;
      say(s, `@${seat} no compra nada al mercader`);
      s.mo = []; settle(s);
      return ok();
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
      if (t.gc > 0) spent(s, t.from);
      if (t.rc > 0) spent(s, t.to);
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
export const digest = (s) => JSON.stringify([s.pos, s.cash, s.jail, s.jt, s.jf, s.bk, s.own, s.lvl, s.mort.map(Number), s.heldBy.c, s.heldBy.h, s.turn, s.phase, s.dbl, s.again, s.buy, s.winner, s.r, s.mc, s.mcBy, s.pot, s.ordCand.map(Number), s.ordRolls, s.railCd, s.tri, s.dj, s.jmax, s.tk, s.ob, s.sw, s.sh, s.fence, s.pl, s.sb, s.imm, s.jc, s.gt, s.ga, s.gd, s.gx, s.snowman, s.mer, s.mo, s.perm, s.evu, s.tr, s.dc, s.rt, s.lg, s.pend, s.frz, s.evk, s.evl, s.evf, s.evChk, s.dog, s.tc, s.sx, s.sg, s.lt, s.tb, s.hb, s.xr, s.stock, s.dayItem, s.debts.map((d) => [d.seat, d.amt, d.to]), s.auction && [s.auction.sq, s.auction.bid, s.auction.bidder, s.auction.cur]]);
