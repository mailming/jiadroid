"""Draw the printable mini-person marker.

The square code says `jiadroid:person`. Printed at actual size, that code is
60 mm wide, which is the width the Android app uses for distance.
"""

from pathlib import Path

import segno

PAYLOAD = "jiadroid:person"
QR_MM = 60.0
OUT = Path(__file__).with_name("mini-person.svg")


def main() -> None:
    code = segno.make(PAYLOAD, error="h")
    rows = [list(row) for row in code.matrix_iter(scale=1, border=0)]
    modules = len(rows)
    module_mm = QR_MM / modules
    quiet = 4 * module_mm
    outer = 14.0
    top = 38.0
    card_w = outer + quiet + QR_MM + quiet + outer
    qr_x = outer + quiet
    qr_y = top + quiet
    quiet_bottom = qr_y + QR_MM + quiet
    dim_y = quiet_bottom + 26.0
    fold_y = dim_y + 10.0
    card_h = fold_y + 16.0
    parts = [
        svg_header(card_w, card_h),
        f'<rect width="{card_w:.2f}" height="{card_h:.2f}" fill="#f4f0e6"/>',
        person(card_w, qr_x, qr_y, quiet, quiet_bottom),
        white_plate(qr_x, qr_y, quiet),
        qr_rects(rows, qr_x, qr_y, module_mm),
        dimension(qr_x, dim_y),
        fold(card_w, fold_y),
        f'<rect x="0.4" y="0.4" width="{card_w - 0.8:.2f}" height="{card_h - 0.8:.2f}" fill="none" stroke="#1c1915" stroke-width="0.6"/>',
        "</svg>",
    ]
    OUT.write_text("\n".join(parts), encoding="utf-8")
    print(f"wrote {OUT} ({modules} modules, code {QR_MM:.0f} mm)")


def svg_header(width: float, height: float) -> str:
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width:.2f}mm" height="{height:.2f}mm" '
        f'viewBox="0 0 {width:.2f} {height:.2f}">'
    )


def person(card_w: float, qr_x: float, qr_y: float, quiet: float, quiet_bottom: float) -> str:
    cx = card_w / 2
    arm_h = 26.0
    arm_w = 7.0
    arm_y = qr_y + (QR_MM - arm_h) / 2
    left_arm = qr_x - quiet - 3 - arm_w
    right_arm = qr_x + QR_MM + quiet + 3
    return "\n".join(
        [
            f'<circle cx="{cx:.2f}" cy="16" r="8" fill="#c4512c"/>',
            f'<circle cx="{cx - 2.4:.2f}" cy="15.2" r="0.8" fill="#1c1915"/>',
            f'<circle cx="{cx + 2.4:.2f}" cy="15.2" r="0.8" fill="#1c1915"/>',
            f'<text x="{cx:.2f}" y="32" text-anchor="middle" font-family="sans-serif" font-size="4" fill="#1c1915">mini person</text>',
            f'<rect x="{left_arm:.2f}" y="{arm_y:.2f}" width="{arm_w:.2f}" height="{arm_h:.2f}" rx="3" fill="#c4512c"/>',
            f'<rect x="{right_arm:.2f}" y="{arm_y:.2f}" width="{arm_w:.2f}" height="{arm_h:.2f}" rx="3" fill="#c4512c"/>',
            f'<rect x="{cx - 7:.2f}" y="{quiet_bottom + 2:.2f}" width="5" height="16" rx="1.5" fill="#1c1915"/>',
            f'<rect x="{cx + 2:.2f}" y="{quiet_bottom + 2:.2f}" width="5" height="16" rx="1.5" fill="#1c1915"/>',
        ]
    )


def white_plate(qr_x: float, qr_y: float, quiet: float) -> str:
    return (
        f'<rect x="{qr_x - quiet:.2f}" y="{qr_y - quiet:.2f}" '
        f'width="{QR_MM + 2 * quiet:.2f}" height="{QR_MM + 2 * quiet:.2f}" fill="#ffffff"/>'
    )


def qr_rects(rows: list[list[int]], origin_x: float, origin_y: float, module_mm: float) -> str:
    rects: list[str] = []
    for y, row in enumerate(rows):
        for x, module in enumerate(row):
            if module & 1:
                size = module_mm + 0.35
                rects.append(
                    f'<rect x="{origin_x + x * module_mm:.3f}" y="{origin_y + y * module_mm:.3f}" '
                    f'width="{size:.3f}" height="{size:.3f}" fill="#1c1915"/>'
                )
    return "\n".join(rects)


def dimension(qr_x: float, y: float) -> str:
    end = qr_x + QR_MM
    return "\n".join(
        [
            f'<line x1="{qr_x:.2f}" y1="{y:.2f}" x2="{end:.2f}" y2="{y:.2f}" stroke="#1c1915" stroke-width="0.4"/>',
            f'<line x1="{qr_x:.2f}" y1="{y - 1.5:.2f}" x2="{qr_x:.2f}" y2="{y + 1.5:.2f}" stroke="#1c1915" stroke-width="0.4"/>',
            f'<line x1="{end:.2f}" y1="{y - 1.5:.2f}" x2="{end:.2f}" y2="{y + 1.5:.2f}" stroke="#1c1915" stroke-width="0.4"/>',
            f'<text x="{(qr_x + end) / 2:.2f}" y="{y - 2:.2f}" text-anchor="middle" font-family="sans-serif" font-size="3.2" fill="#1c1915">60 mm</text>',
        ]
    )


def fold(card_w: float, y: float) -> str:
    return "\n".join(
        [
            f'<line x1="8" y1="{y:.2f}" x2="{card_w - 8:.2f}" y2="{y:.2f}" stroke="#6e675e" stroke-width="0.4" stroke-dasharray="1.6 1.2"/>',
            f'<text x="{card_w / 2:.2f}" y="{y + 8:.2f}" text-anchor="middle" font-family="sans-serif" font-size="3.2" fill="#6e675e">fold back to stand</text>',
        ]
    )


if __name__ == "__main__":
    main()
