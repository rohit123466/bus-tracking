#!/usr/bin/env python3
"""Build the 3-minute "Delhi Bus Live" walkthrough film from a raw screen recording.

usage: python3 build.py SOURCE.mp4 OUTPUT.mp4 WORKDIR

Pipeline: generate motion-graphic assets (Pillow) -> render each segment onto an
animated canvas with grade, lower third and kinetic type (ffmpeg) -> synthesize an
ambient score timed to the cuts (numpy) -> xfade everything together, loudness-normalize.
"""
import json
import os
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SRC, OUT, WORK = sys.argv[1], sys.argv[2], sys.argv[3]
A = os.path.join(WORK, 'assets')
os.makedirs(A, exist_ok=True)

W, H, FPS = 1920, 1080, 30
TOTAL = 180.0
CARD_W, CARD_H, CARD_X, CARD_Y = 1680, 757, 120, 128
FD = '/usr/share/fonts/opentype/inter/'
F_BOLD, F_SEMI = FD + 'InterDisplay-Bold.otf', FD + 'Inter-SemiBold.otf'
F_MED, F_REG, F_LIGHT = FD + 'Inter-Medium.otf', FD + 'Inter-Regular.otf', FD + 'InterDisplay-Light.otf'
WHITE, GREY, GREEN, AMBER = (246, 243, 236, 255), (178, 186, 181, 255), (52, 178, 120, 255), (240, 178, 98, 255)

# ---------------------------------------------------------------- edit decision list
CHAPTERS = ['Live Explore', 'Nearby Stops', 'Journey Planner', 'Saved & Routes',
            'Accessibility', 'Operations', 'Fleet & Health']
SEGS = {
    'c1': dict(src=(0.0, 42.0), pref=30, ch=1, lt=('Live Explore', 'Real-time GPS for every DTC bus across North & North-West Delhi'),
               hi='Every bus, live on the map.'),
    'c2': dict(src=(54.0, 83.0), pref=19, ch=2, lt=('Nearby Stops', 'Upcoming arrivals, ranked by walking distance'),
               hi='Know exactly when to leave.'),
    'c3': dict(src=(103.0, 131.0), pref=21, ch=3, lt=('Journey Planner', 'Door-to-door options with walk and ride times'),
               hi='≈ 39 minutes, door to door.'),
    'c4': dict(src=(131.0, 156.0), pref=13, ch=4, lt=('Saved & Route Search', 'Pin your places, look up any DTC route'),
               hi='Your city, one tap away.'),
    'c5': dict(src=(173.0, 225.8), pref=32, ch=5, lt=('Accessibility & Seats', 'Wheelchair-ready buses and live seat availability'),
               hi='Accessible by design.'),
    'c6a': dict(src=(226.4, 234.0), pref=8, ch=6, lt=('Operations Console', 'Fleet-wide health for DTC operators, at a glance'),
                hi=None),
    'c6b': dict(src=(254.0, 334.0), pref=33, ch=6, lt=None, hi='Thousands of buses. One live map.'),
    'c7': dict(src=(388.0, 408.0), pref=14, ch=7, lt=('Fleet & System Health', 'Every vehicle, every data feed, monitored'),
               hi='Every feed, monitored.'),
}
CARDS = {'title': 6.5, 'act1': 3.6, 'act2': 3.6, 'outro': 7.0}
ORDER = ['title', 'act1', 'c1', 'c2', 'c3', 'c4', 'c5', 'act2', 'c6a', 'c6b', 'c7', 'outro']
TRANS = [('fade', .8), ('fade', .8), ('smoothleft', .6), ('smoothleft', .6), ('smoothleft', .6),
         ('smoothleft', .6), ('fadeblack', 1.0), ('fade', .8), ('fade', .5), ('smoothleft', .6), ('fadeblack', 1.2)]


def fr(x):
    return round(x * FPS) / FPS


