# Delhi Bus Live — 3-minute walkthrough film

`build.py` turns the raw screen recording of the app into a finished 1920×1080, 3:00 film:
title and act cards, per-chapter lower thirds, word-by-word kinetic captions, a drifting
light-field background, a light warm grade on the footage, cross-dissolve/slide transitions,
and an ambient score synthesized to the cut and normalized to -16 LUFS.

```bash
python3 promo-video/build.py recording.mp4 out.mp4 /path/to/workdir
```

Needs `ffmpeg` (with libfreetype/libx264), Python 3 with `numpy` and `Pillow`, and the Inter font family.
The edit decision list (source in/out points, chapter titles, captions) is the `SEGS` table at the top of the script.
