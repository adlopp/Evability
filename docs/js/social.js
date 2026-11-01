// Parte social: perfil, amigos, presencia (punto verde) y «toques» (corazones). Misma lógica que la app de Android:
// habla con Firebase por su API REST (sin SDK). Las claves no son secretas; la seguridad la ponen las reglas de `firestore.rules`.

import { h, icon, toast, confirmDialog, modal, uid } from './ui.js';

const PROJECT = 'evability-a4c4d';
const API_KEY = 'AIzaSyD1LmNMAePR63FPvCldLWPXQl4JvmnFars';
const DB = `projects/${PROJECT}/databases/(default)/documents`;
const DOCS = `https://firestore.googleapis.com/v1/${DB}`;
const AUTH = 'https://identitytoolkit.googleapis.com/v1/accounts';
export const COOLDOWN_MS = 15 * 60000, ONLINE_MS = 90000;
const SKEY = 'ev.social';

let session = (() => { try { return JSON.parse(localStorage.getItem(SKEY) || 'null'); } catch { return null; } })();
const saveSession = (s) => { session = s; if (s) localStorage.setItem(SKEY, JSON.stringify(s)); else localStorage.removeItem(SKEY); };
export const currentUser = () => session;
export const cleanName = (raw) => raw.trim().toLowerCase().replace(/[^a-z0-9_]/g, '');

// ───────── red ─────────

function explain(code, text) {
  let msg = ''; try { msg = JSON.parse(text).error.message || ''; } catch { /* sin cuerpo */ }
  if (msg.startsWith('EMAIL_EXISTS')) return 'Ya hay una cuenta con ese correo';
  if (msg.startsWith('INVALID_EMAIL')) return 'El correo no es válido';
  if (/^(INVALID_LOGIN_CREDENTIALS|EMAIL_NOT_FOUND|INVALID_PASSWORD)/.test(msg)) return 'Usuario o contraseña incorrectos';
  if (msg.startsWith('WEAK_PASSWORD')) return 'La contraseña debe tener al menos 6 caracteres';
  if (msg.startsWith('OPERATION_NOT_ALLOWED')) return 'Falta activar «Correo/contraseña» en Firebase → Authentication';
  if (msg.startsWith('TOO_MANY_ATTEMPTS')) return 'Demasiados intentos. Espera un poco y vuelve a probar';
  if (code === 409 || msg.includes('ALREADY_EXISTS')) return 'Ya existe';
  if (code === 403 || msg.includes('PERMISSION_DENIED')) return 'Sin permiso (¿has publicado las reglas de Firestore?)';
  if (code === 404) return 'Falta crear la base de datos Firestore en Firebase';
  if (code === 429) return 'Se ha agotado la cuota gratuita de hoy. Inténtalo mañana';
  return `Error del servidor (${code})`;
}

async function postText(url, body, { contentType = 'application/json', token } = {}) {
  let res;
  try {
    res = await fetch(url, { method: 'POST', headers: { 'Content-Type': contentType, ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body });
  } catch { throw new Error('Sin conexión a internet'); }
  const text = await res.text();
  if (!res.ok) throw new Error(explain(res.status, text));
  return text;
}
const post = async (url, obj, token) => JSON.parse(await postText(url, JSON.stringify(obj), { token }));

let refreshing = null;
async function live() {
  if (!session) throw new Error('Inicia sesión primero');
  if (session.expiresAt - 60000 < Date.now()) {
    refreshing ||= (async () => {
      const r = JSON.parse(await postText(`https://securetoken.googleapis.com/v1/token?key=${API_KEY}`,
        'grant_type=refresh_token&refresh_token=' + encodeURIComponent(session.refreshToken), { contentType: 'application/x-www-form-urlencoded' }));
      saveSession({ ...session, idToken: r.id_token, refreshToken: r.refresh_token, expiresAt: Date.now() + Number(r.expires_in) * 1000 });
    })().finally(() => { refreshing = null; });
    await refreshing;
  }
  return session;
}

// ───────── Firestore (todo con POST: commit, batchGet y runQuery) ─────────

const str = (v) => ({ stringValue: v });
const strs = (v) => ({ arrayValue: { values: v.map(str) } });
const write = (path, fields, { serverTime, mustNotExist } = {}) => ({
  update: { name: `${DB}/${path}`, fields },
  ...(serverTime ? { updateTransforms: [{ fieldPath: serverTime, setToServerValue: 'REQUEST_TIME' }] } : {}),
  ...(mustNotExist ? { currentDocument: { exists: false } } : {}),
});
const del = (path) => ({ delete: `${DB}/${path}` });
const commit = async (...writes) => { await post(`${DOCS}:commit`, { writes }, (await live()).idToken); };

const idOf = (name) => name.split('/').pop();
const text = (d, k) => d?.fields?.[k]?.stringValue || '';
const list = (d, k) => (d?.fields?.[k]?.arrayValue?.values || []).map((v) => v.stringValue);
const time = (d, k) => { const t = d?.fields?.[k]?.timestampValue; return t ? Date.parse(t) : 0; };

async function getAll(collection, ids) {
  if (!ids.length) return {};
  const arr = JSON.parse(await postText(`${DOCS}:batchGet`, JSON.stringify({ documents: ids.map((i) => `${DB}/${collection}/${i}`) }), { token: (await live()).idToken }));
  const out = {};
  for (const r of arr) if (r.found) out[idOf(r.found.name)] = r.found;
  return out;
}
const filter = (field, op, value) => ({ fieldFilter: { field: { fieldPath: field }, op, value } });
async function query(collection, where, limit = 50) {
  const arr = JSON.parse(await postText(`${DOCS}:runQuery`, JSON.stringify({ structuredQuery: { from: [{ collectionId: collection }], where, limit } }), { token: (await live()).idToken }));
  return arr.filter((r) => r.document).map((r) => r.document);
}
const profile = (uid, d) => ({ uid, username: text(d, 'username') || '?', photo: text(d, 'photo') });

// ───────── cuenta ─────────

async function enter(endpoint, email, pass, username) {
  const r = await post(`${AUTH}:${endpoint}?key=${API_KEY}`, { email, password: pass, returnSecureToken: true });
  return { uid: r.localId, username, idToken: r.idToken, refreshToken: r.refreshToken, expiresAt: Date.now() + Number(r.expiresIn) * 1000 };
}

function cleanEmail(raw) {
  const e = raw.trim().toLowerCase();
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(e)) throw new Error('Escribe un correo válido');
  return e;
}

