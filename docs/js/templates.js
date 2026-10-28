// Plantillas (mensual, semanal y hoja en blanco) dibujadas como páginas A4 de 595 × 842 puntos, igual que en la app de Android.

import { toBuffer } from './pdfdoc.js';

export const W = 595;
export const H = 842;

export const TEMPLATE_KINDS = {
  MONTHLY: { title: 'Mensual', subtitle: 'Calendario del mes con objetivo, prioridades y tareas', docName: 'Plan mensual' },
  WEEKLY: { title: 'Semanal', subtitle: 'Agenda de la semana, prioridades, tareas y hábitos', docName: 'Plan semanal' },
  BLANK: { title: 'En blanco', subtitle: 'Una hoja vacía para escribir libremente', docName: 'Documento en blanco' },
};

const BLUE = '#4A5C9E';
const GRAY = '#C4C7D0';
const INK = '#1A1A1A';
const LEFT = 36, RIGHT = 560, COL2 = 310, LEFT_END = 286;

function stroke(c, color, width) { c.strokeStyle = color; c.lineWidth = width; }
function hLine(c, x1, x2, y, strong) { stroke(c, strong ? BLUE : GRAY, strong ? 1 : 0.6); c.beginPath(); c.moveTo(x1, y); c.lineTo(x2, y); c.stroke(); }
function vLine(c, x, y1, y2) { stroke(c, GRAY, 0.6); c.beginPath(); c.moveTo(x, y1); c.lineTo(x, y2); c.stroke(); }
function rect(c, x1, y1, x2, y2, color, width) { stroke(c, color, width); c.strokeRect(x1, y1, x2 - x1, y2 - y1); }

/** Texto con espaciado entre letras (el canvas de Safari no lo soporta de forma nativa). */
function text(c, s, x, y, { size = 6.4, bold = true, serif = false, spacing = 0, align = 'left' } = {}) {
  c.fillStyle = INK;
  c.textBaseline = 'alphabetic';
  c.font = `${bold ? 'bold ' : ''}${size}px ${serif ? 'Georgia, "Times New Roman", serif' : '-apple-system, "Helvetica Neue", Arial, sans-serif'}`;
  c.textAlign = 'left';
  const gap = spacing * size;
  const widths = [...s].map((ch) => c.measureText(ch).width);
  const total = widths.reduce((a, b) => a + b, 0) + gap * (widths.length - 1);
  let px = align === 'center' ? x - total / 2 : x;
  [...s].forEach((ch, i) => { c.fillText(ch, px, y); px += widths[i] + gap; });
}

const label = (c, s, x, y) => text(c, s, x, y, { size: 6.4, spacing: 0.14 });

function header(c, title) {
  text(c, title, LEFT, 49.5, { size: 21, serif: true });
  text(c, 'Month:', 420.5, 46, { size: 6, bold: false });
  hLine(c, 419, RIGHT, 53.3, true);
}

function numbered(c, x1, x2, top, rows, sepX) {
  const h = 24;
  for (let k = 0; k < rows; k++) {
    text(c, String(k + 1).padStart(2, '0'), x1 + 8, top + k * h + 16, { size: 6, spacing: 0.1 });
    hLine(c, x1, x2, top + (k + 1) * h, k === rows - 1);
  }
  vLine(c, sepX, top, top + rows * h);
}

function taskLines(c, x1, x2, top, bottom, rows, divX) {
  const h = (bottom - top) / rows;
  for (let k = 1; k <= rows; k++) hLine(c, x1, x2, top + k * h, k === rows);
  vLine(c, divX, top, bottom);
}

