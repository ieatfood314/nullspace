#!/usr/bin/env python3
"""Prints the SVGs for the hero key and the gesture glyphs (pasted into index.html)."""
import math

N, GAP, C = 21, 20, 10


def key():
    out = ['<svg class="key" viewBox="0 0 420 420" aria-hidden="true" focusable="false">']
    for j in range(N):
        for i in range(N):
            d = math.hypot(i - C, j - C)
            if d > 10.2:
                continue
            cls = "c" if d <= 7.6 else "r" if d > 8.6 else None
            if cls is None:
                continue
            r = 6.4 if cls == "c" else 4.6
            delay = int(d * 55)
            out.append(f'<circle class="{cls}" cx="{i * GAP + 10}" cy="{j * GAP + 10}" r="{r}" style="--d:{delay}ms"/>')
    out.append("</svg>")
    return "\n".join(out)


def glyph(lit, cells=12):
    out = [f'<svg class="glyph" viewBox="0 0 {cells * 10} 10" aria-hidden="true" focusable="false">']
    for i in range(cells):
        out.append(f'<circle{" class=on" if i in lit else ""} cx="{i * 10 + 5}" cy="5" r="3"/>')
    out.append("</svg>")
    return "".join(out)


if __name__ == "__main__":
    print(key())
    for name, lit in [("hold", range(0, 9)), ("press", [0]), ("press-hold", [0, *range(3, 10)]), ("double", [0, 2])]:
        print(name, glyph(set(lit)))
