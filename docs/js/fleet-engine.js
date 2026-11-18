// Hundir la flota: reglas y formatos. Sin pantalla ni red, para que se pueda probar y para que Android haga exactamente lo mismo.
//
// Formatos (iguales en la web y en Android, porque los dos dispositivos se leen entre sí):
//  · Flota «F»: los 5 barcos por orden (5, 4, 3, 3, 2) como «NNd» separados por comas; NN = casilla de la proa (fila × 10 + columna, de
//    00 a 99) y d = 'h' (se extiende hacia la derecha) o 'v' (hacia abajo). Ej.: «00h,10h,20h,30h,40h».
//  · Compromiso: sha256(F + ':' + sal) en hexadecimal. Se sube al empezar; la flota y la sal se guardan solo en el dispositivo de cada uno
//    y se revelan al terminar, así el rival comprueba que las respuestas «agua / tocado / hundido» eran verdad.
//  · Disparos: «NN» por disparo. Respuestas: un carácter por disparo del rival: '0' agua, '1' tocado, '2'–'5' hundido (tamaño del barco).

export const SIZE = 10;
export const LENS = [5, 4, 3, 3, 2];
export const NAMES = ['Portaaviones', 'Acorazado', 'Crucero', 'Submarino', 'Destructor'];

export const pad2 = (n) => String(n).padStart(2, '0');
const rowOf = (c) => Math.floor(c / SIZE), colOf = (c) => c % SIZE;

/** Casillas que ocupa el barco `i` colocado en `s` = { cell, dir }. */
export function cellsOf(s, i) {
  const out = [];
  for (let k = 0; k < LENS[i]; k++) out.push(s.dir === 'h' ? s.cell + k : s.cell + SIZE * k);
  return out;
}

/** ¿Cabe dentro del tablero? */
export function fits(s, i) {
  if (!s || !(s.cell >= 0 && s.cell < SIZE * SIZE) || (s.dir !== 'h' && s.dir !== 'v')) return false;
  return s.dir === 'h' ? colOf(s.cell) + LENS[i] <= SIZE : rowOf(s.cell) + LENS[i] <= SIZE;
}

const near = (a, b) => Math.abs(rowOf(a) - rowOf(b)) <= 1 && Math.abs(colOf(a) - colOf(b)) <= 1;

/** ¿Se puede poner el barco `i` en `s` sin salirse, sin pisar a los demás y (si `adj` es falso) sin tocarlos? `ships` = los demás (con null en los que faltan). */
export function canPlace(ships, i, s, adj) {
  if (!fits(s, i)) return false;
  const mine = cellsOf(s, i);
  for (let j = 0; j < ships.length; j++) {
    if (j === i || !ships[j]) continue;
    for (const c of cellsOf(ships[j], j)) for (const d of mine) { if (c === d) return false; if (!adj && near(c, d)) return false; }
  }
  return true;
}

/** ¿Es una flota completa y válida? */
export function validFleet(ships, adj) {
  if (!Array.isArray(ships) || ships.length !== LENS.length || ships.some((s) => !s)) return false;
  return ships.every((s, i) => canPlace(ships, i, s, adj));
}

export const packFleet = (ships) => ships.map((s) => pad2(s.cell) + s.dir).join(',');
export function parseFleet(f) {
  if (typeof f !== 'string') return null;
  const parts = f.split(',');
  if (parts.length !== LENS.length) return null;
  const ships = parts.map((p) => (/^\d\d[hv]$/.test(p) ? { cell: Number(p.slice(0, 2)), dir: p[2] } : null));
  return ships.some((s) => !s) ? null : ships;
}

/** Colocación al azar de toda la flota (sirve también para «Aleatorio» en la pantalla de colocación). */
export function randomFleet(adj, rnd = Math.random) {
  for (let intento = 0; intento < 500; intento++) {
    const ships = LENS.map(() => null);
    let ok = true;
    for (let i = 0; i < LENS.length && ok; i++) {
      let placed = false;
      for (let t = 0; t < 200 && !placed; t++) {
        const dir = rnd() < 0.5 ? 'h' : 'v', s = { cell: Math.floor(rnd() * SIZE * SIZE), dir };
        if (canPlace(ships, i, s, adj)) { ships[i] = s; placed = true; }
      }
      ok = placed;
    }
    if (ok) return ships;
  }
  // (no debería pasar) colocación fija
  return LENS.map((_, i) => ({ cell: i * 20, dir: 'h' }));
}

