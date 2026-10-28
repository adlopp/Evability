// Editor de documentos: visor de páginas con zoom, herramientas de anotación, selección de texto y exportación.

import { h, icon, fill, toast, modal, confirmDialog, promptDialog, menu } from './ui.js';
import * as store from './store.js';
import * as ann from './ann.js';
import { openPdf, pageSizes, renderToCanvas } from './pdfdoc.js';
import { templateCanvas } from './templates.js';
import { PACKS, EMOJI } from './stickers.js';
import { exportPdf } from './export.js';

const MIN_ZOOM = 0.5, MAX_ZOOM = 3;
const MAX_PX = 2000; // ancho máximo en píxeles de una página dibujada (cuida la memoria del iPad)
const DRAW_TOOLS = ['pen', 'hl', 'eraser', 'cover'];

export class Editor {
  constructor(info, onClose) {
    this.info = info;
    this.onClose = onClose;
    this.tool = 'pen';
    this.penColor = '#1E88E5'; this.hlColor = '#FFF59D'; this.textColor = '#111111';
    this.penWidth = 0.003; this.hlWidth = 0.024; this.textSize = 0.028; this.eraserRadius = 0.02; this.postitSize = 0.3;
    this.stickerSrc = null; this.stickerTab = 0;
    this.straight = false; this.fingerDraw = false;
    this.zoom = 1;
    this.undoStack = []; this.redoStack = [];
    this.selected = null; // id de la anotación elegida (pegatina, texto o post-it)
    this.views = []; this.queue = []; this.pumping = false;
    this.position = info.lastPage || 0;
    this.dirty = false; this.erasing = false;
  }

  // ───────── arranque ─────────

  async init() {
    const bytes = await store.pdfBytes(this.info.id);
    this.pdf = await openPdf(bytes);
    this.sizes = await pageSizes(this.pdf);
    this.anns = await store.anns(this.info.id);
    this.extraKinds = Array.from({ length: this.info.extra }, (_, i) => this.info.extraKinds?.[i] || '');
    this.hidden = [...(this.info.hidden || [])];
    this.templateKind = this.detectTemplate();

    this.root = h('div', { class: 'editor' });
    this.topbar = h('div', { class: 'topbar' });
    this.optbar = h('div', { class: 'optbar' });
    this.viewer = h('div', { class: 'viewer' });
    this.pagesEl = h('div', { class: 'pages' });
    this.counter = h('div', { class: 'pagecount' });
    this.selbar = h('div', { class: 'floatbar', style: { display: 'none' } });
    this.objbar = h('div', { class: 'floatbar', style: { display: 'none' } });
    this.viewer.append(this.pagesEl);
    this.root.append(this.topbar, this.optbar, this.viewer, this.counter, this.selbar, this.objbar);
    this.showBars = true;

    ann.setImageReadyHandler(() => this.redrawAll());
    this.renderToolbar();
    this.renderOptions();
    this.bindViewer();
    return this.root;
  }

  /** Se llama cuando el editor ya está en el documento (necesita medidas reales). */
  start() {
    this.buildPages();
    const pos = Math.max(0, Math.min(this.position, this.views.length - 1));
    const target = this.views[pos];
    if (target) this.viewer.scrollTop = target.el.offsetTop - 16;
    this.updateVisible();
    this.saveTimer = setInterval(() => { if (this.dirty) this.saveNow(); }, 1500);
    this.onResize = () => { this.layout(); this.updateVisible(); };
    window.addEventListener('resize', this.onResize);
    document.addEventListener('selectionchange', this.onSelection = () => this.updateSelectionBar());
  }

  detectTemplate() {
    const t = this.info.template || { 'Plan mensual': 'MONTHLY', 'Plan semanal': 'WEEKLY' }[this.info.name] || '';
    return t === 'MONTHLY' || t === 'WEEKLY' ? t : '';
  }

  get total() { return this.pdf.numPages + this.extraKinds.length; }
  visiblePages() { return [...Array(this.total).keys()].filter((i) => !this.hidden.includes(i)); }
  aspect(i) { const s = this.sizes[i < this.sizes.length ? i : 0]; return s[1] / s[0]; }

  // ───────── páginas ─────────

  buildPages() {
    this.views.forEach((v) => this.release(v));
    this.pagesEl.replaceChildren();
    this.views = this.visiblePages().map((index) => {
      const v = { index, el: h('div', { class: 'page' }), main: h('canvas', { class: 'main' }), text: h('div', { class: 'textLayer' }), base: null, key: '', busy: false, live: null };
      v.el.append(v.main, v.text);
      v.el.addEventListener('pointerdown', (e) => this.onPointerDown(e, v));
      this.pagesEl.append(v.el);
      return v;
    });
    this.addBtn = h('button', { class: 'btn outline add-page', onclick: () => this.addBlankPage() }, icon('plus', 20), 'Añadir página en blanco');
    this.pagesEl.append(this.addBtn);
    this.layout();
    this.renderToolbar(); // el botón de eliminar página depende de cuántas páginas hay
  }

  layout() {
    const vw = this.viewer.clientWidth || 800;
    this.pageW = (vw - 32) * this.zoom;
    this.pagesEl.style.width = Math.max(vw, this.pageW + 32) + 'px';
    for (const v of this.views) {
      v.el.style.width = this.pageW + 'px';
      v.el.style.height = this.pageW * this.aspect(v.index) + 'px';
    }
    this.counterUpdate();
  }

