// Utilidades de interfaz: crear elementos, iconos, diálogos y avisos.

export function h(tag, attrs = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === 'html') el.innerHTML = v;
    else if (k === 'value') el.value = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  const add = (kid) => {
    if (kid == null || kid === false) return;
    if (Array.isArray(kid)) kid.forEach(add);
    else el.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
  };
  kids.forEach(add);
  return el;
}

/** Reemplaza el contenido de un elemento ignorando los huecos (null/false). */
export function fill(el, ...kids) {
  el.replaceChildren(...kids.flat(Infinity).filter((k) => k != null && k !== false));
  return el;
}

const P = {
  back: '<path d="M19 12H5M12 19l-7-7 7-7"/>',
  close: '<path d="M18 6 6 18M6 6l12 12"/>',
  check: '<path d="M20 6 9 17l-5-5"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  minus: '<path d="M5 12h14"/>',
  pen: '<path d="M17 3a2.85 2.85 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/>',
  highlighter: '<path d="m9 11-6 6v3h9l3-3"/><path d="m22 12-4.6 4.6a2 2 0 0 1-2.8 0l-5.2-5.2a2 2 0 0 1 0-2.8L14 4"/>',
  eraser: '<path d="m7 21-4.3-4.3c-1-1-1-2.5 0-3.4l9.6-9.6c1-1 2.5-1 3.4 0l5.6 5.6c1 1 1 2.5 0 3.4L13 21"/><path d="M22 21H7"/><path d="m5 11 9 9"/>',
  text: '<path d="M4 7V4h16v3M9 20h6M12 4v16"/>',
  smile: '<circle cx="12" cy="12" r="10"/><path d="M8 14s1.5 2 4 2 4-2 4-2M9 9h.01M15 9h.01"/>',
  note: '<path d="M15.5 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V8.5L15.5 3Z"/><path d="M15 3v6h6"/>',
  cover: '<rect x="3" y="8" width="18" height="8" rx="1.5"/><path d="M7 12h6"/>',
  ruler: '<path d="M21.3 15.3a2.4 2.4 0 0 1 0 3.4l-2.6 2.6a2.4 2.4 0 0 1-3.4 0L2.7 8.7a2.41 2.41 0 0 1 0-3.4l2.6-2.6a2.41 2.41 0 0 1 3.4 0Z"/><path d="m14.5 12.5 2-2M11.5 9.5l2-2M8.5 6.5l2-2M17.5 15.5l2-2"/>',
  hand: '<path d="M18 11V6a2 2 0 0 0-4 0M14 10V4a2 2 0 0 0-4 0v2M10 10.5V6a2 2 0 0 0-4 0v8"/><path d="M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.9-5.9-2.4L3.4 16a2 2 0 0 1 3.2-2.4L8 15"/>',
  undo: '<path d="M3 7v6h6"/><path d="M21 17a9 9 0 0 0-9-9 9 9 0 0 0-6 2.3L3 13"/>',
  redo: '<path d="M21 7v6h-6"/><path d="M3 17a9 9 0 0 1 9-9 9 9 0 0 1 6 2.3l3 2.7"/>',
  zoomIn: '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3M11 8v6M8 11h6"/>',
  zoomOut: '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3M8 11h6"/>',
  filePlus: '<path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2Z"/><path d="M14 2v6h6M12 18v-6M9 15h6"/>',
  copy: '<rect x="8" y="8" width="14" height="14" rx="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>',
  trash: '<path d="M3 6h18M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2M10 11v6M14 11v6"/>',
  touch: '<path d="M9 11V5a2 2 0 0 1 4 0v6"/><path d="M13 10a2 2 0 0 1 4 0v1a2 2 0 0 1 4 0v4a7 7 0 0 1-7 7h-1.5a6 6 0 0 1-4.5-2L4 14a2 2 0 0 1 3-2.6L9 13"/>',
  download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3"/>',
  upload: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12"/>',
  star: '<polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/>',
  folder: '<path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"/>',
  timer: '<path d="M10 2h4M12 14l3-3"/><circle cx="12" cy="14" r="8"/>',
  settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1Z"/>',
  bot: '<rect x="3" y="11" width="18" height="10" rx="2"/><circle cx="12" cy="5" r="2"/><path d="M12 7v4M8 16h.01M16 16h.01"/>',
  search: '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>',
  sort: '<path d="M3 6h18M6 12h12M10 18h4"/>',
  grid: '<rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/>',
  more: '<circle cx="12" cy="5" r="1.2"/><circle cx="12" cy="12" r="1.2"/><circle cx="12" cy="19" r="1.2"/>',
  restore: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/>',
  play: '<polygon points="6 3 20 12 6 21 6 3" fill="currentColor"/>',
  stop: '<rect x="5" y="5" width="14" height="14" rx="2" fill="currentColor"/>',
  summary: '<path d="M4 6h16M4 12h16M4 18h10"/>',
  globe: '<circle cx="12" cy="12" r="10"/><path d="M2 12h20M12 2a15 15 0 0 1 0 20 15 15 0 0 1 0-20"/>',
  chevUp: '<path d="m18 15-6-6-6 6"/>',
  chevDown: '<path d="m6 9 6 6 6-6"/>',
  rotCw: '<path d="M21 12a9 9 0 1 1-3-6.7L21 8"/><path d="M21 3v5h-5"/>',
  rotCcw: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/>',
  link: '<path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/>',
};

