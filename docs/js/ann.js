// Anotaciones sobre las páginas: modelo, pintado y detección de toques. Las coordenadas están normalizadas respecto al ANCHO
// de la página (x e y se dividen entre el ancho), así que no dependen del zoom ni de la pantalla. Igual que en la app de Android.
//
//   Trazo   {t:'s', id, page, hl, color, width, pts:[[x,y]…]}
//   Texto   {t:'t', id, page, text, color, size, x, y}
//   Pegatina{t:'k', id, page, src, x, y, size, rot}     src: "a:<archivo>" | "e:<emoji>" | "u:<archivo propio>"
//   Post-it {t:'p', id, page, x, y, size, rot, strokes:[{color,width,hl,pts}]}
//   Tapado  {t:'c', id, page, x, y, w, h, color}

import * as store from './store.js';

export const PALETTE = ['#111111', '#FFFFFF', '#E53935', '#FB8C00', '#FDD835', '#43A047', '#1E88E5', '#8E24AA', '#EC407A'];
export const HL_PALETTE = ['#FFF59D', '#FFCC80', '#A5D6A7', '#90CAF9', '#CE93D8', '#F8BBD0'];

const LEGACY_HL = {
  '#FDD835': '#FFF59D', '#FB8C00': '#FFCC80', '#43A047': '#A5D6A7', '#1E88E5': '#90CAF9',
  '#8E24AA': '#CE93D8', '#EC407A': '#F8BBD0', '#E53935': '#FFAB91', '#111111': '#B0BEC5',
};
const hlColor = (c) => LEGACY_HL[c.toUpperCase()] || c;

const POST_FILL = '#FFCB9A';
const POST_BAND = '#FFDDBB';
const FONT = '-apple-system, "Helvetica Neue", Arial, sans-serif';
const LINE = 1.17;

// ───────── imágenes de pegatinas ─────────

const images = new Map();
let onImageReady = () => {};
export function setImageReadyHandler(fn) { onImageReady = fn; }

function stickerImage(src) {
  if (images.has(src)) return images.get(src);
  const img = new Image();
  images.set(src, null);
  const done = () => { images.set(src, img); onImageReady(); };
  img.onload = done;
  if (src.startsWith('a:')) img.src = `stickers/${src.slice(2)}.png`;
  else store.userSticker(src.slice(2)).then((u) => { if (u) img.src = u; });
  return null;
}

// ───────── pintado ─────────

export function drawAll(ctx, list, w) {
  for (const a of list) if (a.t === 'c') drawCover(ctx, a, w);
  for (const a of list) if (a.t === 's' && a.hl) drawStroke(ctx, a.pts, a.color, a.width, true, w);
  for (const a of list) if (a.t === 'k') drawSticker(ctx, a, w);
  for (const a of list) if (a.t === 'p') drawPostit(ctx, a, w);
  for (const a of list) if (a.t === 's' && !a.hl) drawStroke(ctx, a.pts, a.color, a.width, false, w);
  for (const a of list) if (a.t === 't') drawText(ctx, a, w);
}