export async function sha256(text) {
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
}
export const newSalt = () => [...crypto.getRandomValues(new Uint8Array(8))].map((b) => b.toString(16).padStart(2, '0')).join('');
export const commitOf = (fleetStr, salt) => sha256(fleetStr + ':' + salt);

/** Respuesta a cada disparo recibido, por orden: `cells` = casillas disparadas contra esta flota. */
export function answers(ships, cells) {
  const owner = new Map();
  ships.forEach((s, i) => cellsOf(s, i).forEach((c) => owner.set(c, i)));
  const out = [], seen = ships.map(() => new Set());
  cells.forEach((c) => {
    const i = owner.get(c);
    if (i === undefined) { out.push('0'); return; }
    seen[i].add(c);
    out.push(seen[i].size >= LENS[i] ? String(LENS[i]) : '1');
  });
  return out.join('');
}

/** Índice del barco que hay en la casilla, o -1. */
export const shipAt = (ships, cell) => ships.findIndex((s, i) => cellsOf(s, i).includes(cell));

/**
 * Barcos que el defensor enseña al ser tocados (opción «al tocar un barco, se ve entero»): «iNNd» por barco = índice, proa y dirección.
 * Se comprueban al terminar la partida contra la flota revelada.
 */
export function parseRevealed(rv) {
  const out = [], s = rv || '';
  for (let k = 0; k + 4 <= s.length; k += 4) {
    const idx = Number(s[k]), cell = Number(s.slice(k + 1, k + 3)), dir = s[k + 3];
    if (Number.isInteger(idx) && idx >= 0 && idx < LENS.length && (dir === 'h' || dir === 'v') && fits({ cell, dir }, idx)) out.push({ idx, ship: { cell, dir } });
  }
  return out;
}

/** Lo que hay que enseñar al responder a `cells`: una entrada por cada barco tocado que aún no se había enseñado (`have` = lo ya enseñado). */
export function newReveals(ships, cells, have) {
  const seen = new Set(parseRevealed(have).map((x) => x.idx));
  let out = '';
  for (const c of cells) {
    const i = shipAt(ships, c);
    if (i >= 0 && !seen.has(i)) { seen.add(i); out += String(i) + pad2(ships[i].cell) + ships[i].dir; }
  }
  return out;
}

/** Casillas disparadas («0507…» → [5, 7, …]). */
export const shotCells = (s) => (s || '').match(/\d\d/g)?.map(Number) || [];

/**
 * Reproduce la partida con lo que hay en la nube: de quién es el turno, qué disparo espera respuesta y el registro de lo ocurrido.
 * `room` = { players: [anfitrión, rival], seed, again, shots, s: { uid: disparos }, a: { uid: respuestas } }. Empieza players[seed % 2]; cada turno
 * son `shots` disparos (de 1 a 5) y, si `again`, un acierto no gasta disparo.
 * Un disparo cuenta cuando su autor tenía el turno y el rival ya lo ha respondido; los hechos fuera de turno no cuentan.
 */
export function replay(room) {
  const [p0, p1] = room.players, N = Math.min(5, Math.max(1, room.shots || 1));
  const idx = { [p0]: 0, [p1]: 0 }, sunk = { [p0]: 0, [p1]: 0 }, log = [];
  let cur = room.players[room.seed % 2], winner = '', left = N;
  for (let guard = 0; guard < 400; guard++) {
    const opp = cur === p0 ? p1 : p0, sc = shotCells(room.s[cur]), ans = room.a[opp] || '';
    if (idx[cur] < sc.length && idx[cur] < ans.length) {
      const res = ans[idx[cur]];
      log.push({ by: cur, cell: sc[idx[cur]], res });
      idx[cur]++;
      if (res >= '2') sunk[cur]++;
      if (sunk[cur] >= LENS.length) { winner = cur; break; }
      if (!(res !== '0' && room.again)) left--; // con «repetir al acertar», un acierto no gasta disparo
      if (left <= 0) { cur = opp; left = N; }
    } else break;
  }
  return { cur, opp: cur === p0 ? p1 : p0, pending: !winner && idx[cur] < shotCells(room.s[cur]).length, log, winner, left };
}

