#!/usr/bin/env python3
"""How well each test band gets through: matched-filter the Lab's chirp track in a recording.

    chirp_margin.py recording.wav [more.wav ...]

For each band (the same chirps as app/.../acoustic/Chirps.kt): the detection margin,
peak over the background of the matched filter's output, in dB, per second and on
average; how steady the chirps' arrival period is (the ranging jitter at rest); and
the ratio of the strongest to the second echo, a feel for reverberation.
"""
import sys
import wave

import numpy as np

RATE = 48_000
PERIOD = RATE
LENGTH = RATE * 60 // 1000
BANDS = [("low", 2_000, 5_000, 0), ("mid", 8_000, 12_000, PERIOD * 3 // 10), ("high", 16_500, 20_000, PERIOD * 6 // 10)]
SPEED_OF_SOUND = 343.0


def chirp(lo, hi):
    t = np.arange(LENGTH) / RATE
    seconds = LENGTH / RATE
    phase = 2 * np.pi * (lo * t + (hi - lo) / (2 * seconds) * t * t)
    return np.sin(phase) * np.hanning(LENGTH)


def read(path):
    with wave.open(path) as w:
        assert w.getframerate() == RATE and w.getnchannels() == 1 and w.getsampwidth() == 2, "48 kHz mono 16-bit expected"
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64) / 32768


def matched(x, template):
    n = 1 << int(np.ceil(np.log2(len(x) + len(template))))
    y = np.fft.irfft(np.fft.rfft(x, n) * np.conj(np.fft.rfft(template, n)), n)[: len(x)]
    return np.abs(y)


def analyse(path):
    x = read(path)
    print(f"{path}: {len(x) / RATE:.1f} s, peak level {np.max(np.abs(x)):.2f}")
    for name, lo, hi, _ in BANDS:
        env = matched(x, chirp(lo, hi))
        seconds = len(x) // PERIOD
        margins, arrivals = [], []
        for s in range(seconds):
            seg = env[s * PERIOD:(s + 1) * PERIOD]
            peak = int(np.argmax(seg))
            background = np.median(seg)
            margins.append(20 * np.log10(seg[peak] / background) if background > 0 else 0)
            arrivals.append(s * PERIOD + peak)
        margins = np.array(margins)
        arrivals = np.array(arrivals)
        # Arrival period: the steadiness of the chirps' timing, as ranging jitter at rest.
        periods = np.diff(arrivals)
        steady = periods[np.abs(periods - PERIOD) < RATE // 50]  # within 20 ms of a second
        jitter_cm = np.std(steady) / RATE * SPEED_OF_SOUND * 100 if len(steady) > 2 else float("nan")
        found = len(steady) + 1 if len(steady) else 0
        # Reverberation: the second-strongest peak at least 5 ms after the first, over the first.
        echo = []
        for a in arrivals:
            seg = env[a: a + RATE // 20]
            if len(seg) < RATE // 100:
                continue
            later = seg[RATE // 200:]
            echo.append(20 * np.log10(np.max(later) / seg[0]) if seg[0] > 0 else 0)
        print(
            f"  {name:4s} {lo // 1000:2d}-{hi // 1000:2d} kHz: margin {np.mean(margins):5.1f} dB on average, "
            f"{np.min(margins):5.1f} worst of {seconds} s · steady arrivals {found}/{seconds}, "
            f"jitter {jitter_cm:.1f} cm · strongest echo {np.mean(echo):+.1f} dB vs the first"
        )


if __name__ == "__main__":
    for p in sys.argv[1:]:
        analyse(p)
