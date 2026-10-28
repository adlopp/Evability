// Análisis de documentos con la API de Gemini. Cada persona usa su propia clave (se guarda solo en este dispositivo).
// Misma lógica que la app de Android: modelos gratuitos con búsqueda de Google, reintentos y modelo de reserva.

export const KEY_URL = 'https://aistudio.google.com/apikey';
const BASE = 'https://generativelanguage.googleapis.com';
const MAX_BYTES = 14 * 1024 * 1024; // límite de la API para peticiones con el archivo dentro (20 MB) menos el margen del base64

export const getKey = () => localStorage.getItem('ev.geminiKey') || '';
export const setKey = (k) => localStorage.setItem('ev.geminiKey', k.trim());

// Los Gemini 2.5 son los que incluyen búsqueda de Google gratis (unas 500 al día); los 3.x no.
const MODELS = {
  research: ['gemini-2.5-flash', 'gemini-2.5-flash-lite'],
  summary: ['gemini-2.5-flash-lite', 'gemini-2.5-flash', 'gemini-3.5-flash-lite'],
};

export class GeminiError extends Error {
  constructor(message, { retryable = false, fallback = false } = {}) { super(message); this.retryable = retryable; this.fallback = fallback; }
}

const UNREADABLE = 'NO_LEGIBLE';
const READABILITY = `Legibilidad: si el documento está vacío, ilegible o tan tachado, borroso o dañado que no puedes leer lo suficiente para resumirlo con fiabilidad, responde ÚNICAMENTE con la palabra ${UNREADABLE} y nada más.
Si solo son ilegibles partes sueltas, no las adivines: escribe «[ilegible]» en su lugar.`;

function prompt(name, mode) {
  if (mode === 'summary') return `Eres un preparador de estudio. Analiza el documento adjunto («${name}») y escribe un resumen en español.
- Quédate solo con lo importante: conceptos clave, definiciones, cifras, fechas, normas y relaciones entre ideas.
- Elimina relleno, repeticiones y explicaciones innecesarias.
- Hazlo fácil de entender y de repasar: usa títulos cortos y listas.
- No inventes nada que no esté en el documento.
Formato: Markdown sencillo (títulos con #, listas con -, negritas con **).
${READABILITY}`;
  const fecha = new Date().toLocaleDateString('es-ES', { day: 'numeric', month: 'long', year: 'numeric' });
  return `Eres un preparador de estudio riguroso. Hoy es ${fecha}.
El documento adjunto («${name}») es material de estudio. Haz esto:
1. Lee el documento a fondo e identifica las leyes, normas, datos, cifras, organismos, tecnologías y procedimientos que menciona.
2. Busca en internet (fuentes oficiales como BOE, boe.es y webs de organismos públicos cuando existan) si alguno ha cambiado, se ha derogado, modificado o actualizado desde que se escribió el documento.
3. Escribe en español un resumen de estudio con lo más importante, ya CORREGIDO con la información vigente, sin relleno y lo más comprensible posible.
Estructura de la respuesta (Markdown sencillo: títulos con #, listas con -, negritas con **):
# Resumen
(contenido esencial y actualizado, con títulos cortos y listas)
# Cambios y actualizaciones detectados
(qué dice el documento, qué es lo vigente hoy y desde cuándo; si no hay cambios relevantes, dilo claramente)
# Dudas
(cualquier punto que no hayas podido verificar)
Sé honesto: si no encuentras confirmación de un cambio, no lo des por cierto.
${READABILITY}`;
}

function errorMessage(code, raw) {
  let detail = '';
  try { detail = JSON.parse(raw).error.message || ''; } catch { /* sin detalle */ }
  if (code === 400 && /API key/i.test(detail)) return 'La clave de Gemini no es válida. Revísala en Ajustes.';
  if (code === 401 || code === 403) return 'Gemini rechazó la clave. Revísala en Ajustes.';
  if (code === 429) return 'Límite de Gemini alcanzado. Espera un rato (o hasta mañana) y vuelve a intentarlo.' + (detail ? ` Detalle de Google: ${detail.slice(0, 500)}` : '');
  if (code === 503) return 'Gemini sigue saturado tras varios intentos. Inténtalo de nuevo en unos minutos.';
  if (code === 404) return 'El modelo de Gemini ya no está disponible. Hay que actualizar la app.';
  return `Error de Gemini (${code})` + (detail ? `: ${detail}` : '');
}

function toBase64(bytes) {
  const u8 = new Uint8Array(bytes);
  let s = '';
  for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
  return btoa(s);
}

