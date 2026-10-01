"""The first prototype: a 2WD chassis with the phone standing on top.

The base is the DIYables 2WD robot car chassis, Amazon ASIN B0H4V8TR38
(model DIY-2WD-RC-ROBOT-CAR-AND-MOTOR-DRIVER): two DC gear motors with
encoders, an L9110S driver (0.8 A per channel, 2.5–12 V), a caster, and
a 4×AA holder. The listing weighs the kit at 11.3 oz (0.32 kg) and does
not publish a drawing. Wheel diameter, track, and plate height below are
the usual figures for this chassis; measure them on the real plate.

The phone stands on the top acrylic, screen facing forward, so the front
camera looks along +x. Lying flat would point that camera at the ceiling.
It has no head, so it announces only `motion.velocity`, and the phone's
Follow Me decision still drives it.

The kinematics are a differential drive on a flat floor. A wall stands
across the floor at `WALL_X` so the range sensor and the bump switch have
something to report.
"""

from __future__ import annotations

import math
from typing import Any

from jiadroid.protocol.messages import Controls, Device, Mount
from jiadroid.sim.body import Body

ROBOT_ID = "2wd-chassis"
ROBOT_NAME = "2WD Chassis"
# Listed kit weight, including the driver and the battery holder, not the cells.
CHASSIS_MASS_KG = 0.32

# 65 mm tires, the usual wheel for this kit.
WHEEL_RADIUS_M = 0.0325
# Distance between the two wheel centers. Approximate.
WHEEL_BASE_M = 0.15
# The encoder disk is on the motor shaft. 20 slots is typical and the
# gearbox is 1:48, so one wheel turn is 960 ticks. Count the slots on the
# real motor before trusting this.
ENCODER_SLOTS = 20
GEAR_RATIO = 48
TICKS_PER_REV = ENCODER_SLOTS * GEAR_RATIO
# What we ask of it with a phone on top, under the L9110S. A free motor at
# 6 V is faster than this.
MAX_FORWARD_M_S = 0.40
MAX_YAW_RAD_S = 1.5
MAX_WHEEL_RAD_S = MAX_FORWARD_M_S / WHEEL_RADIUS_M
WALL_X = 3.0
# Nose of the plate ahead of the rear axle, for the range sensor.
NOSE_M = 0.16
RANGE_MAX_M = 2.0
# Top acrylic, about 20 mm above the axle. A 155 mm phone standing on it
# has its center 77.5 mm above the plate. The axle is near the back, so
# the middle of the plate is ahead of the origin.
PLATE_ABOVE_AXLE_M = 0.020
PHONE_CENTER_ABOVE_PLATE_M = 0.0775
PLATE_CENTER_X_M = 0.06

ROVER_CONTROLS: Controls = {
    "motion.velocity": {
        "forward": (-MAX_FORWARD_M_S, MAX_FORWARD_M_S),
        "yaw": (-MAX_YAW_RAD_S, MAX_YAW_RAD_S),
    },
}

# Body frame: x forward, y left, z up, origin at the axle center.
# `top` is a phone standing on the top plate, screen forward. 0.30 kg
# covers a large phone. The chassis itself is only 0.32 kg, so the phone
# is about half the robot.
ROVER_MOUNTS = (
    Mount(
        "top",
        (PLATE_CENTER_X_M, 0.0, PLATE_ABOVE_AXLE_M + PHONE_CENTER_ABOVE_PLATE_M),
        0.30,
    ),
)

ROVER_DEVICES = (
    Device("motor", "left_wheel", ("velocity",)),
    Device("motor", "right_wheel", ("velocity",)),
    Device("encoder", "left_wheel", ("ticks",)),
    Device("encoder", "right_wheel", ("ticks",)),
    Device("sensor", "bump", ("boolean",)),
    Device("sensor", "range_front", ("meters",)),
)


