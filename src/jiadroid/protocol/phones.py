"""Published phone sizes, so a robot can be told what it is carrying.

Mass is the bare phone in kilograms, from the maker's spec sheet, with no
case and no dock. `width` is the short side, `height` the long side,
`thickness` the thin side, all in meters.

`size_in_body` turns those into the protocol's `[x, y, z]` extents. On a
vertical mount (the duck's head or back, or the chassis `top` mount) the
phone stands up: thin along x, short side along y, long side along z. On
a horizontal mount (`deck`) it lies flat: long side along x, short side
along y, thin side along z.

A model that is not in this list uses `GENERIC_PHONE`. A case is extra
mass the phone should add itself; this table does not include one.
"""

from __future__ import annotations

from dataclasses import dataclass

# Horizontal mounts: the phone lies flat. Anything else is treated as vertical.
HORIZONTAL_MOUNTS = frozenset({"deck"})


@dataclass(frozen=True)
class PhoneSpec:
    name: str
    mass: float
    width: float
    height: float
    thickness: float

    def size_in_body(self, mount: str) -> tuple[float, float, float]:
        """Full extents `[x, y, z]` in meters, in the robot body frame."""
        if mount in HORIZONTAL_MOUNTS:
            return (self.height, self.width, self.thickness)
        return (self.thickness, self.width, self.height)


# Bare phones. Masses and sizes are the published ones, rounded to 0.1 mm and 1 g.
PHONES: tuple[PhoneSpec, ...] = (
    PhoneSpec("iPhone 16", 0.170, 0.0716, 0.1476, 0.0078),
    PhoneSpec("iPhone 16 Plus", 0.199, 0.0778, 0.1609, 0.0078),
    PhoneSpec("iPhone 16 Pro", 0.199, 0.0715, 0.1496, 0.0083),
    PhoneSpec("iPhone 16 Pro Max", 0.227, 0.0776, 0.1630, 0.0083),
    PhoneSpec("iPhone 15", 0.171, 0.0716, 0.1476, 0.0078),
    PhoneSpec("iPhone 15 Plus", 0.201, 0.0778, 0.1609, 0.0078),
    PhoneSpec("iPhone 15 Pro", 0.187, 0.0706, 0.1466, 0.0083),
    PhoneSpec("iPhone 15 Pro Max", 0.221, 0.0767, 0.1599, 0.0083),
    PhoneSpec("Pixel 9", 0.198, 0.0720, 0.1528, 0.0085),
    PhoneSpec("Pixel 9 Pro", 0.199, 0.0720, 0.1528, 0.0085),
    PhoneSpec("Pixel 9 Pro XL", 0.221, 0.0765, 0.1628, 0.0085),
    PhoneSpec("Pixel 8", 0.187, 0.0708, 0.1505, 0.0089),
    PhoneSpec("Pixel 8 Pro", 0.213, 0.0765, 0.1626, 0.0088),
)

# Used when the model string matches nothing above. 190 g, 155 x 73 x 8 mm.
GENERIC_PHONE = PhoneSpec("generic", 0.190, 0.073, 0.155, 0.008)

# Machine id (utsname) -> marketing name, for the iOS app and for tests.
IPHONE_MACHINE: dict[str, str] = {
    "iPhone17,3": "iPhone 16",
    "iPhone17,4": "iPhone 16 Plus",
    "iPhone17,1": "iPhone 16 Pro",
    "iPhone17,2": "iPhone 16 Pro Max",
    "iPhone15,4": "iPhone 15",
    "iPhone15,5": "iPhone 15 Plus",
    "iPhone16,1": "iPhone 15 Pro",
    "iPhone16,2": "iPhone 15 Pro Max",
}


def lookup_phone(model: str) -> PhoneSpec:
    """Match a device model string. Longer names win, so 'Pro Max' beats 'Pro'."""
    folded = model.lower()
    for phone in sorted(PHONES, key=lambda item: len(item.name), reverse=True):
        if phone.name.lower() in folded:
            return phone
    machine = IPHONE_MACHINE.get(model)
    if machine is not None:
        return lookup_phone(machine)
    return GENERIC_PHONE
