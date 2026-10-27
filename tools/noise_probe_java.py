import math

import numpy as np

MASK = (1 << 48) - 1


class JavaRandom:
    def __init__(self, seed):
        self.seed = (seed ^ 0x5DEECE66D) & MASK
        self.next_gaussian = None

    def _next(self, bits):
        self.seed = (self.seed * 0x5DEECE66D + 0xB) & MASK
        r = self.seed >> (48 - bits)
        return r - (1 << bits) if r >= 1 << (bits - 1) else r

    def next_float(self):
        return (self._next(24) & 0xFFFFFF) / float(1 << 24)

    def next_double(self):
        return (((self._next(26) & 0x3FFFFFF) << 27) + (self._next(27) & 0x7FFFFFF)) * (1.0 / (1 << 53))

    def gaussian(self):
        if self.next_gaussian is not None:
            v, self.next_gaussian = self.next_gaussian, None
            return v
        while True:
            v1 = 2 * self.next_double() - 1
            v2 = 2 * self.next_double() - 1
            s = v1 * v1 + v2 * v2
            if 0 < s < 1:
                break
        m = math.sqrt(-2 * math.log(s) / s)
        self.next_gaussian = v2 * m
        return v1 * m


def f32(v):
    return float(np.float32(v))


def low_pass(x, a):
    acc = np.float32(0)
    a = np.float32(a)
    b = np.float32(1) - a
    for i in range(len(x)):
        acc = np.float32(a * acc + b * x[i])
        x[i] = acc
    return x


def amplitude(db):
    return np.float32(10 ** (db / 20))


def samples(rate=16000, seconds=12, floor_db=-74.0):
    r = JavaRandom(1)
    n = seconds * rate
    x = low_pass(np.array([r.gaussian() for _ in range(n)], dtype=np.float32), 0.9)
    floor_rms = np.float32(math.sqrt(float(np.sum(x.astype(np.float64) ** 2)) / n))
    x *= np.float32(amplitude(floor_db) / floor_rms)

    def uniform(a, b):
        return np.float32(a + np.float32(r.next_float()) * np.float32(b - a))

    def add(t, burst, peak_db):
        start = int(np.float32(t) * rate)
        gain = np.float32(amplitude(peak_db) / np.max(np.abs(burst)))
        end = min(n, start + len(burst))
        x[start:end] += (burst[:end - start] * gain).astype(np.float32)

    def click():
        return np.array([np.float32(r.gaussian()) * np.float32(math.exp(-i / 24.0)) for i in range(96)], dtype=np.float32)

    third = np.float32(seconds / 3)
    t = np.float32(0.3)
    while t < third:
        burst = click()
        add(t, burst, uniform(-50, -20))
        t = np.float32(t + uniform(0.25, 0.5))
    while t < 2 * third:
        burst = click()
        add(t, burst, uniform(-50, -20))
        t = np.float32(t + uniform(0.08, 0.2))
    while t < seconds - 0.8:
        k = int(uniform(0.4, 0.8) * rate)
        breath = low_pass(np.array([r.gaussian() for _ in range(k)], dtype=np.float32), 0.7)
        breath *= np.array([np.float32(0.5 - 0.5 * math.cos(2 * math.pi * i / (k - 1))) for i in range(k)], dtype=np.float32)
        add(t, breath, uniform(-50, -30))
        t = np.float32(t + np.float32(k) / rate + uniform(0.2, 0.5))
    return np.clip(np.round(x * 32768), -32768, 32767).astype(np.float32)
