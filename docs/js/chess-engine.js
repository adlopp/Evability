// Motor de ajedrez con las reglas oficiales: movimientos legales, jaque, jaque mate, tablas por ahogado, material insuficiente,
// triple repetición y regla de los 50 movimientos, enroque, captura al paso y promoción.
// Casillas 0..63 como en FEN: 0 = a8, 7 = h8, 56 = a1, 63 = h1. Piezas: mayúscula = blancas, minúscula = negras, '' = vacío.
// Hay una versión idéntica en Kotlin (ChessEngine.kt): si se cambia una, hay que cambiar la otra.

export const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
const FILES = 'abcdefgh';
export const sqName = (s) => FILES[s & 7] + (8 - (s >> 3));
export const sqIndex = (name) => (8 - Number(name[1])) * 8 + FILES.indexOf(name[0]);
export const colorOf = (c) => (c === '' ? '' : c === c.toUpperCase() ? 'w' : 'b');

export function fromFen(fen) {
  const [placement, turn, castling, ep, half, full] = fen.split(' ');
  const b = [];
  for (const row of placement.split('/')) for (const ch of row) { if (ch >= '1' && ch <= '8') for (let i = 0; i < +ch; i++) b.push(''); else b.push(ch); }
  return { b, turn, castling: castling === '-' ? '' : castling, ep: ep === '-' ? -1 : sqIndex(ep), half: +half, full: +full };
}

export function toFen(p) {
  let out = '';
  for (let r = 0; r < 8; r++) {
    let empty = 0;
    for (let f = 0; f < 8; f++) { const c = p.b[r * 8 + f]; if (c === '') empty++; else { if (empty) out += empty; empty = 0; out += c; } }
    if (empty) out += empty;
    if (r < 7) out += '/';
  }
  return `${out} ${p.turn} ${p.castling || '-'} ${p.ep < 0 ? '-' : sqName(p.ep)} ${p.half} ${p.full}`;
}

export const startPosition = () => fromFen(START_FEN);

const KNIGHT = [[-2, -1], [-2, 1], [-1, -2], [-1, 2], [1, -2], [1, 2], [2, -1], [2, 1]];
const KING = [[-1, -1], [-1, 0], [-1, 1], [0, -1], [0, 1], [1, -1], [1, 0], [1, 1]];
const DIAG = [[-1, -1], [-1, 1], [1, -1], [1, 1]];
const ORTHO = [[-1, 0], [1, 0], [0, -1], [0, 1]];

/** ¿La casilla `sq` está atacada por las piezas de color `by`? */
export function attacked(p, sq, by) {
  const r = sq >> 3, f = sq & 7, b = p.b;
  const at = (rr, ff) => (rr < 0 || rr > 7 || ff < 0 || ff > 7 ? null : b[rr * 8 + ff]);
  const pawn = by === 'w' ? 'P' : 'p', knight = by === 'w' ? 'N' : 'n', king = by === 'w' ? 'K' : 'k';
  const pr = by === 'w' ? r + 1 : r - 1; // una peón blanca ataca hacia arriba (filas menores)
  if (at(pr, f - 1) === pawn || at(pr, f + 1) === pawn) return true;
  for (const [dr, df] of KNIGHT) if (at(r + dr, f + df) === knight) return true;
  for (const [dr, df] of KING) if (at(r + dr, f + df) === king) return true;
  const bishop = by === 'w' ? 'B' : 'b', rook = by === 'w' ? 'R' : 'r', queen = by === 'w' ? 'Q' : 'q';
  for (const [dr, df] of DIAG) {
    for (let rr = r + dr, ff = f + df; rr >= 0 && rr < 8 && ff >= 0 && ff < 8; rr += dr, ff += df) {
      const c = b[rr * 8 + ff];
      if (c === '') continue;
      if (c === bishop || c === queen) return true;
      break;
    }
  }
  for (const [dr, df] of ORTHO) {
    for (let rr = r + dr, ff = f + df; rr >= 0 && rr < 8 && ff >= 0 && ff < 8; rr += dr, ff += df) {
      const c = b[rr * 8 + ff];
      if (c === '') continue;
      if (c === rook || c === queen) return true;
      break;
    }
  }
  return false;
}