/**
 * Dónde estaban los barcos que `by` ha hundido: cada respuesta «hundido» dice el tamaño del barco y la casilla del último disparo, y con
 * lo ya acertado se deduce la línea (sin barcos tocándose es única; si se tocan puede haber más de una y se elige la primera). Devuelve
 * [{ idx, ship }] con `idx` = qué barco de la flota era (por tamaño) y `ship` = su colocación; al terminar se ve la flota verdadera.
 */
export function sunkShips(log, by) {
  const hits = new Set(), used = new Set(), taken = new Set(), out = [];
  for (const e of log) {
    if (e.by !== by || e.res === '0') continue;
    hits.add(e.cell);
    if (e.res < '2') continue;
    const L = Number(e.res);
    let found = null;
    for (const dir of ['h', 'v']) {
      for (let k = 0; k < L && !found; k++) {
        const head = e.cell - k * (dir === 'h' ? 1 : SIZE);
        if (head < 0 || (dir === 'h' ? (head % SIZE) + L > SIZE : Math.floor(head / SIZE) + L > SIZE)) continue;
        const cells = Array.from({ length: L }, (_, j) => head + j * (dir === 'h' ? 1 : SIZE));
        if (cells.every((c) => hits.has(c) && !used.has(c))) { found = { cell: head, dir }; cells.forEach((c) => used.add(c)); }
      }
      if (found) break;
    }
    const idx = LENS.findIndex((l, i) => l === L && !taken.has(i));
    if (idx >= 0) taken.add(idx);
    if (found) out.push({ idx, ship: found });
  }
  return out;
}

/**
 * Comprobación al terminar: la flota revelada `reveal` («F:sal») de `owner` ¿coincide con su compromiso, es válida y con ella
 * se habrían dado las mismas respuestas que dio? Devuelve '' si todo está bien o el motivo del problema.
 */
export async function verify({ reveal, commit, adj, shots, given, rv }) {
  if (!reveal) return 'sin revelar';
  const cut = reveal.lastIndexOf(':');
  const fleet = reveal.slice(0, cut), salt = reveal.slice(cut + 1);
  if ((await commitOf(fleet, salt)) !== commit) return 'la flota no coincide con la que dejó al empezar';
  const ships = parseFleet(fleet);
  if (!ships || !validFleet(ships, adj)) return 'la flota no era válida';
  const expect = answers(ships, shotCells(shots));
  const n = Math.min(expect.length, (given || '').length);
  if (expect.slice(0, n) !== (given || '').slice(0, n)) return 'respondió algo que no era cierto';
  for (const { idx, ship } of parseRevealed(rv)) { const real = ships[idx]; if (!real || real.cell !== ship.cell || real.dir !== ship.dir) return 'enseñó un barco que no estaba ahí'; }
  return '';
}

// ───── dibujo de los barcos (en una cuadrícula de largo × 1; los dos sistemas dibujan estas mismas piezas) ─────

const C = { hull: '#6f7d8c', dark: '#3f4a56', mid: '#566370', light: '#b9c4cf', red: '#d9534f', glass: '#8fd3ff' };

