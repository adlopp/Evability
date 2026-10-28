// Lectura y dibujo de PDFs con PDF.js.

pdfjsLib.GlobalWorkerOptions.workerSrc = 'vendor/pdf.worker.min.js';

/** Abre un PDF a partir de sus bytes (se copian: PDF.js se queda con el buffer que recibe). */
export async function openPdf(bytes) {
  const data = new Uint8Array(bytes.byteLength);
  data.set(new Uint8Array(bytes));
  const pdf = await pdfjsLib.getDocument({ data, isEvalSupported: false }).promise;
  return pdf;
}

/** Tamaño (en puntos) de cada página. */
export async function pageSizes(pdf) {
  const out = [];
  for (let i = 1; i <= pdf.numPages; i++) {
    const p = await pdf.getPage(i);
    const v = p.getViewport({ scale: 1 });
    out.push([v.width, v.height]);
  }
  return out;
}

/** Dibuja la página `pageNo` (1-based) en un canvas nuevo de `pxWidth` píxeles de ancho. */
export async function renderToCanvas(pdf, pageNo, pxWidth, canvas = document.createElement('canvas')) {
  const page = await pdf.getPage(pageNo);
  const base = page.getViewport({ scale: 1 });
  const viewport = page.getViewport({ scale: pxWidth / base.width });
  canvas.width = Math.round(viewport.width);
  canvas.height = Math.round(viewport.height);
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  const task = page.render({ canvasContext: ctx, viewport });
  await task.promise;
  return canvas;
}

/** Bytes de un Uint8Array como ArrayBuffer independiente. */
export const toBuffer = (u8) => u8.buffer.slice(u8.byteOffset, u8.byteOffset + u8.byteLength);