/** El correo solo sirve para entrar y recuperar la contraseña; vive en Firebase Auth y nadie más lo ve. */
export async function signUp(rawEmail, rawName, pass) {
  const email = cleanEmail(rawEmail), name = cleanName(rawName);
  if (name.length < 3 || name.length > 16) throw new Error('El nombre de usuario debe tener entre 3 y 16 letras, números o _');
  if (pass.length < 6) throw new Error('La contraseña debe tener al menos 6 caracteres');
  const s = await enter('signUp', email, pass, name);
  saveSession(s);
  try {
    await commit(write(`usernames/${name}`, { uid: str(s.uid) }, { mustNotExist: true }));
    await commit(write(`users/${s.uid}`, { username: str(name), photo: str('') }));
  } catch (e) {
    try { await post(`${AUTH}:delete?key=${API_KEY}`, { idToken: s.idToken }); } catch { /* ya no importa */ }
    saveSession(null);
    throw e.message === 'Ya existe' ? new Error('Ese nombre de usuario ya está cogido') : e;
  }
}

export async function signIn(rawEmail, pass) {
  if (!rawEmail.trim() || !pass) throw new Error('Escribe tu correo y contraseña');
  const s = await enter('signInWithPassword', cleanEmail(rawEmail), pass, '');
  saveSession(s);
  try { saveSession({ ...s, username: profile(s.uid, (await getAll('users', [s.uid]))[s.uid]).username }); }
  catch (e) { saveSession(null); throw e; }
  lastShownPoke = -1;
}

/** Firebase manda un correo con un enlace para elegir una contraseña nueva. */
export async function resetPassword(rawEmail) {
  const email = cleanEmail(rawEmail);
  try { await post(`${AUTH}:sendOobCode?key=${API_KEY}`, { requestType: 'PASSWORD_RESET', email }); }
  catch (e) { throw e.message === 'Usuario o contraseña incorrectos' ? new Error('No hay ninguna cuenta con ese correo') : e; }
}

export function signOut() { saveSession(null); lastShownPoke = -1; }

export async function myProfile() { const s = await live(); return profile(s.uid, (await getAll('users', [s.uid]))[s.uid]); }
/** Guarda la miniatura (la de las listas) y, aparte, la foto en alta resolución para cuando se amplía. */
export async function setPhoto(thumb, full) {
  const s = await live();
  await commit(write(`users/${s.uid}`, { username: str(s.username), photo: str(thumb) }));
  try { await commit(write(`photos/${s.uid}`, { data: str(full) })); fullCache.set(photoKey(s.uid, thumb), full); } catch { /* sin reglas nuevas: se amplía la miniatura */ }
}
const fullCache = new Map();
const photoKey = (uid, thumb) => `${uid}:${thumb.length}:${thumb.slice(-16)}`;
export async function fullPhoto(uid, thumb) {
  const key = photoKey(uid, thumb);
  if (fullCache.has(key)) return fullCache.get(key);
  try {
    const data = text((await getAll('photos', [uid]))[uid], 'data');
    if (data) { fullCache.set(key, data); return data; }
  } catch { /* sin foto grande */ }
  return null;
}