  release(v) {
    v.main.width = 0; v.main.height = 0;
    v.base = null; v.key = '';
    v.text.replaceChildren();
  }

  updateVisible() {
    const r = this.viewer.getBoundingClientRect();
    const margin = r.height * 1.2, far = r.height * 3.5;
    for (const v of this.views) {
      const b = v.el.getBoundingClientRect();
      if (b.bottom > r.top - margin && b.top < r.bottom + margin) {
        const key = Math.round(this.pageW) + '|' + this.renderVersion;
        if (v.key !== key && !this.queue.includes(v)) { this.queue.push(v); }
      } else if ((b.bottom < r.top - far || b.top > r.bottom + far) && v.base) this.release(v);
    }
    this.queue.sort((a, b) => Math.abs(a.el.getBoundingClientRect().top - r.top) - Math.abs(b.el.getBoundingClientRect().top - r.top));
    this.pump();
  }

  async pump() {
    if (this.pumping) return;
    this.pumping = true;
    try {
      while (this.queue.length) {
        const v = this.queue.shift();
        if (!this.views.includes(v)) continue;
        await this.render(v);
      }
    } finally { this.pumping = false; }
  }

  async render(v) {
    const key = Math.round(this.pageW) + '|' + this.renderVersion;
    if (v.key === key) return;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const pxW = Math.min(Math.round(this.pageW * dpr), MAX_PX);
    try {
      let base;
      if (v.index < this.pdf.numPages) base = await renderToCanvas(this.pdf, v.index + 1, pxW);
      else {
        const kind = this.extraKinds[v.index - this.pdf.numPages];
        if (kind) base = templateCanvas(kind, pxW);
        else {
          base = document.createElement('canvas');
          base.width = pxW; base.height = Math.round(pxW * this.aspect(v.index));
          const c = base.getContext('2d'); c.fillStyle = '#fff'; c.fillRect(0, 0, base.width, base.height);
        }
      }
      if (!this.views.includes(v)) return;
      v.base = base; v.key = key;
      v.main.width = base.width; v.main.height = base.height;
      this.redraw(v);
      if (v.index < this.pdf.numPages) await this.buildTextLayer(v);
    } catch (e) {
      console.error('No se pudo dibujar la página', e);
    }
  }

  async buildTextLayer(v) {
    const page = await this.pdf.getPage(v.index + 1);
    const tc = await page.getTextContent();
    const base = page.getViewport({ scale: 1 });
    const scale = this.pageW / base.width;
    v.text.replaceChildren();
    v.text.style.setProperty('--scale-factor', String(scale));
    await pdfjsLib.renderTextLayer({ textContentSource: tc, container: v.text, viewport: page.getViewport({ scale }), textDivs: [] }).promise;
  }

  get renderVersion() { return this._rv || 0; }
  invalidateRenders() { this._rv = (this._rv || 0) + 1; }

  redraw(v) {
    if (!v.base) return;
    const ctx = v.main.getContext('2d');
    const w = v.main.width;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.drawImage(v.base, 0, 0);
    ann.drawAll(ctx, this.anns.filter((a) => a.page === v.index), w);
    if (v.live) {
      const L = v.live;
      if (L.kind === 'stroke') ann.drawStroke(ctx, this.straight ? ann.straightLine(L.pts) : L.pts, L.color, L.width, L.hl, w);
      else if (L.kind === 'rect') {
        ctx.fillStyle = 'rgba(245,169,198,0.4)';
        const x = Math.min(L.a[0], L.b[0]) * w, y = Math.min(L.a[1], L.b[1]) * w;
        ctx.fillRect(x, y, Math.abs(L.a[0] - L.b[0]) * w, Math.abs(L.a[1] - L.b[1]) * w);
      }
    }
    const sel = this.selected && this.anns.find((a) => a.id === this.selected && a.page === v.index);
    if (sel) this.drawOutline(ctx, sel, w);
  }

  drawOutline(ctx, a, w) {
    ctx.save();
    ctx.strokeStyle = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim() || '#F5A9C6';
    ctx.lineWidth = Math.max(2, w / 400);
    ctx.setLineDash([w / 90, w / 140]);
    if (a.t === 'k' || a.t === 'p') {
      ctx.translate(a.x * w, a.y * w);
      ctx.rotate(a.rot * Math.PI / 180);
      const s = a.size * w;
      ctx.strokeRect(-s / 2, -s / 2, s, s);
    } else if (a.t === 't') {
      ctx.font = `${a.size * w}px -apple-system, Arial, sans-serif`;
      const lines = a.text.split('\n');
      const width = Math.max(...lines.map((l) => ctx.measureText(l).width));
      ctx.strokeRect(a.x * w - 6, a.y * w - 6, width + 12, a.size * w * 1.17 * lines.length + 12);
    }
    ctx.restore();
  }

  redrawAll() { this.views.forEach((v) => this.redraw(v)); }

  invalidate(v) {
    if (v.raf) return;
    v.raf = requestAnimationFrame(() => { v.raf = 0; this.redraw(v); });
  }

  // ───────── anotaciones (con deshacer) ─────────

