// Ajustes: colores, clave de Gemini, copia de seguridad y almacenamiento.

import { h, modal, toast, confirmDialog } from './ui.js';
import { ACCENTS, BACKGROUNDS, LIGHT_BACKGROUNDS, theme, setAccent, setBackground } from './theme.js';
import * as store from './store.js';
import { getKey, setKey, KEY_URL } from './gemini.js';
import { shareOrDownload } from './editor.js';

export const VERSION = '1.0 web';

const standalone = () => window.navigator.standalone === true || matchMedia('(display-mode: standalone)').matches;

// ───────── copia de seguridad ─────────

export async function exportBackup(onProgress) {
  const zip = new JSZip();
  const docs = await store.list();
  const manifest = { app: 'evability', version: 1, created: Date.now(), folders: await store.folders(), docs: [], stickers: {} };
  let n = 0;
  for (const d of docs) {
    zip.file(`pdf/${d.id}.pdf`, await store.pdfBytes(d.id));
    zip.file(`anns/${d.id}.json`, JSON.stringify(await store.anns(d.id)));
    manifest.docs.push({ ...d, thumb: await store.thumb(d.id) });
    onProgress(++n, docs.length);
  }
  for (const name of await store.userStickers()) manifest.stickers[name] = await store.userSticker(name);
  zip.file('manifest.json', JSON.stringify(manifest));
  return zip.generateAsync({ type: 'blob', compression: 'STORE' });
}

export async function importBackup(file) {
  const zip = await JSZip.loadAsync(file);
  const mf = zip.file('manifest.json');
  if (!mf) throw new Error('No es una copia de Evability');
  const manifest = JSON.parse(await mf.async('string'));
  let added = 0;
  for (const { thumb, ...info } of manifest.docs) {
    const pdf = await zip.file(`pdf/${info.id}.pdf`)?.async('arraybuffer');
    if (!pdf) continue;
    const anns = JSON.parse((await zip.file(`anns/${info.id}.json`)?.async('string')) || '[]');
    if (await store.restoreDoc(info, pdf, anns, thumb)) added++;
  }
  for (const f of manifest.folders || []) await store.addFolder(f);
  for (const [name, url] of Object.entries(manifest.stickers || {})) await store.putUserSticker(name, url);
  return added;
}

// ───────── pantalla de ajustes ─────────

export function openSettings(onChanged) {
  const swatch = (list, current, onPick) => h('div', { class: 'colors wrap' }, list.map(([name, c]) => h('button', {
    class: 'swatch' + (c.toLowerCase() === current().toLowerCase() ? ' on' : ''), style: { background: c }, title: name, 'aria-label': name,
    onclick: (e) => { onPick(c); e.currentTarget.parentElement.parentElement.querySelectorAll('.swatch.on').forEach((s) => s.classList.remove('on')); e.currentTarget.classList.add('on'); },
  })));

  const keyInput = h('input', { class: 'field', type: 'password', placeholder: 'Pega aquí tu clave (AIza…)', value: getKey(), autocomplete: 'off' });
  keyInput.addEventListener('input', () => setKey(keyInput.value));
  const show = h('button', { class: 'text-btn', onclick: () => { keyInput.type = keyInput.type === 'password' ? 'text' : 'password'; show.textContent = keyInput.type === 'password' ? 'Ver' : 'Ocultar'; } }, 'Ver');

  const storageInfo = h('p', { class: 'dim small' }, 'Calculando…');
  (async () => {
    try {
      const est = await navigator.storage.estimate();
      const persisted = navigator.storage.persisted ? await navigator.storage.persisted() : false;
      storageInfo.textContent = `Usado: ${(est.usage / 1048576).toFixed(1)} MB. ` + (persisted ? 'Tus datos están protegidos contra borrado automático.' : 'El iPad podría borrar los datos de la web si lo necesita: haz copias de seguridad.');
    } catch { storageInfo.textContent = 'No se puede calcular el espacio usado.'; }
  })();

  const section = (title, ...kids) => h('section', { class: 'set-sec' }, h('h3', {}, title), ...kids);

  const content = h('div', { class: 'settings' },
    section('Color de los botones', swatch(ACCENTS, () => theme.accentBase, (c) => { setAccent(c); onChanged?.(); })),
    section('Color de fondo', h('p', { class: 'dim small' }, 'Oscuros'), swatch(BACKGROUNDS, () => theme.bg, (c) => { setBackground(c); onChanged?.(); }),
      h('p', { class: 'dim small' }, 'Claros'), swatch(LIGHT_BACKGROUNDS, () => theme.bg, (c) => { setBackground(c); onChanged?.(); })),
    section('Clave de Gemini (para «Analizar»)',
      h('div', { class: 'row' }, keyInput, show),
      h('p', { class: 'dim small pre' }, 'Es gratis y cada persona usa la suya:\n1. Pulsa «Conseguir mi clave» (abre Google AI Studio).\n2. Inicia sesión con tu cuenta de Google.\n3. Pulsa «Crear clave de API» y elige un proyecto (o crea uno nuevo).\n4. Copia la clave que empieza por AIza y pégala aquí arriba.'),
      h('a', { class: 'text-btn link', href: KEY_URL, target: '_blank', rel: 'noopener' }, 'Conseguir mi clave'),
      h('p', { class: 'dim small' }, 'La clave se guarda solo en este dispositivo. Los documentos que analices se envían a Google.')),
    section('Copia de seguridad',
      h('p', { class: 'dim small' }, 'Guarda todos tus documentos y anotaciones en un archivo (por ejemplo en iCloud Drive) y recupéralos cuando quieras. Es la mejor protección si cambias de iPad o el sistema limpia los datos.'),
      h('div', { class: 'row gap' },
        h('button', { class: 'btn outline', onclick: async (e) => {
          const b = e.currentTarget; b.disabled = true;
          try {
            const blob = await exportBackup((i, n) => { b.textContent = `Preparando ${i}/${n}…`; });
            await shareOrDownload(blob, `evability-copia-${new Date().toISOString().slice(0, 10)}.zip`);
          } catch (err) { console.error(err); toast('No se pudo crear la copia'); }
          b.textContent = 'Exportar copia'; b.disabled = false;
        } }, 'Exportar copia'),
        h('button', { class: 'btn outline', onclick: () => {
          const input = h('input', { type: 'file', accept: '.zip,application/zip' });
          input.addEventListener('change', async () => {
            if (!input.files[0]) return;
            try { const n = await importBackup(input.files[0]); toast(n ? `Se han recuperado ${n} documento${n === 1 ? '' : 's'}` : 'No había documentos nuevos en esa copia'); onChanged?.(); }
            catch (err) { console.error(err); toast('No se pudo leer esa copia de seguridad'); }
          });
          input.click();
        } }, 'Importar copia'))),
    section('Almacenamiento', storageInfo,
      h('button', { class: 'btn outline', onclick: async () => {
        const ok = await navigator.storage?.persist?.();
        toast(ok ? 'Datos protegidos' : 'El navegador no lo ha permitido; añade la app a la pantalla de inicio y haz copias de seguridad');
      } }, 'Proteger mis datos')),
    standalone() ? null : section('Instalar en el iPad',
      h('p', { class: 'dim small pre' }, '1. Abre esta página en Safari.\n2. Pulsa el botón Compartir (el cuadrado con la flecha).\n3. Elige «Añadir a pantalla de inicio».\nAsí se abre como una app y funciona sin conexión.')),
    h('p', { class: 'dim small' }, `Evability ${VERSION}`));

  return modal({ title: 'Ajustes', content, wide: true, actions: [{ label: 'Listo', primary: true }] });
}
