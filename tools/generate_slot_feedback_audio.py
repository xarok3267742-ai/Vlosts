#!/usr/bin/env python3
"""Generate deterministic, license-free slot feedback WAV assets."""

from __future__ import annotations

import math
import random
import struct
import wave
from pathlib import Path


SAMPLE_RATE = 44_100
OUTPUT_DIR = Path(__file__).resolve().parents[1] / "app/src/main/res/raw"


def envelope(time: float, duration: float, attack: float, release: float) -> float:
    attack_gain = min(1.0, time / max(attack, 1e-6))
    release_gain = min(1.0, (duration - time) / max(release, 1e-6))
    return max(0.0, min(attack_gain, release_gain))


def sine(frequency: float, time: float, phase: float = 0.0) -> float:
    return math.sin(math.tau * frequency * time + phase)


def write_wave(name: str, samples: list[float]) -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    peak = max(1.0, max(abs(sample) for sample in samples) / 0.94)
    payload = bytearray()
    for sample in samples:
        pcm = int(max(-1.0, min(1.0, sample / peak)) * 32_767)
        payload.extend(struct.pack("<h", pcm))

    path = OUTPUT_DIR / name
    with wave.open(str(path), "wb") as target:
        target.setnchannels(1)
        target.setsampwidth(2)
        target.setframerate(SAMPLE_RATE)
        target.writeframes(payload)
    print(f"generated {path.relative_to(OUTPUT_DIR.parents[4])}")


def spin_start() -> list[float]:
    duration = 0.58
    rng = random.Random(7)
    samples: list[float] = []
    filtered_noise = 0.0
    for index in range(int(duration * SAMPLE_RATE)):
        time = index / SAMPLE_RATE
        progress = time / duration
        filtered_noise = filtered_noise * 0.72 + rng.uniform(-1.0, 1.0) * 0.28
        motor_frequency = 72.0 + 178.0 * progress * progress
        pulse = sine(motor_frequency, time) + 0.34 * sine(motor_frequency * 2.03, time)
        gain = envelope(time, duration, attack=0.035, release=0.16)
        samples.append((0.34 * pulse + 0.22 * filtered_noise) * gain)
    return samples


def reel_spin_loop() -> list[float]:
    """A phase-aligned motor bed that loops without an audible boundary click."""
    duration = 0.75
    samples: list[float] = []
    for index in range(int(duration * SAMPLE_RATE)):
        time = index / SAMPLE_RATE
        modulation = 0.9 + 0.1 * sine(4.0, time)
        motor = (
            0.44 * sine(84.0, time)
            + 0.22 * sine(168.0, time, phase=0.18)
            + 0.11 * sine(252.0, time, phase=0.34)
        )
        belt = 0.08 * sine(1_008.0, time, phase=0.12) + 0.04 * sine(1_512.0, time)
        samples.append((motor * modulation + belt) * 0.72)
    return samples


def reel_stop() -> list[float]:
    duration = 0.16
    rng = random.Random(11)
    samples: list[float] = []
    for index in range(int(duration * SAMPLE_RATE)):
        time = index / SAMPLE_RATE
        thump = sine(92.0, time) * math.exp(-time * 24.0)
        click = rng.uniform(-1.0, 1.0) * math.exp(-time * 82.0)
        metal = sine(1_760.0, time) * math.exp(-time * 46.0)
        samples.append(0.62 * thump + 0.34 * click + 0.16 * metal)
    return samples


def payout_tick() -> list[float]:
    return arpeggio(
        notes=[(0.00, 659.25), (0.10, 783.99)],
        duration=0.38,
        sparkle=False,
    )


def arpeggio(notes: list[tuple[float, float]], duration: float, sparkle: bool) -> list[float]:
    samples: list[float] = []
    for index in range(int(duration * SAMPLE_RATE)):
        time = index / SAMPLE_RATE
        value = 0.0
        for start, frequency in notes:
            local = time - start
            if local < 0.0:
                continue
            note_duration = min(0.58, duration - start)
            if local >= note_duration:
                continue
            gain = envelope(local, note_duration, attack=0.012, release=0.22) * math.exp(-local * 1.45)
            value += gain * (
                0.55 * sine(frequency, local)
                + 0.20 * sine(frequency * 2.0, local)
                + 0.10 * sine(frequency * 3.01, local)
            )
        if sparkle:
            value += 0.055 * sine(2_900.0 + 420.0 * math.sin(time * 8.0), time) * max(0.0, 1.0 - time / duration)
        samples.append(value)
    return samples


def main() -> None:
    write_wave("slot_spin_start.wav", spin_start())
    write_wave("slot_reel_spin_loop.wav", reel_spin_loop())
    write_wave("slot_reel_stop.wav", reel_stop())
    write_wave("slot_payout.wav", payout_tick())
    write_wave(
        "slot_win.wav",
        arpeggio(
            notes=[(0.00, 523.25), (0.12, 659.25), (0.24, 783.99), (0.38, 1_046.50)],
            duration=0.96,
            sparkle=True,
        ),
    )
    write_wave(
        "slot_bonus.wav",
        arpeggio(
            notes=[(0.00, 392.00), (0.13, 493.88), (0.26, 587.33), (0.41, 783.99), (0.59, 987.77)],
            duration=1.38,
            sparkle=True,
        ),
    )


if __name__ == "__main__":
    main()