  checkpoint() {
    this.undoStack.push(this.anns);
    if (this.undoStack.length > 100) this.undoStack.shift();
    this.redoStack = [];
    this.renderToolbar();
  }

  setAnns(list) { this.anns = list; this.dirty = true; this.redrawAll(); }
  add(a) { this.checkpoint(); this.setAnns([...this.anns, a]); }
  addAll(list) { this.checkpoint(); this.setAnns([...this.anns, ...list]); }
  remove(id) { this.checkpoint(); this.setAnns(this.anns.filter((a) => a.id !== id)); }
  replace(a) { this.setAnns(this.anns.map((x) => (x.id === a.id ? a : x))); }
  nextId() { return Date.now() * 1000 + Math.floor(Math.random() * 1000); }

  undo() { if (!this.undoStack.length) return; this.redoStack.push(this.anns); this.setAnns(this.undoStack.pop()); this.afterHistory(); }
  redo() { if (!this.redoStack.length) return; this.undoStack.push(this.anns); this.setAnns(this.redoStack.pop()); this.afterHistory(); }
  afterHistory() { if (this.selected && !this.anns.some((a) => a.id === this.selected)) this.select(null); this.renderToolbar(); }

  async saveNow() {
    this.dirty = false;
    await store.saveAnns(this.info.id, this.anns);
  }

  // ───────── punteros ─────────

  norm(e, v) {
    const r = v.el.getBoundingClientRect();
    return [(e.clientX - r.left) / r.width, (e.clientY - r.top) / r.width];
  }

  /** Objeto que un dedo puede arrastrar con la herramienta actual (el resto del tiempo el dedo desplaza la página). */
  touchTarget(v, p) {
    const only = { text: 'text', sticker: 'sticker', postit: 'postit' }[this.tool];
    if (this.tool === 'hand') return ann.hit(this.anns, v.index, p);
    return only ? ann.hit(this.anns, v.index, p, only) : null;
  }

  pageAt(x, y) {
    const el = document.elementFromPoint(x, y)?.closest?.('.page');
    return el ? this.views.find((v) => v.el === el) : null;
  }

  onPointerDown(e, v) {
    if (e.button > 0 && e.pointerType === 'mouse') return;
    const type = e.pointerType;
    const tool = type === 'pen' && (e.buttons & 32) ? 'eraser' : this.tool;
    const canDraw = type !== 'touch' || this.fingerDraw;
    const p0 = this.norm(e, v);

    if (!canDraw) {
      const target = this.touchTarget(v, p0);
      if (target) this.dragObject(e, v, target, p0);
      return; // si no hay objeto, el dedo desplaza la página o selecciona texto de forma nativa
    }
    e.preventDefault();
    v.el.setPointerCapture?.(e.pointerId);

    if (tool === 'pen' || tool === 'hl') this.drawStroke(e, v, tool === 'hl', p0);
    else if (tool === 'eraser') this.eraseGesture(e, v, p0);
    else if (tool === 'cover') this.coverGesture(e, v, p0);
    else {
      const only = { text: 'text', sticker: 'sticker', postit: 'postit' }[tool] || null;
      const target = ann.hit(this.anns, v.index, p0, tool === 'hand' ? null : only);
      if (target) this.dragObject(e, v, target, p0);
      else if (tool === 'hand') this.select(null);
      else this.tapGesture(e, v, p0, tool);
    }
  }

  /** Sigue al puntero hasta que se levanta; `onMove(p, ev)` en cada movimiento y `onUp(ev, moved)` al final. */
  track(e, v, onMove, onUp) {
    const id = e.pointerId, start = [e.clientX, e.clientY];
    let moved = false;
    const move = (ev) => {
      if (ev.pointerId !== id) return;
      if (!moved && Math.hypot(ev.clientX - start[0], ev.clientY - start[1]) > 6) moved = true;
      const evs = ev.getCoalescedEvents ? ev.getCoalescedEvents() : [];
      (evs.length ? evs : [ev]).forEach((c) => onMove(this.norm(c, v), c, moved));
    };
    const up = (ev) => {
      if (ev.pointerId !== id) return;
      v.el.removeEventListener('pointermove', move);
      v.el.removeEventListener('pointerup', up);
      v.el.removeEventListener('pointercancel', up);
      onUp(ev, moved);
    };
    v.el.addEventListener('pointermove', move);
    v.el.addEventListener('pointerup', up);
    v.el.addEventListener('pointercancel', up);
  }

  drawStroke(e, v, hl, p0) {
    const color = hl ? this.hlColor : this.penColor, width = hl ? this.hlWidth : this.penWidth;
    const onPost = !hl ? ann.hit(this.anns, v.index, p0, 'postit') : null;
    v.live = { kind: 'stroke', pts: [p0], color, width, hl };
    this.invalidate(v);
    this.track(e, v, (p) => { v.live.pts.push(p); this.invalidate(v); }, () => {
      const L = v.live; v.live = null;
      const pts = this.straight ? ann.straightLine(L.pts) : L.pts;
      if (onPost) {
        const ps = this.anns.find((a) => a.id === onPost.id);
        this.checkpoint();
        this.replace({ ...ps, strokes: [...ps.strokes, { color, width: width / ps.size, hl: false, pts: pts.map((p) => ann.toLocal(ps, p)) }] });
        this.dirty = true;
      } else this.add({ t: 's', id: this.nextId(), page: v.index, hl, color, width, pts });
      this.invalidate(v);
    });
  }

