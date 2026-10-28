// Ternurín (método pomodoro): estudio y descanso se alternan con una alarma hasta que se pulsa Detener.
// En el iPad una web no puede sonar con la app cerrada: el aviso funciona con la app abierta (se mantiene la pantalla encendida).

import { h, icon, toast } from './ui.js';

const KEY = 'ev.pomo';
const S = Object.assign({ phase: 'IDLE', endAt: 0, phaseMs: 0, completed: 0, study: 25, rest: 5 }, JSON.parse(localStorage.getItem(KEY) || '{}'));
const listeners = new Set();
let audio = null, timer = null, wake = null;

const save = () => localStorage.setItem(KEY, JSON.stringify(S));

// ───────── registro del tiempo de estudio (sin descansos), por día ─────────

const LOG = 'ev.study';
export const dayKey = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
export const studyLog = () => { try { return JSON.parse(localStorage.getItem(LOG) || '{}'); } catch { return {}; } };

/** Suma un tramo de estudio; si cruza la medianoche se reparte entre los dos días. */
function addStudy(from, to) {
  if (to <= from) return;
  const log = studyLog();
  let a = from;
  while (a < to) {
    const d = new Date(a);
    const next = new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1).getTime();
    const b = Math.min(to, next);
    log[dayKey(d)] = (log[dayKey(d)] || 0) + (b - a);
    a = b;
  }
  localStorage.setItem(LOG, JSON.stringify(log));
}

/** Guarda el estudio transcurrido hasta ahora (nunca más allá del fin de la fase). Sin `force` espera a juntar 5 s. */
function accrue(force) {
  if (S.phase !== 'STUDY') return;
  const to = Math.min(Date.now(), S.endAt);
  if (!S.mark) S.mark = to;
  if (to - S.mark < (force ? 1 : 5000)) return;
  addStudy(S.mark, to);
  S.mark = to;
  save();
}

/** Milisegundos estudiados en un día, incluido el tramo en curso que aún no se ha guardado. */
export function studyMs(key) {
  let ms = studyLog()[key] || 0;
  if (key === dayKey(new Date()) && S.phase === 'STUDY' && S.mark) ms += Math.max(0, Math.min(Date.now(), S.endAt) - S.mark);
  return ms;
}
const emit = () => listeners.forEach((f) => f());

function ensureAudio() {
  const AC = window.AudioContext || window.webkitAudioContext;
  if (!AC) return null;
  if (!audio) audio = new AC();
  if (audio.state === 'suspended') audio.resume();
  return audio;
}

/** Alarma: una serie de pitidos. */
function ring() {
  const ctx = ensureAudio();
  if (ctx) {
    const t0 = ctx.currentTime + 0.05;
    for (let round = 0; round < 2; round++) for (let i = 0; i < 4; i++) {
      const t = t0 + round * 1.9 + i * 0.4;
      const osc = ctx.createOscillator(), gain = ctx.createGain();
      osc.type = 'sine'; osc.frequency.value = i % 2 ? 988 : 784;
      gain.gain.setValueAtTime(0.0001, t);
      gain.gain.exponentialRampToValueAtTime(0.5, t + 0.03);
      gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.32);
      osc.connect(gain).connect(ctx.destination);
      osc.start(t); osc.stop(t + 0.35);
    }
  }
  navigator.vibrate?.([300, 150, 300]);
}

function begin(phase) {
  S.phase = phase;
  S.phaseMs = (phase === 'STUDY' ? S.study : S.rest) * 60000;
  S.endAt = Date.now() + S.phaseMs;
  S.mark = phase === 'STUDY' ? Date.now() : 0;
  save();
}

function tick() {
  accrue(false);
  if (S.phase !== 'IDLE' && Date.now() >= S.endAt) {
    accrue(true);
    const wasStudy = S.phase === 'STUDY';
    if (wasStudy) S.completed++;
    begin(wasStudy ? 'REST' : 'STUDY');
    ring();
    toast(wasStudy ? '¡A descansar!' : '¡A estudiar!', 4000);
    if (document.hidden && window.Notification?.permission === 'granted') new Notification(wasStudy ? '¡A descansar!' : '¡A estudiar!');
  }
  emit();
}

async function keepAwake(on) {
  try {
    if (on && navigator.wakeLock && !wake) {
      wake = await navigator.wakeLock.request('screen');
      wake.addEventListener('release', () => { wake = null; });
    } else if (!on && wake) { await wake.release(); wake = null; }
  } catch { /* no disponible */ }
}

document.addEventListener('visibilitychange', () => {
  if (document.hidden) accrue(true); // por si el sistema suspende la web
  else if (S.phase !== 'IDLE') { keepAwake(true); tick(); }
});

