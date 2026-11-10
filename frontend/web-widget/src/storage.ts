// The browser's memory of the widget (ADR 0014, WC2): the session token in the shop page's localStorage,
// by decision. Storage can be missing or throw (private windows, blocked site data): the widget then works
// for the page's lifetime only.

const memory = new Map<string, string>()

function key(publicKey: string, name: string): string {
  return `cauce:web:${publicKey}:${name}`
}

export function load(publicKey: string, name: string): string | null {
  try {
    return window.localStorage.getItem(key(publicKey, name))
  } catch {
    return memory.get(key(publicKey, name)) ?? null
  }
}

export function save(publicKey: string, name: string, value: string): void {
  memory.set(key(publicKey, name), value)
  try {
    window.localStorage.setItem(key(publicKey, name), value)
  } catch {
    // the in-memory copy keeps it for this page
  }
}

export function forget(publicKey: string, name: string): void {
  memory.delete(key(publicKey, name))
  try {
    window.localStorage.removeItem(key(publicKey, name))
  } catch {
    // nothing stored
  }
}
