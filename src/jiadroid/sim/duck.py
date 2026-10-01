"""Open Duck Mini v2, as a phone-facing robot.

Joint order and the standing pose match `HWI` in
[Open Duck Mini Runtime](https://github.com/apirrone/Open_Duck_Mini_Runtime)
(`rustypot_position_hwi.py`). Command limits match `XBoxController`
(`xbox_controller.py`): the same 7 numbers the walk policy reads as
`last_commands`.

`DuckBody` is the kinematic stand-in: no physics, legs swing on a sine
wave so a step is visible. `jiadroid.sim.duck_physics.DuckPhysicsBody`
is the MuJoCo version.
"""

from __future__ import annotations

import math
from typing import Any

from jiadroid.protocol.messages import Controls, Device, Mount
from jiadroid.sim.body import Body

# name, standing position in radians. Order matches the runtime.
JOINTS: tuple[tuple[str, float], ...] = (
    ("left_hip_yaw", 0.002),
    ("left_hip_roll", 0.053),
    ("left_hip_pitch", -0.63),
    ("left_knee", 1.368),
    ("left_ankle", -0.784),
    ("neck_pitch", 0.0),
    ("head_pitch", 0.0),
    ("head_yaw", 0.0),
    ("head_roll", 0.0),
    ("right_hip_yaw", -0.003),
    ("right_hip_roll", -0.065),
    ("right_hip_pitch", 0.635),
    ("right_knee", 1.379),
    ("right_ankle", -0.796),
)

JOINT_NAMES = tuple(name for name, _position in JOINTS)
STANDING = {name: position for name, position in JOINTS}

# How far each leg joint swings from the standing pose while walking.
LEG_SWING = {
    "left_hip_pitch": 0.25,
    "right_hip_pitch": -0.25,
    "left_knee": 0.18,
    "right_knee": -0.18,
    "left_ankle": 0.12,
    "right_ankle": -0.12,
}

# Inclusive limits. Locomotion values are what the Xbox controller emits.
# Head values are radians.
COMMAND_LIMITS = {
    "forward": (-0.15, 0.15),
    "lateral": (-0.2, 0.2),
    "yaw": (-1.0, 1.0),
    "neck_pitch": (-0.34, 1.1),
    "head_pitch": (-0.78, 0.3),
    "head_yaw": (-0.5, 0.5),
    "head_roll": (-0.5, 0.5),
}

HEAD_JOINTS = ("neck_pitch", "head_pitch", "head_yaw", "head_roll")
LOCOMOTION = ("forward", "lateral", "yaw")

# `head.pose` field -> duck joint
HEAD_FIELD_TO_JOINT = {
    "neck_pitch": "neck_pitch",
    "pitch": "head_pitch",
    "yaw": "head_yaw",
    "roll": "head_roll",
}

ROBOT_ID = "open-duck-mini"
ROBOT_NAME = "Open Duck Mini"

DUCK_CONTROLS: Controls = {
    "motion.velocity": {name: COMMAND_LIMITS[name] for name in LOCOMOTION},
    "head.pose": {field: COMMAND_LIMITS[joint] for field, joint in HEAD_FIELD_TO_JOINT.items()},
    # 0.1 alias, kept so older phone builds keep working.
    "walk.velocity": {},
}

DUCK_DEVICES = tuple(Device("servo", name, ("position",)) for name in JOINT_NAMES)

# Body frame: x forward, y left, z up, origin at the trunk (the hip).
# `position` is where a centered phone's center of mass sits, in meters,
# at the standing pose. `head` is on the face; `back` is on the rear shell.
# A mount holds at most 0.30 kg, a large phone with a light case.
DUCK_MOUNTS = (
    Mount("head", (0.02, 0.0, 0.22), 0.30),
    Mount("back", (-0.055, 0.0, 0.09), 0.30),
)

GAIT_RATE = 7.0
SWING_RAD = 0.18


def walk_command(motion: dict[str, float], head: dict[str, float]) -> list[float]:
    """The runtime's 7-number `last_commands`, from 0.2 motion and head maps."""
    return [
        motion.get("forward", 0.0),
        motion.get("lateral", 0.0),
        motion.get("yaw", 0.0),
        head.get("neck_pitch", 0.0),
        head.get("pitch", 0.0),
        head.get("yaw", 0.0),
        head.get("roll", 0.0),
    ]


class DuckBody(Body):
    """Open Duck Mini joints plus the walk command its policy expects, without physics."""

    robot_id = ROBOT_ID
    robot_name = ROBOT_NAME
    kind = "biped"

    def __init__(self) -> None:
        super().__init__(DUCK_DEVICES, DUCK_CONTROLS, DUCK_MOUNTS)
        self._positions = dict(STANDING)
        self._phase = 0.0

    @property
    def positions(self) -> dict[str, float]:
        with self._lock:
            return dict(self._positions)

    def _do_head(self, pose: dict[str, float]) -> None:
        for field, joint in HEAD_FIELD_TO_JOINT.items():
            self._positions[joint] = pose.get(field, 0.0)

    def _do_stop(self) -> None:
        self._positions = dict(STANDING)
        self._phase = 0.0

    def _do_reset(self) -> None:
        self._do_stop()

    def _do_integrate(self, dt: float) -> None:
        if self._moving_locked():
            self._phase += dt * GAIT_RATE
            swing = math.sin(self._phase) * SWING_RAD
            for name, sign in LEG_SWING.items():
                self._positions[name] = STANDING[name] + sign * swing

    def _do_snapshot(self) -> dict[str, Any]:
        return {"servos": {name: round(position, 4) for name, position in self._positions.items()}}

    def _do_servo_position(self, device_id: str, position: float) -> None:
        self._positions[device_id] = position
        for field, joint in HEAD_FIELD_TO_JOINT.items():
            if joint == device_id:
                self._head[field] = position

    def _do_servo_read(self, device_id: str) -> float:
        return self._positions[device_id]