function monthly(c) {
  header(c, 'Monthly Planner');
  const gridTop = 86, gridBottom = 517;
  const colW = (RIGHT - LEFT) / 7, rowH = (gridBottom - gridTop) / 6;
  ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'].forEach((d, i) => text(c, d, LEFT + colW * (i + 0.5), 80, { spacing: 0.14, align: 'center' }));
  for (let i = 1; i <= 6; i++) vLine(c, LEFT + i * colW, gridTop, gridBottom);
  for (let j = 1; j <= 5; j++) hLine(c, LEFT, RIGHT, gridTop + j * rowH, false);
  rect(c, LEFT, gridTop, RIGHT, gridBottom, BLUE, 1);

  label(c, 'GOAL / INSPIRATION', LEFT, 539.5);
  hLine(c, LEFT, LEFT_END, 549.5, true);
  hLine(c, LEFT, LEFT_END, 609.5, true);
  label(c, 'TOP PRIORITIES', LEFT, 628.6);
  hLine(c, LEFT, LEFT_END, 638.5, true);
  numbered(c, LEFT, LEFT_END, 638.5, 4, 64);
  label(c, 'TASKS', COL2, 539.5);
  hLine(c, COL2, RIGHT, 549.5, true);
  taskLines(c, COL2, RIGHT, 549.5, 734.5, 8, 536);
}

function weekly(c) {
  header(c, 'Weekly Planner');
  label(c, 'SCHEDULE', LEFT, 78.4);
  const top = 89.2, bottom = 733.5, rowH = (bottom - top) / 7;
  for (let k = 1; k <= 6; k++) hLine(c, LEFT, LEFT_END, top + k * rowH, false);
  vLine(c, 74, top, bottom);
  [...'MTWTFSS'].forEach((ch, k) => {
    const y = top + k * rowH;
    hLine(c, 44, 68, y + 47.7, false);
    text(c, ch, 56, y + 64, { align: 'center' });
  });
  rect(c, LEFT, top, LEFT_END, bottom, BLUE, 1);

  label(c, 'GOAL / INSPIRATION', COL2, 78.4);
  hLine(c, COL2, RIGHT, 89.2, true);
  hLine(c, COL2, RIGHT, 122.2, true);
  label(c, 'TOP PRIORITIES', COL2, 143.8);
  hLine(c, COL2, RIGHT, 153, true);
  numbered(c, COL2, RIGHT, 153, 4, 336);
  label(c, 'OTHER TASKS', COL2, 275.2);
  hLine(c, COL2, RIGHT, 284.5, true);
  taskLines(c, COL2, RIGHT, 284.5, 498, 9, 536.7);

  label(c, 'HABITS', COL2, 521.3);
  const xs = [454.4, 471.3, 487.5, 504.2, 520.6, 537.7, 553.7];
  [...'MTWTFSS'].forEach((ch, i) => text(c, ch, xs[i], 521.3, { align: 'center' }));
  hLine(c, COL2, RIGHT, 532.8, true);
  for (let k = 0; k < 5; k++) {
    const rowTop = 532.8 + k * 23.8, yc = rowTop + 11.9;
    xs.forEach((x) => rect(c, x - 4.5, yc - 4.5, x + 4.5, yc + 4.5, GRAY, 0.6));
    hLine(c, COL2, RIGHT, rowTop + 23.8, k === 4);
  }
  hLine(c, COL2, RIGHT, 734, true);
}

/** Dibuja la plantilla en un contexto 2D cuyo sistema de coordenadas ya está en puntos (595 × 842). */
export function drawTemplate(ctx, kind) {
  if (kind === 'MONTHLY') monthly(ctx);
  else if (kind === 'WEEKLY') weekly(ctx);
}

/** Canvas con la plantilla a `pxWidth` píxeles de ancho. */
export function templateCanvas(kind, pxWidth) {
  const canvas = document.createElement('canvas');
  const s = pxWidth / W;
  canvas.width = Math.round(pxWidth);
  canvas.height = Math.round(H * s);
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.scale(s, s);
  drawTemplate(ctx, kind);
  return canvas;
}

/** PDF de una página con la plantilla (rasterizada a buena resolución) o en blanco. */
export async function templatePdfBytes(kind) {
  const doc = await PDFLib.PDFDocument.create();
  const page = doc.addPage([W, H]);
  if (kind !== 'BLANK') {
    const png = await new Promise((res) => templateCanvas(kind, W * 3).toBlob(res, 'image/png'));
    const img = await doc.embedPng(await png.arrayBuffer());
    page.drawImage(img, { x: 0, y: 0, width: W, height: H });
  }
  return toBuffer(await doc.save());
}
