/* Propeva Hunt: «prop hunt» en 2D visto desde arriba.
 *
 * Una habitación cuadrada con objetos (sillas, mesas, macetas, jarrones, alfombras…) que se genera sola a partir de una semilla: la misma
 * semilla da siempre la misma habitación, pero cada sala es distinta, así que no te la puedes aprender. Quien se esconde puede
 * convertirse en cualquiera de esos objetos acercándose a él y quedándose quieto; ya disfrazado se puede mover, y tocarse a sí mismo lo gira
 * (la silla mira a otro lado), que es lo que hay que ajustar para no delatarse. Quien busca ve la pantalla en negro durante 15 segundos y
 * después tiene que encontrar al escondido inspeccionando objetos: si se equivoca pierde una vida.
 *
 * Esto es un PROTOTIPO para una sola persona (contra un bot) y para ver cómo se crea la sala (modo «Ver sala»). Todavía no tiene
 * multijugador. El archivo no usa import/export a propósito: así lo carga igual la web (import dinámico) que una pantalla web de Android
 * (<script src>), y la lógica (`Propeva.core`) está separada del dibujo para poder probarla sin pantalla y, más adelante, cambiar el dibujo.
 */
(function (root) {
  'use strict';

  // ───────────────────────── lógica (sin pantalla) ─────────────────────────

  const SIZE = 12;          // la habitación mide 12 × 12 casillas
  const PREP = 15;          // segundos a ciegas del buscador
  const ROUND = 90;         // duración de la caza
  const LIVES = 3;          // intentos del buscador
  const TRANSFORM = 1.2;    // segundos quieto, cerca de un objeto, para transformarse
  const HIDER_R = 0.3;

  /** Tipos de objeto: w × h = huella sin girar, r = radio de choque, orient = giros posibles (1 = ninguno, 2 = horizontal/vertical, 4 = hacia dónde mira). */
  const TYPES = {
    chair: { n: 'Silla', w: 0.8, h: 0.8, r: 0.36, orient: 4 },
    table: { n: 'Mesa', w: 1.6, h: 1.0, r: 0.62, orient: 2 },
    pot: { n: 'Maceta', w: 0.7, h: 0.7, r: 0.32, orient: 1 },
    vase: { n: 'Jarrón', w: 0.6, h: 0.6, r: 0.28, orient: 1 },
    rug: { n: 'Alfombra', w: 2.2, h: 1.5, r: 0.5, orient: 2, flat: true },
    lamp: { n: 'Lámpara', w: 0.6, h: 0.6, r: 0.26, orient: 1 },
    shelf: { n: 'Estantería', w: 1.5, h: 0.6, r: 0.55, orient: 2 },
    box: { n: 'Caja', w: 0.8, h: 0.8, r: 0.36, orient: 1 },
    bin: { n: 'Papelera', w: 0.55, h: 0.55, r: 0.27, orient: 1 },
  };
  const ORDER = ['chair', 'table', 'pot', 'vase', 'rug', 'lamp', 'shelf', 'box', 'bin'];

  /** Generador pseudoaleatorio con semilla (mulberry32): misma semilla, mismos números. */
  function rng(seed) {
    let a = seed >>> 0;
    return () => {
      a = (a + 0x6D2B79F5) >>> 0;
      let t = a;
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  const FLOORS = [
    { n: 'Parqué', kind: 'planks', a: '#c99a62', b: '#b8864f', wall: '#6b4a2f' },
    { n: 'Madera clara', kind: 'planks', a: '#e3c79a', b: '#d3b27f', wall: '#8a6a45' },
    { n: 'Baldosa azul', kind: 'tiles', a: '#a9d3e0', b: '#8fc1d1', wall: '#3f6f80' },
    { n: 'Moqueta lila', kind: 'plain', a: '#cdb8e6', b: '#bda5da', wall: '#6a5590' },
    { n: 'Ajedrez', kind: 'checker', a: '#f1e4cf', b: '#d9c4a3', wall: '#7a5a3c' },
  ];
  const RUGS = ['#e8716d', '#6fb7a6', '#f0b35e', '#7d9be0', '#c98bd6'];

  /** Crea la habitación: objetos repartidos al azar sin pisarse, con sillas junto a las mesas y estanterías contra las paredes. */
  function makeRoom(seed) {
    const rnd = rng(seed);
    const ri = (a, b) => a + Math.floor(rnd() * (b - a + 1));
    const floor = FLOORS[ri(0, FLOORS.length - 1)];
    const objs = [];
    const spawnH = { x: SIZE / 2, y: SIZE - 1.1 }, spawnS = { x: SIZE / 2, y: 1.1 };
    const free = (x, y, r, ignoreFlat = true) => {
      if (x < r + 0.2 || y < r + 0.2 || x > SIZE - r - 0.2 || y > SIZE - r - 0.2) return false;
      if (Math.hypot(x - spawnH.x, y - spawnH.y) < 1.7 + r || Math.hypot(x - spawnS.x, y - spawnS.y) < 1.7 + r) return false;
      for (const o of objs) {
        if (ignoreFlat && (TYPES[o.type].flat)) continue;
        if (Math.hypot(x - o.x, y - o.y) < r + o.r + 0.3) return false;
      }
      return true;
    };
    const add = (type, x, y, rot) => { const t = TYPES[type]; const o = { id: objs.length, type, x, y, rot, r: t.r }; if (t.flat) o.color = RUGS[ri(0, RUGS.length - 1)]; objs.push(o); return o; };
    const place = (type, makeRot, tries = 120) => {
      const t = TYPES[type];
      for (let k = 0; k < tries; k++) {
        const rot = makeRot(), f = footprint(type, rot), hx = f.w / 2 + 0.2, hy = f.h / 2 + 0.2; // la huella entera tiene que caber dentro de la habitación
        const x = hx + rnd() * (SIZE - 2 * hx), y = hy + rnd() * (SIZE - 2 * hy);
        if (!free(x, y, t.flat ? 0.2 : t.r)) continue;
        return add(type, x, y, rot);
      }
      return null;
    };
    // 1-2 alfombras primero (van por debajo de todo)
    for (let k = ri(1, 2); k > 0; k--) place('rug', () => ri(0, 1));
    // mesas, y alrededor de cada una, 1-2 sillas mirando hacia ella
    for (let k = ri(2, 3); k > 0; k--) {
      const tb = place('table', () => ri(0, 1));
      if (!tb) continue;
      const horizontal = tb.rot === 0;
      for (let c = ri(1, 2); c > 0; c--) {
        const side = ri(0, 3); // 0 arriba, 1 derecha, 2 abajo, 3 izquierda
        const off = 0.62 + 0.45;
        const dx = side === 1 ? (horizontal ? 1.05 : 0.8) : side === 3 ? -(horizontal ? 1.05 : 0.8) : (rnd() - 0.5) * (horizontal ? 0.9 : 0.2);
        const dy = side === 0 ? -(horizontal ? 0.8 : 1.05) : side === 2 ? (horizontal ? 0.8 : 1.05) : (rnd() - 0.5) * (horizontal ? 0.2 : 0.9);
        void off;
        const x = tb.x + dx, y = tb.y + dy;
        if (free(x, y, TYPES.chair.r)) add('chair', x, y, [2, 3, 0, 1][side]); // mira hacia la mesa
      }
    }
    // el resto, varias de cada tipo para que se pueda mezclar entre iguales
    const plan = [['chair', ri(1, 2)], ['pot', ri(2, 3)], ['vase', ri(2, 3)], ['lamp', ri(2, 3)], ['box', ri(2, 3)], ['bin', ri(2, 3)]];
    for (const [type, n] of plan) for (let k = 0; k < n; k++) place(type, () => (TYPES[type].orient === 4 ? ri(0, 3) : TYPES[type].orient === 2 ? ri(0, 1) : 0));
    // estanterías contra las paredes
    for (let k = ri(2, 3); k > 0; k--) {
      for (let tries = 0; tries < 60; tries++) {
        const wall = ri(0, 3), t = TYPES.shelf, along = 1.6 + rnd() * (SIZE - 3.2), gap = t.h / 2 + 0.22;
        const x = wall === 0 ? along : wall === 1 ? SIZE - gap : wall === 2 ? along : gap;
        const y = wall === 0 ? gap : wall === 1 ? along : wall === 2 ? SIZE - gap : along;
        const horizontal = wall === 0 || wall === 2;
        if (!free(x, y, 0.32)) continue;
        add('shelf', x, y, horizontal ? 0 : 1);
        break;
      }
    }
    return { seed, size: SIZE, floor, objs, spawnH, spawnS, rugColors: RUGS };
  }

  /** Huella de un objeto ya girado (ancho y alto en casillas). */
  function footprint(type, rot) {
    const t = TYPES[type];
    return t.orient === 2 && rot % 2 === 1 ? { w: t.h, h: t.w } : { w: t.w, h: t.h };
  }

  /** Estado de una partida. `seekerIsBot` = el buscador lo lleva el ordenador; si no, el escondido. */
  function newGame(room, opts) {
    const o = opts || {};
    return {
      room, t: 0, phase: 'prep', prep: PREP, left: ROUND, over: null, msg: '', msgT: 0, fx: [], user: o.user || 'hider',
      hider: { x: room.spawnH.x, y: room.spawnH.y, r: HIDER_R, dis: null, still: 0, prog: 0, tgt: -1, moved: 0, speed: 0, ai: { mode: 'pick', t: 0 } },
      // el buscador que lleva el bot tiene más vidas: así es un rival decente
      seeker: { x: room.spawnS.x, y: room.spawnS.y, lives: o.user === 'seeker' ? LIVES : 6, max: o.user === 'seeker' ? LIVES : 6, vis: new Set(), insp: null, ai: { mode: 'wait', t: 0, tx: 0, ty: 0 }, flash: 0 },
    };
  }

  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

  /** Mueve un círculo (x, y, r) con choque contra paredes y objetos sólidos. */
  function moveCircle(g, body, dx, dy) {
    const room = g.room;
    const nx = body.x + dx, ny = body.y + dy;
    let x = clamp(nx, body.r + 0.1, room.size - body.r - 0.1), y = clamp(ny, body.r + 0.1, room.size - body.r - 0.1);
    for (let pass = 0; pass < 2; pass++) {
      for (const o of room.objs) {
        if (TYPES[o.type].flat) continue;
        if (body.self === o.id) continue;
        const d = Math.hypot(x - o.x, y - o.y), min = body.r + o.r;
        if (d < min && d > 1e-6) { x = o.x + ((x - o.x) / d) * min; y = o.y + ((y - o.y) / d) * min; }
      }
    }
    body.x = clamp(x, body.r + 0.1, room.size - body.r - 0.1); body.y = clamp(y, body.r + 0.1, room.size - body.r - 0.1);
  }

  /** Objeto más cercano al que el escondido se puede transformar (o -1). */
  function nearestProp(g) {
    const h = g.hider;
    let best = -1, bd = 1e9;
    for (const o of g.room.objs) {
      const t = TYPES[o.type];
      const d = Math.hypot(h.x - o.x, h.y - o.y);
      const lim = t.flat ? 0.9 : o.r + h.r + 0.55;
      if (d <= lim && d < bd) { bd = d; best = o.id; }
    }
    return best;
  }

  /**
   * El escondido: se mueve en la dirección (ix, iy) (de −1 a 1). Si está quieto cerca de un objeto, se va transformando; en cuanto se
   * mueve, la transformación se interrumpe (pero el disfraz que ya tenía se mantiene).
   */
  function stepHider(g, dt, ix, iy) {
    const h = g.hider;
    const len = Math.hypot(ix, iy);
    const moving = len > 0.12;
    const spd = (h.dis ? 2.3 : 3.1) * (moving ? Math.min(1, len) : 0);
    if (moving) {
      moveCircle(g, h, (ix / len) * spd * dt, (iy / len) * spd * dt);
      h.still = 0; h.prog = 0; h.tgt = -1; h.moved = 0.5; h.speed = spd;
    } else {
      h.still += dt; h.speed = 0; h.moved = Math.max(0, h.moved - dt);
      const near = nearestProp(g);
      const same = h.dis && near >= 0 && g.room.objs[near].type === h.dis.type && g.room.objs[near].rot === h.dis.rot && Math.hypot(h.x - g.room.objs[near].x, h.y - g.room.objs[near].y) < 0.9;
      if (near >= 0 && !same && h.still > 0.35) {
        if (h.tgt !== near) { h.tgt = near; h.prog = 0; }
        h.prog += dt / TRANSFORM;
        if (h.prog >= 1) {
          const o = g.room.objs[near], t = TYPES[o.type];
          h.dis = { type: o.type, rot: o.rot, r: t.r, color: o.color, src: o.id };
          h.r = t.flat ? 0.34 : t.r; h.prog = 0; h.tgt = -1;
          puff(g, h.x, h.y, '#ffffff');
        }
      } else { h.prog = 0; h.tgt = -1; }
    }
    if (h.dis) h.dis.moving = moving;
  }

  /** Tocarse a sí mismo estando disfrazado: la silla mira hacia otro lado, la mesa se pone vertical… (lo que no se puede girar, solo se sacude). */
  function rotateHider(g) {
    const h = g.hider;
    if (!h.dis) return false;
    const t = TYPES[h.dis.type];
    if (t.orient > 1) h.dis.rot = (h.dis.rot + 1) % t.orient;
    puff(g, h.x, h.y, '#ffffff', 4);
    return t.orient > 1;
  }

  function puff(g, x, y, color, n) {
    for (let k = 0; k < (n || 10); k++) { const a = Math.random() * Math.PI * 2, s = 0.6 + Math.random() * 1.4; g.fx.push({ x, y, vx: Math.cos(a) * s, vy: Math.sin(a) * s, life: 0.5 + Math.random() * 0.3, max: 0.8, color }); }
  }

  /** «Cosas» que se pueden inspeccionar: los objetos de la sala y, si está disfrazado, el escondido (que cuenta como un objeto más). */
  function things(g) {
    const list = g.room.objs.map((o) => ({ kind: 'obj', id: o.id, x: o.x, y: o.y, r: TYPES[o.type].flat ? 0.5 : o.r }));
    // el escondido cuenta como un objeto más (si está disfrazado) o se ve tal cual (si no)
    list.push({ kind: 'hider', id: -1, x: g.hider.x, y: g.hider.y, r: g.hider.dis ? (TYPES[g.hider.dis.type].flat ? 0.5 : g.hider.dis.r) : 0.3 });
    return list;
  }

  /** El buscador inspecciona lo que hay en el punto (px, py): acierta si es el escondido; si no, pierde una vida. */
  function inspectAt(g, px, py) {
    const s = g.seeker;
    if (g.over || g.phase !== 'hunt') return { ok: false, text: '' };
    let best = null, bd = 1e9;
    for (const t of things(g)) {
      const d = Math.hypot(px - t.x, py - t.y);
      if (d <= t.r + 0.35 && d < bd) { bd = d; best = t; }
    }
    if (!best) return { ok: false, text: 'Ahí no hay nada' };
    if (Math.hypot(s.x - best.x, s.y - best.y) > 1.7 + best.r) return { ok: false, text: 'Acércate más para inspeccionarlo' };
    return resolveInspect(g, best);
  }

  function resolveInspect(g, th) {
    const s = g.seeker;
    s.insp = { x: th.x, y: th.y, t: 0, hit: th.kind === 'hider' };
    if (th.kind === 'hider') { g.over = { winner: 'seeker', why: 'caught' }; puff(g, th.x, th.y, '#ffd54f', 18); return { ok: true, hit: true, text: '¡Lo has encontrado!' }; }
    s.lives--; s.flash = 0.5;
    if (s.lives <= 0) g.over = { winner: 'hider', why: 'lives' };
    return { ok: true, hit: false, text: s.lives > 0 ? 'No era él… te quedan ' + s.lives + (s.lives === 1 ? ' vida' : ' vidas') : 'Te has quedado sin vidas' };
  }

  /** El que está más cerca del buscador al inspeccionar con el botón: el objeto (o el escondido) a menos de 1,7 casillas. */
  function inspectNearest(g) {
    const s = g.seeker;
    let best = null, bd = 1e9;
    for (const t of things(g)) { const d = Math.hypot(s.x - t.x, s.y - t.y) - t.r; if (d < bd) { bd = d; best = t; } }
    if (!best || bd > 1.0) return { ok: false, text: 'No hay nada a tu alcance' };
    return resolveInspect(g, best);
  }

  /** El buscador que lleva una persona: se mueve con (ix, iy). */
  function stepSeekerHuman(g, dt, ix, iy) {
    if (g.phase !== 'hunt' || g.over) return;
    const s = g.seeker, len = Math.hypot(ix, iy);
    s.r = 0.32;
    if (len > 0.12) moveCircle(g, s, (ix / len) * 3.0 * Math.min(1, len) * dt, (iy / len) * 3.0 * Math.min(1, len) * dt);
  }

  /** Bot buscador: espera a que acabe la cuenta atrás, va inspeccionando objetos cercanos y persigue lo que se mueva a su vista. */
  function botSeekerStep(g, dt) {
    const s = g.seeker, h = g.hider, ai = s.ai;
    s.r = 0.32;
    if (g.phase !== 'hunt' || g.over) return;
    const dHider = Math.hypot(s.x - h.x, s.y - h.y);
    // si ve al escondido sin disfraz cerca, lo caza; si ve algo que se mueve (un «objeto» deslizándose) va directo a por ello
    if (!h.dis && dHider < 4.2) { ai.mode = 'chase'; ai.tx = h.x; ai.ty = h.y; }
    else if (h.dis && h.moved > 0 && dHider < 5 && ai.mode !== 'inspect') { ai.mode = 'chase'; ai.tx = h.x; ai.ty = h.y; }
    if (ai.mode === 'wait') { ai.t += dt; if (ai.t > 0.4) { ai.mode = 'pick'; ai.t = 0; } return; }
    if (ai.mode === 'pick') {
      // candidatos: los objetos que aún no ha mirado y, si hay uno «nuevo» que parece un objeto más, también el escondido disfrazado (el bot no sabe cuál es)
      const cand = g.room.objs.filter((o) => !s.vis.has(o.id) && !TYPES[o.type].flat);
      if (h.dis && !TYPES[h.dis.type].flat) cand.push({ id: -1, x: h.x, y: h.y, r: h.r });
      if (!cand.length) { s.vis.clear(); return; }
      cand.sort((a, b) => Math.hypot(s.x - a.x, s.y - a.y) - Math.hypot(s.x - b.x, s.y - b.y));
      const o = cand[Math.min(cand.length - 1, Math.floor(Math.random() * Math.min(3, cand.length)))];
      ai.tgt = o.id; ai.tx = o.x; ai.ty = o.y; ai.mode = 'go'; ai.t = 0;
    }
    if (ai.mode === 'go' || ai.mode === 'chase') {
      const dx = ai.tx - s.x, dy = ai.ty - s.y, d = Math.hypot(dx, dy);
      const stop = (ai.mode === 'chase' ? 0.5 : 0.9) + (ai.mode === 'go' && ai.tgt >= 0 ? g.room.objs[ai.tgt].r : 0.3);
      if (d > stop) {
        const px = s.x, py = s.y;
        moveCircle(g, s, (dx / d) * 2.5 * dt, (dy / d) * 2.5 * dt);
        if (Math.hypot(s.x - px, s.y - py) < 0.2 * dt) ai.t += dt; else ai.t = 0; // atascado
        if (ai.t > 1.2) { if (ai.mode === 'go') s.vis.add(ai.tgt); ai.mode = 'pick'; ai.t = 0; }
      } else { ai.mode = 'inspect'; ai.t = 0; }
      return;
    }
    if (ai.mode === 'inspect') {
      ai.t += dt;
      if (ai.t >= 0.8) {
        // inspecciona lo que tiene delante
        let best = null, bd = 1e9;
        for (const t of things(g)) { const d = Math.hypot(ai.tx - t.x, ai.ty - t.y); if (d <= t.r + 0.5 && d < bd) { bd = d; best = t; } }
        if (best) { if (best.kind === 'obj') s.vis.add(best.id); resolveInspect(g, best); } else if (ai.tgt >= 0) s.vis.add(ai.tgt);
        ai.mode = 'wait'; ai.t = 0;
      }
    }
  }

  /** Bot escondido: elige un objeto de los que más se repiten, va hasta él, se transforma y se pone igual que los demás. */
  function botHiderStep(g, dt) {
    const h = g.hider, ai = h.ai, objs = g.room.objs;
    const count = {};
    for (const o of objs) count[o.type] = (count[o.type] || 0) + 1;
    if (ai.mode === 'pick') {
      const cand = objs.filter((o) => !TYPES[o.type].flat && Math.hypot(o.x - g.room.spawnS.x, o.y - g.room.spawnS.y) > 4);
      cand.sort((a, b) => count[b.type] - count[a.type] + (Math.random() - 0.5) * 2);
      const o = cand[Math.floor(Math.random() * Math.min(4, cand.length))] || objs[0];
      ai.tgt = o.id; ai.mode = 'go'; ai.t = 0;
    }
    if (ai.mode === 'go') {
      const o = objs[ai.tgt], dx = o.x - h.x, dy = o.y - h.y, d = Math.hypot(dx, dy);
      if (d > o.r + h.r + 0.35) { stepHider(g, dt, dx / d, dy / d); ai.t += dt; if (ai.t > 10) ai.mode = 'pick'; }
      else { stepHider(g, dt, 0, 0); if (h.dis) { ai.mode = 'turn'; ai.t = 0; } }
      return;
    }
    if (ai.mode === 'turn') {
      stepHider(g, dt, 0, 0);
      ai.t += dt;
      if (ai.t > 0.5) {
        // se pone como la mayoría de los de su tipo
        const same = objs.filter((o) => o.type === h.dis.type), votes = {};
        for (const o of same) votes[o.rot] = (votes[o.rot] || 0) + 1;
        const want = Number(Object.entries(votes).sort((a, b) => b[1] - a[1])[0][0]);
        if (h.dis.rot !== want && TYPES[h.dis.type].orient > 1) { rotateHider(g); ai.t = 0; } else ai.mode = 'idle';
      }
      return;
    }
    stepHider(g, dt, 0, 0);
  }

  /** Avanza el tiempo de la partida: cuenta atrás, ronda y efectos. */
  function tick(g, dt) {
    if (g.over) { stepFx(g, dt); return; }
    g.t += dt;
    if (g.phase === 'prep') { g.prep -= dt; if (g.prep <= 0) { g.prep = 0; g.phase = 'hunt'; } }
    else { g.left -= dt; if (g.left <= 0) { g.left = 0; g.over = { winner: 'hider', why: 'time' }; } }
    // quien no está en el bando del usuario lo mueve el bot
    if (g.user === 'hider') botSeekerStep(g, dt); else botHiderStep(g, dt);
    const s = g.seeker;
    if (s.insp) { s.insp.t += dt; if (s.insp.t > 1) s.insp = null; }
    if (s.flash > 0) s.flash -= dt;
    stepFx(g, dt);
  }

  function stepFx(g, dt) {
    for (const p of g.fx) { p.x += p.vx * dt; p.y += p.vy * dt; p.vx *= 0.94; p.vy *= 0.94; p.life -= dt; }
    g.fx = g.fx.filter((p) => p.life > 0);
  }

  const core = { SIZE, PREP, ROUND, LIVES, TYPES, ORDER, rng, makeRoom, footprint, newGame, moveCircle, nearestProp, stepHider, rotateHider, things, inspectAt, inspectNearest, stepSeekerHuman, botSeekerStep, botHiderStep, tick };

  // ───────────────────────── dibujo y pantalla ─────────────────────────

  const INK = '#33261b';

  function rr(ctx, x, y, w, h, r) {
    ctx.beginPath(); ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r); ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
  }
  function fillStroke(ctx, fill) { ctx.fillStyle = fill; ctx.fill(); ctx.lineWidth = 0.06; ctx.strokeStyle = INK; ctx.lineJoin = 'round'; ctx.stroke(); }
  function disc(ctx, x, y, r, fill, stroke) { ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fillStyle = fill; ctx.fill(); if (stroke !== false) { ctx.lineWidth = 0.06; ctx.strokeStyle = INK; ctx.stroke(); } }

  /** Dibuja un objeto con el centro en (x, y), en casillas (el contexto ya está escalado). */
  function drawProp(ctx, type, x, y, rot, opt) {
    const o = opt || {}, t = TYPES[type];
    ctx.save();
    ctx.translate(x, y);
    ctx.globalAlpha = o.alpha == null ? 1 : o.alpha;
    // sombra
    if (!t.flat) { ctx.save(); ctx.translate(0.07, 0.1); ctx.fillStyle = 'rgba(0,0,0,.2)'; ctx.beginPath(); ctx.ellipse(0, 0, t.w * 0.55, t.h * 0.5, 0, 0, Math.PI * 2); ctx.fill(); ctx.restore(); }
    // giro: sillas a los cuatro lados; el resto, horizontal o vertical
    ctx.rotate(t.orient === 4 ? rot * Math.PI / 2 : t.orient === 2 ? (rot % 2) * Math.PI / 2 : 0);
    const bob = o.wobble ? Math.sin(o.wobble) * 0.02 : 0;
    ctx.translate(0, bob);
    switch (type) {
      case 'chair': {
        rr(ctx, -0.34, -0.3, 0.68, 0.64, 0.12); fillStroke(ctx, '#d79b57');                 // asiento
        rr(ctx, -0.4, 0.26, 0.8, 0.17, 0.07); fillStroke(ctx, '#9a6430');                    // respaldo (abajo cuando no está girada)
        for (const [px, py] of [[-0.28, -0.24], [0.28, -0.24]]) disc(ctx, px, py, 0.05, '#7a4e25', false);
        break;
      }
      case 'table': {
        rr(ctx, -0.8, -0.5, 1.6, 1.0, 0.16); fillStroke(ctx, '#c58a4b');
        rr(ctx, -0.68, -0.38, 1.36, 0.76, 0.1); ctx.fillStyle = '#d9a468'; ctx.fill();
        disc(ctx, 0.05, 0, 0.17, '#f4f0e6'); disc(ctx, 0.05, 0, 0.09, '#e7c56d');           // frutero
        break;
      }
      case 'pot': {
        disc(ctx, 0, 0, 0.34, '#d6764f');
        disc(ctx, 0, 0, 0.26, '#4a3326', false);
        for (let k = 0; k < 6; k++) { const a = k * Math.PI / 3; ctx.save(); ctx.rotate(a); ctx.beginPath(); ctx.ellipse(0, -0.15, 0.1, 0.2, 0, 0, Math.PI * 2); ctx.fillStyle = k % 2 ? '#5fae5a' : '#74c46c'; ctx.fill(); ctx.lineWidth = 0.04; ctx.strokeStyle = INK; ctx.stroke(); ctx.restore(); }
        disc(ctx, 0, 0, 0.07, '#f2cf4a', false);
        break;
      }
      case 'vase': {
        disc(ctx, 0, 0, 0.3, '#5b8def');
        ctx.beginPath(); ctx.arc(0, 0, 0.2, Math.PI * 1.1, Math.PI * 1.8); ctx.lineWidth = 0.06; ctx.strokeStyle = 'rgba(255,255,255,.7)'; ctx.stroke();
        disc(ctx, 0, 0, 0.13, '#2e4f9e'); disc(ctx, -0.03, -0.02, 0.05, '#f27fa6', false); disc(ctx, 0.05, 0.03, 0.05, '#ffd166', false); disc(ctx, 0.0, 0.07, 0.04, '#f27fa6', false);
        break;
      }
      case 'rug': {
        const c = o.color || '#e8716d';
        rr(ctx, -1.1, -0.75, 2.2, 1.5, 0.18); fillStroke(ctx, c);
        rr(ctx, -0.92, -0.57, 1.84, 1.14, 0.12); ctx.lineWidth = 0.05; ctx.strokeStyle = 'rgba(255,255,255,.8)'; ctx.stroke();
        for (let k = -3; k <= 3; k++) { ctx.beginPath(); ctx.moveTo(k * 0.26, -0.4); ctx.lineTo(k * 0.26, 0.4); ctx.lineWidth = 0.04; ctx.strokeStyle = 'rgba(255,255,255,.45)'; ctx.stroke(); }
        break;
      }
      case 'lamp': {
        disc(ctx, 0, 0, 0.3, '#f6dd7a');
        disc(ctx, 0, 0, 0.19, '#fff3b8', false);
        disc(ctx, 0, 0, 0.06, '#8d6b2f', false);
        break;
      }
      case 'shelf': {
        rr(ctx, -0.75, -0.3, 1.5, 0.6, 0.06); fillStroke(ctx, '#8c5a31');
        const cols = ['#e8716d', '#5b8def', '#f0b35e', '#6fb7a6', '#c98bd6', '#e8716d', '#5b8def'];
        cols.forEach((c, k) => { const w = 0.16 + (k % 3) * 0.02; rr(ctx, -0.68 + k * 0.2, -0.22, w, 0.44, 0.02); ctx.fillStyle = c; ctx.fill(); ctx.lineWidth = 0.03; ctx.strokeStyle = INK; ctx.stroke(); });
        break;
      }
      case 'box': {
        rr(ctx, -0.4, -0.4, 0.8, 0.8, 0.07); fillStroke(ctx, '#c9a06a');
        ctx.beginPath(); ctx.moveTo(-0.4, 0); ctx.lineTo(0.4, 0); ctx.moveTo(0, -0.4); ctx.lineTo(0, 0.4); ctx.lineWidth = 0.07; ctx.strokeStyle = '#e6cc9a'; ctx.stroke();
        rr(ctx, -0.4, -0.4, 0.8, 0.8, 0.07); ctx.lineWidth = 0.05; ctx.strokeStyle = INK; ctx.stroke();
        break;
      }
      case 'bin': {
        disc(ctx, 0, 0, 0.28, '#8f99a3');
        disc(ctx, 0, 0, 0.2, '#5d6670', false);
        disc(ctx, -0.06, -0.03, 0.06, '#f4f4f4', false); disc(ctx, 0.07, 0.05, 0.05, '#f4f4f4', false);
        break;
      }
      default: break;
    }
    ctx.restore();
  }

  function drawHiderBlob(ctx, x, y, t, prog) {
    ctx.save(); ctx.translate(x, y);
    const sq = 1 + Math.sin(t * 6) * 0.04;
    ctx.fillStyle = 'rgba(0,0,0,.2)'; ctx.beginPath(); ctx.ellipse(0.05, 0.1, 0.34, 0.2, 0, 0, Math.PI * 2); ctx.fill();
    ctx.scale(1 / sq, sq);
    disc(ctx, -0.18, -0.24, 0.09, '#f5a9c6'); disc(ctx, 0.18, -0.24, 0.09, '#f5a9c6');
    disc(ctx, 0, 0, 0.3, '#f5a9c6');
    disc(ctx, -0.1, -0.05, 0.07, '#ffffff'); disc(ctx, 0.1, -0.05, 0.07, '#ffffff'); disc(ctx, -0.09, -0.04, 0.03, INK, false); disc(ctx, 0.11, -0.04, 0.03, INK, false);
    ctx.beginPath(); ctx.arc(0, 0.06, 0.08, 0.15 * Math.PI, 0.85 * Math.PI); ctx.lineWidth = 0.04; ctx.strokeStyle = INK; ctx.stroke();
    ctx.restore();
    if (prog > 0) { ctx.save(); ctx.translate(x, y); ctx.beginPath(); ctx.arc(0, 0, 0.55, -Math.PI / 2, -Math.PI / 2 + prog * Math.PI * 2); ctx.lineWidth = 0.1; ctx.lineCap = 'round'; ctx.strokeStyle = '#ffffff'; ctx.stroke(); ctx.restore(); }
  }

  function drawSeeker(ctx, x, y, t) {
    ctx.save(); ctx.translate(x, y);
    ctx.fillStyle = 'rgba(0,0,0,.2)'; ctx.beginPath(); ctx.ellipse(0.05, 0.12, 0.36, 0.22, 0, 0, Math.PI * 2); ctx.fill();
    disc(ctx, 0, 0, 0.32, '#5aa0e6');
    ctx.beginPath(); ctx.arc(0, -0.04, 0.32, Math.PI, 0); ctx.closePath(); ctx.fillStyle = '#2c4b7c'; ctx.fill(); ctx.lineWidth = 0.05; ctx.strokeStyle = INK; ctx.stroke();
    disc(ctx, -0.1, 0.06, 0.06, '#ffffff'); disc(ctx, 0.1, 0.06, 0.06, '#ffffff'); disc(ctx, -0.09, 0.07, 0.025, INK, false); disc(ctx, 0.11, 0.07, 0.025, INK, false);
    // lupa
    ctx.save(); ctx.translate(0.34, 0.12); ctx.rotate(Math.sin(t * 4) * 0.08);
    ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(0.18, 0.2); ctx.lineWidth = 0.07; ctx.strokeStyle = INK; ctx.stroke();
    disc(ctx, 0, 0, 0.12, 'rgba(255,255,255,.55)');
    ctx.restore();
    ctx.restore();
  }

  function drawFloor(ctx, room) {
    const f = room.floor, S = room.size;
    ctx.fillStyle = f.a; ctx.fillRect(0, 0, S, S);
    if (f.kind === 'planks') { for (let y = 0; y < S; y += 1) { ctx.fillStyle = y % 2 ? f.b : f.a; ctx.fillRect(0, y, S, 1); } ctx.strokeStyle = 'rgba(0,0,0,.12)'; ctx.lineWidth = 0.03; for (let y = 0; y <= S; y++) { ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(S, y); ctx.stroke(); } for (let y = 0; y < S; y++) for (let x = (y % 3); x < S; x += 3) { ctx.beginPath(); ctx.moveTo(x + (y % 2) * 0.5, y); ctx.lineTo(x + (y % 2) * 0.5, y + 1); ctx.stroke(); } }
    else if (f.kind === 'tiles') { ctx.strokeStyle = 'rgba(255,255,255,.55)'; ctx.lineWidth = 0.05; for (let i = 0; i <= S; i += 2) { ctx.beginPath(); ctx.moveTo(i, 0); ctx.lineTo(i, S); ctx.moveTo(0, i); ctx.lineTo(S, i); ctx.stroke(); } }
    else if (f.kind === 'checker') { for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) if ((x + y) % 2) { ctx.fillStyle = f.b; ctx.fillRect(x, y, 1, 1); } }
    else { ctx.fillStyle = f.b; for (let k = 0; k < 40; k++) { const r = rng(room.seed + k); ctx.globalAlpha = 0.25; ctx.fillRect(r() * S, r() * S, 0.12, 0.12); } ctx.globalAlpha = 1; }
  }

  function render(ctx, g, cam, opt) {
    const o = opt || {}, room = g.room, W = ctx.canvas.width, H = ctx.canvas.height;
    ctx.save();
    ctx.clearRect(0, 0, W, H);
    ctx.fillStyle = room.floor.wall; ctx.fillRect(0, 0, W, H);
    ctx.translate(cam.ox, cam.oy); ctx.scale(cam.s, cam.s);
    drawFloor(ctx, room);
    // puerta (abajo)
    ctx.fillStyle = room.floor.wall; ctx.fillRect(SIZE / 2 - 0.6, SIZE - 0.05, 1.2, 0.5); ctx.fillStyle = '#e8d9b8'; ctx.fillRect(SIZE / 2 - 0.5, SIZE - 0.05, 1.0, 0.1);
    // objetos: primero lo plano (alfombras), luego el resto por altura
    const list = room.objs.slice().sort((a, b) => (TYPES[b.type].flat ? 1 : 0) - (TYPES[a.type].flat ? 1 : 0) || a.y - b.y);
    const hd = g.hider.dis;
    const drawHider = () => {
      if (hd) drawProp(ctx, hd.type, g.hider.x, g.hider.y, hd.rot, { color: hd.color });
      else if (!o.hideBlob) drawHiderBlob(ctx, g.hider.x, g.hider.y, g.t, g.hider.prog);
      if (hd && g.hider.prog > 0) { ctx.save(); ctx.translate(g.hider.x, g.hider.y); ctx.beginPath(); ctx.arc(0, 0, 0.6, -Math.PI / 2, -Math.PI / 2 + g.hider.prog * Math.PI * 2); ctx.lineWidth = 0.1; ctx.lineCap = 'round'; ctx.strokeStyle = '#ffffff'; ctx.stroke(); ctx.restore(); }
      if (!hd && g.hider.prog > 0) { /* el anillo ya lo dibuja el blob */ }
    };
    let hiderDrawn = false;
    for (const ob of list) {
      if (!hiderDrawn && !TYPES[ob.type].flat && g.hider.y < ob.y) { drawHider(); hiderDrawn = true; }
      drawProp(ctx, ob.type, ob.x, ob.y, ob.rot, { color: ob.color });
      if (o.labels) { ctx.save(); ctx.font = '0.32px sans-serif'; ctx.textAlign = 'center'; ctx.fillStyle = '#ffffff'; ctx.strokeStyle = INK; ctx.lineWidth = 0.08; const txt = String(ob.id + 1); ctx.strokeText(txt, ob.x, ob.y + 0.11); ctx.fillText(txt, ob.x, ob.y + 0.11); ctx.restore(); }
      if (o.selected === ob.id) { ctx.beginPath(); ctx.arc(ob.x, ob.y, ob.r + 0.25, 0, Math.PI * 2); ctx.lineWidth = 0.08; ctx.strokeStyle = '#ffffff'; ctx.setLineDash([0.15, 0.12]); ctx.stroke(); ctx.setLineDash([]); }
    }
    if (!hiderDrawn) drawHider();
    if (o.revealHider) { ctx.beginPath(); ctx.arc(g.hider.x, g.hider.y, 0.7, 0, Math.PI * 2); ctx.lineWidth = 0.08; ctx.strokeStyle = '#ff5c8a'; ctx.setLineDash([0.18, 0.12]); ctx.stroke(); ctx.setLineDash([]); }
    if (!o.noSeeker) drawSeeker(ctx, g.seeker.x, g.seeker.y, g.t);
    // inspección
    const ins = g.seeker.insp;
    if (ins) { const p = ins.t; ctx.beginPath(); ctx.arc(ins.x, ins.y, 0.4 + p * 0.5, 0, Math.PI * 2); ctx.lineWidth = 0.1; ctx.strokeStyle = ins.hit ? 'rgba(255,213,79,' + (1 - p) + ')' : 'rgba(255,92,92,' + (1 - p) + ')'; ctx.stroke(); }
    for (const p of g.fx) { ctx.globalAlpha = Math.max(0, p.life / p.max); disc(ctx, p.x, p.y, 0.09, p.color, false); }
    ctx.globalAlpha = 1;
    ctx.restore();
  }

  // ───────────────────────── interfaz ─────────────────────────

  const CSS = `
.pv-root { position: fixed; inset: 0; z-index: 60; background: #14161c; color: #f2f2f5; font-family: system-ui, -apple-system, 'Segoe UI', sans-serif; display: flex; flex-direction: column; user-select: none; -webkit-user-select: none; touch-action: none; }
.pv-top { display: flex; align-items: center; gap: 10px; padding: calc(env(safe-area-inset-top, 0px) + 8px) 10px 6px; }
.pv-top h1 { font-size: 19px; margin: 0; flex: 1; font-weight: 800; }
.pv-btn { border: 0; border-radius: 14px; padding: 10px 14px; font-size: 15px; font-weight: 800; background: #f5a9c6; color: #231a1f; cursor: pointer; }
.pv-btn.ghost { background: rgba(255,255,255,.1); color: #f2f2f5; }
.pv-btn.round { width: 40px; height: 40px; padding: 0; border-radius: 50%; background: rgba(255,255,255,.1); color: #f2f2f5; font-size: 20px; }
.pv-btn:disabled { opacity: .4; }
.pv-stage { position: relative; flex: 1; min-height: 0; }
.pv-stage canvas { position: absolute; inset: 0; width: 100%; height: 100%; display: block; }
.pv-hud { position: absolute; left: 0; right: 0; top: 6px; display: flex; justify-content: center; gap: 8px; pointer-events: none; flex-wrap: wrap; }
.pv-chip { padding: 5px 12px; border-radius: 999px; background: rgba(20,22,28,.78); font-weight: 800; font-size: 14px; backdrop-filter: blur(6px); }
.pv-msg { position: absolute; left: 50%; bottom: 150px; transform: translateX(-50%); padding: 8px 16px; border-radius: 14px; background: rgba(20,22,28,.85); font-weight: 700; pointer-events: none; opacity: 0; transition: opacity .2s; max-width: 90%; text-align: center; }
.pv-msg.on { opacity: 1; }
.pv-dark { position: absolute; inset: 0; background: #000; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; z-index: 3; }
.pv-dark b { font-size: min(34vw, 220px); line-height: 1; font-variant-numeric: tabular-nums; }
.pv-dark span { opacity: .75; font-weight: 700; }
.pv-ctl { position: absolute; left: 0; right: 0; bottom: 0; height: 150px; display: flex; justify-content: space-between; align-items: flex-end; padding: 0 18px calc(env(safe-area-inset-bottom, 0px) + 16px); pointer-events: none; z-index: 2; }
.pv-joy { position: relative; width: 120px; height: 120px; border-radius: 50%; background: rgba(255,255,255,.09); border: 2px solid rgba(255,255,255,.18); pointer-events: auto; touch-action: none; }
.pv-knob { position: absolute; left: 50%; top: 50%; width: 52px; height: 52px; margin: -26px 0 0 -26px; border-radius: 50%; background: rgba(245,169,198,.85); }
.pv-act { pointer-events: auto; min-width: 110px; padding: 18px 20px; font-size: 17px; border-radius: 22px; box-shadow: 0 8px 20px rgba(0,0,0,.4); }
.pv-panel { position: absolute; inset: 0; z-index: 5; background: rgba(10,11,15,.9); display: flex; align-items: center; justify-content: center; padding: 18px; overflow-y: auto; }
.pv-card { width: min(440px, 100%); background: #1f222b; border-radius: 22px; padding: 20px; display: flex; flex-direction: column; gap: 10px; box-shadow: 0 20px 50px rgba(0,0,0,.5); }
.pv-card h2 { margin: 0 0 2px; font-size: 22px; } .pv-card p { margin: 0; opacity: .8; line-height: 1.35; font-size: 14px; }
.pv-card .pv-btn { width: 100%; padding: 14px; font-size: 16px; }
.pv-side { position: absolute; right: 8px; top: 40px; bottom: 8px; width: 168px; z-index: 4; background: rgba(20,22,28,.88); border-radius: 16px; padding: 10px; display: flex; flex-direction: column; gap: 6px; overflow-y: auto; font-size: 13px; backdrop-filter: blur(6px); }
.pv-side h3 { margin: 0; font-size: 14px; } .pv-side .row { display: flex; justify-content: space-between; opacity: .9; } .pv-side .pv-btn { padding: 8px; font-size: 13px; width: 100%; }
.pv-side input { width: 100%; box-sizing: border-box; padding: 7px; border-radius: 10px; border: 0; background: rgba(255,255,255,.1); color: inherit; font-size: 14px; }
@media (max-width: 560px) { .pv-side { left: 8px; right: 8px; top: auto; bottom: 8px; width: auto; height: 214px; display: grid; grid-template-columns: 1fr 1fr; grid-auto-rows: min-content; align-content: start; gap: 6px 10px; } .pv-side h3, .pv-side > p { grid-column: 1 / -1; } }
.pv-tag { display: inline-block; padding: 2px 8px; border-radius: 999px; background: #f5a9c6; color: #231a1f; font-size: 11px; font-weight: 900; letter-spacing: .05em; }
`;

  function ensureStyle(doc) {
    if (doc.getElementById('pv-style')) return;
    const st = doc.createElement('style'); st.id = 'pv-style'; st.textContent = CSS; doc.head.appendChild(st);
  }

  function el(doc, tag, props, ...kids) {
    const e = doc.createElement(tag);
    for (const [k, v] of Object.entries(props || {})) { if (k === 'class') e.className = v; else if (k.startsWith('on')) e.addEventListener(k.slice(2), v); else if (v != null && v !== false) e.setAttribute(k, v === true ? '' : v); }
    for (const c of kids.flat()) if (c != null && c !== false) e.append(c.nodeType ? c : doc.createTextNode(String(c)));
    return e;
  }

  /** Abre Propeva Hunt. `opts`: { onClose, standalone }. Devuelve { close }. */
  function open(opts) {
    const o = opts || {}, doc = root.document;
    ensureStyle(doc);
    const rootEl = el(doc, 'div', { class: 'pv-root propeva' });
    const canvas = el(doc, 'canvas', {});
    const stage = el(doc, 'div', { class: 'pv-stage' }, canvas);
    const title = el(doc, 'h1', {}, 'Propeva Hunt');
    const closeBtn = el(doc, 'button', { class: 'pv-btn round', 'aria-label': 'Volver', onclick: () => close() }, '←');
    rootEl.append(el(doc, 'div', { class: 'pv-top' }, closeBtn, title, el(doc, 'span', { class: 'pv-tag' }, 'PROTOTIPO')), stage);
    doc.body.appendChild(rootEl);
    const ctx = canvas.getContext('2d');

    let G = null, mode = 'menu', raf = 0, last = 0, closed = false, selected = -1, labels = true;
    let cam = { s: 40, ox: 0, oy: 0 };
    const input = { x: 0, y: 0, keys: new Set(), joy: null };
    let hud = null, msgEl = null, darkEl = null, ctlEl = null, sideEl = null, panel = null, msgTimer = 0;

    function fit() {
      const w = stage.clientWidth, h = stage.clientHeight, dpr = Math.min(2, root.devicePixelRatio || 1);
      canvas.width = Math.max(1, Math.round(w * dpr)); canvas.height = Math.max(1, Math.round(h * dpr));
      const wide = mode === 'view' && w > 560;                                  // en pantallas anchas el panel va a la derecha
      const reserve = mode === 'play' ? 150 : mode === 'view' && !wide ? 226 : 0; // sitio para los controles o el panel de abajo
      const side = wide ? 180 : 0;
      const avail = Math.min(w - side, h - reserve - 8);
      const s = Math.max(10, avail / (SIZE + 0.4));
      cam = { s: s * dpr, ox: ((w - side) * dpr - SIZE * s * dpr) / 2, oy: Math.max(0, ((h - reserve) * dpr - SIZE * s * dpr) / 2) };
    }
    const onResize = () => { fit(); };
    root.addEventListener('resize', onResize);

    const say = (t) => { if (!msgEl) return; msgEl.textContent = t; msgEl.classList.add('on'); clearTimeout(msgTimer); msgTimer = setTimeout(() => msgEl && msgEl.classList.remove('on'), 1800); };

    function clearUi() {
      [hud, msgEl, darkEl, ctlEl, sideEl, panel].forEach((e) => e && e.remove());
      hud = msgEl = darkEl = ctlEl = sideEl = panel = null;
    }

    // ── menú ──
    function showMenu() {
      mode = 'menu'; clearUi(); G = null; fit();
      const seed = Math.floor(Math.random() * 100000);
      G = newGame(makeRoom(seed), { user: 'hider' });
      panel = el(doc, 'div', { class: 'pv-panel' }, el(doc, 'div', { class: 'pv-card' },
        el(doc, 'h2', {}, 'Propeva Hunt'),
        el(doc, 'p', {}, 'Un escondite en 2D: quien se esconde se convierte en un objeto de la habitación; quien busca tiene que adivinar cuál es. Cada sala se genera sola, así que no te la puedes aprender.'),
        el(doc, 'button', { class: 'pv-btn', onclick: () => start('hider') }, 'Esconderme (contra el bot)'),
        el(doc, 'button', { class: 'pv-btn', onclick: () => start('seeker') }, 'Buscar (contra el bot)'),
        el(doc, 'button', { class: 'pv-btn ghost', onclick: () => showView(seed) }, 'Ver cómo se crea la sala'),
        el(doc, 'p', {}, 'Prototipo de una sola persona: todavía no se puede jugar con otra.')));
      stage.append(panel);
    }

    // ── ver sala (modo creador) ──
    function showView(seed) {
      mode = 'view'; clearUi(); selected = -1; fit();
      G = newGame(makeRoom(seed), { user: 'hider' });
      G.phase = 'hunt';
      const info = el(doc, 'div', { class: 'row' }), counts = el(doc, 'div', {}), sel = el(doc, 'div', { class: 'row' });
      const seedIn = el(doc, 'input', { type: 'number', value: String(seed), 'aria-label': 'Semilla' });
      const refresh = () => {
        const c = {}; for (const ob of G.room.objs) c[ob.type] = (c[ob.type] || 0) + 1;
        info.textContent = ''; info.append(el(doc, 'span', {}, 'Sala #' + G.room.seed), el(doc, 'span', {}, G.room.objs.length + ' objetos'));
        counts.textContent = ''; for (const t of ORDER) if (c[t]) counts.append(el(doc, 'div', { class: 'row' }, el(doc, 'span', {}, TYPES[t].n), el(doc, 'b', {}, '×' + c[t])));
        sel.textContent = '';
        if (selected >= 0) { const ob = G.room.objs[selected]; sel.append(el(doc, 'span', {}, '#' + (ob.id + 1) + ' ' + TYPES[ob.type].n), el(doc, 'span', {}, 'giro ' + ob.rot + ' · (' + ob.x.toFixed(1) + ', ' + ob.y.toFixed(1) + ')')); }
        seedIn.value = String(G.room.seed);
      };
      const regen = (s) => { G = newGame(makeRoom(s), { user: 'hider' }); G.phase = 'hunt'; selected = -1; refresh(); };
      sideEl = el(doc, 'div', { class: 'pv-side' },
        el(doc, 'h3', {}, 'Vista del creador'),
        info, el(doc, 'p', { style: 'margin:0;font-size:12px;opacity:.75' }, 'Suelo: ' + G.room.floor.n + '. Toca un objeto para ver sus datos.'), counts, sel,
        seedIn,
        el(doc, 'button', { class: 'pv-btn', onclick: () => regen(Math.floor(Math.random() * 100000)) }, 'Otra sala'),
        el(doc, 'button', { class: 'pv-btn ghost', onclick: () => regen(Number(seedIn.value) || 0) }, 'Cargar semilla'),
        el(doc, 'button', { class: 'pv-btn ghost', onclick: () => { labels = !labels; } }, 'Números / sin números'),
        el(doc, 'button', { class: 'pv-btn ghost', onclick: () => showMenu() }, 'Menú'));
      stage.append(sideEl);
      refresh();
      canvas.onpointerdown = (e) => {
        const r = canvas.getBoundingClientRect(), dpr = canvas.width / r.width;
        const x = ((e.clientX - r.left) * dpr - cam.ox) / cam.s, y = ((e.clientY - r.top) * dpr - cam.oy) / cam.s;
        let best = -1, bd = 1e9;
        for (const ob of G.room.objs) { const d = Math.hypot(ob.x - x, ob.y - y); const lim = TYPES[ob.type].flat ? 0.9 : ob.r + 0.25; if (d < lim && d < bd) { bd = d; best = ob.id; } }
        selected = best; refresh();
      };
    }

    // ── partida ──
    function start(user) {
      mode = 'play'; clearUi(); fit();
      G = newGame(makeRoom(Math.floor(Math.random() * 100000)), { user });
      hud = el(doc, 'div', { class: 'pv-hud' });
      msgEl = el(doc, 'div', { class: 'pv-msg' });
      const act = el(doc, 'button', { class: 'pv-btn pv-act', onpointerdown: (e) => { e.preventDefault(); doAction(); } }, user === 'hider' ? 'Girar' : 'Inspeccionar');
      const joy = el(doc, 'div', { class: 'pv-joy' }, el(doc, 'div', { class: 'pv-knob' }));
      bindJoy(joy);
      ctlEl = el(doc, 'div', { class: 'pv-ctl' }, joy, act);
      stage.append(hud, msgEl, ctlEl);
      if (user === 'seeker') { darkEl = el(doc, 'div', { class: 'pv-dark' }, el(doc, 'span', {}, 'El escondido se está escondiendo…'), el(doc, 'b', {}, '15')); stage.append(darkEl); }
      canvas.onpointerdown = (e) => {
        if (user !== 'seeker' || G.over) return;
        const r = canvas.getBoundingClientRect(), dpr = canvas.width / r.width;
        const x = ((e.clientX - r.left) * dpr - cam.ox) / cam.s, y = ((e.clientY - r.top) * dpr - cam.oy) / cam.s;
        const res = inspectAt(G, x, y); if (res.text) say(res.text);
      };
    }

    function doAction() {
      if (!G || G.over || mode !== 'play') return;
      if (G.user === 'hider') {
        if (!G.hider.dis) { say('Acércate a un objeto y quédate quieto para transformarte'); return; }
        if (!rotateHider(G)) say('Este objeto no se puede girar');
      } else {
        if (G.phase !== 'hunt') return;
        const res = inspectNearest(G); if (res.text) say(res.text);
      }
    }

    function bindJoy(joy) {
      const knob = joy.firstChild;
      const upd = (e) => {
        const r = joy.getBoundingClientRect(), cx = r.left + r.width / 2, cy = r.top + r.height / 2;
        let dx = (e.clientX - cx) / (r.width / 2), dy = (e.clientY - cy) / (r.height / 2);
        const l = Math.hypot(dx, dy); if (l > 1) { dx /= l; dy /= l; }
        input.joy = { x: dx, y: dy }; knob.style.transform = 'translate(' + dx * 34 + 'px,' + dy * 34 + 'px)';
      };
      joy.addEventListener('pointerdown', (e) => { joy.setPointerCapture(e.pointerId); upd(e); });
      joy.addEventListener('pointermove', (e) => { if (input.joy) upd(e); });
      const end = () => { input.joy = null; knob.style.transform = ''; };
      joy.addEventListener('pointerup', end); joy.addEventListener('pointercancel', end);
    }

    const keyMap = { ArrowUp: 'u', w: 'u', W: 'u', ArrowDown: 'd', s: 'd', S: 'd', ArrowLeft: 'l', a: 'l', A: 'l', ArrowRight: 'r', d: 'r', D: 'r' };
    const onKeyDown = (e) => {
      const k = keyMap[e.key];
      if (k) { input.keys.add(k); e.preventDefault(); }
      else if ((e.key === ' ' || e.key === 'Enter' || e.key === 'e' || e.key === 'E') && mode === 'play') { doAction(); e.preventDefault(); }
      else if (e.key === 'Escape') close();
    };
    const onKeyUp = (e) => { const k = keyMap[e.key]; if (k) input.keys.delete(k); };
    doc.addEventListener('keydown', onKeyDown); doc.addEventListener('keyup', onKeyUp);

    function readInput() {
      let x = 0, y = 0;
      if (input.keys.has('l')) x -= 1; if (input.keys.has('r')) x += 1; if (input.keys.has('u')) y -= 1; if (input.keys.has('d')) y += 1;
      if (input.joy) { x = input.joy.x; y = input.joy.y; }
      return { x, y };
    }

    function endPanel() {
      if (panel || !G.over) return;
      const mine = G.over.winner === G.user;
      const why = G.over.why === 'caught' ? (mine ? 'Lo has encontrado.' : 'Te han encontrado.') : G.over.why === 'time' ? (mine ? 'Has aguantado hasta el final.' : 'Se ha acabado el tiempo.') : (mine ? 'Al buscador se le han acabado las vidas.' : 'Te has quedado sin vidas.');
      panel = el(doc, 'div', { class: 'pv-panel' }, el(doc, 'div', { class: 'pv-card' },
        el(doc, 'h2', {}, mine ? '¡Has ganado!' : 'Has perdido'), el(doc, 'p', {}, why),
        el(doc, 'p', {}, 'Sala #' + G.room.seed + ' · el escondido era ' + (G.hider.dis ? 'un objeto: ' + TYPES[G.hider.dis.type].n.toLowerCase() : 'visible')),
        el(doc, 'button', { class: 'pv-btn', onclick: () => start(G.user) }, 'Otra sala'),
        el(doc, 'button', { class: 'pv-btn ghost', onclick: () => start(G.user === 'hider' ? 'seeker' : 'hider') }, 'Cambiar de papel'),
        el(doc, 'button', { class: 'pv-btn ghost', onclick: () => showMenu() }, 'Menú')));
      stage.append(panel);
      if (darkEl) { darkEl.remove(); darkEl = null; }
    }

    function frame(ts) {
      if (closed) return;
      const dt = Math.min(0.05, last ? (ts - last) / 1000 : 0.016); last = ts;
      if (G) {
        if (mode === 'play') {
          const inp = readInput();
          if (G.over) { /* partida terminada: nadie se mueve */ } else if (G.user === 'hider') stepHider(G, dt, inp.x, inp.y); else stepSeekerHuman(G, dt, inp.x, inp.y);
          tick(G, dt);
          if (hud) {
            const lives = G.seeker.lives, hearts = '♥'.repeat(Math.max(0, lives)) + '♡'.repeat(Math.max(0, G.seeker.max - lives));
            hud.textContent = '';
            hud.append(el(doc, 'span', { class: 'pv-chip' }, G.user === 'hider' ? 'Eres el escondido' : 'Eres el buscador'));
            if (G.phase === 'prep') hud.append(el(doc, 'span', { class: 'pv-chip' }, (G.user === 'hider' ? 'El buscador llega en ' : 'Empiezas en ') + Math.ceil(G.prep) + ' s'));
            else hud.append(el(doc, 'span', { class: 'pv-chip' }, Math.ceil(G.left) + ' s'));
            hud.append(el(doc, 'span', { class: 'pv-chip' }, hearts));
          }
          if (darkEl) { darkEl.lastChild.textContent = String(Math.ceil(G.prep)); if (G.phase === 'hunt') { darkEl.remove(); darkEl = null; } }
          if (G.over) endPanel();
        }
        render(ctx, G, cam, { labels: mode === 'view' && labels, selected: mode === 'view' ? selected : -1, noSeeker: mode === 'view' || mode === 'menu', hideBlob: mode === 'view' || mode === 'menu', revealHider: false });
      }
      raf = root.requestAnimationFrame(frame);
    }

    function close() {
      if (closed) return; closed = true;
      root.cancelAnimationFrame(raf); root.removeEventListener('resize', onResize); doc.removeEventListener('keydown', onKeyDown); doc.removeEventListener('keyup', onKeyUp);
      rootEl.remove(); if (o.onClose) o.onClose();
    }

    showMenu();
    root.requestAnimationFrame(() => { fit(); });
    raf = root.requestAnimationFrame(frame);
    return { close, debug: () => ({ G, cam, mode }) };
  }

  root.Propeva = { open, core, render, drawProp };
})(typeof window !== 'undefined' ? window : globalThis);