class RoverBody(Body):
    robot_id = ROBOT_ID
    robot_name = ROBOT_NAME
    kind = "wheeled"

    def __init__(self) -> None:
        super().__init__(ROVER_DEVICES, ROVER_CONTROLS, ROVER_MOUNTS)
        self._wheel = {"left_wheel": 0.0, "right_wheel": 0.0}  # rad/s
        self._angle = {"left_wheel": 0.0, "right_wheel": 0.0}  # rad, accumulated
        self._x = 0.0
        self._y = 0.0
        self._heading = 0.0
        self._bumped = False

    @property
    def pose(self) -> tuple[float, float, float]:
        with self._lock:
            return (self._x, self._y, self._heading)

    def _do_motion(self, command: dict[str, float]) -> None:
        forward = command.get("forward", 0.0)
        yaw = command.get("yaw", 0.0)
        left = (forward - yaw * WHEEL_BASE_M / 2) / WHEEL_RADIUS_M
        right = (forward + yaw * WHEEL_BASE_M / 2) / WHEEL_RADIUS_M
        self._wheel["left_wheel"] = _clamp(left, MAX_WHEEL_RAD_S)
        self._wheel["right_wheel"] = _clamp(right, MAX_WHEEL_RAD_S)

    def _do_stop(self) -> None:
        self._wheel = {name: 0.0 for name in self._wheel}

    def _do_reset(self) -> None:
        self._do_stop()
        self._angle = {name: 0.0 for name in self._angle}
        self._x = self._y = self._heading = 0.0
        self._bumped = False

    def _do_integrate(self, dt: float) -> None:
        left = self._wheel["left_wheel"]
        right = self._wheel["right_wheel"]
        if self._bumped and (left + right) > 0:
            # Pressed against the wall: wheels spin, the body does not advance.
            forward = 0.0
        else:
            forward = (left + right) / 2 * WHEEL_RADIUS_M
        yaw = (right - left) * WHEEL_RADIUS_M / WHEEL_BASE_M
        self._angle["left_wheel"] += left * dt
        self._angle["right_wheel"] += right * dt
        self._x += forward * math.cos(self._heading) * dt
        self._y += forward * math.sin(self._heading) * dt
        self._heading = (self._heading + yaw * dt + math.pi) % (2 * math.pi) - math.pi
        if self._x + NOSE_M >= WALL_X:
            self._x = WALL_X - NOSE_M
            self._bumped = True
        elif self._bumped and self._x + NOSE_M < WALL_X - 0.01:
            self._bumped = False

    def _moving_locked(self) -> bool:
        return any(abs(value) > 1e-6 for value in self._wheel.values())

    def _do_snapshot(self) -> dict[str, Any]:
        return {
            "motors": {name: round(value, 4) for name, value in self._wheel.items()},
            "sensors": {
                "bump": 1.0 if self._bumped else 0.0,
                "range_front": round(self._range_locked(), 4),
                "left_wheel": self._do_encoder_read("left_wheel"),
                "right_wheel": self._do_encoder_read("right_wheel"),
            },
            "base": {"x": round(self._x, 4), "y": round(self._y, 4), "yaw": round(self._heading, 4)},
        }

    def _do_motor_velocity(self, device_id: str, velocity: float) -> None:
        self._wheel[device_id] = velocity
        # Keep the announced motion in step with what the wheels do.
        left = self._wheel["left_wheel"]
        right = self._wheel["right_wheel"]
        self._motion["forward"] = (left + right) / 2 * WHEEL_RADIUS_M
        self._motion["yaw"] = (right - left) * WHEEL_RADIUS_M / WHEEL_BASE_M

    def _motor_limits(self, device_id: str) -> tuple[float, float]:
        return (-MAX_WHEEL_RAD_S, MAX_WHEEL_RAD_S)

    def _do_encoder_read(self, device_id: str) -> int:
        return int(round(self._angle[device_id] / (2 * math.pi) * TICKS_PER_REV))

    def _do_sensor_read(self, device_id: str) -> float:
        if device_id == "bump":
            return 1.0 if self._bumped else 0.0
        return self._range_locked()

    def _range_locked(self) -> float:
        facing = math.cos(self._heading)
        if facing <= 1e-6:
            return RANGE_MAX_M
        distance = (WALL_X - self._x - NOSE_M) / facing
        return max(0.0, min(RANGE_MAX_M, distance))


def _clamp(value: float, limit: float) -> float:
    return max(-limit, min(limit, value))