// ───────── amigos ─────────

export async function search(raw) {
  const q = cleanName(raw);
  if (!q) return [];
  const where = { compositeFilter: { op: 'AND', filters: [filter('username', 'GREATER_THAN_OR_EQUAL', str(q)), filter('username', 'LESS_THAN_OR_EQUAL', str(q + ''))] } };
  const me = (await live()).uid;
  return (await query('users', where, 10)).map((d) => profile(idOf(d.name), d)).filter((p) => p.uid !== me);
}

export async function overview() {
  const me = (await live()).uid;
  const friendUids = (await query('friends', filter('members', 'ARRAY_CONTAINS', str(me)))).map((d) => list(d, 'members').find((u) => u !== me));
  const incomingUids = (await query('requests', filter('to', 'EQUAL', str(me)))).map((d) => text(d, 'from'));
  const outgoing = new Set((await query('requests', filter('from', 'EQUAL', str(me)))).map((d) => text(d, 'to')));
  const profiles = await getAll('users', [...new Set([...friendUids, ...incomingUids])]);
  const presence = await getAll('presence', friendUids).catch(() => ({}));
  const pokes = await getAll('pokes', friendUids.map((u) => `${u}_${me}`)).catch(() => ({}));
  return {
    friends: friendUids.map((u) => ({ profile: profile(u, profiles[u]), lastSeen: time(presence[u], 'lastSeen'), lastPokeAt: time(pokes[`${u}_${me}`], 'at') }))
      .sort((a, b) => a.profile.username.localeCompare(b.profile.username)),
    incoming: incomingUids.map((u) => profile(u, profiles[u])),
    outgoing,
  };
}

export async function request(uid) {
  const me = (await live()).uid;
  try { await commit(write(`requests/${me}_${uid}`, { from: str(me), to: str(uid) }, { mustNotExist: true })); }
  catch (e) { throw e.message === 'Ya existe' ? new Error('Ya le enviaste una solicitud') : e; }
}
export async function accept(uid) {
  const me = (await live()).uid, m = [me, uid].sort();
  await commit(write(`friends/${m[0]}_${m[1]}`, { members: strs(m) }, { mustNotExist: true }), del(`requests/${uid}_${me}`));
}
export async function decline(uid) { await commit(del(`requests/${uid}_${(await live()).uid}`)); }
export async function removeFriend(uid) { const m = [(await live()).uid, uid].sort(); await commit(del(`friends/${m[0]}_${m[1]}`)); }

// ───────── presencia y toques ─────────

export async function heartbeat() { await commit(write(`presence/${(await live()).uid}`, {}, { serverTime: 'lastSeen' })); }

export async function poke(uid) {
  const me = (await live()).uid;
  try { await commit(write(`pokes/${uid}_${me}`, { from: str(me), to: str(uid) }, { serverTime: 'at' })); }
  catch (e) { throw e.message.startsWith('Sin permiso') ? new Error('Ya le enviaste un ❤ hace menos de 15 minutos') : e; }
}

let lastShownPoke = -1;

// ───────── documentos entre amigos ─────────

export const MAX_SHARE = 10 * 1024 * 1024, CHUNK = 600000;
let receiver = null;
const receiving = new Set();
export const setReceiver = (fn) => { receiver = fn; };
const intV = (n) => ({ integerValue: String(n) });
const boolV = (b) => ({ booleanValue: b });
const toB64 = (u8) => { let s = ''; for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000)); return btoa(s); };
const fromB64 = (b) => Uint8Array.from(atob(b), (c) => c.charCodeAt(0));

/** Envía un documento a un amigo, troceado en partes de 600 KB (Firestore admite 1 MB por documento). */
export async function sendPdf(toUid, name, buffer, onProgress = () => {}) {
  const me = (await live()).uid, bytes = new Uint8Array(buffer);
  if (bytes.length > MAX_SHARE) throw new Error('Este documento pesa más de 10 MB y no se puede enviar');
  const chunks = Math.max(1, Math.ceil(bytes.length / CHUNK)), id = uid();
  await commit(write(`shares/${id}`, { from: str(me), to: str(toUid), name: str(name.slice(0, 150)), size: intV(bytes.length), chunks: intV(chunks), ready: boolV(false) }, { serverTime: 'at' }));
  try {
    for (let i = 0; i < chunks; i++) {
      await commit(write(`shares/${id}/parts/${i}`, { data: str(toB64(bytes.subarray(i * CHUNK, (i + 1) * CHUNK))) }));
      onProgress((i + 1) / chunks);
    }
    // Solo ahora se marca como listo, para que el otro no lo baje a medias.
    await commit({ update: { name: `${DB}/shares/${id}`, fields: { ready: boolV(true) } }, updateMask: { fieldPaths: ['ready'] } });
  } catch (e) {
    try { await commit(...Array.from({ length: chunks }, (_, i) => del(`shares/${id}/parts/${i}`)), del(`shares/${id}`)); } catch { /* ya no importa */ }
    throw e;
  }
}

