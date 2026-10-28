// Convierte el resumen de Gemini (Markdown sencillo: # títulos, - listas, **negrita**) en un PDF A4.

import { toBuffer } from './pdfdoc.js';

const W = 595, H = 842, M = 48;
const BLUE = [0.29, 0.36, 0.62], INK = [0.1, 0.1, 0.1], GRAY = [0.43, 0.45, 0.5];

export async function summaryPdf({ title, originalName, text, sources = [] }) {
  const doc = await PDFLib.PDFDocument.create();
  const reg = await doc.embedFont(PDFLib.StandardFonts.Helvetica);
  const bold = await doc.embedFont(PDFLib.StandardFonts.HelveticaBold);
  const { rgb } = PDFLib;
  const color = (c) => rgb(c[0], c[1], c[2]);

  // Solo se pueden dibujar los caracteres de WinAnsi: lo demás se sustituye.
  const ok = new Map();
  const safe = (s) => [...s.replace(/→/g, '->').replace(/≥/g, '>=').replace(/≤/g, '<=').replace(/[•◦▪]/g, '•')].map((ch) => {
    if (ok.has(ch)) return ok.get(ch);
    let r = ch;
    if (ch !== '\n') { try { reg.encodeText(ch); } catch { r = '?'; } }
    ok.set(ch, r);
    return r;
  }).join('');

  let page = null, y = 0;
  const newPage = () => { page = doc.addPage([W, H]); y = H - M; };
  const ensure = (h) => { if (!page || y - h < M) newPage(); };

  /** Escribe un párrafo con ajuste de línea. `runs`: [{text, bold}]. */
  function paragraph(runs, { size = 11.5, x = M, color: col = INK, gapBefore = 5, indent = 0, bullet = null } = {}) {
    const lead = size * 1.3;
    const maxW = W - M - x;
    const words = [];
    runs.forEach((r) => safe(r.text).split(/(\s+)/).forEach((w) => { if (w) words.push({ w, font: r.bold ? bold : reg }); }));
    if (page && y < H - M) y -= gapBefore;
    ensure(lead);
    if (bullet) page.drawText(safe(bullet), { x, y: y - size, size, font: reg, color: color(col) });
    const left = x + indent;
    let cx = left, line = [];
    const flush = () => {
      if (!line.length) return;
      ensure(lead);
      let px = left;
      line.forEach((t) => { page.drawText(t.w, { x: px, y: y - size, size, font: t.font, color: color(col) }); px += t.font.widthOfTextAtSize(t.w, size); });
      y -= lead; line = []; cx = left;
    };
    for (const t of words) {
      const wd = t.font.widthOfTextAtSize(t.w, size);
      if (/^\s+$/.test(t.w)) { if (line.length) { line.push(t); cx += wd; } continue; }
      if (cx + wd > left + maxW - indent && line.length) { while (line.length && /^\s+$/.test(line[line.length - 1].w)) line.pop(); flush(); }
      line.push(t); cx += wd;
    }
    while (line.length && /^\s+$/.test(line[line.length - 1].w)) line.pop();
    flush();
  }

  const inline = (s) => s.split('**').map((t, i) => ({ text: t, bold: i % 2 === 1 })).filter((r) => r.text);

  paragraph([{ text: title, bold: true }], { size: 22, color: BLUE, gapBefore: 0 });
  paragraph([{ text: `Generado con IA el ${new Date().toLocaleDateString('es-ES')} a partir de «${originalName}»` }], { size: 10, color: GRAY, gapBefore: 4 });

  for (const raw of text.split('\n')) {
    const line = raw.replace(/\s+$/, ''), t = line.trimStart(), indent = line.length - t.length;
    if (!t) continue;
    if (t.startsWith('#')) {
      const level = t.match(/^#+/)[0].length;
      paragraph([{ text: t.replace(/^#+/, '').trim().replace(/\*\*/g, ''), bold: true }], { size: level === 1 ? 18 : level === 2 ? 15 : 13, color: BLUE, gapBefore: 14 });
    } else if (/^[-*] /.test(t)) {
      const m = 14 + Math.min(3, Math.floor(indent / 2)) * 14;
      paragraph(inline(t.slice(2)), { x: M + m - 14, indent: 14, bullet: '•', gapBefore: 3 });
    } else paragraph(inline(t));
  }

  if (sources.length) {
    paragraph([{ text: 'Fuentes consultadas', bold: true }], { size: 13, color: BLUE, gapBefore: 18 });
    for (const [name, url] of sources) paragraph([{ text: `${name}\n${url}`.replace(/\n/g, ' ') }], { size: 9, color: GRAY, indent: 14, bullet: '•', gapBefore: 4 });
  }
  if (!page) newPage();
  return toBuffer(await doc.save());
}
