// Wordle en español. La palabra del día sale de una lista guardada en la app según la fecha, así que es la misma para todos
// (móvil, tablet y web) y no hace falta conexión. Las tildes no cuentan: «árbol» se escribe «arbol».

import { darkPref, darkToggle } from './ui.js';
import { h, icon, toast } from './ui.js';

const ROWS = 6;
let answers = null, valid = null;

async function loadWords() {
  if (answers) return;
  const read = async (url) => (await (await fetch(url)).text()).split('\n').map((w) => w.trim()).filter((w) => w.length === 5);
  const [a, v] = await Promise.all([read('data/wordle-answers.txt'), read('data/wordle-valid.txt')]);
  answers = a; valid = new Set(v);
}

/** Número de días desde 1970 (igual en todas las plataformas). */
export const today = () => { const d = new Date(); return Math.floor(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 86400000); };
/** 7919 es primo y no divide el tamaño de la lista, así que recorre todas sin repetir hasta que se acaban. */
const answerFor = (day) => answers[(day * 7919) % answers.length];
/** Wordle nº 1 = 1 de enero de 2024. */
const number = (day) => day - 19723 + 1;

/** 2 = en su sitio, 1 = está en otro sitio, 0 = no está. */
export function evaluate(guess, answer) {
  const res = [0, 0, 0, 0, 0], left = {};
  for (let i = 0; i < 5; i++) { if (guess[i] === answer[i]) res[i] = 2; else left[answer[i]] = (left[answer[i]] || 0) + 1; }
  for (let i = 0; i < 5; i++) { if (res[i] === 2) continue; if (left[guess[i]] > 0) { res[i] = 1; left[guess[i]]--; } }
  return res;
}

// ───── partida del día (se guarda para no poder repetirla) y chats a los que ya se mandó el resultado ─────
const load = (key) => { try { return JSON.parse(localStorage.getItem(key) || '{}'); } catch { return {}; } };
const loadGuesses = (day) => { const s = load('ev.wordle'); return s.day === day ? s.guesses : []; };
const saveGuesses = (day, guesses) => localStorage.setItem('ev.wordle', JSON.stringify({ day, guesses }));
const wasPosted = (day, uid) => { const s = load('ev.wordlePosted'); return s.day === day && (s.uids || []).includes(uid); };
const markPosted = (day, uid) => { const s = load('ev.wordlePosted'); const uids = s.day === day ? s.uids || [] : []; localStorage.setItem('ev.wordlePosted', JSON.stringify({ day, uids: [...new Set([...uids, uid])] })); };

/** Lo que ve el amigo en el chat: el número de intentos y los cuadros de colores, sin letras (no desvela la palabra). */
function shareText(day, guesses, answer) {
  const solved = guesses[guesses.length - 1] === answer;
  const grid = guesses.map((g) => evaluate(g, answer).map((s) => (s === 2 ? '🟩' : s === 1 ? '🟨' : '⬛')).join('')).join('\n');
  return `Wordle #${number(day)} ${solved ? guesses.length : 'X'}/${ROWS}\n${grid}`;
}

const KEYS = ['QWERTYUIOP', 'ASDFGHJKLÑ', 'ZXCVBNM'];