  eraseGesture(e, v, p0) {
    this.erasing = false;
    this.eraseAt(v, p0);
    this.track(e, v, (p) => this.eraseAt(v, p), () => { this.erasing = false; });
  }

  eraseAt(v, p) {
    const r = this.eraserRadius;
    const hits = this.anns.filter((a) => a.page === v.index && (
      (a.t === 's' && ann.distToPoints(a.pts, p) <= r + a.width / 2) ||
      (a.t === 't' && ann.hitText(a, p)) || (a.t === 'k' && ann.hitSticker(a, p)) || (a.t === 'c' && ann.hitCover(a, p))));
    // Un tapado solo se borra si no hay nada más bajo el borrador.
    const nonCover = hits.filter((a) => a.t !== 'c');
    const ids = new Set((nonCover.length ? nonCover : hits).map((a) => a.id));
    let stroked = false;
    const after = this.anns.map((a) => {
      if (a.t === 'p' && a.page === v.index && a.strokes.length) {
        const lp = ann.toLocal(a, p), rl = r / a.size;
        const kept = a.strokes.filter((s) => ann.distToPoints(s.pts, lp) > rl + s.width / 2);
        if (kept.length !== a.strokes.length) { stroked = true; return { ...a, strokes: kept }; }
      }
      return a;
    });
    if (!ids.size && !stroked) return;
    if (!this.erasing) { this.checkpoint(); this.erasing = true; }
    this.setAnns(after.filter((a) => !ids.has(a.id)));
  }

  coverGesture(e, v, p0) {
    const existing = ann.hit(this.anns, v.index, p0, 'cover');
    if (existing) return this.dragObject(e, v, existing, p0);
    let cur = p0;
    v.live = { kind: 'rect', a: p0, b: p0 };
    this.track(e, v, (p) => { cur = p; v.live.b = p; this.invalidate(v); }, async () => {
      v.live = null; this.invalidate(v);
      const x0 = Math.min(p0[0], cur[0]), y0 = Math.min(p0[1], cur[1]), x1 = Math.max(p0[0], cur[0]), y1 = Math.max(p0[1], cur[1]);
      if (x1 - x0 < 0.015 || y1 - y0 < 0.006) return;
      const bg = v.base ? ann.sampleBackground(v.base, x0, y0, x1, y1) : '#ffffff';
      const pad = 0.003;
      const cover = { t: 'c', id: this.nextId(), page: v.index, x: x0 - pad, y: y0 - pad, w: x1 - x0 + 2 * pad, h: y1 - y0 + 2 * pad, color: bg };
      const size = Math.max(0.012, Math.min(0.1, (y1 - y0) * 0.8));
      const text = await promptDialog({ title: 'Texto nuevo', label: 'Escribe el texto que sustituye al tapado', multiline: true, confirm: 'Guardar' });
      this.checkpoint();
      const list = [...this.anns, cover];
      if (text && text.trim()) list.push({ t: 't', id: this.nextId(), page: v.index, text, color: this.textColor, size, x: x0, y: y0 + (y1 - y0 - size * 1.17) / 2 });
      this.setAnns(list);
    });
  }

  /** Toque corto en una zona vacía: texto, pegatina o post-it nuevos. */
  tapGesture(e, v, p0, tool) {
    this.track(e, v, () => {}, (ev, moved) => {
      if (moved) return;
      this.select(null);
      if (tool === 'text') this.editText(null, v, p0);
      else if (tool === 'sticker') {
        if (!this.stickerSrc) return toast('Elige primero una pegatina de la barra de abajo');
        const a = { t: 'k', id: this.nextId(), page: v.index, src: this.stickerSrc, x: p0[0], y: p0[1], size: 0.16, rot: 0 };
        this.add(a); this.select(a.id);
      } else if (tool === 'postit') {
        const half = this.postitSize / 2;
        const a = { t: 'p', id: this.nextId(), page: v.index, x: Math.min(1 - half, Math.max(half, p0[0])), y: Math.max(half, p0[1]), size: this.postitSize, rot: 0, strokes: [] };
        this.add(a); this.setTool('pen');
      }
    });
  }

  dragObject(e, v, target, p0) {
    e.preventDefault();
    v.el.setPointerCapture?.(e.pointerId);
    let last = p0, began = false, id = target.id;
    this.track(e, v, (p, _ev, moved) => {
      if (!moved) return;
      if (!began) { began = true; this.checkpoint(); }
      const cur = this.anns.find((a) => a.id === id);
      if (cur) this.replace(ann.moved(cur, p[0] - last[0], p[1] - last[1]));
      last = p;
      this.dirty = true;
    }, (_ev, moved) => {
      if (moved) return;
      if (target.t === 't') this.editText(target, v, p0);
      else if (target.t === 'k' || target.t === 'p') this.select(target.id);
      else this.select(null);
    });
  }

  select(id) {
    this.selected = id;
    this.renderObjBar();
    this.redrawAll();
  }

