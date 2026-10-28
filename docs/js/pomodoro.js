// Ternurín (método pomodoro): estudio y descanso se alternan con una alarma hasta que se pulsa Detener.
// En el iPad una web no puede sonar con la app cerrada: el aviso funciona con la app abierta (se mantiene la pantalla encendida).

import { h, icon, toast } from './ui.js';

const KEY = 'ev.pomo';
const S = Object.assign({ phase: 'IDLE', endAt: 0, phaseMs: 0, completed: 0, study: 25, rest: 5 }, JSON.parse(localStorage.getItem(KEY) || '{}'));
const listeners = new Set();
let audio = null, timer = null, wake = null;

const save = () => localStorage.setItem(KEY, JSON.stringify(S));
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
  save();
}

function tick() {
  if (S.phase !== 'IDLE' && Date.now() >= S.endAt) {
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

document.addEventListener('visibilitychange', () => { if (!document.hidden && S.phase !== 'IDLE') { keepAwake(true); tick(); } });

export function start(study, rest) {
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
  S.phase = 'IDLE'; S.endAt = 0;
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
    h('div', { class: 'pomo-top' }, h('button', { class: 'icon-btn', 'aria-label': 'Volver', onclick: () => { close(); } }, icon('back')), h('h1', {}, 'Ternurín')),
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