/** Baja los documentos que te han enviado, los entrega a `receiver` y los borra de la nube. */
async function checkShares() {
  if (!receiver) return;
  const me = (await live()).uid;
  const where = { compositeFilter: { op: 'AND', filters: [filter('to', 'EQUAL', str(me)), filter('ready', 'EQUAL', boolV(true))] } };
  // De más antiguo a más nuevo: si te lo mandan dos veces, la última copia es la que se queda.
  for (const d of (await query('shares', where, 5)).sort((a, b) => time(a, 'at') - time(b, 'at'))) {
    const id = idOf(d.name);
    if (receiving.has(id)) continue;
    receiving.add(id);
    try {
      const chunks = Number(d.fields?.chunks?.integerValue || 0), from = text(d, 'from'), name = text(d, 'name') || 'Documento recibido';
      const parts = [];
      for (let i = 0; i < chunks; i += 3) {
        const ids = Array.from({ length: Math.min(3, chunks - i) }, (_, k) => String(i + k));
        const got = await getAll(`shares/${id}/parts`, ids);
        for (const p of ids) { if (!got[p]) throw new Error('Faltan partes del documento'); parts.push(fromB64(text(got[p], 'data'))); }
      }
      const bytes = new Uint8Array(parts.reduce((n, p) => n + p.length, 0)); let at = 0;
      for (const p of parts) { bytes.set(p, at); at += p.length; }
      const sender = text((await getAll('users', [from]))[from], 'username') || 'un amigo';
      try { await receiver(name, sender, bytes.buffer); }
      finally { try { await commit(...Array.from({ length: chunks }, (_, i) => del(`shares/${id}/parts/${i}`)), del(`shares/${id}`)); } catch { /* se reintenta */ } }
    } finally { receiving.delete(id); }
  }
}

/**
 * «Enviar a un amigo»: elige a quién mandar el documento tal cual lo tienes. `source` = { modified, build }:
 * si tiene cambios (anotaciones, páginas añadidas o eliminadas) `build()` devuelve el documento con ellos; si no, el original.
 */
export function openSend(doc, source) {
  const body = h('div', { class: 'stack' }, h('p', { class: 'dim' }, session ? 'Cargando amigos…' : 'Entra primero en Amigos (el botón de las personas de la cabecera) para poder enviar documentos.'));
  const m = modal({ title: `Enviar «${doc.name}»`, content: body, actions: [{ label: 'Cerrar', primary: true }] });
  if (!session) return;
  const note = h('p', { class: 'dim small' }, source.modified ? 'Se enviará tal cual lo tienes, con tus anotaciones y cambios (máx. 10 MB).' : 'Se enviará el documento tal cual (máx. 10 MB).');
  overview().then(({ friends }) => {
    if (!friends.length) { body.replaceChildren(h('p', { class: 'dim' }, 'Todavía no tienes amigos a quienes enviárselo.'), note); return; }
    const status = h('p', { class: 'dim small' });
    body.replaceChildren(...friends.map((f) => h('div', { class: 'person' }, avatar(f.profile.username, f.profile.photo, 40), h('span', { class: 'grow' }, f.profile.username),
      h('button', { class: 'text-btn strong', onclick: async (e) => {
        const btns = body.querySelectorAll('button'); btns.forEach((b) => { b.disabled = true; });
        try {
          status.textContent = source.modified ? 'Preparando el documento con tus cambios…' : 'Enviando…';
          await sendPdf(f.profile.uid, doc.name, await source.build(), (p) => { status.textContent = `Enviando… ${Math.round(p * 100)} %`; });
          toast('Enviado a ' + f.profile.username); m.close();
        }
        catch (err) { status.textContent = err.message || 'No se pudo enviar'; status.style.color = '#ff6b6b'; btns.forEach((b) => { b.disabled = false; }); }
      } }, 'Enviar'))), status, note);
  }).catch((e) => { body.replaceChildren(h('p', { class: 'err' }, e.message || 'Sin conexión')); });
}

/** Revisa si alguien te ha mandado un ❤ desde la última vez. La primera vez solo toma nota de los antiguos. */
async function checkPokes() {
  const me = (await live()).uid;
  const times = (await query('pokes', filter('to', 'EQUAL', str(me)))).map((d) => time(d, 'at'));
  const newest = Math.max(0, ...times);
  if (lastShownPoke < 0) { lastShownPoke = newest; return; }
  const fresh = times.filter((t) => t > lastShownPoke).length;
  lastShownPoke = Math.max(lastShownPoke, newest);
  for (let i = 0; i < Math.min(fresh, 3); i++) showHeart();
}