export const kingSquare = (p, color) => p.b.indexOf(color === 'w' ? 'K' : 'k');
export const inCheck = (p, color) => attacked(p, kingSquare(p, color), color === 'w' ? 'b' : 'w');

/** Movimientos «pseudo-legales»: siguen las reglas de cada pieza pero pueden dejar al propio rey en jaque. */
function pseudoMoves(p) {
  const moves = [], b = p.b, me = p.turn, enemy = me === 'w' ? 'b' : 'w';
  const push = (from, to, promo = '', flag = '') => moves.push({ from, to, promo, flag });
  for (let from = 0; from < 64; from++) {
    const c = b[from];
    if (c === '' || colorOf(c) !== me) continue;
    const r = from >> 3, f = from & 7, kind = c.toLowerCase();
    if (kind === 'p') {
      const dir = me === 'w' ? -1 : 1, startRow = me === 'w' ? 6 : 1, lastRow = me === 'w' ? 0 : 7;
      const addPawn = (to, flag = '') => {
        if (to >> 3 === lastRow) for (const pr of ['q', 'r', 'b', 'n']) push(from, to, pr, flag); else push(from, to, '', flag);
      };
      const one = from + dir * 8;
      if (b[one] === '') {
        addPawn(one);
        if (r === startRow && b[one + dir * 8] === '') push(from, one + dir * 8, '', 'double');
      }
      for (const df of [-1, 1]) {
        const ff = f + df;
        if (ff < 0 || ff > 7) continue;
        const to = (r + dir) * 8 + ff;
        if (b[to] !== '' && colorOf(b[to]) === enemy) addPawn(to);
        else if (to === p.ep) push(from, to, '', 'ep');
      }
    } else if (kind === 'n' || kind === 'k') {
      for (const [dr, df] of kind === 'n' ? KNIGHT : KING) {
        const rr = r + dr, ff = f + df;
        if (rr < 0 || rr > 7 || ff < 0 || ff > 7) continue;
        const to = rr * 8 + ff;
        if (b[to] === '' || colorOf(b[to]) === enemy) push(from, to);
      }
    } else {
      const dirs = kind === 'b' ? DIAG : kind === 'r' ? ORTHO : DIAG.concat(ORTHO);
      for (const [dr, df] of dirs) {
        for (let rr = r + dr, ff = f + df; rr >= 0 && rr < 8 && ff >= 0 && ff < 8; rr += dr, ff += df) {
          const to = rr * 8 + ff;
          if (b[to] === '') { push(from, to); continue; }
          if (colorOf(b[to]) === enemy) push(from, to);
          break;
        }
      }
    }
  }
  // enroque: rey y torre sin moverse, casillas libres y el rey no sale, pasa ni llega a una casilla atacada
  const home = me === 'w' ? 60 : 4, king = me === 'w' ? 'K' : 'k', rook = me === 'w' ? 'R' : 'r';
  if (b[home] === king && !attacked(p, home, enemy)) {
    const short = me === 'w' ? 'K' : 'k', long = me === 'w' ? 'Q' : 'q';
    if (p.castling.includes(short) && b[home + 1] === '' && b[home + 2] === '' && b[home + 3] === rook
      && !attacked(p, home + 1, enemy) && !attacked(p, home + 2, enemy)) push(home, home + 2, '', 'castle');
    if (p.castling.includes(long) && b[home - 1] === '' && b[home - 2] === '' && b[home - 3] === '' && b[home - 4] === rook
      && !attacked(p, home - 1, enemy) && !attacked(p, home - 2, enemy)) push(home, home - 2, '', 'castle');
  }
  return moves;
}

/** Aplica una jugada y devuelve la posición nueva (la anterior no se modifica). */
export function applyMove(p, m) {
  const b = p.b.slice(), piece = b[m.from], me = p.turn;
  const capture = b[m.to] !== '' || m.flag === 'ep';
  b[m.to] = m.promo ? (me === 'w' ? m.promo.toUpperCase() : m.promo) : piece;
  b[m.from] = '';
  if (m.flag === 'ep') b[m.to + (me === 'w' ? 8 : -8)] = '';
  if (m.flag === 'castle') {
    if (m.to === 62) { b[61] = b[63]; b[63] = ''; } else if (m.to === 58) { b[59] = b[56]; b[56] = ''; }
    else if (m.to === 6) { b[5] = b[7]; b[7] = ''; } else if (m.to === 2) { b[3] = b[0]; b[0] = ''; }
  }
  let castling = p.castling;
  const drop = (chars) => { for (const ch of chars) castling = castling.replace(ch, ''); };
  if (piece === 'K') drop('KQ'); if (piece === 'k') drop('kq');
  for (const s of [m.from, m.to]) { if (s === 63) drop('K'); if (s === 56) drop('Q'); if (s === 7) drop('k'); if (s === 0) drop('q'); }
  return {
    b, turn: me === 'w' ? 'b' : 'w', castling,
    ep: m.flag === 'double' ? (m.from + m.to) / 2 : -1,
    half: piece.toLowerCase() === 'p' || capture ? 0 : p.half + 1,
    full: p.full + (me === 'b' ? 1 : 0),
  };
}