export function icon(name, size = 22) {
  const s = h('span', { class: 'ic', style: { width: size + 'px', height: size + 'px' } });
  s.innerHTML = `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${P[name] || ''}</svg>`;
  return s;
}

export function uid() {
  return (crypto.randomUUID ? crypto.randomUUID() : 'id-' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10));
}

// ───────── avisos ─────────

let toastEl, toastTimer;
export function toast(msg, ms = 2600) {
  if (!toastEl) { toastEl = h('div', { class: 'toast' }); document.body.append(toastEl); }
  toastEl.textContent = msg;
  toastEl.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toastEl.classList.remove('show'), ms);
}

/** Aviso con botón de acción (p. ej. «Deshacer»). Devuelve una promesa que dice si se pulsó la acción. */
export function snackbar(msg, actionLabel, ms = 7000) {
  return new Promise((resolve) => {
    const el = h('div', { class: 'snackbar' }, h('span', {}, msg),
      h('button', { class: 'text-btn', onclick: () => { clearTimeout(t); el.remove(); resolve(true); } }, actionLabel));
    document.body.append(el);
    const t = setTimeout(() => { el.remove(); resolve(false); }, ms);
  });
}

// ───────── diálogos ─────────

/** Ventana modal genérica. `content` es un nodo; `actions` botones [{label, primary, onClick}] (onClick devuelve false para no cerrar). */
export function modal({ title, content, actions = [], wide = false, onClose } = {}) {
  const overlay = h('div', { class: 'overlay' });
  const close = (v) => { overlay.remove(); onClose?.(v); };
  const sheet = h('div', { class: 'sheet' + (wide ? ' wide' : '') },
    title ? h('h2', {}, title) : null,
    h('div', { class: 'sheet-body' }, content),
    actions.length ? h('div', { class: 'sheet-actions' }, actions.map((a) =>
      h('button', {
        class: a.primary ? 'text-btn strong' : 'text-btn',
        onclick: async () => { if ((await a.onClick?.()) !== false) close(a.value); },
      }, a.label))) : null);
  overlay.addEventListener('pointerdown', (e) => { if (e.target === overlay) close(null); });
  overlay.append(sheet);
  document.body.append(overlay);
  return { el: sheet, close };
}

export function confirmDialog(title, text, okLabel = 'Aceptar') {
  return new Promise((resolve) => {
    modal({
      title, content: h('p', { class: 'dim' }, text),
      actions: [{ label: 'Cancelar', value: false }, { label: okLabel, primary: true, value: true }],
      onClose: (v) => resolve(v === true),
    });
  });
}

export function promptDialog({ title, label = '', initial = '', confirm = 'Aceptar', multiline = false }) {
  return new Promise((resolve) => {
    const input = multiline
      ? h('textarea', { class: 'field', rows: 4, placeholder: label }, initial)
      : h('input', { class: 'field', type: 'text', placeholder: label, value: initial });
    let result = null;
    const m = modal({
      title, content: input,
      actions: [{ label: 'Cancelar' }, { label: confirm, primary: true, onClick: () => { result = input.value; } }],
      onClose: () => resolve(result),
    });
    setTimeout(() => { input.focus(); input.select?.(); }, 50);
    if (!multiline) input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { result = input.value; m.close(); } });
  });
}

/** Menú contextual sencillo: lista de [etiqueta, función] bajo un elemento. */
export function menu(anchor, items) {
  document.querySelectorAll('.menu').forEach((m) => m.remove());
  const r = anchor.getBoundingClientRect();
  const side = r.left < 230 ? { left: Math.max(8, r.left) + 'px' } : { right: Math.max(8, innerWidth - r.right) + 'px' };
  const el = h('div', { class: 'menu', style: { top: Math.min(r.bottom + 4, innerHeight - 44 * items.length - 12) + 'px', ...side } },
    items.map(([label, fn, extra]) => h('button', { class: extra || '', onclick: () => { el.remove(); fn(); } }, label)));
  const off = (e) => { if (!el.contains(e.target)) { el.remove(); document.removeEventListener('pointerdown', off, true); } };
  setTimeout(() => document.addEventListener('pointerdown', off, true), 0);
  document.body.append(el);
}

export function ago(t) {
  const s = Math.round((Date.now() - t) / 1000);
  if (s < 60) return 'ahora mismo';
  const m = Math.round(s / 60);
  if (m < 60) return `hace ${m} min`;
  const hr = Math.round(m / 60);
  if (hr < 24) return `hace ${hr} h`;
  const d = Math.round(hr / 24);
  return d === 1 ? 'ayer' : `hace ${d} días`;
}
