"""Lay out the printable person photo at a known height.

The phone app follows a real person, or this photo, by body pose. Its
person-height field must match the printed height of the figure, head to shoe.
"""

import base64
import io
from pathlib import Path

from PIL import Image

HERE = Path(__file__).parent
PHOTO = HERE / "printed-person.jpg"
OUT = HERE / "printed-person.svg"

FIGURE_MM = 230.0
FIGURE_TOP_PX = 34
FIGURE_BOTTOM_PX = 1187
CROP = (130, 14, 590, 1207)
PAGE_W = 215.9
PAGE_H = 279.4


def main() -> None:
    mm_per_px = FIGURE_MM / (FIGURE_BOTTOM_PX - FIGURE_TOP_PX + 1)
    photo = Image.open(PHOTO).convert("RGB").crop(CROP)
    photo_w = photo.width * mm_per_px
    photo_h = photo.height * mm_per_px
    buffer = io.BytesIO()
    photo.save(buffer, format="JPEG", quality=90)
    data = base64.b64encode(buffer.getvalue()).decode("ascii")
    x = (PAGE_W - photo_w) / 2
    y = PAGE_H - 12.0 - photo_h
    ruler_y = 24.0
    ruler_x = (PAGE_W - 100.0) / 2
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{PAGE_W}mm" height="{PAGE_H}mm" '
        f'viewBox="0 0 {PAGE_W} {PAGE_H}">',
        f'<rect width="{PAGE_W}" height="{PAGE_H}" fill="#ffffff"/>',
        f'<text x="{PAGE_W / 2}" y="12" font-family="Helvetica, Arial, sans-serif" font-size="5" '
        f'text-anchor="middle" fill="#222">Jiadroid printed person · set Person height to '
        f'{FIGURE_MM:.0f} mm</text>',
        f'<line x1="{ruler_x}" y1="{ruler_y}" x2="{ruler_x + 100}" y2="{ruler_y}" '
        f'stroke="#222" stroke-width="0.4"/>',
        f'<line x1="{ruler_x}" y1="{ruler_y - 2}" x2="{ruler_x}" y2="{ruler_y + 2}" stroke="#222" stroke-width="0.4"/>',
        f'<line x1="{ruler_x + 100}" y1="{ruler_y - 2}" x2="{ruler_x + 100}" y2="{ruler_y + 2}" '
        f'stroke="#222" stroke-width="0.4"/>',
        f'<text x="{PAGE_W / 2}" y="{ruler_y - 2.5}" font-family="Helvetica, Arial, sans-serif" '
        f'font-size="3.2" text-anchor="middle" fill="#555">this line is 100 mm when printed at actual size</text>',
        f'<image x="{x:.2f}" y="{y:.2f}" width="{photo_w:.2f}" height="{photo_h:.2f}" '
        f'href="data:image/jpeg;base64,{data}"/>',
        "</svg>",
    ]
    OUT.write_text("\n".join(parts) + "\n")
    print(f"wrote {OUT} (figure {FIGURE_MM:.0f} mm, photo {photo_w:.0f} x {photo_h:.0f} mm)")


if __name__ == "__main__":
    main()