/** Pantalla del Wordle: 6 intentos para una palabra de 5 letras. Al acabar, el resultado se manda solo al chat desde el que se abrió. */
export function openWordle({ friendUid, post, onClose }) {
  const day = today();
  let guesses = [], current = '', ready = false, answer = '';
  const board = h('div', { class: 'wboard' }), msg = h('div', { class: 'wmsg' }), kb = h('div', { class: 'wkeys' });
  const cells = Array.from({ length: ROWS }, () => Array.from({ length: 5 }, () => h('div', { class: 'wcell' })));
  board.append(...cells.map((row) => h('div', { class: 'wrow' }, ...row)));
  const keyEls = {};
  KEYS.forEach((letters, ri) => {
    const row = h('div', { class: 'wkrow' });
    if (ri === 2) row.append(h('button', { class: 'wkey wide', onclick: () => press('ENTER') }, 'ENTER'));
    for (const ch of letters) { const b = h('button', { class: 'wkey', onclick: () => press(ch) }, ch); keyEls[ch.toLowerCase()] = b; row.append(b); }
    if (ri === 2) row.append(h('button', { class: 'wkey wide', 'aria-label': 'Borrar', onclick: () => press('⌫') }, '⌫'));
    kb.append(row);
  });
  const root = h('div', { class: 'pomo wordle' },
    h('div', { class: 'pomo-top' }, h('button', { class: 'icon-btn', 'aria-label': 'Volver', onclick: () => close() }, icon('back')), h('h1', { class: 'grow' }, 'Wordle'),
      h('button', { class: 'icon-btn', 'aria-label': 'Fondo oscuro', title: 'Fondo oscuro', onclick: () => root.classList.toggle('dark-bg', darkToggle()) }, icon('moon'))),
    h('div', { class: 'wordle-main' }, board, msg, kb));

  if (darkPref()) root.classList.add('dark-bg');

  const finished = () => ready && (guesses[guesses.length - 1] === answer || guesses.length >= ROWS);

  function paint() {
    for (let r = 0; r < ROWS; r++) {
      const word = guesses[r] ?? (r === guesses.length && !finished() ? current : '');
      const ev = guesses[r] ? evaluate(guesses[r], answer) : null;
      for (let c = 0; c < 5; c++) {
        const el = cells[r][c];
        el.textContent = (word[c] || '').toUpperCase();
        el.className = 'wcell' + (ev ? ' s' + ev[c] : word[c] ? ' filled' : '');
      }
    }
    // mejor estado conocido de cada letra, para colorear el teclado
    const best = {};
    if (ready) for (const g of guesses) evaluate(g, answer).forEach((s, i) => { best[g[i]] = Math.max(best[g[i]] ?? -1, s); });
    for (const [ch, el] of Object.entries(keyEls)) el.className = 'wkey' + (best[ch] !== undefined ? ' s' + best[ch] : '');
  }

  function setMsg(t) { msg.textContent = t; }

  async function afterFinish() {
    const solved = guesses[guesses.length - 1] === answer;
    setMsg(solved ? `¡Resuelto en ${guesses.length}!` : `Era: ${answer.toUpperCase()}`);
    msg.classList.toggle('good', solved);
    if (friendUid && !wasPosted(day, friendUid)) {
      try { await post(shareText(day, guesses, answer)); markPosted(day, friendUid); } catch (e) { setMsg(e.message || 'No se pudo enviar'); }
    }
  }

  function press(k) {
    if (!ready || finished()) return;
    setMsg('');
    if (k === '⌫') current = current.slice(0, -1);
    else if (k === 'ENTER') {
      if (current.length < 5) setMsg('Faltan letras');
      else if (!valid.has(current)) setMsg('No está en la lista');
      else { guesses.push(current); saveGuesses(day, guesses); current = ''; paint(); if (finished()) afterFinish(); return; }
    } else if (current.length < 5) current += k.toLowerCase();
    paint();
  }

  const onKey = (e) => {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    if (e.key === 'Enter') press('ENTER');
    else if (e.key === 'Backspace') press('⌫');
    else if (/^[a-zñA-ZÑ]$/.test(e.key)) press(e.key.toUpperCase());
  };
  document.addEventListener('keydown', onKey);

  loadWords().then(() => {
    answer = answerFor(day); guesses = loadGuesses(day); ready = true; paint();
    if (finished()) afterFinish();
  }).catch(() => toast('No se pudo cargar el juego'));

  function close() { document.removeEventListener('keydown', onKey); root.remove(); onClose?.(); }
  paint();
  return root;
}