/** Un corazón en un sitio cualquiera de la pantalla que desaparece a los 3 segundos. */
// La canción del logo («tiki, tiki, tiki») suena entera cuando llega un corazón. Los navegadores solo dejan sonar un audio
// que se ha «desbloqueado» antes con un toque del usuario, así que se prepara con el primer toque en la app.
const heartSong = new Audio('easter-egg.mp3');
heartSong.preload = 'auto';
document.addEventListener('pointerdown', () => {
  heartSong.muted = true;
  heartSong.play().then(() => { heartSong.pause(); heartSong.currentTime = 0; heartSong.muted = false; }).catch(() => { heartSong.muted = false; });
}, { once: true });

export function showHeart() {
  heartSong.currentTime = 0;
  heartSong.play().catch(() => {});
  const el = h('div', { class: 'float-heart', style: { left: 12 + Math.random() * 76 + '%', top: 15 + Math.random() * 60 + '%' } }, '❤');
  document.body.append(el);
  navigator.vibrate?.(60);
  setTimeout(() => el.remove(), 3000);
}

// ───────── segundo plano de la app: latido y avisos ─────────

let hostTimers = [];
export function startHost() {
  stopHost();
  if (!session) return;
  const visible = () => document.visibilityState === 'visible' && session;
  const beat = () => { if (visible()) heartbeat().catch(() => {}); };
  const poll = () => { if (visible()) { checkPokes().catch(() => {}); checkShares().catch(() => {}); } };
  beat(); poll();
  hostTimers = [setInterval(beat, 30000), setInterval(poll, 12000)];
}
export function stopHost() { hostTimers.forEach(clearInterval); hostTimers = []; }
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible' && session && hostTimers.length) { heartbeat().catch(() => {}); checkPokes().catch(() => {}); } });

/** «En línea» o «Desconectado hace 5 min»: el último latido de presencia marca cuándo se fue. */
function presence(lastSeen) {
  if (lastSeen > 0 && Date.now() - lastSeen < ONLINE_MS) return { online: true, text: 'En línea' };
  if (lastSeen <= 0) return { online: false, text: 'Desconectado' };
  const min = Math.max(1, Math.floor((Date.now() - lastSeen) / 60000));
  const ago = min < 60 ? `${min} min` : min < 1440 ? `${Math.floor(min / 60)} h` : min < 2880 ? '1 día' : `${Math.floor(min / 1440)} días`;
  return { online: false, text: `Desconectado hace ${ago}` };
}
const paintPresence = (el, lastSeen) => { const p = presence(lastSeen); el.textContent = p.text; el.style.color = p.online ? 'var(--accent)' : 'var(--on-dim)'; };

// ───────── pantalla ─────────

/** Foto ampliada en el centro de la pantalla mientras se mantiene pulsado el perfil. */
let zoomEl = null;
function showZoom(name, photo, uid) {
  hideZoom();
  const img = photo ? h('img', { src: 'data:image/jpeg;base64,' + photo, alt: '' }) : null;
  const el = zoomEl = h('div', { class: 'photo-zoom' }, h('div', { class: 'photo-zoom-circle' }, img || h('span', {}, name.slice(0, 1).toUpperCase())));
  document.body.append(el);
  // primero se ve la miniatura y, en cuanto llega, la foto grande (sin pixelar)
  if (img && uid) fullPhoto(uid, photo).then((full) => { if (full && zoomEl === el) img.src = 'data:image/jpeg;base64,' + full; });
}
function hideZoom() { zoomEl?.remove(); zoomEl = null; }

function avatar(name, photo, size = 46, online = null, uid = null) {
  const inner = photo ? h('img', { src: 'data:image/jpeg;base64,' + photo, alt: '', draggable: 'false' }) : h('span', {}, name.slice(0, 1).toUpperCase());
  const el = h('div', { class: 'avatar', style: { width: size + 'px', height: size + 'px', fontSize: size * 0.42 + 'px' } }, inner,
    online === null ? null : h('i', { class: 'dot' + (online ? ' on' : '') }));
  // Mantener pulsado: se ve la foto grande; al soltar desaparece (y ese toque no cuenta como «clic»).
  let timer, held = false;
  el.addEventListener('pointerdown', () => { held = false; clearTimeout(timer); timer = setTimeout(() => { held = true; showZoom(name, photo, uid); }, 380); });
  const end = () => { clearTimeout(timer); if (held) hideZoom(); };
  ['pointerup', 'pointercancel', 'pointerleave'].forEach((ev) => el.addEventListener(ev, end));
  el.addEventListener('contextmenu', (e) => e.preventDefault());
  el.addEventListener('click', (e) => { if (held) { e.stopPropagation(); e.preventDefault(); held = false; } }, true);
  return el;
}