export function drawStroke(ctx, pts, color, width, hl, w) {
  if (!pts.length) return;
  ctx.save();
  const c = hl ? hlColor(color) : color;
  // El subrayador "multiplica" con la página: el color pastel se ve sobre el papel y el texto negro sigue negro.
  if (hl) ctx.globalCompositeOperation = 'multiply';
  if (pts.length === 1) {
    ctx.fillStyle = c;
    ctx.beginPath();
    ctx.arc(pts[0][0] * w, pts[0][1] * w, Math.max(width * w / 2, 0.5), 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
    return;
  }
  ctx.strokeStyle = c;
  ctx.lineWidth = width * w;
  ctx.lineCap = hl ? 'butt' : 'round';
  ctx.lineJoin = 'round';
  ctx.beginPath();
  ctx.moveTo(pts[0][0] * w, pts[0][1] * w);
  for (let i = 1; i < pts.length - 1; i++) {
    const mx = (pts[i][0] + pts[i + 1][0]) / 2, my = (pts[i][1] + pts[i + 1][1]) / 2;
    ctx.quadraticCurveTo(pts[i][0] * w, pts[i][1] * w, mx * w, my * w);
  }
  const last = pts[pts.length - 1];
  ctx.lineTo(last[0] * w, last[1] * w);
  ctx.stroke();
  ctx.restore();
}

function drawSticker(ctx, s, w) {
  const side = s.size * w;
  ctx.save();
  ctx.translate(s.x * w, s.y * w);
  ctx.rotate(s.rot * Math.PI / 180);
  if (s.src.startsWith('e:')) {
    ctx.font = `${side * 0.78}px ${FONT}`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(s.src.slice(2), 0, side * 0.04);
  } else {
    const img = stickerImage(s.src);
    if (img) {
      const k = Math.min(side / img.width, side / img.height);
      ctx.drawImage(img, -img.width * k / 2, -img.height * k / 2, img.width * k, img.height * k);
    }
  }
  ctx.restore();
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

function drawPostit(ctx, p, w) {
  const side = p.size * w, corner = side * 0.03;
  ctx.save();
  ctx.translate(p.x * w, p.y * w);
  ctx.rotate(p.rot * Math.PI / 180);
  const x = -side / 2, y = -side / 2;
  ctx.fillStyle = 'rgba(0,0,0,0.22)';
  roundRect(ctx, x + side * 0.012, y + side * 0.022, side, side, corner);
  ctx.fill();
  ctx.fillStyle = POST_FILL;
  roundRect(ctx, x, y, side, side, corner);
  ctx.fill();
  ctx.save();
  ctx.beginPath();
  ctx.rect(x, y, side, side * 0.11);
  ctx.clip();
  ctx.fillStyle = POST_BAND;
  roundRect(ctx, x, y, side, side, corner);
  ctx.fill();
  ctx.restore();
  if (p.strokes.length) {
    ctx.save();
    ctx.beginPath();
    ctx.rect(x, y, side, side);
    ctx.clip();
    p.strokes.forEach((s) => drawStroke(ctx, s.pts, s.color, s.width, s.hl, side));
    ctx.restore();
  }
  ctx.restore();
}

function drawCover(ctx, c, w) {
  ctx.fillStyle = c.color;
  ctx.fillRect(c.x * w, c.y * w, c.w * w, c.h * w);
}

function drawText(ctx, t, w) {
  ctx.save();
  ctx.fillStyle = t.color;
  ctx.font = `${t.size * w}px ${FONT}`;
  ctx.textAlign = 'left';
  ctx.textBaseline = 'top';
  t.text.split('\n').forEach((line, i) => ctx.fillText(line, t.x * w, t.y * w + i * t.size * w * LINE));
  ctx.restore();
}

// ───────── geometría ─────────

const measureCtx = document.createElement('canvas').getContext('2d');

function textRect(t) {
  measureCtx.font = `${t.size * 1000}px ${FONT}`;
  const lines = t.text.split('\n');
  const width = Math.max(...lines.map((l) => measureCtx.measureText(l).width));
  return { x: t.x * 1000, y: t.y * 1000, w: width, h: t.size * 1000 * LINE * lines.length };
}

function toLocalAxes(s, p) {
  const r = s.rot * Math.PI / 180, dx = p[0] - s.x, dy = p[1] - s.y;
  return [dx * Math.cos(r) + dy * Math.sin(r), -dx * Math.sin(r) + dy * Math.cos(r)];
}

export const toLocal = (s, p) => { const [lx, ly] = toLocalAxes(s, p); return [lx / s.size, ly / s.size]; };

export function hitText(t, p) {
  const r = textRect(t), x = p[0] * 1000, y = p[1] * 1000;
  return x >= r.x - 12 && x <= r.x + r.w + 12 && y >= r.y - 12 && y <= r.y + r.h + 12;
}

function hitSquare(s, p, pad) {
  const [lx, ly] = toLocalAxes(s, p), h = s.size / 2 + pad;
  return Math.abs(lx) <= h && Math.abs(ly) <= h;
}
export const hitSticker = (s, p) => hitSquare(s, p, 0.01);
export const hitPostit = (s, p) => hitSquare(s, p, 0.005);
export const hitCover = (c, p) => p[0] >= c.x && p[0] <= c.x + c.w && p[1] >= c.y && p[1] <= c.y + c.h;

function distToSegment(p, a, b) {
  const dx = b[0] - a[0], dy = b[1] - a[1], len2 = dx * dx + dy * dy;
  const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2));
  return Math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dy));
}

