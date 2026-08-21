import { useCallback, useEffect, useState } from 'react';
import {
  isVoiceAnnouncementsEnabled,
  setVoiceAnnouncementsEnabled,
  subscribeVoiceAnnouncementsEnabled,
} from '../utils/voiceAnnouncer';

// Keeps any component reading the voice-announcements toggle in sync, since
// it can be flipped from the NavBar while a different page is mounted.
export default function useVoiceAnnouncements() {
  const [enabled, setEnabled] = useState(isVoiceAnnouncementsEnabled);

  useEffect(() => subscribeVoiceAnnouncementsEnabled(setEnabled), []);

  const toggle = useCallback(() => setVoiceAnnouncementsEnabled(!enabled), [enabled]);

  return [enabled, toggle];
}
