// Exporta el documento a un PDF nuevo con las anotaciones integradas (las páginas quedan rasterizadas, como en la app de Android).

import { renderToCanvas } from './pdfdoc.js';
import { templateCanvas } from './templates.js';
import * as ann from './ann.js';

const MAX_PX = 2000; // cuida la memoria del iPad

export async function exportPdf({ pdf, anns, extraKinds, hidden, sizes, onProgress = () => {} }) {
  const out = await PDFLib.PDFDocument.create();
  const total = pdf.numPages + extraKinds.length;
  const pages = [...Array(total).keys()].filter((i) => !hidden.includes(i));
  let n = 0;
  for (const i of pages) {
    const [pw, ph] = sizes[i < sizes.length ? i : 0];
    const px = Math.min(MAX_PX, Math.round(pw * 3));
    let canvas;
    if (i < pdf.numPages) canvas = await renderToCanvas(pdf, i + 1, px);
    else if (extraKinds[i - pdf.numPages]) canvas = templateCanvas(extraKinds[i - pdf.numPages], px);
    else {
      canvas = document.createElement('canvas');
      canvas.width = px; canvas.height = Math.round(px * ph / pw);
      const c = canvas.getContext('2d'); c.fillStyle = '#fff'; c.fillRect(0, 0, canvas.width, canvas.height);
    }
    ann.drawAll(canvas.getContext('2d'), anns.filter((a) => a.page === i), canvas.width);
    const jpg = await new Promise((res) => canvas.toBlob(res, 'image/jpeg', 0.9));
    const img = await out.embedJpg(await jpg.arrayBuffer());
    out.addPage([pw, ph]).drawImage(img, { x: 0, y: 0, width: pw, height: ph });
    canvas.width = 0; canvas.height = 0;
    onProgress(++n, pages.length);
    await new Promise((r) => setTimeout(r, 0)); // deja respirar a la interfaz
  }
  return new Blob([await out.save()], { type: 'application/pdf' });
}
