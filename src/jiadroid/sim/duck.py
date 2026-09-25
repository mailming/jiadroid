"""Open Duck Mini v2, as a phone-facing robot.

Joint order and the standing pose match `HWI` in
[Open Duck Mini Runtime](https://github.com/apirrone/Open_Duck_Mini_Runtime)
(`rustypot_position_hwi.py`). Command limits match `XBoxController`
(`xbox_controller.py`): the same 7 numbers the walk policy reads as
`last_commands`.

This profile does not run that policy. On a real duck, `walk.velocity`
is copied into those commands and the existing ONNX walk stays in charge
of the joints.
"""

from __future__ import annotations

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

ROBOT_ID = "open-duck-mini"
ROBOT_NAME = "Open Duck Mini"
