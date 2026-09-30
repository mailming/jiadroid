"""A two-wheel rover, the second body.

This is the shape of a robot vacuum or a classroom robot: two drive
motors, two wheel encoders, a bump switch, and a forward range sensor.
It has no legs and no head, so it announces only `motion.velocity`, and
the phone's Follow Me decision still drives it.

The kinematics are a differential drive on a flat floor. A wall stands
across the floor at `WALL_X` so the range sensor and the bump switch have
something to report.
"""

from __future__ import annotations

import math
from typing import Any

from jiadroid.protocol.messages import Controls, Device
from jiadroid.sim.body import Body

ROBOT_ID = "rover"
ROBOT_NAME = "Rover"

WHEEL_RADIUS_M = 0.035
WHEEL_BASE_M = 0.24
TICKS_PER_REV = 360
MAX_WHEEL_RAD_S = 15.0
MAX_FORWARD_M_S = 0.5
MAX_YAW_RAD_S = 2.0
WALL_X = 3.0
BODY_RADIUS_M = 0.17
RANGE_MAX_M = 2.0

ROVER_CONTROLS: Controls = {
    "motion.velocity": {
        "forward": (-MAX_FORWARD_M_S, MAX_FORWARD_M_S),
        "yaw": (-MAX_YAW_RAD_S, MAX_YAW_RAD_S),
    },
}

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
        super().__init__(ROVER_DEVICES, ROVER_CONTROLS)
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
        if self._x + BODY_RADIUS_M >= WALL_X:
            self._x = WALL_X - BODY_RADIUS_M
            self._bumped = True
        elif self._bumped and self._x + BODY_RADIUS_M < WALL_X - 0.01:
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
        distance = (WALL_X - self._x - BODY_RADIUS_M) / facing
        return max(0.0, min(RANGE_MAX_M, distance))


def _clamp(value: float, limit: float) -> float:
    return max(-limit, min(limit, value))