/** Piezas del barco `i`: { k: 'poly'|'rect'|'ell'|'line', ... , f: relleno }. */
export function shipParts(i) {
  const L = LENS[i];
  const hull = { k: 'poly', f: C.hull, pts: [[0.06, 0.5], [L - 0.06, 0.5], [L - 0.2, 0.9], [0.22, 0.9]] };
  const waterline = { k: 'rect', f: C.dark, x: 0.2, y: 0.82, w: L - 0.4, h: 0.08 };
  switch (i) {
    case 0: // portaaviones: cubierta larga, isla y dos avioncitos
      return [hull, waterline, { k: 'rect', f: C.light, x: 0.1, y: 0.4, w: L - 0.24, h: 0.12 }, { k: 'rect', f: C.dark, x: L * 0.62, y: 0.16, w: 0.5, h: 0.26 },
        { k: 'rect', f: C.glass, x: L * 0.62 + 0.08, y: 0.22, w: 0.34, h: 0.08 }, { k: 'rect', f: C.mid, x: L * 0.62 + 0.2, y: 0.06, w: 0.06, h: 0.12 },
        { k: 'poly', f: C.red, pts: [[0.5, 0.4], [1.1, 0.4], [0.8, 0.26]] }, { k: 'poly', f: C.red, pts: [[1.6, 0.4], [2.2, 0.4], [1.9, 0.26]] }];
    case 1: // acorazado: tres torretas con cañones y puente
      return [hull, waterline, { k: 'rect', f: C.mid, x: 0.3, y: 0.34, w: 0.5, h: 0.17 }, { k: 'line', f: C.dark, x1: 0.7, y1: 0.42, x2: 1.1, y2: 0.42, w: 0.07 },
        { k: 'rect', f: C.mid, x: 1.2, y: 0.34, w: 0.5, h: 0.17 }, { k: 'line', f: C.dark, x1: 1.6, y1: 0.42, x2: 2.0, y2: 0.42, w: 0.07 },
        { k: 'rect', f: C.dark, x: 2.2, y: 0.14, w: 0.6, h: 0.36 }, { k: 'rect', f: C.glass, x: 2.3, y: 0.22, w: 0.4, h: 0.07 }, { k: 'rect', f: C.mid, x: 2.45, y: 0.04, w: 0.06, h: 0.12 },
        { k: 'rect', f: C.mid, x: 3.1, y: 0.34, w: 0.4, h: 0.17 }];
    case 2: // crucero: torreta, puente y chimenea
      return [hull, waterline, { k: 'rect', f: C.mid, x: 0.3, y: 0.34, w: 0.45, h: 0.17 }, { k: 'line', f: C.dark, x1: 0.65, y1: 0.42, x2: 1.0, y2: 0.42, w: 0.07 },
        { k: 'rect', f: C.dark, x: 1.1, y: 0.16, w: 0.6, h: 0.34 }, { k: 'rect', f: C.glass, x: 1.2, y: 0.24, w: 0.4, h: 0.07 }, { k: 'rect', f: C.red, x: 1.85, y: 0.2, w: 0.2, h: 0.3 }, { k: 'rect', f: C.mid, x: 1.4, y: 0.06, w: 0.05, h: 0.12 }];
    case 3: // submarino: casco redondeado, torre y periscopio
      return [{ k: 'ell', f: C.hull, cx: L / 2, cy: 0.64, rx: L / 2 - 0.08, ry: 0.24 }, { k: 'rect', f: C.dark, x: L / 2 - 0.3, y: 0.28, w: 0.6, h: 0.3 },
        { k: 'rect', f: C.glass, x: L / 2 - 0.2, y: 0.34, w: 0.4, h: 0.07 }, { k: 'line', f: C.mid, x1: L / 2 + 0.1, y1: 0.28, x2: L / 2 + 0.1, y2: 0.08, w: 0.05 }, { k: 'line', f: C.mid, x1: L / 2 + 0.1, y1: 0.08, x2: L / 2 + 0.32, y2: 0.08, w: 0.05 },
        { k: 'rect', f: C.dark, x: 0.12, y: 0.6, w: 0.22, h: 0.07 }];
    default: // destructor: pequeño con cañón y puente
      return [hull, waterline, { k: 'rect', f: C.mid, x: 0.3, y: 0.36, w: 0.34, h: 0.15 }, { k: 'line', f: C.dark, x1: 0.55, y1: 0.43, x2: 0.82, y2: 0.43, w: 0.06 },
        { k: 'rect', f: C.dark, x: 0.95, y: 0.2, w: 0.5, h: 0.3 }, { k: 'rect', f: C.glass, x: 1.02, y: 0.27, w: 0.34, h: 0.07 }, { k: 'rect', f: C.red, x: 1.5, y: 0.3, w: 0.14, h: 0.2 }];
  }
}