  transformSelected(zoom, rot) {
    const a = this.anns.find((x) => x.id === this.selected);
    if (!a) return;
    this.checkpoint();
    if (a.t === 'k') this.replace({ ...a, size: Math.max(0.03, Math.min(1.2, a.size * zoom)), rot: a.rot + rot });
    else if (a.t === 'p') this.replace({ ...a, size: Math.max(0.12, Math.min(1, a.size * zoom)), rot: a.rot + rot });
    else if (a.t === 't') this.replace({ ...a, size: Math.max(0.01, Math.min(0.2, a.size * zoom)) });
    this.dirty = true;
  }

  async editText(existing, v, p) {
    const area = h('textarea', { class: 'field', rows: 4, placeholder: 'Escribe aquí' }, existing ? existing.text : '');
    let result = null;
    modal({
      title: existing ? 'Editar texto' : 'Texto nuevo', content: area,
      actions: [
        { label: 'Cancelar' },
        existing && { label: 'Eliminar', onClick: () => { result = ''; } },
        { label: 'Guardar', primary: true, onClick: () => { result = area.value; } },
      ].filter(Boolean),
      onClose: () => {
        if (result === null) return;
        const t = result.trim() ? result : '';
        if (existing) {
          if (!t) this.remove(existing.id); else { this.checkpoint(); this.replace({ ...existing, text: t }); this.dirty = true; }
        } else if (t) {
          const a = { t: 't', id: this.nextId(), page: v.index, text: t, color: this.textColor, size: this.textSize, x: p[0], y: p[1] - this.textSize * 0.6 };
          this.add(a); this.select(a.id);
        }
      },
    });
    setTimeout(() => area.focus(), 60);
  }

  // ───────── gestos del visor: desplazamiento con lápiz bloqueado, zoom con dos dedos ─────────