export function distToPoints(pts, p) {
  if (pts.length === 1) return Math.hypot(p[0] - pts[0][0], p[1] - pts[0][1]);
  let best = Infinity;
  for (let i = 0; i < pts.length - 1; i++) best = Math.min(best, distToSegment(p, pts[i], pts[i + 1]));
  return best;
}

/** La anotación más alta bajo el punto `p` de esa página. `only`: 'text' | 'sticker' | 'postit' | 'cover' (null = cualquiera salvo tapados). */
export function hit(list, page, p, only = null) {
  for (let i = list.length - 1; i >= 0; i--) {
    const a = list[i];
    if (a.page !== page) continue;
    const any = only == null;
    if (a.t === 't' && (any || only === 'text') && hitText(a, p)) return a;
    if (a.t === 's' && any && distToPoints(a.pts, p) <= Math.max(a.width / 2, 0.012)) return a;
    if (a.t === 'k' && (any || only === 'sticker') && hitSticker(a, p)) return a;
    if (a.t === 'p' && (any || only === 'postit') && hitPostit(a, p)) return a;
    if (a.t === 'c' && only === 'cover' && hitCover(a, p)) return a;
  }
  return null;
}

export function moved(a, dx, dy) {
  if (a.t === 's') return { ...a, pts: a.pts.map(([x, y]) => [x + dx, y + dy]) };
  return { ...a, x: a.x + dx, y: a.y + dy };
}

/** Color del fondo alrededor del recuadro (mediana de 8 muestras justo fuera de él), para que el tapado pase desapercibido. */
export function sampleBackground(canvas, x0, y0, x1, y1) {
  const s = canvas.width, m = 5;
  const xs = [x0 * s - m, (x0 + x1) / 2 * s, x1 * s + m], ys = [y0 * s - m, (y0 + y1) / 2 * s, y1 * s + m];
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  const px = [];
  xs.forEach((x, xi) => ys.forEach((y, yi) => {
    if (xi === 1 && yi === 1) return;
    const d = ctx.getImageData(Math.max(0, Math.min(canvas.width - 1, Math.round(x))), Math.max(0, Math.min(canvas.height - 1, Math.round(y))), 1, 1).data;
    px.push([d[0], d[1], d[2]]);
  }));
  px.sort((a, b) => (0.299 * a[0] + 0.587 * a[1] + 0.114 * a[2]) - (0.299 * b[0] + 0.587 * b[1] + 0.114 * b[2]));
  const [r, g, b] = px[Math.floor(px.length / 2)];
  return '#' + [r, g, b].map((v) => v.toString(16).padStart(2, '0')).join('');
}

/** Línea recta de punto inicial a final (con ajuste a horizontal/vertical si está casi alineada). */
export function straightLine(pts) {
  if (pts.length < 2) return pts;
  const a = pts[0];
  let b = pts[pts.length - 1];
  const dx = b[0] - a[0], dy = b[1] - a[1];
  if (Math.abs(dx) < 0.004 && Math.abs(dy) < 0.004) return pts;
  const snap = 0.07;
  if (Math.abs(dy) < Math.abs(dx) * snap) b = [b[0], a[1]];
  else if (Math.abs(dx) < Math.abs(dy) * snap) b = [a[0], b[1]];
  return [a, b];
}
