// Biblioteca de documentos: importar, duplicar, carpetas, favoritos, papelera y anotaciones. Todo se guarda en IndexedDB.

import * as db from './db.js';
import { uid } from './ui.js';
import { openPdf, renderToCanvas, toBuffer } from './pdfdoc.js';
import { templatePdfBytes, TEMPLATE_KINDS } from './templates.js';

const DAY = 86_400_000;

export async function list() {
  return (await db.values('meta')).sort((a, b) => b.lastOpened - a.lastOpened);
}

export const get = (id) => db.get('meta', id);

async function update(id, fn) {
  const info = await db.get('meta', id);
  if (!info) return null;
  const next = fn({ ...info });
  await db.put('meta', id, next);
  return next;
}

export const touch = (id) => update(id, (d) => ({ ...d, lastOpened: Date.now() }));
export const setLastPage = (id, p) => update(id, (d) => ({ ...d, lastPage: p }));
export const setExtra = (id, extra, kinds) => update(id, (d) => ({ ...d, extra, extraKinds: kinds }));
export const setHidden = (id, hidden) => update(id, (d) => ({ ...d, hidden }));
export const rename = (id, name) => update(id, (d) => ({ ...d, name }));

export async function setFavorite(ids, favorite) { for (const id of ids) await update(id, (d) => ({ ...d, favorite })); }

export async function setFolder(ids, folder) {
  for (const id of ids) await update(id, (d) => ({ ...d, folder }));
  if (folder) await addFolder(folder);
}

// ───────── importar y crear ─────────

async function thumbnailOf(pdf) {
  const canvas = await renderToCanvas(pdf, 1, 420);
  return canvas.toDataURL('image/jpeg', 0.82);
}

/** Guarda un PDF (bytes) como documento nuevo. Lanza excepción si no es un PDF válido. */
export async function addPdf(bytes, name, extra = {}) {
  const pdf = await openPdf(bytes);
  const id = uid();
  const now = Date.now();
  const info = {
    id, name, pages: pdf.numPages, lastOpened: now, added: now, lastPage: 0, extra: 0,
    favorite: false, folder: '', template: '', extraKinds: [], hidden: [], ...extra,
  };
  await db.put('pdf', id, bytes);
  await db.put('thumb', id, await thumbnailOf(pdf));
  await db.put('anns', id, []);
  await db.put('meta', id, info);
  pdf.destroy();
  return info;
}

export async function importFile(file) {
  const name = file.name.replace(/\.pdf$/i, '');
  return addPdf(await file.arrayBuffer(), name || 'Documento');
}

export async function createFromTemplate(kind) {
  return addPdf(await templatePdfBytes(kind), TEMPLATE_KINDS[kind].docName, { template: kind });
}

export async function duplicate(id) {
  const src = await db.get('meta', id);
  if (!src) return null;
  const nid = uid();
  const now = Date.now();
  const copy = { ...src, id: nid, name: src.name + ' (copia)', lastOpened: now, added: now, favorite: false };
  await db.put('pdf', nid, await db.get('pdf', id));
  await db.put('anns', nid, (await db.get('anns', id)) || []);
  const th = await db.get('thumb', id);
  if (th) await db.put('thumb', nid, th);
  await db.put('meta', nid, copy);
  return copy;
}

export const pdfBytes = (id) => db.get('pdf', id);
export const thumb = (id) => db.get('thumb', id);
export const setThumb = (id, dataUrl) => db.put('thumb', id, dataUrl);
export async function anns(id) { return (await db.get('anns', id)) || []; }
export const saveAnns = (id, list) => db.put('anns', id, list);

// ───────── carpetas ─────────

export async function folders() {
  const saved = (await db.get('kv', 'folders')) || [];
  const used = (await db.values('meta')).map((d) => d.folder).filter(Boolean);
  return [...new Set([...saved, ...used])].sort((a, b) => a.localeCompare(b, 'es'));
}

export async function addFolder(name) {
  const saved = (await db.get('kv', 'folders')) || [];
  if (name && !saved.includes(name)) await db.put('kv', 'folders', [...saved, name]);
}

export async function removeFolder(name) {
  for (const d of await db.values('meta')) if (d.folder === name) await db.put('meta', d.id, { ...d, folder: '' });
  await db.put('kv', 'folders', ((await db.get('kv', 'folders')) || []).filter((f) => f !== name));
}

// ───────── papelera ─────────

export async function softDelete(ids) {
  const now = Date.now();
  for (const id of ids) {
    const info = await db.get('meta', id);
    if (!info) continue;
    await db.put('trash', id, { info, deleted: now });
    await db.del('meta', id);
  }
}

export async function restore(infos) {
  for (const info of infos) {
    await db.put('meta', info.id, info);
    await db.del('trash', info.id);
  }
}

export async function purge(infos) {
  for (const { id } of infos) for (const s of ['trash', 'meta', 'pdf', 'anns', 'thumb']) await db.del(s, id);
}

export async function loadTrash() {
  return (await db.values('trash')).sort((a, b) => b.deleted - a.deleted);
}

/** Vacía lo que lleva más de 30 días en la papelera y borra archivos que no pertenecen a ningún documento. */
export async function cleanup() {
  const limit = Date.now() - 30 * DAY;
  const trash = await loadTrash();
  await purge(trash.filter((t) => t.deleted < limit).map((t) => t.info));
  const live = new Set([...(await db.keys('meta')), ...(await db.keys('trash'))]);
  for (const s of ['pdf', 'anns', 'thumb']) for (const k of await db.keys(s)) if (!live.has(k)) await db.del(s, k);
}

// ───────── pegatinas propias ─────────

export async function userStickers() {
  const names = await db.keys('ust');
  return names.sort();
}
export const userSticker = (name) => db.get('ust', name);
export async function addUserSticker(file) {
  const dataUrl = await new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      const s = Math.min(1, 512 / Math.max(img.width, img.height));
      const c = document.createElement('canvas');
      c.width = Math.round(img.width * s); c.height = Math.round(img.height * s);
      c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
      resolve(c.toDataURL('image/png'));
    };
    img.onerror = reject;
    img.src = URL.createObjectURL(file);
  });
  const name = `u_${Date.now()}_${Math.floor(Math.random() * 1000)}`;
  await db.put('ust', name, dataUrl);
  return name;
}
export const deleteUserSticker = (name) => db.del('ust', name);
export const putUserSticker = (name, dataUrl) => db.put('ust', name, dataUrl);

/** Devuelve un documento de una copia de seguridad (si ya existe, no se toca). */
export async function restoreDoc(info, bytes, annotations, thumbUrl) {
  if (await db.get('meta', info.id)) return false;
  await db.put('pdf', info.id, bytes);
  await db.put('anns', info.id, annotations || []);
  if (thumbUrl) await db.put('thumb', info.id, thumbUrl);
  await db.put('meta', info.id, info);
  return true;
}
