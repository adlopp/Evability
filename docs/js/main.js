// Arranque de la app: tema, almacenamiento, navegación entre la biblioteca y el editor, y modo sin conexión.

import { applyTheme } from './theme.js';
import { toast } from './ui.js';
import * as store from './store.js';
import { createHome } from './home.js';
import { Editor } from './editor.js';
import { ALL_STICKER_FILES } from './stickers.js';

applyTheme();
const app = document.getElementById('app');

const home = createHome({ openDoc, onTheme: applyTheme });

function showHome() {
  app.replaceChildren(home.root);
  home.refresh();
}

async function openDoc(info) {
  try {
    await store.touch(info.id);
    const ed = new Editor((await store.get(info.id)) || info, showHome);
    const root = await ed.init();
    app.replaceChildren(root);
    ed.start();
    window.__editor = ed;
  } catch (e) {
    console.error(e);
    toast('No se pudo abrir el PDF');
    showHome();
  }
}

async function boot() {
  try { await store.cleanup(); } catch (e) { console.error(e); }
  showHome();
  document.getElementById('boot')?.remove();
  // Pide que el navegador no borre los datos automáticamente (solo funciona si la app está instalada o se usa con frecuencia).
  navigator.storage?.persist?.().catch(() => {});
}

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('sw.js').then((reg) => {
    // Cuando el navegador ya tiene la app guardada, descarga en segundo plano las pegatinas para el modo sin conexión.
    navigator.serviceWorker.ready.then(() => { try { reg.active?.postMessage({ type: 'precache', files: ALL_STICKER_FILES }); } catch { /* sin conexión */ } });
  }).catch((e) => console.warn('Sin service worker', e));
}

boot();
