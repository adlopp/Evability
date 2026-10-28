// Colores de la app: el fondo y el color de los botones se eligen en Ajustes y de ahí salen todos los demás (igual que en Android).

export const ACCENTS = [
  ['Rosa', '#F5A9C6'], ['Lila', '#CDB8F7'], ['Menta', '#A8E6CF'],
  ['Melocotón', '#FFC09A'], ['Celeste', '#A9D4F5'], ['Limón', '#F4E58A'],
];
export const BACKGROUNDS = [
  ['Grafito', '#0E0F13'], ['Azul noche', '#0B1220'], ['Océano', '#0A1A1F'], ['Bosque', '#0B1612'],
  ['Ciruela', '#170E1A'], ['Vino', '#1A0D12'], ['Café', '#17110D'],
];
export const LIGHT_BACKGROUNDS = [
  ['Hueso', '#F6F4F0'], ['Rosa pálido', '#FBEFF3'], ['Lavanda', '#F1EEFA'],
  ['Menta', '#EAF6F0'], ['Cielo', '#EAF2FB'], ['Crema', '#FAF3E4'],
];

const hex = (s) => [1, 3, 5].map((i) => parseInt(s.slice(i, i + 2), 16));
const toHex = (c) => '#' + c.map((v) => Math.round(Math.max(0, Math.min(255, v))).toString(16).padStart(2, '0')).join('');
const lerp = (a, b, t) => toHex(hex(a).map((v, i) => v + (hex(b)[i] - v) * t));

function luminance(s) {
  const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
  const [r, g, b] = hex(s).map(f);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function rgbToHsv([r, g, b]) {
  r /= 255; g /= 255; b /= 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b), d = max - min;
  let h = 0;
  if (d) {
    if (max === r) h = ((g - b) / d) % 6; else if (max === g) h = (b - r) / d + 2; else h = (r - g) / d + 4;
    h *= 60; if (h < 0) h += 360;
  }
  return [h, max ? d / max : 0, max];
}

function hsvToHex(h, s, v) {
  const c = v * s, x = c * (1 - Math.abs(((h / 60) % 2) - 1)), m = v - c;
  const [r, g, b] = h < 60 ? [c, x, 0] : h < 120 ? [x, c, 0] : h < 180 ? [0, c, x] : h < 240 ? [0, x, c] : h < 300 ? [x, 0, c] : [c, 0, x];
  return toHex([(r + m) * 255, (g + m) * 255, (b + m) * 255]);
}

/** Sobre fondos claros el color elegido se oscurece/intensifica un poco para que textos e iconos se lean. */
const deepen = (c) => { const [h, s] = rgbToHsv(hex(c)); return hsvToHex(h, Math.min(s * 1.9 + 0.05, 0.85), 0.74); };

export const theme = {
  accentBase: localStorage.getItem('ev.accent') || ACCENTS[0][1],
  bg: localStorage.getItem('ev.bg') || BACKGROUNDS[0][1],
  get isLight() { return luminance(this.bg) > 0.5; },
  get accent() { return this.isLight ? deepen(this.accentBase) : this.accentBase; },
};

export function applyTheme() {
  const light = theme.isLight, bg = theme.bg, accent = theme.accent;
  const root = document.documentElement.style;
  root.setProperty('--bg', bg);
  root.setProperty('--accent', accent);
  root.setProperty('--on-accent', luminance(accent) > 0.38 ? '#2A1420' : '#FFFFFF');
  root.setProperty('--surface1', light ? lerp(bg, '#ffffff', 0.65) : lerp(bg, '#ffffff', 0.035));
  root.setProperty('--surface2', light ? lerp(bg, '#000000', 0.05) : lerp(bg, '#ffffff', 0.07));
  root.setProperty('--outline', light ? lerp(bg, '#000000', 0.13) : lerp(bg, '#ffffff', 0.14));
  root.setProperty('--on-surface', light ? '#1C1B22' : '#E8E8EC');
  root.setProperty('--on-dim', light ? '#6A6872' : '#9A9CA6');
  root.setProperty('--editor-bg', lerp(bg, '#000000', light ? 0.10 : 0.35));
  root.setProperty('--accent-soft', accent + '2e');
  root.setProperty('--accent-sel', accent + '66');
  document.documentElement.dataset.light = light ? '1' : '0';
  document.querySelector('meta[name=theme-color]')?.setAttribute('content', bg);
}

export function setBackground(c) { theme.bg = c; localStorage.setItem('ev.bg', c); applyTheme(); }
export function setAccent(c) { theme.accentBase = c; localStorage.setItem('ev.accent', c); applyTheme(); }
