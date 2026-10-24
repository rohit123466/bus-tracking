// A tiny localStorage-backed store shared by every component that reads it, kept in sync across
// tabs through the `storage` event. Storage can be missing or throw (private mode, blocked site
// data, node tests); the store then simply lives in memory for this page load.

function read(key, fallback, validate) {
  try {
    const raw = globalThis.localStorage?.getItem(key);
    if (raw == null) return fallback;
    const value = JSON.parse(raw);
    return validate(value) ? value : fallback;
  } catch {
    return fallback;
  }
}

export function createPersistentStore(key, fallback, validate = () => true) {
  let value = read(key, fallback, validate);
  const listeners = new Set();
  const emit = () => listeners.forEach(l => l());

  const set = (next) => {
    value = typeof next === 'function' ? next(value) : next;
    try { globalThis.localStorage?.setItem(key, JSON.stringify(value)); } catch { /* memory only */ }
    emit();
  };

  if (typeof window !== 'undefined') {
    window.addEventListener('storage', (e) => {
      if (e.key !== key) return;
      value = read(key, fallback, validate);
      emit();
    });
  }

  return {
    key,
    get: () => value,
    set,
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}
