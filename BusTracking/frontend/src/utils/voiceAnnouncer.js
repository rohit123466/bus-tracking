// Shared Web Speech API helper for spoken stop announcements, plus the
// on/off flag persisted so the setting sticks across pages and reloads.
const STORAGE_KEY = 'dtc_voice_announcements';
const CHANGE_EVENT = 'dtc-voice-announcements-changed';

export function isVoiceAnnouncementsEnabled() {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'true';
  } catch {
    return false;
  }
}

export function setVoiceAnnouncementsEnabled(enabled) {
  try {
    localStorage.setItem(STORAGE_KEY, String(enabled));
  } catch {
    // localStorage unavailable (private browsing, etc.) - setting just won't persist.
  }
  window.dispatchEvent(new CustomEvent(CHANGE_EVENT, { detail: enabled }));
}

export function subscribeVoiceAnnouncementsEnabled(callback) {
  const handler = (e) => callback(e.detail);
  window.addEventListener(CHANGE_EVENT, handler);
  return () => window.removeEventListener(CHANGE_EVENT, handler);
}

export function speak(text) {
  if (!isVoiceAnnouncementsEnabled()) return;
  if (typeof window === 'undefined' || !('speechSynthesis' in window)) return;
  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(text);
  window.speechSynthesis.speak(utterance);
}
