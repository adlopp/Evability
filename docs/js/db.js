// Almacenamiento en el propio dispositivo (IndexedDB): documentos, anotaciones, miniaturas, papelera y pegatinas propias.

const STORES = ['meta', 'trash', 'pdf', 'anns', 'thumb', 'ust', 'kv'];
let dbp;

function open() {
  if (!dbp) {
    dbp = new Promise((resolve, reject) => {
      const req = indexedDB.open('evability', 1);
      req.onupgradeneeded = () => STORES.forEach((s) => { if (!req.result.objectStoreNames.contains(s)) req.result.createObjectStore(s); });
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }
  return dbp;
}

async function run(store, mode, fn) {
  const db = await open();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(store, mode);
    const req = fn(tx.objectStore(store));
    tx.oncomplete = () => resolve(req ? req.result : undefined);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

export const get = (store, key) => run(store, 'readonly', (s) => s.get(key));
export const put = (store, key, value) => run(store, 'readwrite', (s) => s.put(value, key));
export const del = (store, key) => run(store, 'readwrite', (s) => s.delete(key));
export const values = (store) => run(store, 'readonly', (s) => s.getAll());
export const keys = (store) => run(store, 'readonly', (s) => s.getAllKeys());
export const clear = (store) => run(store, 'readwrite', (s) => s.clear());
