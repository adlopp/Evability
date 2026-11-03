// Pantalla de inicio: biblioteca de documentos, plantillas, papelera, Ternurín, ajustes y «Analizar».

import { h, icon, fill, toast, snackbar, modal, confirmDialog, promptDialog, menu, ago } from './ui.js';
import * as store from './store.js';
import { TEMPLATE_KINDS, templateCanvas } from './templates.js';
import { openSettings, VERSION } from './settings.js';
import { openTime, timeRunning } from './pomodoro.js';
import { openAnalyze } from './analyze.js';
import { openSocial, openSend, setReceiver, anyUnread } from './social.js';
import { openPdf, pageSizes } from './pdfdoc.js';
import { exportPdf } from './export.js';
import { shareOrDownload } from './editor.js';

const SORTS = { RECENT: 'Recientes', NAME: 'Nombre', CREATED: 'Creación' };

function buildEntries(docs, sort) {
  const out = [];
  if (sort === 'NAME') docs.slice().sort((a, b) => a.name.localeCompare(b.name, 'es')).forEach((d) => out.push({ doc: d }));
  else if (sort === 'CREATED') docs.slice().sort((a, b) => b.added - a.added).forEach((d) => out.push({ doc: d }));
  else {
    const favs = docs.filter((d) => d.favorite).sort((a, b) => b.lastOpened - a.lastOpened);
    if (favs.length) { out.push({ header: 'Favoritos' }); favs.forEach((d) => out.push({ doc: d })); }
    const today = new Date(); today.setHours(0, 0, 0, 0);
    const t0 = today.getTime(), yesterday = t0 - 86400000, week = t0 - 6 * 86400000;
    const bucket = (t) => (t >= t0 ? 'Hoy' : t >= yesterday ? 'Ayer' : t >= week ? 'Esta semana' : 'Anteriores');
    let last = null;
    for (const d of docs.filter((x) => !x.favorite).sort((a, b) => b.lastOpened - a.lastOpened)) {
      const b = bucket(d.lastOpened);
      if (b !== last) { out.push({ header: b }); last = b; }
      out.push({ doc: d });
    }
  }
  return out;
}