const sleep = (ms, signal) => new Promise((resolve, reject) => {
  const t = setTimeout(resolve, ms);
  signal?.addEventListener('abort', () => { clearTimeout(t); reject(new DOMException('Cancelado', 'AbortError')); });
});

/** `onProgress(etapa, fracción|null)`: la fracción es null cuando no se puede saber cuánto falta. Devuelve {text, sources}. */
export async function analyze({ apiKey, bytes, name, mode, onProgress = () => {}, signal }) {
  if (!apiKey) throw new GeminiError('Falta tu clave de Gemini. Consíguela gratis en Ajustes.');
  if (bytes.byteLength > MAX_BYTES) throw new GeminiError('El PDF es demasiado grande para analizarlo en la versión web (máximo 14 MB).');
  onProgress('Preparando el documento…', null);
  const body = JSON.stringify({
    contents: [{ role: 'user', parts: [{ inlineData: { mimeType: 'application/pdf', data: toBase64(bytes) } }, { text: prompt(name, mode) }] }],
    ...(mode === 'research' ? { tools: [{ googleSearch: {} }] } : {}),
    generationConfig: { temperature: 0.3 },
  });

  // Por cada modelo: si Google está saturado (500/503) se reintenta con espera creciente; si sigue fallando (o no hay cupo) se pasa al siguiente.
  const waits = [5000, 15000];
  let last = null;
  const models = MODELS[mode];
  for (let m = 0; m < models.length; m++) {
    if (m > 0) onProgress('Probando con otro modelo…', null);
    for (let attempt = 0; ; attempt++) {
      try {
        return await generate({ apiKey, model: models[m], body, mode, onProgress, signal });
      } catch (e) {
        if (!(e instanceof GeminiError)) throw e;
        if (e.retryable && attempt < waits.length) { onProgress('Gemini está saturado; reintentando…', null); await sleep(waits[attempt], signal); continue; }
        if (!e.fallback) throw e;
        last = e;
        break;
      }
    }
  }
  throw last || new GeminiError('No se pudo analizar el documento.');
}

async function generate({ apiKey, model, body, mode, onProgress, signal }) {
  onProgress(mode === 'research' ? 'Analizando y buscando en internet…' : 'Analizando el documento…', null);
  const expected = mode === 'research' ? 7000 : 5000;
  let res;
  try {
    res = await fetch(`${BASE}/v1beta/models/${model}:streamGenerateContent?alt=sse`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'x-goog-api-key': apiKey }, body, signal,
    });
  } catch (e) {
    if (e.name === 'AbortError') throw e;
    throw new GeminiError('No se pudo conectar con Gemini. ¿Tienes conexión a internet?');
  }
  if (!res.ok) {
    const code = res.status;
    throw new GeminiError(errorMessage(code, await res.text()), { retryable: code === 500 || code === 503, fallback: [404, 429, 500, 503].includes(code) });
  }

  let text = '', blocked = '';
  const sources = new Map();
  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '';
  const handle = (line) => {
    if (!line.startsWith('data:')) return;
    const chunk = JSON.parse(line.slice(5).trim());
    if (chunk.error) {
      const c = chunk.error.code || 500;
      throw new GeminiError(errorMessage(c, JSON.stringify(chunk)), { retryable: c === 500 || c === 503, fallback: [404, 429, 500, 503].includes(c) });
    }
    if (chunk.promptFeedback?.blockReason) blocked = chunk.promptFeedback.blockReason;
    const cand = chunk.candidates?.[0];
    if (!cand) return;
    for (const p of cand.content?.parts || []) if (!p.thought && p.text) text += p.text;
    if (text) onProgress('Escribiendo el resumen…', Math.min(0.95, text.length / expected));
    for (const c of cand.groundingMetadata?.groundingChunks || []) if (c.web?.uri && !sources.has(c.web.uri)) sources.set(c.web.uri, c.web.title || c.web.uri);
  };
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      const lines = buf.split('\n');
      buf = lines.pop();
      lines.forEach(handle);
    }
    if (buf) handle(buf);
  } catch (e) {
    if (e instanceof GeminiError || e.name === 'AbortError') throw e;
    throw new GeminiError('Se cortó la conexión con Gemini. Inténtalo de nuevo.');
  }

  text = text.trim();
  if (text.startsWith(UNREADABLE)) throw new GeminiError('No se puede leer el documento: está vacío, borroso o demasiado tachado para analizarlo.');
  if (!text) throw new GeminiError(blocked ? `Gemini no devolvió respuesta (${blocked}).` : 'Gemini devolvió una respuesta vacía. Prueba de nuevo.', { retryable: !blocked });
  return { text, sources: [...sources].map(([uri, title]) => [title, uri]) };
}