export function start(study, rest) {
  accrue(true);
  S.study = study; S.rest = rest; S.completed = 0;
  ensureAudio(); // el navegador solo permite sonido tras un toque del usuario
  begin('STUDY');
  clearInterval(timer);
  timer = setInterval(tick, 250);
  keepAwake(true);
  if (window.Notification?.permission === 'default') Notification.requestPermission();
  emit();
}

export function stop() {
  accrue(true);
  S.phase = 'IDLE'; S.endAt = 0; S.mark = 0;
  save();
  clearInterval(timer); timer = null;
  keepAwake(false);
  emit();
}

// Si se recarga la app con el temporizador en marcha, se retoma.
if (S.phase !== 'IDLE') { timer = setInterval(tick, 250); }

export const running = () => S.phase !== 'IDLE';

// ───────── pantalla ─────────

export function openPomodoro(onClose) {
  const R = 134, C = 2 * Math.PI * R;
  const arc = (cls) => { const e = document.createElementNS('http://www.w3.org/2000/svg', 'circle'); e.setAttribute('class', cls); e.setAttribute('cx', 150); e.setAttribute('cy', 150); e.setAttribute('r', R); return e; };
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 300 300');
  const track = arc('ring-track'), prog = arc('ring-prog');
  prog.setAttribute('stroke-dasharray', C);
  svg.append(track, prog);
  const time = h('div', { class: 'ring-time' }), label = h('div', { class: 'ring-label' });
  const ring_ = h('div', { class: 'ring' }, svg, h('div', { class: 'ring-center' }, time, label));
  const body = h('div', { class: 'pomo-body' });

  const stepper = (name, get, set, min, max) => {
    const val = h('span', { class: 'step-val' });
    const upd = () => { val.textContent = get() + ' min'; };
    const b = (txt, d) => h('button', { class: 'text-btn', onclick: () => { set(Math.max(min, Math.min(max, get() + d))); upd(); update(); } }, txt);
    upd();
    return h('div', { class: 'stepper' }, h('span', { class: 'dim step-name' }, name), b('−5', -5), b('−1', -1), val, b('+1', 1), b('+5', 5));
  };

  let study = S.study, rest = S.rest;
  const root = h('div', { class: 'pomo' },
    h('div', { class: 'pomo-top' }, h('button', { class: 'icon-btn', 'aria-label': 'Volver', onclick: () => { close(); } }, icon('back')), h('h1', { class: 'grow' }, 'Ternurín'),
      h('button', { class: 'round', 'aria-label': 'Tu estudio', title: 'Tu estudio', onclick: () => root.append(openStudyStats()) }, icon('chart'))),
    h('div', { class: 'pomo-main' }, ring_, body));

  function update() {
    const run = S.phase !== 'IDLE';
    const total = run ? S.phaseMs : study * 60000;
    const left = run ? Math.max(0, Math.min(total, S.endAt - Date.now())) : total;
    const secs = Math.ceil(left / 1000);
    time.textContent = String(Math.floor(secs / 60)).padStart(2, '0') + ':' + String(secs % 60).padStart(2, '0');
    label.textContent = S.phase === 'STUDY' ? 'Estudio' : S.phase === 'REST' ? 'Descanso' : 'Listo';
    prog.style.stroke = S.phase === 'REST' ? '#A8E6CF' : 'var(--accent)';
    prog.setAttribute('stroke-dashoffset', C * (1 - (total ? left / total : 1)));
    const sig = run ? 'run' : 'idle';
    if (body.dataset.sig !== sig) {
      body.dataset.sig = sig;
      body.replaceChildren(...(run
        ? [h('p', { class: 'dim' }, ''), h('p', { class: 'dim small' }, 'Mantén esta pantalla abierta: el aviso suena con la app abierta.'),
          h('button', { class: 'btn big', onclick: () => stop() }, icon('stop', 22), ' Detener')]
        : [stepper('Estudio', () => study, (v) => { study = v; }, 1, 180), stepper('Descanso', () => rest, (v) => { rest = v; }, 1, 60),
          h('button', { class: 'btn big', onclick: () => start(study, rest) }, icon('play', 22), ' Empezar'),
          h('p', { class: 'dim small' }, 'Al terminar cada fase suena una alarma y empieza la siguiente hasta que pulses Detener. Con la app cerrada el iPad no puede avisar.')]));
    }
    if (run) body.firstChild.textContent = `Ternurines completados: ${S.completed}`;
  }

  listeners.add(update);
  const raf = setInterval(update, 250);
  update();
  function close() { listeners.delete(update); clearInterval(raf); root.remove(); onClose?.(); }
  return root;
}

