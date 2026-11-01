// Parte social: perfil, amigos, presencia (punto verde) y «toques» (corazones). Misma lógica que la app de Android:
// habla con Firebase por su API REST (sin SDK). Las claves no son secretas; la seguridad la ponen las reglas de `firestore.rules`.

import { h, icon, toast, confirmDialog } from './ui.js';

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
const emailOf = (name) => `${name}@evability.app`;

// ───────── red ─────────

function explain(code, text) {
  let msg = ''; try { msg = JSON.parse(text).error.message || ''; } catch { /* sin cuerpo */ }
  if (msg.startsWith('EMAIL_EXISTS')) return 'Ese nombre de usuario ya está cogido';
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

async function enter(endpoint, name, pass) {
  const r = await post(`${AUTH}:${endpoint}?key=${API_KEY}`, { email: emailOf(name), password: pass, returnSecureToken: true });
  return { uid: r.localId, username: name, idToken: r.idToken, refreshToken: r.refreshToken, expiresAt: Date.now() + Number(r.expiresIn) * 1000 };
}

export async function signUp(rawName, pass) {
  const name = cleanName(rawName);
  if (name.length < 3 || name.length > 16) throw new Error('El nombre de usuario debe tener entre 3 y 16 letras, números o _');
  if (pass.length < 6) throw new Error('La contraseña debe tener al menos 6 caracteres');
  const s = await enter('signUp', name, pass);
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

export async function signIn(rawName, pass) {
  const name = cleanName(rawName);
  if (!name || !pass) throw new Error('Escribe tu usuario y contraseña');
  saveSession(await enter('signInWithPassword', name, pass));
  lastShownPoke = -1;
}

export function signOut() { saveSession(null); lastShownPoke = -1; }

export async function myProfile() { const s = await live(); return profile(s.uid, (await getAll('users', [s.uid]))[s.uid]); }
export async function setPhoto(base64) { const s = await live(); await commit(write(`users/${s.uid}`, { username: str(s.username), photo: str(base64) })); }

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
export function showHeart() {
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
  const poll = () => { if (visible()) checkPokes().catch(() => {}); };
  beat(); poll();
  hostTimers = [setInterval(beat, 30000), setInterval(poll, 12000)];
}
export function stopHost() { hostTimers.forEach(clearInterval); hostTimers = []; }
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible' && session && hostTimers.length) { heartbeat().catch(() => {}); checkPokes().catch(() => {}); } });

// ───────── pantalla ─────────

function avatar(name, photo, size = 46, online = null) {
  const inner = photo ? h('img', { src: 'data:image/jpeg;base64,' + photo, alt: '' }) : h('span', {}, name.slice(0, 1).toUpperCase());
  return h('div', { class: 'avatar', style: { width: size + 'px', height: size + 'px', fontSize: size * 0.42 + 'px' } }, inner,
    online === null ? null : h('i', { class: 'dot' + (online ? ' on' : '') }));
}

/** Foto elegida → cuadrado de 96×96 en JPEG y base64 (unos 5 KB). */
async function photoToBase64(file) {
  const bmp = await createImageBitmap(file);
  const side = Math.min(bmp.width, bmp.height);
  const c = document.createElement('canvas'); c.width = c.height = 96;
  c.getContext('2d').drawImage(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side, 0, 0, 96, 96);
  return c.toDataURL('image/jpeg', 0.8).split(',')[1];
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
    const user = h('input', { class: 'field', placeholder: 'Nombre de usuario', autocomplete: 'username', autocapitalize: 'none', autocorrect: 'off' });
    const pass = h('input', { class: 'field', type: 'password', placeholder: 'Contraseña', autocomplete: 'current-password' });
    const err = h('p', { class: 'err' }), hint = h('p', { class: 'dim small' });
    const go = h('button', { class: 'btn big' });
    const toggle = h('button', { class: 'text-btn' });
    const paint = () => {
      go.textContent = creating ? 'Crear cuenta' : 'Entrar';
      toggle.textContent = creating ? 'Ya tengo cuenta' : 'No tengo cuenta: crear una';
      hint.textContent = creating ? '3 a 16 letras minúsculas, números o _' : '';
    };
    toggle.onclick = () => { creating = !creating; err.textContent = ''; paint(); };
    go.onclick = async () => {
      go.disabled = true; err.textContent = '';
      try { await (creating ? signUp(user.value, pass.value) : signIn(user.value, pass.value)); startHost(); showPanel(); }
      catch (e) { err.textContent = e.message; go.disabled = false; }
    };
    paint();
    body.replaceChildren(
      h('h2', {}, 'Entra en tu perfil'),
      h('p', { class: 'dim' }, 'Añade amigos, mira quién está en la app y mándales un ❤ cada 15 minutos.'),
      user, pass, hint, err, go, toggle,
      h('p', { class: 'dim small' }, 'Se guardan en la nube (Firebase) tu nombre de usuario, tu foto y tu última conexión. Tus documentos NO se suben. No hay recuperación de contraseña: si la olvidas, crea otra cuenta.'));
  }

  function showPanel() {
    const me = session;
    const myAvatar = h('button', { class: 'plain', 'aria-label': 'Cambiar foto' });
    const paintMe = () => myAvatar.replaceChildren(avatar(me.username, myPhoto, 60));
    paintMe();
    const picker = h('input', { type: 'file', accept: 'image/*', style: { display: 'none' } });
    picker.onchange = async () => {
      if (!picker.files[0]) return;
      try { const b = await photoToBase64(picker.files[0]); await setPhoto(b); myPhoto = b; paintMe(); } catch { toast('No se pudo cambiar la foto'); }
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

    function personRow(p, ...actions) { return h('div', { class: 'person' }, avatar(p.username, p.photo, 42), h('span', { class: 'grow' }, p.username), ...actions); }
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
        h('button', { class: 'icon-btn', style: { color: '#4CD964' }, 'aria-label': 'Aceptar', onclick: act(() => accept(p.uid)) }, icon('check')),
        h('button', { class: 'icon-btn', 'aria-label': 'Rechazar', onclick: act(() => decline(p.uid)) }, icon('close'))))] : []));
      friendsEl.replaceChildren(h('h3', {}, 'Mis amigos'), ...(ov.friends.length ? ov.friends.map((f) => {
        const online = f.lastSeen > 0 && Date.now() - f.lastSeen < ONLINE_MS;
        const wait = f.lastPokeAt + COOLDOWN_MS + 2000 - Date.now();
        const mm = Math.max(0, Math.ceil(wait / 1000));
        return h('div', { class: 'person', 'data-uid': f.profile.uid }, avatar(f.profile.username, f.profile.photo, 46, online),
          h('div', { class: 'grow' }, h('div', {}, f.profile.username), h('div', { class: 'small', style: { color: online ? '#4CD964' : 'var(--on-dim)' } }, online ? 'En línea' : 'Desconectado')),
          h('span', { class: 'dim small cd', 'data-until': f.lastPokeAt + COOLDOWN_MS + 2000 }, wait > 0 ? `${Math.floor(mm / 60)}:${String(mm % 60).padStart(2, '0')}` : ''),
          h('button', { class: 'icon-btn heart', disabled: wait > 0, 'aria-label': 'Mandar un corazón', onclick: act(async () => { await poke(f.profile.uid); toast('❤ enviado a ' + f.profile.username); }) }, icon('heart')),
          h('button', { class: 'icon-btn', 'aria-label': 'Eliminar amigo', onclick: async () => { if (await confirmDialog('Eliminar amigo', `¿Dejar de ser amigo de ${f.profile.username}? Dejaréis de veros en línea y de mandaros corazones.`, 'Eliminar')) act(() => removeFriend(f.profile.uid))(); } }, icon('userMinus')));
      }) : [h('p', { class: 'dim' }, 'Todavía no tienes amigos. Búscalos arriba por su nombre de usuario.')]));
    }
    async function refresh() { try { ov = await overview(); } catch (e) { if (!ov) fail(e); } paintLists(); paintResults(); }

    body.replaceChildren(
      h('div', { class: 'me-card' }, myAvatar, h('div', { class: 'grow' }, h('div', { class: 'strong', style: { fontSize: '20px' } }, me.username), h('div', { class: 'dim small' }, 'Toca la foto para cambiarla')),
        h('button', { class: 'text-btn', onclick: () => { stopHost(); signOut(); showLogin(); } }, 'Salir'), picker),
      search_, resultsEl, incomingEl, friendsEl);
    myProfile().then((p) => { myPhoto = p.photo; paintMe(); }).catch(() => {});
    paintLists(); refresh();
    timers.push(setInterval(refresh, 15000));
    // cuenta atrás de los corazones
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
