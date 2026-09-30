"""The Open Duck Mini in MuJoCo, behind the protocol.

Same identity and controls as the kinematic `DuckBody`, but the legs are
driven by a walking policy and the body obeys gravity. Without a trained
policy the duck stands and turns its head; give `--policy` a file and it
walks where the phone tells it.

Each 20 ms server tick is one control step, so simulated time tracks wall
time and the phone sees the duck move at real speed.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import numpy as np

from jiadroid.protocol.messages import Device
from jiadroid.sim.body import Body
from jiadroid.sim.duck import (
    DUCK_CONTROLS,
    DUCK_DEVICES,
    HEAD_FIELD_TO_JOINT,
    JOINT_NAMES,
    ROBOT_ID,
    ROBOT_NAME,
    walk_command,
)
from jiadroid.sim.physics import GAIT_PERIOD_S, DuckPhysics
from jiadroid.sim.policy import Policy, WalkRunner, load_policy

SENSOR_DEVICES = (
    Device("sensor", "left_foot", ("boolean",)),
    Device("sensor", "right_foot", ("boolean",)),
    Device("sensor", "height", ("meters",)),
    Device("sensor", "upright", ("boolean",)),
)

FALL_RESET_DELAY_S = 1.0
MAX_STEPS_PER_TICK = 5


class DuckPhysicsBody(Body):
    robot_id = ROBOT_ID
    robot_name = ROBOT_NAME
    kind = "biped"

    def __init__(
        self,
        policy: Policy | str | Path | None = None,
        xml_path: str | Path | None = None,
        gait_period_s: float = GAIT_PERIOD_S,
        auto_reset: bool = True,
    ) -> None:
        super().__init__(DUCK_DEVICES + SENSOR_DEVICES, DUCK_CONTROLS)
        self.physics = DuckPhysics() if xml_path is None else DuckPhysics(xml_path)
        if policy is None or isinstance(policy, (str, Path)):
            policy = load_policy(policy)
        self.runner = WalkRunner(self.physics, policy, gait_period_s=gait_period_s)
        self.auto_reset = auto_reset
        self._index = {name: i for i, name in enumerate(JOINT_NAMES)}
        self._overrides: dict[str, float] = {}
        self._fallen_for = 0.0
        self._falls = 0

    @property
    def lock(self):
        """Hold this while reading `physics.data` from another thread, e.g. a viewer."""
        return self._lock

    @property
    def policy_name(self) -> str:
        return getattr(self.runner.policy, "name", type(self.runner.policy).__name__)

    @property
    def falls(self) -> int:
        with self._lock:
            return self._falls

    # ---- Body hooks ---------------------------------------------------------------

    def _do_stop(self) -> None:
        self._overrides.clear()

    def _do_reset(self) -> None:
        self._overrides.clear()
        self.runner.reset()
        self._fallen_for = 0.0

    def _do_integrate(self, dt: float) -> None:
        steps = min(MAX_STEPS_PER_TICK, max(1, int(round(dt / self.physics.ctrl_dt))))
        command = np.array(walk_command(self._motion, self._head))
        for _ in range(steps):
            if not self.physics.is_upright():
                self._fallen_for += self.physics.ctrl_dt
                if self._fallen_for >= FALL_RESET_DELAY_S and self.auto_reset:
                    self._falls += 1
                    self._motion = {name: 0.0 for name in self._motion}
                    if self._safety == "running":
                        self._safety = "ready"
                    self._do_reset()
                    command = np.array(walk_command(self._motion, self._head))
                    continue
            elif self._fallen_for:
                self._fallen_for = 0.0
            overrides = {self._index[name]: position for name, position in self._overrides.items()}
            self.runner.step(command, overrides or None)

    def _do_snapshot(self) -> dict[str, Any]:
        p = self.physics
        positions = p.joint_positions()
        contacts = p.feet_contacts()
        gyro = p.gyro()
        accel = p.accelerometer()
        x, y, z = p.base_position()
        return {
            "servos": {name: round(float(positions[i]), 4) for i, name in enumerate(JOINT_NAMES)},
            "sensors": {
                "left_foot": float(contacts[0]),
                "right_foot": float(contacts[1]),
                "height": round(float(z), 4),
                "upright": 1.0 if p.is_upright() else 0.0,
                "gyro_x": round(float(gyro[0]), 4),
                "gyro_y": round(float(gyro[1]), 4),
                "gyro_z": round(float(gyro[2]), 4),
                "accel_x": round(float(accel[0]), 4),
                "accel_y": round(float(accel[1]), 4),
                "accel_z": round(float(accel[2]), 4),
            },
            "base": {
                "x": round(float(x), 4),
                "y": round(float(y), 4),
                "z": round(float(z), 4),
                "yaw": round(p.base_yaw(), 4),
                "falls": float(self._falls),
            },
        }

    def _do_servo_position(self, device_id: str, position: float) -> None:
        for field, joint in HEAD_FIELD_TO_JOINT.items():
            if joint == device_id:
                self._head[field] = position
                return
        self._overrides[device_id] = position

    def _do_servo_read(self, device_id: str) -> float:
        return float(self.physics.joint_positions()[self._index[device_id]])

    def _do_sensor_read(self, device_id: str) -> float:
        p = self.physics
        if device_id == "left_foot":
            return float(p.feet_contacts()[0])
        if device_id == "right_foot":
            return float(p.feet_contacts()[1])
        if device_id == "height":
            return p.base_height()
        return 1.0 if p.is_upright() else 0.0