// ───────── estadísticas: tiempo de estudio por día ─────────

const DAYS = ['L', 'M', 'X', 'J', 'V', 'S', 'D'];
const fmt = (ms) => {
  const min = Math.floor(ms / 60000), hh = Math.floor(min / 60);
  return hh ? (min % 60 ? `${hh} h ${min % 60} min` : `${hh} h`) : `${min} min`;
};
const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);

export function openStudyStats() {
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const addDays = (d, n) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
  const monday0 = addDays(today, -((today.getDay() + 6) % 7));
  let weekOffset = 0, selected = today;

  const big = h('div', { class: 'stat-big' }), when = h('div', { class: 'dim' });
  const weekLabel = h('div', { class: 'grow strong', style: { textAlign: 'center' } });
  const next = h('button', { class: 'icon-btn', 'aria-label': 'Semana siguiente', onclick: () => { weekOffset++; selected = addDays(monday0, weekOffset * 7); draw(); } }, icon('chevR'));
  const bars = h('div', { class: 'bars' }), letters = h('div', { class: 'bar-letters' }), cards = h('div', { class: 'stat-cards' });

  function draw() {
    const monday = addDays(monday0, weekOffset * 7);
    const days = Array.from({ length: 7 }, (_, i) => addDays(monday, i));
    const vals = days.map((d) => (d > today ? 0 : studyMs(dayKey(d))));
    const top = Math.max(...vals, 3600000);
    const selMs = selected > today ? 0 : studyMs(dayKey(selected));
    const diff = Math.round((today - selected) / 86400000);
    when.textContent = diff === 0 ? 'Hoy' : diff === 1 ? 'Ayer' : cap(selected.toLocaleDateString('es-ES', { weekday: 'long', day: 'numeric', month: 'long' }));
    big.textContent = fmt(selMs);
    weekLabel.textContent = weekOffset === 0 ? 'Esta semana' : `${monday.toLocaleDateString('es-ES', { day: 'numeric', month: 'short' })} – ${addDays(monday, 6).toLocaleDateString('es-ES', { day: 'numeric', month: 'short' })}`;
    next.disabled = weekOffset >= 0; next.style.opacity = weekOffset >= 0 ? 0.25 : 1;
    bars.replaceChildren(...days.map((d, i) => {
      const future = d > today, sel = +d === +selected;
      const fill = h('div', { class: 'bar-fill' });
      fill.style.height = vals[i] ? Math.max(3, vals[i] / top * 100) + '%' : '0%';
      return h('button', { class: 'bar' + (sel ? ' sel' : '') + (future ? ' future' : ''), disabled: future, 'aria-label': `${DAYS[i]}: ${fmt(vals[i])}`, onclick: () => { selected = d; draw(); } }, fill);
    }));
    letters.replaceChildren(...days.map((d, i) => h('span', { class: +d === +selected ? 'sel' : '', style: { fontWeight: +d === +today ? 700 : 400 } }, DAYS[i])));
    let streak = 0, d = studyMs(dayKey(today)) > 0 ? today : addDays(today, -1);
    while (studyMs(dayKey(d)) > 0) { streak++; d = addDays(d, -1); }
    const elapsed = Math.max(1, days.filter((x) => x <= today).length), total = vals.reduce((a, b) => a + b, 0);
    const card = (label, value) => h('div', { class: 'stat-card' }, h('div', { class: 'strong' }, value), h('div', { class: 'dim small' }, label));
    cards.replaceChildren(card('Semana', fmt(total)), card('Media por día', fmt(total / elapsed)), card('Racha', streak === 1 ? '1 día' : `${streak} días`));
  }

  const root = h('div', { class: 'pomo stats' },
    h('div', { class: 'pomo-top' }, h('button', { class: 'icon-btn', 'aria-label': 'Volver', onclick: () => close() }, icon('back')), h('h1', {}, 'Tu estudio')),
    h('div', { class: 'stats-body' },
      h('div', { class: 'stat-hero' }, when, big, h('div', { class: 'dim small' }, 'de estudio efectivo')),
      h('div', { class: 'row', style: { alignItems: 'center' } },
        h('button', { class: 'icon-btn', 'aria-label': 'Semana anterior', onclick: () => { weekOffset--; selected = addDays(monday0, weekOffset * 7); draw(); } }, icon('chevL')), weekLabel, next),
      bars, letters, cards,
      h('p', { class: 'dim small' }, 'Solo cuenta el tiempo de estudio del Ternurín; los descansos no suman. El día se reinicia a las 00:00.')));

  draw();
  const live = setInterval(draw, 1000);
  function close() { clearInterval(live); root.remove(); }
  return root;
}