/** Todos los movimientos legales: los que no dejan al propio rey en jaque (si estás en jaque, solo valen los que lo resuelven). */
export function legalMoves(p) {
  return pseudoMoves(p).filter((m) => !inCheck(applyMove(p, m), p.turn));
}

export const legalMovesFrom = (p, sq) => legalMoves(p).filter((m) => m.from === sq);
export const moveString = (m) => sqName(m.from) + sqName(m.to) + m.promo;

/** Busca el movimiento legal que corresponde a un texto como «e2e4» o «e7e8q». */
export function parseMove(p, str) {
  return legalMoves(p).find((m) => moveString(m) === str) || null;
}

/** Clave de la posición para contar repeticiones (la casilla de captura al paso solo cuenta si de verdad se puede capturar). */
export function positionKey(p) {
  const epOk = p.ep >= 0 && legalMoves(p).some((m) => m.flag === 'ep');
  return `${p.b.map((c) => c || '.').join('')} ${p.turn} ${p.castling} ${epOk ? p.ep : '-'}`;
}

function insufficientMaterial(p) {
  const rest = p.b.filter((c) => c !== '' && c.toLowerCase() !== 'k');
  if (rest.length === 0) return true;
  if (rest.length === 1 && 'bn'.includes(rest[0].toLowerCase())) return true;
  // solo alfiles, todos en casillas del mismo color
  if (rest.every((c) => c.toLowerCase() === 'b')) {
    const colors = new Set();
    p.b.forEach((c, i) => { if (c !== '' && c.toLowerCase() === 'b') colors.add(((i >> 3) + (i & 7)) % 2); });
    return colors.size === 1;
  }
  return false;
}

/**
 * Estado de la partida. `keys` = claves de todas las posiciones desde el principio (la actual incluida).
 * Devuelve { over, result: 'w' | 'b' | 'd', reason, check }.
 */
export function outcome(p, keys = []) {
  const moves = legalMoves(p), check = inCheck(p, p.turn);
  if (moves.length === 0) return check ? { over: true, result: p.turn === 'w' ? 'b' : 'w', reason: 'mate', check } : { over: true, result: 'd', reason: 'stalemate', check };
  if (insufficientMaterial(p)) return { over: true, result: 'd', reason: 'insufficient', check };
  if (p.half >= 100) return { over: true, result: 'd', reason: '50moves', check };
  const key = keys.length ? keys[keys.length - 1] : positionKey(p);
  if (keys.filter((k) => k === key).length >= 3) return { over: true, result: 'd', reason: 'repetition', check };
  return { over: false, result: '', reason: '', check };
}

/** Rejuega una lista de movimientos desde el principio: devuelve la posición, las claves y el último movimiento (o null si algo no es legal). */
export function replay(moves) {
  let p = startPosition();
  const keys = [positionKey(p)], history = [];
  for (const s of moves) {
    const m = parseMove(p, s);
    if (!m) return null;
    history.push({ m, captured: m.flag === 'ep' ? (p.turn === 'w' ? 'p' : 'P') : p.b[m.to], piece: p.b[m.from] });
    p = applyMove(p, m);
    keys.push(positionKey(p));
  }
  return { p, keys, history };
}

/** Cuenta de posibles jugadas hasta cierta profundidad (sirve para comprobar que el motor es correcto). */
export function perft(p, depth) {
  if (depth === 0) return 1;
  let n = 0;
  for (const m of legalMoves(p)) n += depth === 1 ? 1 : perft(applyMove(p, m), depth - 1);
  return n;
}