  bindViewer() {
    const viewer = this.viewer;
    viewer.addEventListener('scroll', () => {
      cancelAnimationFrame(this.scrollRaf);
      this.scrollRaf = requestAnimationFrame(() => { this.updateVisible(); this.counterUpdate(); });
    }, { passive: true });

    const blocks = (t) => {
      if (t.touchType === 'stylus') return true; // el Apple Pencil dibuja, no desplaza
      const v = this.pageAt(t.clientX, t.clientY);
      if (!v) return false;
      if (this.fingerDraw) return true; // con «dibujar con el dedo» el dedo actúa como el lápiz
      const r = v.el.getBoundingClientRect();
      return !!this.touchTarget(v, [(t.clientX - r.left) / r.width, (t.clientY - r.top) / r.width]);
    };
    viewer.addEventListener('touchstart', (e) => {
      if (e.touches.length === 2) {
        const [a, b] = e.touches;
        this.pinch = { d0: Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY), z0: this.zoom };
        return;
      }
      if (e.touches.length === 1 && blocks(e.touches[0])) e.preventDefault();
    }, { passive: false });
    viewer.addEventListener('touchmove', (e) => {
      if (this.pinch && e.touches.length === 2) {
        e.preventDefault();
        const [a, b] = e.touches;
        const s = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY) / this.pinch.d0;
        const cx = (a.clientX + b.clientX) / 2, cy = (a.clientY + b.clientY) / 2;
        this.pinch.next = { z: this.pinch.z0 * s, cx, cy };
        if (!this.pinch.raf) this.pinch.raf = requestAnimationFrame(() => {
          if (!this.pinch) return;
          this.pinch.raf = 0;
          const n = this.pinch.next;
          this.setZoom(n.z, n.cx, n.cy, false);
        });
        return;
      }
      if (e.touches.length === 1 && e.touches[0].touchType === 'stylus') e.preventDefault();
    }, { passive: false });
    const end = (e) => {
      if (this.pinch && e.touches.length < 2) { this.pinch = null; this.invalidateRenders(); this.updateVisible(); }
    };
    viewer.addEventListener('touchend', end, { passive: true });
    viewer.addEventListener('touchcancel', end, { passive: true });
    viewer.addEventListener('wheel', (e) => {
      if (!e.ctrlKey) return;
      e.preventDefault();
      this.setZoom(this.zoom * (e.deltaY < 0 ? 1.1 : 0.9), e.clientX, e.clientY);
    }, { passive: false });
  }

  setZoom(z, cx, cy, render = true) {
    z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
    if (Math.abs(z - this.zoom) < 0.001) return;
    const r = this.viewer.getBoundingClientRect();
    const ax = (cx ?? r.left + r.width / 2) - r.left, ay = (cy ?? r.top + r.height / 2) - r.top;
    const k = z / this.zoom;
    const x = this.viewer.scrollLeft + ax, y = this.viewer.scrollTop + ay;
    this.zoom = z;
    this.layout();
    this.viewer.scrollLeft = x * k - ax;
    this.viewer.scrollTop = y * k - ay;
    this.renderToolbar();
    if (render) { this.invalidateRenders(); this.updateVisible(); }
  }

  counterUpdate() {
    const r = this.viewer.getBoundingClientRect(), line = r.top + r.height * 0.3;
    let pos = 0;
    this.views.forEach((v, i) => { if (v.el.getBoundingClientRect().top <= line) pos = i; });
    this.position = pos;
    this.counter.textContent = `${pos + 1} / ${this.views.length}`;
  }

  // ───────── páginas: añadir, duplicar plantilla, eliminar ─────────

  async addExtra(kind) {
    this.extraKinds = [...this.extraKinds, kind];
    await store.setExtra(this.info.id, this.extraKinds.length, this.extraKinds);
    this.buildPages();
    this.updateVisible();
    setTimeout(() => { this.viewer.scrollTop = this.viewer.scrollHeight; }, 60);
  }
  addBlankPage() { return this.addExtra(''); }
  addTemplatePage() { return this.templateKind && this.addExtra(this.templateKind); }

  async deleteCurrentPage() {
    if (this.views.length <= 1) return toast('No se puede eliminar la única página');
    const pos = this.position, v = this.views[pos];
    if (!(await confirmDialog('Eliminar página', `¿Eliminar la página ${pos + 1} de ${this.views.length}? Desaparecerá del documento y de la exportación a PDF.`, 'Eliminar'))) return;
    this.hidden = [...this.hidden, v.index];
    await store.setHidden(this.info.id, this.hidden);
    this.buildPages();
    this.updateVisible();
  }

  // ───────── texto seleccionado (selección nativa de Safari sobre la capa de texto) ─────────

  currentSelection() {
    const sel = getSelection();
    if (!sel || sel.isCollapsed || !sel.rangeCount) return null;
    const range = sel.getRangeAt(0);
    const node = range.commonAncestorContainer;
    const el = node.nodeType === 1 ? node : node.parentElement;
    if (!el?.closest('.textLayer')?.closest('.editor') && !el?.querySelector?.('.textLayer')) return null;
    const text = sel.toString().replace(/\s+/g, ' ').trim();
    return text ? { text, range } : null;
  }

  updateSelectionBar() {
    clearTimeout(this.selTimer);
    this.selTimer = setTimeout(() => {
      const s = this.currentSelection();
      if (!s) { this.selbar.style.display = 'none'; return; }
      this.selbar.replaceChildren(
        h('span', { class: 'dim ellipsis' }, s.text),
        h('button', { class: 'text-btn', onclick: () => this.copySelection(s.text) }, icon('copy', 18), ' Copiar'),
        h('button', { class: 'text-btn', onclick: () => this.highlightSelection() }, icon('highlighter', 18), ' Subrayar'),
        h('button', { class: 'icon-btn', title: 'Cerrar', onclick: () => { getSelection().removeAllRanges(); this.selbar.style.display = 'none'; } }, icon('close', 20)));
      this.selbar.style.display = 'flex';
    }, 120);
  }

  async copySelection(text) {
    try { await navigator.clipboard.writeText(text); } catch {
      const ta = h('textarea', { style: { position: 'fixed', opacity: 0 } }, text);
      document.body.append(ta); ta.select(); document.execCommand('copy'); ta.remove();
    }
    toast('Texto copiado');
    getSelection().removeAllRanges();
    this.selbar.style.display = 'none';
  }

  /** Subraya con el resaltador las líneas del texto seleccionado. */
  highlightSelection() {
    const s = this.currentSelection();
    if (!s) return;
    const strokes = [];
    for (const v of this.views) {
      const pr = v.el.getBoundingClientRect();
      const rects = [...s.range.getClientRects()]
        .filter((r) => r.width > 1 && r.height > 1 && r.top >= pr.top - 2 && r.bottom <= pr.bottom + 2 && r.left >= pr.left - 2 && r.right <= pr.right + 2);
      // Un trazo por línea: se unen los rectángulos que comparten altura.
      const lines = [];
      for (const r of rects) {
        const cy = (r.top + r.bottom) / 2;
        const line = lines.find((l) => Math.abs(l.cy - cy) < r.height * 0.5);
        if (line) { line.l = Math.min(line.l, r.left); line.r = Math.max(line.r, r.right); line.t = Math.min(line.t, r.top); line.b = Math.max(line.b, r.bottom); line.cy = (line.t + line.b) / 2; }
        else lines.push({ cy, l: r.left, r: r.right, t: r.top, b: r.bottom });
      }
      for (const l of lines) {
        strokes.push({ t: 's', id: this.nextId() + strokes.length, page: v.index, hl: true, color: this.hlColor, width: ((l.b - l.t) * 1.05) / pr.width,
          pts: [[(l.l - pr.left) / pr.width, ((l.t + l.b) / 2 - pr.top) / pr.width], [(l.r - pr.left) / pr.width, ((l.t + l.b) / 2 - pr.top) / pr.width]] });
      }
    }
    if (!strokes.length) return toast('No se pudo subrayar esa selección');
    this.addAll(strokes);
    getSelection().removeAllRanges();
    this.selbar.style.display = 'none';
  }

  // ───────── barras de herramientas ─────────

  setTool(t) {
    this.tool = t;
    this.select(null);
    this.renderToolbar();
    this.renderOptions();
  }

  tbtn(tool, ic, label) {
    return h('button', { class: 'icon-btn' + (this.tool === tool ? ' on' : ''), title: label, 'aria-label': label, onclick: () => this.setTool(tool) }, icon(ic));
  }

  ibtn(ic, label, fn, { on = false, disabled = false } = {}) {
    return h('button', { class: 'icon-btn' + (on ? ' on' : ''), title: label, 'aria-label': label, disabled, onclick: fn }, icon(ic));
  }

  renderToolbar() {
    const sep = () => h('span', { class: 'vsep' });
    fill(this.topbar,
      this.ibtn('back', 'Volver', () => this.close()),
      this.ibtn('chevUp', 'Ocultar barra', () => { this.showBars = false; this.applyBars(); }),
      h('span', { class: 'doc-title' }, this.info.name),
      this.ibtn('more', 'Más acciones', (e) => this.moreMenu(e.currentTarget)),
      sep(),
      this.tbtn('hand', 'hand', 'Mover'), this.tbtn('pen', 'pen', 'Lápiz'), this.tbtn('hl', 'highlighter', 'Resaltador'),
      this.tbtn('text', 'text', 'Texto'), this.tbtn('sticker', 'smile', 'Pegatinas'), this.tbtn('cover', 'cover', 'Cambiar texto del PDF'),
      this.tbtn('postit', 'note', 'Post-it'), this.tbtn('eraser', 'eraser', 'Borrador'),
      this.ibtn('ruler', 'Línea recta', () => { this.straight = !this.straight; this.renderToolbar(); }, { on: this.straight }),
      sep(),
      this.ibtn('undo', 'Deshacer', () => this.undo(), { disabled: !this.undoStack.length }),
      this.ibtn('redo', 'Rehacer', () => this.redo(), { disabled: !this.redoStack.length }),
      sep(),
      this.ibtn('zoomOut', 'Alejar', () => this.setZoom(this.zoom - 0.25)),
      h('span', { class: 'zoom-label' }, Math.round(this.zoom * 100) + '%'),
      this.ibtn('zoomIn', 'Acercar', () => this.setZoom(this.zoom + 0.25)),
      sep(),
      this.ibtn('touch', 'Dibujar también con el dedo', () => { this.fingerDraw = !this.fingerDraw; this.renderToolbar(); toast(this.fingerDraw ? 'El dedo también dibuja' : 'Solo dibuja el lápiz; el dedo desplaza'); }, { on: this.fingerDraw }),
    );
    // Las barras se reconstruyen: conserva el desplazamiento horizontal.
  }

  /** Acciones de página y exportación (siempre accesibles, aunque la barra de herramientas no quepa entera). */
  moreMenu(anchor) {
    menu(anchor, [
      ['Añadir página en blanco', () => this.addBlankPage()],
      this.templateKind ? ['Duplicar la plantilla (vacía) al final', () => this.addTemplatePage()] : null,
      ['Eliminar esta página', () => this.deleteCurrentPage(), 'danger'],
      ['Exportar PDF con anotaciones', () => this.export()],
    ].filter(Boolean));
  }

  applyBars() {
    this.topbar.style.display = this.showBars ? '' : 'none';
    this.optbar.style.display = this.showBars ? '' : 'none';
    if (!this.showBars) {
      this.revealBtn?.remove();
      this.revealBtn = h('button', { class: 'reveal-btn', onclick: () => { this.showBars = true; this.applyBars(); } }, icon('chevDown', 22));
      this.root.append(this.revealBtn);
    } else this.revealBtn?.remove();
    this.layout();
    this.updateVisible();
  }

  colorRow(palette, current, onPick) {
    return h('div', { class: 'colors' }, palette.map((c) => h('button', {
      class: 'swatch' + (c.toLowerCase() === current.toLowerCase() ? ' on' : ''), style: { background: c }, 'aria-label': c,
      onclick: () => { onPick(c); this.renderOptions(); },
    })));
  }

  slider(label, value, min, max, onInput) {
    const input = h('input', { type: 'range', min, max, step: (max - min) / 200, value });
    input.addEventListener('input', () => onInput(parseFloat(input.value)));
    return h('div', { class: 'slider' }, h('span', { class: 'dim' }, label), input);
  }

  renderOptions() {
    const o = this.optbar;
    o.classList.toggle('tall', this.tool === 'sticker');
    const hint = (t) => h('span', { class: 'dim hint' }, t);
    let kids;
    switch (this.tool) {
      case 'sticker': kids = [this.stickerBar()]; break;
      case 'cover': kids = [hint('Cambiar texto: arrastra un recuadro sobre el texto del PDF y escribe el nuevo · el borrador quita el tapado')]; break;
      case 'hand': kids = [hint('Arrastra textos, pegatinas y trazos para moverlos · toca un texto para editarlo · mantén pulsado el texto del PDF para seleccionarlo y copiarlo')]; break;
      case 'postit': kids = [this.slider('Tamaño', this.postitSize, 0.12, 0.6, (v) => { this.postitSize = v; }), hint('Toca la página para pegar un post-it y dibuja sobre él con el lápiz')]; break;
      case 'eraser': kids = [this.slider('Borrador', this.eraserRadius, 0.01, 0.06, (v) => { this.eraserRadius = v; })]; break;
      case 'hl': kids = [this.colorRow(ann.HL_PALETTE, this.hlColor, (c) => { this.hlColor = c; }), this.slider('Grosor', this.hlWidth, 0.01, 0.05, (v) => { this.hlWidth = v; })]; break;
      case 'text': kids = [this.colorRow(ann.PALETTE, this.textColor, (c) => { this.textColor = c; }), this.slider('Tamaño', this.textSize, 0.014, 0.07, (v) => { this.textSize = v; })]; break;
      default: kids = [this.colorRow(ann.PALETTE, this.penColor, (c) => { this.penColor = c; }), this.slider('Grosor', this.penWidth, 0.001, 0.012, (v) => { this.penWidth = v; })];
    }
    fill(o, kids);
  }

  stickerBar() {
    const tabs = [...PACKS.map((p) => p.title), 'Emoji', 'Mis pegatinas'];
    const items = h('div', { class: 'sticker-items' });
    const fill = async () => {
      items.replaceChildren();
      const t = this.stickerTab;
      if (t < PACKS.length) PACKS[t].items.forEach((n) => items.append(this.stickerBtn('a:' + n, h('img', { src: `stickers/${n}.png`, alt: '' }))));
      else if (t === PACKS.length) EMOJI.forEach((e) => items.append(this.stickerBtn('e:' + e, h('span', { class: 'emoji' }, e))));
      else {
        items.append(h('button', { class: 'sticker-btn add', title: 'Importar imagen', onclick: () => this.importSticker() }, icon('plus', 26)));
        for (const n of await store.userStickers()) items.append(this.stickerBtn('u:' + n, h('img', { src: await store.userSticker(n), alt: '' })));
      }
    };
    const tabsEl = h('div', { class: 'tabs' }, tabs.map((name, i) => h('button', {
      class: 'tab' + (i === this.stickerTab ? ' on' : ''), onclick: () => { this.stickerTab = i; this.renderOptions(); },
    }, name)));
    fill();
    return h('div', { class: 'sticker-bar' }, tabsEl, items);
  }

  stickerBtn(src, content) {
    return h('button', { class: 'sticker-btn' + (this.stickerSrc === src ? ' on' : ''), onclick: (e) => {
      this.stickerSrc = src;
      document.querySelectorAll('.sticker-btn.on').forEach((b) => b.classList.remove('on'));
      e.currentTarget.classList.add('on');
    } }, content);
  }

  importSticker() {
    const input = h('input', { type: 'file', accept: 'image/*', multiple: true });
    input.addEventListener('change', async () => {
      for (const f of input.files) { try { await store.addUserSticker(f); } catch { toast('No se pudo importar esa imagen'); } }
      this.renderOptions();
    });
    input.click();
  }

  renderObjBar() {
    const a = this.anns.find((x) => x.id === this.selected);
    if (!a) { this.objbar.style.display = 'none'; return; }
    const b = (ic, label, fn) => h('button', { class: 'icon-btn', title: label, 'aria-label': label, onclick: fn }, icon(ic, 20));
    fill(this.objbar,
      b('minus', 'Más pequeño', () => this.transformSelected(0.88, 0)), b('plus', 'Más grande', () => this.transformSelected(1.14, 0)),
      a.t !== 't' ? b('rotCcw', 'Girar a la izquierda', () => this.transformSelected(1, -10)) : null,
      a.t !== 't' ? b('rotCw', 'Girar a la derecha', () => this.transformSelected(1, 10)) : null,
      a.t === 't' ? b('pen', 'Editar texto', () => this.editText(a, this.views.find((v) => v.index === a.page), [a.x, a.y])) : null,
      b('trash', 'Eliminar', () => { this.remove(a.id); this.select(null); }),
      b('close', 'Listo', () => this.select(null)),
    );
    this.objbar.style.display = 'flex';
  }

  // ───────── exportar y cerrar ─────────

  async export() {
    await this.saveNow();
    const bar = h('div', { class: 'progress' }, h('div'));
    const label = h('p', { class: 'dim' }, 'Preparando…');
    const m = modal({ title: 'Exportando PDF', content: h('div', {}, label, bar) });
    try {
      const blob = await exportPdf({
        pdf: this.pdf, info: this.info, anns: this.anns, extraKinds: this.extraKinds, hidden: this.hidden, sizes: this.sizes,
        onProgress: (i, n) => { label.textContent = `Página ${i} de ${n}`; bar.firstChild.style.width = (100 * i / n) + '%'; },
      });
      m.close();
      await shareOrDownload(blob, `${this.info.name}-anotado.pdf`);
    } catch (e) {
      console.error(e);
      m.close();
      toast('No se pudo exportar el PDF');
    }
  }

  async close() {
    clearInterval(this.saveTimer);
    window.removeEventListener('resize', this.onResize);
    document.removeEventListener('selectionchange', this.onSelection);
    await this.saveNow();
    await store.setLastPage(this.info.id, this.position);
    try {
      const first = this.visiblePages().find((i) => i < this.pdf.numPages);
      if (first !== undefined) {
        const c = await renderToCanvas(this.pdf, first + 1, 420);
        ann.drawAll(c.getContext('2d'), this.anns.filter((a) => a.page === first), c.width);
        await store.setThumb(this.info.id, c.toDataURL('image/jpeg', 0.82));
      }
    } catch { /* se queda la miniatura anterior */ }
    this.pdf.destroy();
    this.root.remove();
    this.onClose?.();
  }
}

/** En el iPad abre la hoja de compartir (Guardar en Archivos, AirDrop…); en el resto descarga el archivo. */
export async function shareOrDownload(blob, filename) {
  const file = new File([blob], filename, { type: blob.type || 'application/pdf' });
  if (navigator.canShare?.({ files: [file] })) {
    try { await navigator.share({ files: [file], title: filename }); return; } catch (e) { if (e.name === 'AbortError') return; }
  }
  const url = URL.createObjectURL(blob);
  const a = h('a', { href: url, download: filename });
  document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60000);
}