export function createHome({ openDoc, onTheme }) {
  const root = h('div', { class: 'home' });
  const S = { docs: [], folders: [], query: '', sort: 'RECENT', filter: { kind: 'all' }, selected: new Set() };

  // PDF que te envían los amigos: se guardan en la biblioteca (carpeta «Amigos») en cuanto llegan.
  setReceiver(async (name, from, bytes) => {
    const { replaced } = await store.receivePdf(bytes, name, from);
    const msg = replaced ? `${from} te ha enviado «${name}» otra vez. Se ha quedado la última copia (la anterior está en la papelera)` : `${from} te ha enviado «${name}». Está en la carpeta ${store.friendsName()}`;
    toast(msg, 6000);
    if (document.hidden && window.Notification?.permission === 'granted') new Notification(replaced ? `${from} te ha enviado una versión nueva` : `${from} te ha enviado un documento`, { body: `«${name}» está en la carpeta ${store.friendsName()}` });
    refresh();
  });

  async function refresh() {
    [S.docs, S.folders, S.order] = await Promise.all([store.list(), store.folders(), store.orderedFolders()]);
    S.selected = new Set([...S.selected].filter((id) => S.docs.some((d) => d.id === id)));
    render();
  }

  // ───────── acciones ─────────

  async function importFiles(files) {
    let last = null, bad = 0, word = 0;
    for (const f of files) {
      if (/\.docx?$/i.test(f.name)) { word++; continue; }
      try { last = await store.importFile(f); } catch (e) { console.error(e); bad++; }
    }
    if (word) toast('Los archivos de Word no se pueden importar en la versión web: guárdalos como PDF desde Word o Pages.', 5000);
    if (bad) toast('No se pudo importar el archivo. ¿Es un PDF válido y sin contraseña?', 4500);
    if (last && files.length === 1) openDoc(last); else await refresh();
  }

  function pickFiles() {
    const input = h('input', { type: 'file', accept: 'application/pdf,.pdf,.doc,.docx', multiple: true });
    input.addEventListener('change', () => input.files.length && importFiles([...input.files]));
    input.click();
  }

  async function deleteDocs(list) {
    await store.softDelete(list.map((d) => d.id));
    S.selected.clear();
    await refresh();
    const undo = await snackbar(list.length === 1 ? `«${list[0].name}» movido a la papelera` : `${list.length} documentos movidos a la papelera`, 'Deshacer');
    if (undo) { await store.restore(list); await refresh(); }
  }

  /** Envía el documento tal cual: con sus anotaciones y cambios si los tiene, o el PDF original si no. */
  async function sendDoc(d) {
    const anns = await store.anns(d.id);
    const modified = anns.length > 0 || d.extra > 0 || (d.hidden || []).length > 0;
    openSend(d, {
      modified,
      build: async () => {
        if (!modified) return store.pdfBytes(d.id);
        const pdf = await openPdf(await store.pdfBytes(d.id));
        try {
          // un poco más ligero que al exportar, para que quepa en los 10 MB del envío
          const blob = await exportPdf({ pdf, info: d, anns, extraKinds: d.extraKinds || [], hidden: d.hidden || [], sizes: await pageSizes(pdf), maxPx: 1500, quality: 0.82 });
          return blob.arrayBuffer();
        } finally { pdf.destroy(); }
      },
    });
  }

  async function exportDoc(d) {
    const m = modal({ title: 'Exportando PDF', content: h('p', { class: 'dim' }, 'Preparando…') });
    try {
      const pdf = await openPdf(await store.pdfBytes(d.id));
      const blob = await exportPdf({ pdf, info: d, anns: await store.anns(d.id), extraKinds: d.extraKinds || [], hidden: d.hidden || [], sizes: await pageSizes(pdf) });
      pdf.destroy();
      m.close();
      await shareOrDownload(blob, `${d.name}-anotado.pdf`);
    } catch (e) { console.error(e); m.close(); toast('No se pudo exportar el PDF'); }
  }

  async function moveDialog(list) {
    const folders = await store.folders();
    const pick = async (folder) => { await store.setFolder(list.map((d) => d.id), folder); S.selected.clear(); mm.close(); refresh(); };
    const mm = modal({
      title: 'Mover a carpeta',
      content: h('div', { class: 'stack' }, h('button', { class: 'text-btn left', onclick: () => pick('') }, 'Sin carpeta'),
        folders.map((f) => h('button', { class: 'text-btn left', onclick: () => pick(f) }, f)),
        h('button', { class: 'text-btn left accent', onclick: async () => { mm.close(); const n = await promptDialog({ title: 'Carpeta nueva', label: 'Nombre de la carpeta', confirm: 'Crear' }); if (n?.trim()) { await store.setFolder(list.map((d) => d.id), n.trim()); S.selected.clear(); refresh(); } } }, '+ Carpeta nueva…')),
      actions: [{ label: 'Cancelar' }],
    });
  }

  function templatesDialog() {
    const cards = Object.entries(TEMPLATE_KINDS).map(([kind, t]) => {
      const prev = kind === 'BLANK' ? h('div', { class: 'tpl-blank' }) : templateCanvas(kind, 260);
      prev.classList.add('tpl-prev');
      return h('button', { class: 'tpl', onclick: async () => { m.close(); try { openDoc(await store.createFromTemplate(kind)); } catch (e) { console.error(e); toast('No se pudo crear el documento'); } } },
        prev, h('b', {}, t.title), h('small', { class: 'dim' }, t.subtitle));
    });
    const m = modal({ title: 'Plantillas', content: h('div', {}, h('p', { class: 'dim' }, 'Elige cómo quieres empezar'), h('div', { class: 'tpl-row' }, cards)), wide: true, actions: [{ label: 'Cerrar' }] });
  }

  async function trashDialog() {
    const body = h('div', { class: 'stack' });
    const m = modal({ title: 'Papelera', content: body, actions: [{ label: 'Vaciar papelera', onClick: async () => { const items = await store.loadTrash(); if (!items.length) return false; if (await confirmDialog('Vaciar papelera', `Se eliminarán para siempre ${items.length} documento(s). No se puede deshacer.`, 'Vaciar')) { await store.purge(items.map((t) => t.info)); draw(); } return false; } }, { label: 'Cerrar', primary: true }] });
    async function draw() {
      const items = await store.loadTrash();
      body.replaceChildren(...(items.length
        ? [h('p', { class: 'dim small' }, 'Se vacía sola a los 30 días.'), ...items.map((t) => h('div', { class: 'row between trash-row' },
          h('div', {}, h('b', { class: 'ellipsis' }, t.info.name), h('div', { class: 'dim small' }, 'Eliminado ' + ago(t.deleted))),
          h('div', { class: 'row' },
            h('button', { class: 'icon-btn accent', title: 'Restaurar', onclick: async () => { await store.restore([t.info]); draw(); refresh(); } }, icon('restore')),
            h('button', { class: 'icon-btn', title: 'Eliminar para siempre', onclick: async () => { await store.purge([t.info]); draw(); } }, icon('trash')))))]
        : [h('p', { class: 'dim' }, 'La papelera está vacía.')]));
    }
    draw();
  }

  // ───────── pintado ─────────

  function card(d) {
    const sel = S.selected.has(d.id), selecting = S.selected.size > 0;
    const img = h('img', { alt: '', loading: 'lazy' });
    store.thumb(d.id).then((u) => { if (u) img.src = u; });
    const total = Math.max(1, d.pages + d.extra - (d.hidden?.length || 0));
    let pressTimer = null, longDone = false;
    const el = h('div', { class: 'card' + (sel ? ' sel' : '') },
      h('div', { class: 'thumb' }, img, sel ? h('div', { class: 'check' }, icon('check', 20)) : null,
        h('button', { class: 'star' + (d.favorite ? ' on' : ''), 'aria-label': 'Favorito', onclick: async (e) => { e.stopPropagation(); await store.setFavorite([d.id], !d.favorite); refresh(); } }, icon('star', 20))),
      h('div', { class: 'card-info' },
        h('div', { class: 'card-name' }, d.name),
        h('div', { class: 'dim small' }, `${d.from ? 'de ' + d.from + ' · ' : ''}${total} pág. · ${ago(d.lastOpened)}`)),
      h('button', { class: 'more', 'aria-label': 'Más', onclick: (e) => {
        e.stopPropagation();
        menu(e.currentTarget, [
          ['Renombrar', async () => { const n = await promptDialog({ title: 'Renombrar documento', label: 'Nombre', initial: d.name, confirm: 'Guardar' }); if (n?.trim()) { await store.rename(d.id, n.trim()); refresh(); } }],
          ['Duplicar', async () => { await store.duplicate(d.id); refresh(); }],
          [d.favorite ? 'Quitar de favoritos' : 'Añadir a favoritos', async () => { await store.setFavorite([d.id], !d.favorite); refresh(); }],
          ['Mover a carpeta', () => moveDialog([d])],
          ['Enviar a un amigo', () => sendDoc(d)],
          ['Exportar PDF con anotaciones', () => exportDoc(d)],
          ['Eliminar', () => deleteDocs([d]), 'danger'],
        ]);
      } }, icon('more', 20)));
    el.addEventListener('pointerdown', (e) => { if (e.target.closest('button')) return; longDone = false; pressTimer = setTimeout(() => { longDone = true; toggleSelect(d.id); }, 550); });
    ['pointerup', 'pointerleave', 'pointercancel', 'pointermove'].forEach((ev) => el.addEventListener(ev, () => clearTimeout(pressTimer)));
    el.addEventListener('click', (e) => { if (longDone) { longDone = false; return; } if (e.target.closest('button')) return; if (selecting) toggleSelect(d.id); else openDoc(d); });
    return el;
  }

  function toggleSelect(id) { S.selected.has(id) ? S.selected.delete(id) : S.selected.add(id); render(); }

  function chip(label, on, fn, ic, onLong) {
    let t, fired = false;
    const b = h('button', { class: 'chip' + (on ? ' on' : ''), onclick: () => { if (fired) { fired = false; return; } fn(); } }, ic ? icon(ic, 16) : null, label);
    if (onLong) {
      b.addEventListener('pointerdown', () => { fired = false; clearTimeout(t); t = setTimeout(() => { fired = true; onLong(); }, 600); });
      ['pointerup', 'pointerleave', 'pointercancel'].forEach((e) => b.addEventListener(e, () => clearTimeout(t)));
      b.addEventListener('contextmenu', (e) => e.preventDefault());
    }
    return b;
  }

  // ───────── carpetas: menú al mantener pulsado, renombrar y ordenar ─────────

  const FRIENDS = store.FRIENDS;
  const labelOf = (t) => (t === FRIENDS ? store.friendsName() : t);
  const locked = () => toast('Esa carpeta no se puede modificar');

  function folderMenu(token) {
    const act = (fn) => () => { m.close(); fn(); };
    const m = modal({
      title: labelOf(token),
      content: h('div', { class: 'stack' },
        h('button', { class: 'text-btn left', onclick: act(() => renameFolder(token)) }, 'Renombrar'),
        h('button', { class: 'text-btn left', onclick: act(() => orderDialog(token)) }, 'Mover (cambiar el orden)'),
        token === FRIENDS ? h('p', { class: 'dim small' }, 'Esta carpeta se puede renombrar y mover, pero no eliminar.')
          : h('button', { class: 'text-btn left', onclick: act(() => removeFolder(token)) }, 'Eliminar')),
      actions: [{ label: 'Cancelar' }],
    });
  }

  async function renameFolder(token) {
    const n = (await promptDialog({ title: 'Renombrar carpeta', label: 'Nombre de la carpeta', initial: labelOf(token), confirm: 'Guardar' }))?.trim();
    if (!n) return;
    const taken = ['Todos', 'Favoritos', store.friendsName(), ...S.folders].filter((x) => x !== labelOf(token)).some((x) => x.toLowerCase() === n.toLowerCase());
    if (taken) { toast('Ya existe una carpeta con ese nombre'); return; }
    if (token === FRIENDS) store.setFriendsName(n);
    else { await store.renameFolder(token, n); if (S.filter.kind === 'folder' && S.filter.name === token) S.filter = { kind: 'folder', name: n }; }
    refresh();
  }

  async function removeFolder(token) {
    if (await confirmDialog('Quitar carpeta', `La carpeta «${token}» desaparecerá. Sus documentos se conservan, sin carpeta.`, 'Quitar')) {
      await store.removeFolder(token);
      if (S.filter.name === token) S.filter = { kind: 'all' };
      refresh();
    }
  }

  /** «Todos» y «Favoritos» quedan fijos arriba (con candado); el resto se reordena arrastrando ☰ o con las flechas. */
  function orderDialog(selected) {
    const items = [...S.order];
    const list = h('div', { class: 'order-list' });
    const lockRow = (name) => {
      const lock = h('span', { class: 'icon-btn handle' }, icon('lock', 20));
      lock.addEventListener('pointerdown', (e) => {
        const y = e.clientY;
        const move = (ev) => { if (Math.abs(ev.clientY - y) > 6) { locked(); end(); } };
        const end = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', end); window.removeEventListener('pointercancel', end); };
        window.addEventListener('pointermove', move); window.addEventListener('pointerup', end); window.addEventListener('pointercancel', end);
      });
      return h('div', { class: 'order-row locked' }, lock, h('span', { class: 'grow dim' }, name));
    };
    function draw() {
      list.replaceChildren(...items.map((t, i) => {
        const handle = h('span', { class: 'icon-btn handle', 'aria-label': 'Arrastrar' }, icon('grip', 20));
        const row = h('div', { class: 'order-row' }, handle,
          h('span', { class: 'grow ellipsis' + (t === selected ? ' accent strong' : '') }, labelOf(t)),
          h('button', { class: 'icon-btn', 'aria-label': 'Subir', disabled: i === 0, onclick: () => { items.splice(i - 1, 0, items.splice(i, 1)[0]); draw(); } }, icon('chevUp', 20)),
          h('button', { class: 'icon-btn', 'aria-label': 'Bajar', disabled: i === items.length - 1, onclick: () => { items.splice(i + 1, 0, items.splice(i, 1)[0]); draw(); } }, icon('chevDown', 20)));
        handle.addEventListener('pointerdown', (e) => {
          e.preventDefault();
          const startY = e.clientY, rowH = row.offsetHeight, startIdx = [...list.children].indexOf(row);
          let cur = startIdx;
          row.classList.add('drag');
          const move = (ev) => {
            const dy = ev.clientY - startY;
            const want = Math.max(0, Math.min(items.length - 1, startIdx + Math.round(dy / rowH)));
            if (want !== cur) {
              items.splice(want, 0, items.splice(cur, 1)[0]);
              list.insertBefore(row, want > cur ? list.children[want].nextSibling : list.children[want]);
              cur = want;
            }
            row.style.transform = `translateY(${dy - (cur - startIdx) * rowH}px)`;
          };
          const end = () => {
            window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', end); window.removeEventListener('pointercancel', end);
            row.classList.remove('drag'); row.style.transform = ''; draw();
          };
          window.addEventListener('pointermove', move); window.addEventListener('pointerup', end); window.addEventListener('pointercancel', end);
        });
        return row;
      }));
    }
    draw();
    modal({
      title: 'Ordenar carpetas',
      content: h('div', {}, h('p', { class: 'dim small' }, 'Arrastra ☰ para cambiar el orden, o usa las flechas.'), lockRow('Todos'), lockRow('Favoritos'), list),
      actions: [{ label: 'Cancelar' }, { label: 'Listo', primary: true, onClick: async () => { await store.saveFolderOrder(items); refresh(); } }],
    });
  }

  const analyze = () => openAnalyze({ docs: S.docs, onCreated: (info) => { refresh(); openDoc(info); }, onOpenSettings: () => openSettings(() => refresh()) });

  function render() {
    const scroll = root.scrollTop;
    const visible = S.docs.filter((d) => (!S.query.trim() || d.name.toLowerCase().includes(S.query.trim().toLowerCase())) &&
      (S.filter.kind === 'all' || (S.filter.kind === 'favs' && d.favorite) || (S.filter.kind === 'friends' && d.from) || (S.filter.kind === 'folder' && d.folder === S.filter.name)));
    const last = S.docs[0];
    const selecting = S.selected.size > 0;
    const sel = S.docs.filter((d) => S.selected.has(d.id));
    const allFav = sel.length && sel.every((d) => d.favorite);

    let logoTimer;
    const logo = h('button', { class: 'logo', 'aria-label': 'Evability', onclick: () => {
      logo.classList.add('bounce'); clearTimeout(logoTimer); logoTimer = setTimeout(() => logo.classList.remove('bounce'), 250);
      const a = new Audio('easter-egg.mp3'); a.play().catch(() => {});
    } }, h('img', { src: 'icons/logo.png', alt: '' }));

    const search = h('input', { class: 'field search', type: 'search', placeholder: 'Buscar documentos', value: S.query });
    search.addEventListener('input', () => { S.query = search.value; clearTimeout(S.qt); S.qt = setTimeout(() => { render(); document.querySelector('.search')?.focus(); }, 180); });

    const entries = buildEntries(visible, S.sort);

    fill(root,
      h('div', { class: 'top-row' }, logo,
        h('div', { class: 'grow' }, h('div', { class: 'app-title' }, 'Evability'), h('div', { class: 'dim small' }, 'v' + VERSION)),
        h('button', { class: 'round' + (anyUnread() ? ' badge' : ''), 'aria-label': 'Amigos', title: 'Amigos', onclick: () => { document.getElementById('app').append(openSocial(() => render())); } }, icon('people')),
        h('button', { class: 'round', 'aria-label': 'Analizar', title: 'Analizar', onclick: analyze }, icon('bot')),
        h('button', { class: 'round', 'aria-label': 'Papelera', title: 'Papelera', onclick: trashDialog }, icon('trash')),
        h('button', { class: 'round', 'aria-label': 'Ajustes', title: 'Ajustes', onclick: () => openSettings(() => { onTheme?.(); refresh(); }) }, icon('settings'))),
      h('div', { class: 'actions' },
        h('button', { class: 'btn', onclick: pickFiles }, icon('upload', 20), 'Importar PDF'),
        h('button', { class: 'btn outline', onclick: templatesDialog }, icon('grid', 20), 'Plantillas'),
        h('button', { class: 'btn outline' + (timeRunning() ? ' live' : ''), onclick: () => openTime({ mount: (el) => document.getElementById('app').append(el), onClose: () => render() }) }, icon('timer', 20), 'Tiempo')),
      last && !S.query.trim() && S.filter.kind === 'all' && !selecting ? continueCard(last) : null,
      S.docs.length ? h('div', { class: 'row search-row' }, h('div', { class: 'grow search-wrap' }, icon('search', 18), search),
        h('button', { class: 'text-btn', onclick: (e) => menu(e.currentTarget, Object.entries(SORTS).map(([k, label]) => [label, () => { S.sort = k; render(); }, S.sort === k ? 'bold' : ''])) }, icon('sort', 18), ' ' + SORTS[S.sort])) : null,
      S.docs.length ? h('div', { class: 'chips' },
        chip('Todos', S.filter.kind === 'all', () => { S.filter = { kind: 'all' }; render(); }, null, locked),
        chip('Favoritos', S.filter.kind === 'favs', () => { S.filter = { kind: 'favs' }; render(); }, 'star', locked),
        S.order.map((t) => (t === FRIENDS
          ? chip(labelOf(t), S.filter.kind === 'friends', () => { S.filter = { kind: 'friends' }; render(); }, 'people', () => folderMenu(t))
          : chip(t, S.filter.kind === 'folder' && S.filter.name === t, () => { S.filter = { kind: 'folder', name: t }; render(); }, 'folder', () => folderMenu(t)))),
        h('button', { class: 'chip add', 'aria-label': 'Carpeta nueva', title: 'Carpeta nueva', onclick: async () => { const n = await promptDialog({ title: 'Carpeta nueva', label: 'Nombre de la carpeta', confirm: 'Crear' }); if (n?.trim()) { await store.addFolder(n.trim()); refresh(); } } }, icon('plus', 18))) : null,
      !S.docs.length ? h('div', { class: 'empty' }, h('div', { class: 'big-ic' }, icon('upload', 48)), h('h2', {}, 'Todavía no hay documentos'), h('p', { class: 'dim' }, 'Importa un PDF, o crea uno desde una plantilla, y empieza a anotar.'))
        : !entries.length ? h('p', { class: 'dim pad' }, S.filter.kind === 'friends' && !S.query.trim() ? 'Aquí aparecerán los documentos que te envíen tus amigos. Para enviar uno: menú ⋮ de un documento → «Enviar a un amigo».' : 'Ningún documento coincide con la búsqueda o el filtro.')
          : h('div', { class: 'grid' }, entries.map((e) => (e.header ? h('h3', { class: 'section' }, e.header) : card(e.doc)))),
      selecting ? h('div', { class: 'selbar' },
        h('button', { class: 'icon-btn', onclick: () => { S.selected.clear(); render(); } }, icon('close')),
        h('b', { class: 'grow' }, `${S.selected.size} seleccionado${S.selected.size === 1 ? '' : 's'}`),
        h('button', { class: 'icon-btn accent', title: 'Favorito', onclick: async () => { await store.setFavorite([...S.selected], !allFav); S.selected.clear(); refresh(); } }, icon('star')),
        h('button', { class: 'icon-btn', title: 'Mover a carpeta', onclick: () => moveDialog(sel) }, icon('folder')),
        h('button', { class: 'icon-btn', title: 'Eliminar', onclick: () => deleteDocs(sel) }, icon('trash'))) : null);
    root.scrollTop = scroll;
  }

  function continueCard(d) {
    const img = h('img', { alt: '' });
    store.thumb(d.id).then((u) => { if (u) img.src = u; });
    const total = Math.max(1, d.pages + d.extra - (d.hidden?.length || 0)), page = Math.min(total, (d.lastPage || 0) + 1);
    return h('div', { class: 'continue', onclick: () => openDoc(d) },
      h('div', { class: 'thumb small-thumb' }, img),
      h('div', { class: 'grow' },
        h('div', { class: 'accent small strong' }, 'Continuar donde lo dejaste'),
        h('div', { class: 'continue-name' }, d.name),
        h('div', { class: 'dim small' }, `Página ${page} de ${total} · ${ago(d.lastOpened)}`),
        h('div', { class: 'progress thin' }, h('div', { style: { width: (100 * page / total) + '%' } }))),
      h('button', { class: 'btn' }, 'Continuar'));
  }

  return { root, refresh };
}