/** Recortar la foto: se arrastra, se pellizca o se usa el deslizador. Devuelve el JPEG de 192×192 en base64 o null. */
function openCrop(file) {
  return new Promise(async (resolve) => {
    let bmp;
    try { bmp = await createImageBitmap(file, { imageOrientation: 'from-image' }); } catch { toast('No se pudo leer la imagen'); return resolve(null); }
    const S = 280, base = S / Math.min(bmp.width, bmp.height);
    let zoom = 1, ox = 0, oy = 0;
    const cv = h('canvas', { class: 'crop-canvas', width: S * 2, height: S * 2 });
    cv.style.width = S + 'px'; cv.style.height = S + 'px';
    const g = cv.getContext('2d');
    const range = h('input', { type: 'range', min: 1, max: 6, step: 0.01, value: 1, style: { width: S + 'px' } });
    const clamp = () => {
      const mx = Math.max(0, (bmp.width * base * zoom - S) / 2), my = Math.max(0, (bmp.height * base * zoom - S) / 2);
      ox = Math.min(mx, Math.max(-mx, ox)); oy = Math.min(my, Math.max(-my, oy));
    };
    const draw = () => {
      clamp();
      g.setTransform(2, 0, 0, 2, 0, 0); g.clearRect(0, 0, S, S);
      const w = bmp.width * base * zoom, hh = bmp.height * base * zoom;
      g.drawImage(bmp, (S - w) / 2 + ox, (S - hh) / 2 + oy, w, hh);
      g.fillStyle = 'rgba(0,0,0,0.67)'; g.beginPath(); g.rect(0, 0, S, S); g.arc(S / 2, S / 2, S / 2 - 2, 0, Math.PI * 2); g.fill('evenodd');
      g.strokeStyle = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim() || '#F5A9C6';
      g.lineWidth = 2; g.beginPath(); g.arc(S / 2, S / 2, S / 2 - 2, 0, Math.PI * 2); g.stroke();
    };
    range.addEventListener('input', () => { zoom = +range.value; draw(); });
    const ptrs = new Map(); let lastDist = 0;
    cv.addEventListener('pointerdown', (e) => { cv.setPointerCapture(e.pointerId); ptrs.set(e.pointerId, [e.clientX, e.clientY]); lastDist = 0; });
    cv.addEventListener('pointermove', (e) => {
      const prev = ptrs.get(e.pointerId); if (!prev) return;
      ptrs.set(e.pointerId, [e.clientX, e.clientY]);
      if (ptrs.size === 1) { ox += e.clientX - prev[0]; oy += e.clientY - prev[1]; }
      else if (ptrs.size === 2) {
        const [a, b] = [...ptrs.values()], d = Math.hypot(a[0] - b[0], a[1] - b[1]);
        if (lastDist) zoom = Math.min(6, Math.max(1, zoom * d / lastDist));
        lastDist = d; range.value = zoom;
      }
      draw();
    });
    const up = (e) => { ptrs.delete(e.pointerId); lastDist = 0; };
    cv.addEventListener('pointerup', up); cv.addEventListener('pointercancel', up);
    cv.addEventListener('wheel', (e) => { e.preventDefault(); zoom = Math.min(6, Math.max(1, zoom * (e.deltaY < 0 ? 1.08 : 0.92))); range.value = zoom; draw(); }, { passive: false });

    const apply = () => {
      const sc = base * zoom, side = S / sc;
      const cx = bmp.width / 2 - ox / sc, cy = bmp.height / 2 - oy / sc;
      const jpeg = (size, q, max) => {
        const c = document.createElement('canvas'); c.width = c.height = size;
        c.getContext('2d').drawImage(bmp, cx - side / 2, cy - side / 2, side, side, 0, 0, size, size);
        let out;
        do { out = c.toDataURL('image/jpeg', q).split(',')[1]; q -= 0.08; } while (out.length > max && q > 0.3);
        return out;
      };
      // miniatura de 256 px para las listas (< 28.000 caracteres) y foto grande de hasta 800 px (< 140.000) para ampliar
      return { thumb: jpeg(256, 0.82, 28000), full: jpeg(Math.max(256, Math.min(800, Math.round(side))), 0.88, 140000) };
    };
    let result = null;
    modal({
      title: 'Recorta tu foto',
      content: h('div', { class: 'crop-box' }, h('p', { class: 'dim small' }, 'Arrastra y pellizca para encuadrarla'), cv, range),
      actions: [{ label: 'Cancelar' }, { label: 'Aplicar', primary: true, onClick: () => { result = apply(); } }],
      onClose: () => resolve(result),
    });
    draw();
  });
}