# fit clip durations so the cut lands at exactly TOTAL seconds
clip_budget = TOTAL + sum(d for _, d in TRANS) - sum(CARDS.values())
scale = clip_budget / sum(s['pref'] for s in SEGS.values())
DUR = dict(CARDS)
for k, s in SEGS.items():
    DUR[k] = fr(s['pref'] * scale)
DUR['c7'] = fr(DUR['c7'] + clip_budget - sum(DUR[k] for k in SEGS))

starts, t = [0.0], DUR[ORDER[0]]
for k, (_, xd) in zip(ORDER[1:], TRANS):
    starts.append(t - xd)
    t += DUR[k] - xd
START = dict(zip(ORDER, starts))
assert abs(t - TOTAL) < 1e-6, t


# ---------------------------------------------------------------- graphic assets
def text_png(name, text, font, size, color, tracking=0, pad=10):
    f = ImageFont.truetype(font, size)
    ws = [f.getlength(c) for c in text] if tracking else [f.getlength(text)]
    w = int(sum(ws) + tracking * max(len(text) - 1, 0)) + 2 * pad
    asc, desc = f.getmetrics()
    im = Image.new('RGBA', (w, asc + desc + 2 * pad), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if tracking:
        x = pad
        for c, cw in zip(text, ws):
            d.text((x, pad), c, font=f, fill=color)
            x += cw + tracking
    else:
        d.text((pad, pad), text, font=f, fill=color)
    path = os.path.join(A, name + '.png')
    im.save(path)
    return path, im.size, pad


def radial(name, size, rgb, alpha):
    yy, xx = np.mgrid[0:size, 0:size]
    r = np.hypot(xx - size / 2, yy - size / 2) / (size / 2)
    a = np.clip(1 - r, 0, 1) ** 2.2 * alpha
    arr = np.zeros((size, size, 4), np.uint8)
    arr[..., :3] = rgb
    arr[..., 3] = a.astype(np.uint8)
    Image.fromarray(arr, 'RGBA').save(os.path.join(A, name + '.png'))


def build_assets():
    yy, xx = np.mgrid[0:H, 0:W]
    r = np.hypot((xx - W / 2) / (W / 2), (yy - H * 0.45) / (H / 2))
    v = np.clip(1 - 0.55 * r ** 1.6, 0, 1)[..., None]
    top, bottom = np.array([22, 27, 26]), np.array([14, 17, 17])
    grad = top + (bottom - top) * (yy / H)[..., None]
    Image.fromarray((grad * v + 4).clip(0, 255).astype(np.uint8), 'RGB').save(os.path.join(A, 'bg.png'))
    radial('orb_green', 1100, (40, 150, 100), 80)
    radial('orb_amber', 1000, (210, 140, 70), 55)

    m = Image.new('L', (CARD_W, CARD_H), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, CARD_W - 1, CARD_H - 1), 18, fill=255)
    m.save(os.path.join(A, 'mask.png'))
    sh = Image.new('RGBA', (CARD_W + 120, CARD_H + 120), (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle((60, 74, 60 + CARD_W, 74 + CARD_H), 22, fill=(0, 0, 0, 170))
    sh.filter(ImageFilter.GaussianBlur(26)).save(os.path.join(A, 'shadow.png'))
    rim = Image.new('RGBA', (CARD_W, CARD_H), (0, 0, 0, 0))
    ImageDraw.Draw(rim).rounded_rectangle((0, 0, CARD_W - 1, CARD_H - 1), 18, outline=(255, 255, 255, 38), width=2)
    rim.save(os.path.join(A, 'rim.png'))
    Image.new('RGBA', (W, 4), (52, 178, 120, 255)).save(os.path.join(A, 'progress.png'))

    for ch in range(1, 8):
        im = Image.new('RGBA', (W, CARD_Y), (0, 0, 0, 0))
        d = ImageDraw.Draw(im)
        d.rounded_rectangle((120, 47, 146, 73), 7, fill=GREEN)
        d.ellipse((128, 55, 138, 65), fill=(255, 255, 255, 235))
        f = ImageFont.truetype(F_SEMI, 17)
        x = 160
        for c in 'DELHI BUS LIVE':
            d.text((x, 49), c, font=f, fill=(236, 233, 226, 230))
            x += f.getlength(c) + 3.2
        label, idx = CHAPTERS[ch - 1].upper(), f'{ch:02d} / 07'
        fm = ImageFont.truetype(F_MED, 17)
        lw = sum(fm.getlength(c) + 3 for c in label)
        x = 1800 - lw
        for c in label:
            d.text((x, 49), c, font=fm, fill=GREEN)
            x += fm.getlength(c) + 3
        iw = fm.getlength(idx)
        d.text((1800 - lw - 28 - iw, 49), idx, font=fm, fill=GREY)
        d.line((1800 - lw - 16, 52, 1800 - lw - 16, 68), fill=(255, 255, 255, 60), width=1)
        im.save(os.path.join(A, f'header{ch}.png'))

    for k, s in SEGS.items():
        if not s['lt']:
            continue
        title, sub = s['lt']
        im = Image.new('RGBA', (1100, 110), (0, 0, 0, 0))
        d = ImageDraw.Draw(im)
        d.rounded_rectangle((0, 10, 5, 96), 2, fill=GREEN)
        d.text((24, 2), title, font=ImageFont.truetype(F_BOLD, 40), fill=WHITE)
        d.text((25, 60), sub, font=ImageFont.truetype(F_REG, 23), fill=GREY)
        im.save(os.path.join(A, f'lt_{k}.png'))


# ---------------------------------------------------------------- animated elements
def ease_expr(t_in, d_in):
    return f'pow(1-clip((t-{t_in})/{d_in},0,1),3)'


def el(png, x, y, t_in, d_in=0.55, t_out=None, d_out=0.45, dx=0, dy=26):
    return dict(png=png, x=x, y=y, t_in=t_in, d_in=d_in, t_out=t_out, d_out=d_out, dx=dx, dy=dy)


def phrase(name, text, font, size, cx, y, t0, align='center', stagger=0.11, accent_last=True,
           t_out=None, color=WHITE, accent=AMBER):
    """Word-by-word kinetic reveal: each word rises and fades in on its own beat."""
    words = text.split()
    f = ImageFont.truetype(font, size)
    space = f.getlength(' ')
    pngs = []
    for i, wd in enumerate(words):
        col = accent if accent_last and i == len(words) - 1 else color
        pngs.append(text_png(f'{name}_w{i}', wd, font, size, col))
    total = sum(f.getlength(w) for w in words) + space * (len(words) - 1)
    x = cx - total / 2 if align == 'center' else cx - total
    out = []
    for i, (wd, (p, _, pad)) in enumerate(zip(words, pngs)):
        out.append(el(p, int(x - pad), int(y - pad), round(t0 + i * stagger, 3), t_out=t_out, dy=34))
        x += f.getlength(wd) + space
    return out


def tracked(name, text, font, size, color, tracking, cx, y, t_in, t_out=None, dy=14):
    p, (w, _), pad = text_png(name, text, font, size, color, tracking)
    return el(p, int(cx - w / 2), int(y - pad), t_in, d_in=0.7, t_out=t_out, dy=dy)


def overlay_chain(base, elements, first_input, dur):
    parts, inputs, cur = [], [], base
    for i, e in enumerate(elements):
        idx = first_input + i
        inputs += ['-loop', '1', '-framerate', str(FPS), '-t', f'{dur:.3f}', '-i', e['png']]
        f = f"[{idx}:v]format=rgba,fade=t=in:st={e['t_in']}:d={e['d_in']}:alpha=1"
        if e['t_out'] is not None:
            f += f",fade=t=out:st={e['t_out']}:d={e['d_out']}:alpha=1"
        parts.append(f + f'[e{i}]')
        ee = ease_expr(e['t_in'], e['d_in'])
        nxt = f'o{i}'
        parts.append(f"[{cur}][e{i}]overlay=x='{e['x']}+{e['dx']}*{ee}':y='{e['y']}+{e['dy']}*{ee}'[{nxt}]")
        cur = nxt
    return parts, inputs, cur


def background(g0, dur):
    """Inputs 1-3 + graph for the slowly drifting warm/green light field (continuous across cuts)."""
    inputs = []
    for n in ('bg', 'orb_green', 'orb_amber'):
        inputs += ['-loop', '1', '-framerate', str(FPS), '-t', f'{dur:.3f}', '-i', os.path.join(A, n + '.png')]
    g = f'(t+{g0:.3f})'
    graph = [
        '[1:v]format=rgba[bg]',
        f"[bg][2:v]overlay=x='-380+300*sin(2*PI*{g}/37)':y='-460+200*cos(2*PI*{g}/29)'[b1]",
        f"[b1][3:v]overlay=x='1150+260*cos(2*PI*{g}/43)':y='420+200*sin(2*PI*{g}/31)'[b2]",
    ]
    return inputs, graph


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        sys.stderr.write(' '.join(cmd)[-3000:] + '\n' + r.stderr[-4000:])
        raise SystemExit(1)


def encode(out, inputs, graph, last, dur):
    script = out + '.filter'
    with open(script, 'w') as fh:
        fh.write(';\n'.join(graph + [f'[{last}]format=yuv420p[vout]']))
    run(['ffmpeg', '-y', '-v', 'error'] + inputs + ['-filter_complex_script', script, '-map', '[vout]',
         '-t', f'{dur:.3f}', '-r', str(FPS), '-c:v', 'libx264', '-preset', 'medium', '-crf', '14', '-an', out])


def render_segment(k):
    s, dur = SEGS[k], DUR[k]
    a, b = s['src']
    speed = (b - a) / dur
    bg_in, graph = background(START[k], dur)
    inputs = ['-ss', f'{a:.3f}', '-t', f'{b - a:.3f}', '-i', SRC] + bg_in
    for n in ('shadow', 'mask', 'rim'):
        inputs += ['-loop', '1', '-framerate', str(FPS), '-t', f'{dur:.3f}', '-i', os.path.join(A, n + '.png')]
    inputs += ['-loop', '1', '-framerate', str(FPS), '-t', f'{dur:.3f}', '-i', os.path.join(A, f"header{s['ch']}.png")]
    graph += [
        # warm, gently contrasted grade -- kept light so UI text and map colours stay true
        f'[0:v]setpts=(PTS-STARTPTS)/{speed:.5f},fps={FPS},scale={CARD_W}:{CARD_H}:flags=lanczos,'
        'eq=contrast=1.035:saturation=1.06:gamma=0.985,'
        'colorbalance=rs=0.03:gs=0.005:bs=-0.035:rm=0.025:bm=-0.03:rh=0.012:bh=-0.02,format=rgba[f]',
        f'[5:v]format=gray,scale={CARD_W}:{CARD_H}[m]',
        '[f][m]alphamerge[card]',
        f'[b2][4:v]overlay={CARD_X - 60}:{CARD_Y - 60}[b3]',
        f'[b3][card]overlay={CARD_X}:{CARD_Y}[b4]',
        f'[b4][6:v]overlay={CARD_X}:{CARD_Y}[b5]',
        '[7:v]format=rgba,fade=t=in:st=0:d=0.6:alpha=1[hd]',
        '[b5][hd]overlay=0:0[b6]',
    ]
    els = []
    if s['lt']:
        els.append(el(os.path.join(A, f'lt_{k}.png'), 120, 912, 0.55, d_in=0.75, t_out=min(5.6, dur - 1.2),
                      dx=-44, dy=0))
    if s['hi']:
        t0 = 6.3 if s['lt'] else 1.4
        els += phrase(f'hi_{k}', s['hi'], F_BOLD, 40, 1800, 930, t0, align='right', t_out=dur - 1.4)
    parts, el_in, last = overlay_chain('b6', els, 8, dur)
    encode(os.path.join(WORK, k + '.mp4'), inputs + el_in, graph + parts, last, dur)
    return k, speed


def render_card(k):
    dur = DUR[k]
    bg_in, graph = background(START[k], dur)
    inputs = ['-ss', '0', '-t', '1', '-i', os.path.join(A, 'bg.png')] + bg_in  # input 0 unused placeholder
    if k == 'title':
        els = [tracked('t_eyebrow', 'DTC  ·  NORTH & NORTH-WEST DELHI', F_SEMI, 21, GREEN, 5, W / 2, 392, 0.3)]
        els += phrase('t_head', 'Every bus. Every stop. Live.', F_BOLD, 104, W / 2, 452, 0.75, stagger=0.2)
        els += [tracked('t_sub', 'A walkthrough of Delhi Bus Live', F_LIGHT, 34, GREY, 0.5, W / 2, 610, 2.3)]
    elif k in ('act1', 'act2'):
        eyebrow, head = ('PART ONE', 'The Rider Experience') if k == 'act1' else ('PART TWO', 'The Operations Console')
        els = [tracked(f'{k}_eb', eyebrow, F_SEMI, 21, GREEN, 6, W / 2, 440, 0.2)]
        els += phrase(f'{k}_h', head, F_BOLD, 84, W / 2, 490, 0.45, stagger=0.16)
    else:
        els = phrase('o_head', 'Delhi Bus Live', F_BOLD, 112, W / 2, 400, 0.4, stagger=0.2)
        els += [tracked('o_sub', 'Real-time transit for North & North-West Delhi', F_LIGHT, 36, WHITE, 0.5, W / 2, 560, 1.6),
                tracked('o_tags', 'LIVE GPS   ·   JOURNEY PLANNING   ·   ACCESSIBILITY   ·   FLEET OPERATIONS',
                        F_MED, 18, GREY, 4, W / 2, 650, 2.4)]
    parts, el_in, last = overlay_chain('b2', els, 4, dur)
    encode(os.path.join(WORK, k + '.mp4'), inputs + el_in, graph + parts, last, dur)
    return k, 1.0


# ---------------------------------------------------------------- score
def build_score(path):
    sr = 48000
    n = int(TOTAL * sr)
    rng = np.random.default_rng(7)
    pad, pluck, low, fx = (np.zeros((2, n)) for _ in range(4))
    midi = lambda m: 440 * 2 ** ((m - 69) / 12)
    prog = [[57, 60, 64, 67, 71], [53, 57, 60, 64, 69], [48, 55, 59, 64, 67], [55, 59, 62, 64, 69]]
    roots = [33, 29, 36, 31]
    bar = 6.0  # one chord per 8 beats at 80 bpm
    beat = 0.75
    rider0, ops0, outro0 = START['c1'], START['act2'], START['outro']

    def place(buf, start, sig_l, sig_r):
        i = int(start * sr)
        j = min(n, i + sig_l.size)
        if j > i:
            buf[0, i:j] += sig_l[:j - i]
            buf[1, i:j] += sig_r[:j - i]

    # warm pad: detuned additive voices, slow attack, long release
    for c in range(int(TOTAL / bar) + 1):
        L = int((bar + 3.5) * sr)
        tt = np.arange(L) / sr
        env = np.clip(tt / 2.2, 0, 1) ** 2 * np.clip((bar + 3.5 - tt) / 3.5, 0, 1)
        sl, sr_ = np.zeros(L), np.zeros(L)
        for m in prog[c % 4]:
            for det, side in ((-7, 0), (0, 2), (7, 1)):
                f0 = midi(m) * 2 ** (det / 1200)
                v = sum(np.sin(2 * np.pi * f0 * h * tt + rng.uniform(0, 6.28)) / h ** 1.8 for h in (1, 2, 3, 4))
                if side != 1:
                    sl += v
                if side != 0:
                    sr_ += v
        place(pad, c * bar, sl * env * 0.018, sr_ * env * 0.018)

    # plucked arpeggio: enters with the rider section, doubles up for operations, drops for the outro
    pattern = [0, 2, 4, 1, 3, 2, 4, 3]
    tpl = np.arange(int(1.4 * sr)) / sr
    t = rider0
    step = 0
    while t < outro0 + 1.5:
        div = beat / 4 if ops0 + 1.0 <= t < outro0 else beat / 2
        chord = prog[int(t / bar) % 4]
        m = chord[pattern[step % 8]] + 12
        f0 = midi(m)
        env = np.exp(-tpl / (0.28 if div < 0.3 else 0.42)) * (1 - np.exp(-tpl / 0.004))
        sig = (np.sin(2 * np.pi * f0 * tpl) + 0.35 * np.sin(4 * np.pi * f0 * tpl) + 0.08 * np.sin(6 * np.pi * f0 * tpl)) * env
        vel = (0.75 + 0.25 * (step % 4 == 0)) * rng.uniform(0.85, 1.0) * (0.7 if div < 0.3 else 1.0)
        pan = 0.5 + 0.28 * np.sin(step * 0.9)
        place(pluck, t, sig * vel * (1 - pan) * 0.11, sig * vel * pan * 0.11)
        t += div
        step += 1

    # sub bass under the content sections
    for c in range(int(TOTAL / bar) + 1):
        s0 = c * bar
        if s0 + bar < rider0 or s0 > outro0 + 2:
            continue
        L = int((bar + 0.5) * sr)
        tt = np.arange(L) / sr
        env = np.clip(tt / 0.6, 0, 1) * np.clip((bar + 0.5 - tt) / 1.0, 0, 1)
        sig = np.sin(2 * np.pi * midi(roots[c % 4]) * tt) * env * 0.16
        place(low, s0, sig, sig)

    # soft heartbeat pulse that drives the operations section
    tk = np.arange(int(0.5 * sr)) / sr
    kick = np.sin(2 * np.pi * np.cumsum(45 + 70 * np.exp(-tk / 0.05)) / sr) * np.exp(-tk / 0.16) * 0.32
    t = ops0 + 1.5
    while t < outro0 - 0.5:
        place(low, t, kick, kick)
        t += beat

    # transition whooshes (band-passed noise swells panned across the cut) + low impacts on act cards
    for k, (_, xd) in zip(ORDER[1:], TRANS):
        centre = START[k] + xd / 2
        L = int(2.2 * sr)
        noise = rng.standard_normal(L)
        spec = np.fft.rfft(noise)
        freqs = np.fft.rfftfreq(L, 1 / sr)
        spec *= np.exp(-((np.log(freqs + 1) - np.log(1800)) ** 2) / 0.9)
        noise = np.fft.irfft(spec, L)
        noise /= np.abs(noise).max()
        tt = np.arange(L) / sr
        env = np.where(tt < 1.4, (tt / 1.4) ** 3, np.exp(-(tt - 1.4) / 0.18))
        pan = np.clip(tt / 2.2, 0, 1)
        g = 0.09 if k in ('act1', 'act2', 'outro') else 0.06
        place(fx, centre - 1.4, noise * env * (1 - pan) * g, noise * env * pan * g)
        if k in ('act1', 'act2', 'outro'):
            ti = np.arange(int(2.5 * sr)) / sr
            boom = np.sin(2 * np.pi * np.cumsum(38 + 30 * np.exp(-ti / 0.15)) / sr) * np.exp(-ti / 0.6) * 0.3
            place(low, START[k], boom, boom)

    # level automation: room for the visuals early, lift for operations, resolve in the outro
    tt = np.arange(n) / sr
    auto = np.interp(tt, [0, rider0, ops0, ops0 + 2, outro0, TOTAL], [1.0, 0.82, 0.82, 1.0, 1.05, 1.05])
    wet_src = pad + pluck * 0.9 + fx
    # simple stereo plate: decaying filtered noise impulse response, convolved via FFT
    irl = int(3.2 * sr)
    ti = np.arange(irl) / sr
    size = 1 << int(np.ceil(np.log2(n + irl)))
    mix = np.zeros((2, n))
    for ch in range(2):
        ir = rng.standard_normal(irl) * np.exp(-ti / 0.75)
        ir = np.convolve(ir, np.ones(6) / 6, 'same')
        ir /= np.sqrt((ir ** 2).sum())
        wet = np.fft.irfft(np.fft.rfft(wet_src[ch], size) * np.fft.rfft(ir, size), size)[:n]
        mix[ch] = (pad[ch] + pluck[ch] + fx[ch] + low[ch]) + wet * 0.35
    mix *= auto
    mix *= np.clip(tt / 1.5, 0, 1) * np.clip((TOTAL - tt) / 4.0, 0, 1)
    mix = np.tanh(mix * 1.6) / 1.6
    pcm = (mix / np.abs(mix).max() * 0.8 * 32767).astype('<i2').T
    import wave
    with wave.open(path, 'wb') as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def loudnorm(src, dst, target=-16.0):
    r = subprocess.run(['ffmpeg', '-hide_banner', '-i', src, '-af', f'loudnorm=I={target}:TP=-1.5:LRA=11:print_format=json',
                        '-f', 'null', '-'], capture_output=True, text=True)
    m = json.loads(r.stderr[r.stderr.rindex('{'):])
    run(['ffmpeg', '-y', '-v', 'error', '-i', src, '-af',
         f"loudnorm=I={target}:TP=-1.5:LRA=11:measured_I={m['input_i']}:measured_TP={m['input_tp']}:"
         f"measured_LRA={m['input_lra']}:measured_thresh={m['input_thresh']}:offset={m['target_offset']}:linear=true,"
         'aresample=48000', dst])


# ---------------------------------------------------------------- assemble
def assemble():
    inputs, graph = [], []
    for i, k in enumerate(ORDER):
        inputs += ['-i', os.path.join(WORK, k + '.mp4')]
        graph.append(f'[{i}:v]settb=AVTB,fps={FPS},format=yuv420p[v{i}]')
    cur = 'v0'
    for i, ((kind, xd), k) in enumerate(zip(TRANS, ORDER[1:])):
        graph.append(f'[{cur}][v{i + 1}]xfade=transition={kind}:duration={xd}:offset={START[k]:.4f}[x{i}]')
        cur = f'x{i}'
    p0, p1 = START['c1'], START['outro'] + TRANS[-1][1]
    pi = len(ORDER)
    inputs += ['-loop', '1', '-framerate', str(FPS), '-t', str(TOTAL), '-i', os.path.join(A, 'progress.png')]
    graph.append(f"[{pi}:v]format=rgba,colorchannelmixer=aa=0.85[pb]")
    graph.append(f"[{cur}][pb]overlay=x='-{W}+{W}*clip((t-{p0:.3f})/{p1 - p0:.3f},0,1)':y=0:"
                 f"enable='between(t,{p0:.3f},{p1:.3f})'[pv]")
    graph.append(f'[pv]fade=t=out:st={TOTAL - 1.6}:d=1.6,format=yuv420p[vout]')
    inputs += ['-i', os.path.join(WORK, 'score_norm.wav')]
    script = os.path.join(WORK, 'final.filter')
    with open(script, 'w') as fh:
        fh.write(';\n'.join(graph))
    run(['ffmpeg', '-y', '-v', 'error'] + inputs + [
        '-filter_complex_script', script, '-map', '[vout]', '-map', f'{pi + 1}:a', '-t', str(TOTAL),
        '-c:v', 'libx264', '-preset', 'slow', '-crf', '19', '-profile:v', 'high', '-pix_fmt', 'yuv420p',
        '-c:a', 'aac', '-b:a', '256k', '-movflags', '+faststart', OUT])


if __name__ == '__main__':
    build_assets()
    print('timeline:', json.dumps({k: [round(START[k], 2), DUR[k]] for k in ORDER}))
    build_score(os.path.join(WORK, 'score.wav'))
    loudnorm(os.path.join(WORK, 'score.wav'), os.path.join(WORK, 'score_norm.wav'))
    with ThreadPoolExecutor(2) as ex:
        for k, sp in ex.map(lambda k: render_segment(k) if k in SEGS else render_card(k), ORDER):
            print(f'rendered {k} (speed {sp:.2f}x)', flush=True)
    assemble()
    print('done ->', OUT)
