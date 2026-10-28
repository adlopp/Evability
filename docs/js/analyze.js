// «Analizar con IA»: resumen o resumen con investigación de un documento. El resultado se guarda como un documento nuevo.

import { h, icon, modal, toast } from './ui.js';
import * as store from './store.js';
import { analyze, getKey, GeminiError } from './gemini.js';
import { summaryPdf } from './summarypdf.js';

export function openAnalyze({ docs, onCreated, onOpenSettings }) {
  let picked = docs.slice().sort((a, b) => b.lastOpened - a.lastOpened)[0] || null;
  const hasKey = !!getKey();

  const list = h('div', { class: 'pick-list' });
  const drawList = () => list.replaceChildren(...docs.slice().sort((a, b) => b.lastOpened - a.lastOpened).map((d) =>
    h('button', { class: 'pick-item' + (picked?.id === d.id ? ' on' : ''), onclick: () => { picked = d; drawList(); } }, d.name)));
  drawList();

  const option = (ic, title, sub, mode, outline) => h('button', { class: 'btn wide' + (outline ? ' outline' : ''), onclick: () => { m.close(); run(picked, mode); } },
    icon(ic, 22), h('span', { class: 'opt' }, h('b', {}, title), h('small', {}, sub)));

  const content = !docs.length
    ? h('p', { class: 'dim' }, 'Primero importa un PDF para poder analizarlo.')
    : h('div', { class: 'stack' },
      h('p', { class: 'dim' }, 'Documento'), list,
      hasKey
        ? [option('summary', 'Resumen', 'Lo esencial del documento, sin relleno', 'summary', false),
          option('globe', 'Resumen con investigación', 'Busca en internet si algo está desactualizado (tarda más)', 'research', true)]
        : [h('p', {}, 'Para usar esta función necesitas tu clave gratuita de Gemini.'),
          h('button', { class: 'btn', onclick: () => { m.close(); onOpenSettings(); } }, 'Cómo conseguirla (Ajustes)')]);

  const m = modal({
    title: h('span', {}, 'Analizar con IA ', h('small', { class: 'dim' }, '(en construcción)')),
    content, actions: [{ label: 'Cerrar' }],
  });

  async function run(doc, mode) {
    const ctl = new AbortController();
    const stage = h('p', { class: 'strong' }, 'Preparando…');
    const bar = h('div', { class: 'progress indeterminate' }, h('div'));
    const pct = h('span', { class: 'dim small' }, 'Esperando a Gemini…');
    const clock = h('span', { class: 'dim small' }, '0:00');
    const t0 = Date.now();
    const tick = setInterval(() => { const s = Math.floor((Date.now() - t0) / 1000); clock.textContent = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; }, 1000);
    const w = modal({
      title: mode === 'research' ? 'Investigando…' : 'Resumiendo…',
      content: h('div', { class: 'stack' },
        h('p', { class: 'dim small' }, mode === 'research' ? `«${doc.name}»: busco en internet si algo ha cambiado. Puede tardar varios minutos; no cierres la app.` : `«${doc.name}»`),
        stage, bar, h('div', { class: 'row between' }, pct, clock)),
      actions: [{ label: 'Cancelar', onClick: () => { ctl.abort(); } }],
      onClose: () => clearInterval(tick),
    });
    const progress = (label, f) => {
      stage.textContent = label;
      if (f == null) { bar.classList.add('indeterminate'); pct.textContent = 'Esperando a Gemini…'; }
      else { bar.classList.remove('indeterminate'); bar.firstChild.style.width = (f * 100) + '%'; pct.textContent = Math.round(f * 100) + '%'; }
    };
    try {
      const bytes = await store.pdfBytes(doc.id);
      const r = await analyze({ apiKey: getKey(), bytes, name: doc.name, mode, onProgress: progress, signal: ctl.signal });
      progress('Creando el documento…', 1);
      const title = (mode === 'research' ? 'Resumen_investigación_' : 'Resumen_') + doc.name;
      const pdf = await summaryPdf({ title, originalName: doc.name, text: r.text, sources: r.sources });
      const info = await store.addPdf(pdf, title);
      w.close();
      onCreated(info);
    } catch (e) {
      w.close();
      if (e.name === 'AbortError') return;
      console.error(e);
      modal({ title: 'No se pudo analizar', content: h('p', {}, e instanceof GeminiError ? e.message : 'No se pudo analizar el documento'), actions: [{ label: 'Cerrar', primary: true }] });
    }
  }
}