export function openSocial(onClose) {
  const body = h('div', { class: 'social-body' });
  const root = h('div', { class: 'pomo social' },
    h('div', { class: 'pomo-top' }, h('button', { class: 'icon-btn', 'aria-label': 'Volver', onclick: () => close() }, icon('back')), h('h1', {}, 'Amigos')),
    body);
  let timers = [], myPhoto = '', ov = null, results = [], query = '';
  const fail = (e) => toast(e.message || 'No se pudo completar');

  function showLogin() {
    let creating = false;
    const email = h('input', { class: 'field', type: 'email', placeholder: 'Correo electrónico', autocomplete: 'email', autocapitalize: 'none', autocorrect: 'off' });
    const user = h('input', { class: 'field', placeholder: 'Nombre de usuario', autocomplete: 'username', autocapitalize: 'none', autocorrect: 'off' });
    const pass = h('input', { class: 'field', type: 'password', placeholder: 'Contraseña', autocomplete: 'current-password' });
    const err = h('p', { class: 'err' }), hint = h('p', { class: 'dim small' });
    const go = h('button', { class: 'btn big' });
    const toggle = h('button', { class: 'text-btn' });
    const forgot = h('button', { class: 'text-btn', onclick: async () => {
      try { await resetPassword(email.value); err.textContent = ''; toast('Te hemos enviado un correo para elegir una contraseña nueva (mira también el spam)', 5000); }
      catch (e) { err.textContent = !email.value.trim() ? 'Escribe arriba tu correo y vuelve a pulsar' : e.message; }
    } }, 'Olvidé mi contraseña');
    const paint = () => {
      go.textContent = creating ? 'Crear cuenta' : 'Entrar';
      toggle.textContent = creating ? 'Ya tengo cuenta' : 'No tengo cuenta: crear una';
      hint.textContent = creating ? 'El nombre de usuario lo ven tus amigos · 3 a 16 letras minúsculas, números o _' : '';
      user.style.display = creating ? '' : 'none';
      forgot.style.display = creating ? 'none' : '';
      pass.autocomplete = creating ? 'new-password' : 'current-password';
    };
    toggle.onclick = () => { creating = !creating; err.textContent = ''; paint(); };
    go.onclick = async () => {
      go.disabled = true; err.textContent = '';
      try {
        await (creating ? signUp(email.value, user.value, pass.value) : signIn(email.value, pass.value));
        startHost(); showPanel();
        if (window.Notification?.permission === 'default') Notification.requestPermission(); // para avisar de los documentos que te lleguen
      }
      catch (e) { err.textContent = e.message; go.disabled = false; }
    };
    paint();
    body.classList.add('narrow');
    body.replaceChildren(
      h('h2', {}, 'Entra en tu perfil'),
      h('p', { class: 'dim' }, 'Añade amigos, mira quién está en la app y mándales un ❤ cada 15 minutos.'),
      email, user, pass, hint, err, go, forgot, toggle,
      h('p', { class: 'dim small' }, 'Tu correo solo sirve para entrar y recuperar la contraseña: nadie lo ve. En la nube se guardan tu nombre de usuario, tu foto y tu última conexión. Tus documentos NO se suben.'));
  }

  function showPanel() {
    body.classList.remove('narrow');
    const me = session;
    const myAvatar = h('button', { class: 'plain', 'aria-label': 'Cambiar foto' });
    const paintMe = () => myAvatar.replaceChildren(avatar(me.username, myPhoto, 60, null, me.uid));
    paintMe();
    const picker = h('input', { type: 'file', accept: 'image/*', style: { display: 'none' } });
    picker.onchange = async () => {
      if (!picker.files[0]) return;
      try {
        const b = await openCrop(picker.files[0]); picker.value = '';
        if (!b) return;
        await setPhoto(b.thumb, b.full); myPhoto = b.thumb; paintMe();
      } catch (e) { toast(e.message || 'No se pudo cambiar la foto'); }
    };
    myAvatar.onclick = () => picker.click();
    const search_ = h('input', { class: 'field', type: 'search', placeholder: 'Buscar personas por nombre de usuario', autocapitalize: 'none' });
    const resultsEl = h('div', {}), incomingEl = h('div', {}), friendsEl = h('div', {});
    let st;
    search_.addEventListener('input', () => {
      clearTimeout(st);
      st = setTimeout(async () => { query = search_.value; try { results = query.trim() ? await search(query) : []; } catch (e) { fail(e); } paintResults(); }, 350);
    });
    const act = (fn) => async () => { try { await fn(); } catch (e) { fail(e); } refresh(); };

    function personRow(p, ...actions) { return h('div', { class: 'person' }, avatar(p.username, p.photo, 42, null, p.uid), h('span', { class: 'grow' }, p.username), ...actions); }
    function paintResults() {
      resultsEl.replaceChildren(...results.map((p) => {
        const isFriend = ov?.friends.some((f) => f.profile.uid === p.uid), pending = ov?.outgoing.has(p.uid), incoming = ov?.incoming.some((i) => i.uid === p.uid);
        return personRow(p,
          isFriend ? h('span', { class: 'dim' }, 'Amigos') : pending ? h('span', { class: 'dim' }, 'Enviada')
            : incoming ? h('button', { class: 'text-btn', onclick: act(() => accept(p.uid)) }, 'Aceptar')
              : h('button', { class: 'icon-btn accent', 'aria-label': 'Añadir', onclick: act(async () => { await request(p.uid); toast('Solicitud enviada a ' + p.username); }) }, icon('userPlus')));
      }), ...(query.trim() && !results.length ? [h('p', { class: 'dim small' }, 'Nadie con ese nombre')] : []));
    }
    function paintLists() {
      if (!ov) { friendsEl.replaceChildren(h('p', { class: 'dim' }, 'Cargando…')); return; }
      incomingEl.replaceChildren(...(ov.incoming.length ? [h('h3', {}, 'Solicitudes'), ...ov.incoming.map((p) => personRow(p,
        h('button', { class: 'icon-btn', style: { color: 'var(--accent)' }, 'aria-label': 'Aceptar', onclick: act(() => accept(p.uid)) }, icon('check')),
        h('button', { class: 'icon-btn', 'aria-label': 'Rechazar', onclick: act(() => decline(p.uid)) }, icon('close'))))] : []));
      friendsEl.replaceChildren(h('h3', {}, 'Mis amigos'), ...(ov.friends.length ? ov.friends.map((f) => {
        const online = f.lastSeen > 0 && Date.now() - f.lastSeen < ONLINE_MS;
        const wait = f.lastPokeAt + COOLDOWN_MS + 2000 - Date.now();
        const mm = Math.max(0, Math.ceil(wait / 1000));
        return h('div', { class: 'person', 'data-uid': f.profile.uid }, avatar(f.profile.username, f.profile.photo, 46, online, f.profile.uid),
          h('div', { class: 'grow' }, h('div', {}, f.profile.username), h('div', { class: 'small seen', 'data-ts': f.lastSeen, style: { color: online ? 'var(--accent)' : 'var(--on-dim)' } }, presence(f.lastSeen).text)),
          h('span', { class: 'dim small cd', 'data-until': f.lastPokeAt + COOLDOWN_MS + 2000 }, wait > 0 ? `${Math.floor(mm / 60)}:${String(mm % 60).padStart(2, '0')}` : ''),
          h('button', { class: 'icon-btn heart', disabled: wait > 0, 'aria-label': 'Mandar un corazón', onclick: act(async () => { await poke(f.profile.uid); toast('❤ enviado a ' + f.profile.username); }) }, icon('heart')),
          h('button', { class: 'icon-btn', 'aria-label': 'Eliminar amigo', onclick: async () => { if (await confirmDialog('Eliminar amigo', `¿Dejar de ser amigo de ${f.profile.username}? Dejaréis de veros en línea y de mandaros corazones.`, 'Eliminar')) act(() => removeFriend(f.profile.uid))(); } }, icon('userMinus')));
      }) : [h('p', { class: 'dim' }, 'Todavía no tienes amigos. Búscalos arriba por su nombre de usuario.')]));
    }
    async function refresh() { try { ov = await overview(); } catch (e) { if (!ov) fail(e); } paintLists(); paintResults(); }

    body.replaceChildren(
      h('div', { class: 'me-card' }, myAvatar, h('div', { class: 'grow' }, h('div', { class: 'strong', style: { fontSize: '20px' } }, me.username), h('div', { class: 'dim small' }, 'Toca la foto para cambiarla · mantenla pulsada para verla grande')),
        h('button', { class: 'text-btn', onclick: () => { stopHost(); signOut(); showLogin(); } }, 'Salir'), picker),
      search_, resultsEl, incomingEl, friendsEl);
    myProfile().then((p) => { myPhoto = p.photo; paintMe(); }).catch(() => {});
    paintLists(); refresh();
    timers.push(setInterval(refresh, 15000));
    // cuenta atrás de los corazones
    timers.push(setInterval(() => root.querySelectorAll('.seen').forEach((el) => paintPresence(el, Number(el.dataset.ts))), 5000));
    timers.push(setInterval(() => root.querySelectorAll('.cd').forEach((el) => {
      const left = Math.ceil((Number(el.dataset.until) - Date.now()) / 1000);
      el.textContent = left > 0 ? `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}` : '';
      const b = el.nextElementSibling; if (b && left <= 0) b.disabled = false;
    }), 1000));
  }

  function close() { timers.forEach(clearInterval); root.remove(); onClose?.(); }
  if (session) showPanel(); else showLogin();
  return root;
}
