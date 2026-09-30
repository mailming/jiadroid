"""A robot body that speaks protocol 0.2.

`Body` owns what every robot shares: identity, the device list, the
`controls` it announces, the safety latch, and the request dispatch. A
concrete body fills in how a motion or head command changes it, how time
moves it, and what telemetry it reports.

Bodies are used by `SimulatorServer` and can also be driven directly from
Python, which is how the tests and the RL tooling exercise them.
"""

from __future__ import annotations

import math
import threading
from typing import Any

from jiadroid.errors import RobotError
from jiadroid.protocol.messages import (
    DEFAULT_TELEMETRY_HZ,
    HEAD_FIELDS,
    MAX_TELEMETRY_HZ,
    MOTION_FIELDS,
    PROTOCOL_NAME,
    PROTOCOL_VERSION,
    Controls,
    Device,
    Message,
    controls_to_dict,
    error_response,
    response,
)

Limits = dict[str, tuple[float, float]]

# 0.1 `walk.velocity` field -> 0.2 (op, field)
_LEGACY_WALK = {
    "forward": ("motion.velocity", "forward"),
    "lateral": ("motion.velocity", "lateral"),
    "yaw": ("motion.velocity", "yaw"),
    "neck_pitch": ("head.pose", "neck_pitch"),
    "head_pitch": ("head.pose", "pitch"),
    "head_yaw": ("head.pose", "yaw"),
    "head_roll": ("head.pose", "roll"),
}


class Body:
    """Shared robot behaviour. Subclasses override the `_do_*` hooks."""

    robot_id = "robot"
    robot_name = "Robot"
    kind = "other"
    # Per-servo position range accepted by `servo.position`, radians.
    servo_range: tuple[float, float] = (-2.5, 2.5)

    def __init__(self, devices: tuple[Device, ...], controls: Controls) -> None:
        self._lock = threading.RLock()
        self._devices = devices
        self._controls = controls
        self._safety = "ready"
        self._motion = {name: 0.0 for name in controls.get("motion.velocity", {})}
        self._head = {name: 0.0 for name in controls.get("head.pose", {})}

    # ---- identity -------------------------------------------------------

    @property
    def devices(self) -> tuple[Device, ...]:
        return self._devices

    @property
    def controls(self) -> Controls:
        return self._controls

    @property
    def safety(self) -> str:
        with self._lock:
            return self._safety

    @property
    def motion(self) -> dict[str, float]:
        with self._lock:
            return dict(self._motion)

    @property
    def head(self) -> dict[str, float]:
        with self._lock:
            return dict(self._head)

    def hello_body(self) -> dict[str, Any]:
        return {
            "protocol": PROTOCOL_NAME,
            "version": PROTOCOL_VERSION,
            "robot": {"id": self.robot_id, "name": self.robot_name, "kind": self.kind},
            "safety": {"state": self.safety},
            "controls": controls_to_dict(self._controls),
            "devices": [device.to_dict() for device in self._devices],
        }

    # ---- protocol -------------------------------------------------------

    def handle(self, request_message: Message) -> Message:
        msg_id = request_message.id or "0"
        body = request_message.body or {}
        try:
            result = self.dispatch(request_message.op, body)
        except RobotError as exc:
            return error_response(request_message.op, msg_id, exc.code, exc.message)
        return response(request_message.op, msg_id, result)

    def dispatch(self, op: str, body: dict[str, Any]) -> dict[str, Any]:
        if op in ("session.ping", "session.bye"):
            return {"ok": True}
        if op == "devices.list":
            return {"devices": [device.to_dict() for device in self._devices]}
        if op == "motion.velocity":
            return self._set_motion(body)
        if op == "head.pose":
            return self._set_head(body)
        if op == "walk.velocity":
            return self._set_walk(body)
        if op == "servo.position":
            return self._servo_position(body)
        if op == "servo.read":
            return self._servo_read(body)
        if op == "motor.velocity":
            return self._motor_velocity(body)
        if op == "encoder.read":
            return self._encoder_read(body)
        if op == "sensor.read":
            return self._sensor_read(body)
        if op == "robot.stop":
            return self.stop()
        if op == "robot.reset":
            return self.reset()
        if op == "telemetry.subscribe":
            return {"hz": parse_hz(body)}
        if op == "safety.estop":
            return self.estop()
        if op == "safety.clear":
            return self.clear_estop()
        raise RobotError("unsupported", f"unknown operation {op}")

    # ---- time and telemetry ----------------------------------------------

    def integrate(self, dt: float) -> None:
        """Advance the body by `dt` seconds of wall time."""
        with self._lock:
            self._do_integrate(dt)

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            sample: dict[str, Any] = {
                "safety": {"state": self._safety},
                "motion": dict(self._motion),
                "servos": {},
            }
            if self._head:
                sample["head"] = dict(self._head)
            sample.update(self._do_snapshot())
            return sample

    # ---- robot-level commands ---------------------------------------------

    def _set_motion(self, body: dict[str, Any]) -> dict[str, Any]:
        limits = self._require_control("motion.velocity")
        command = parse_fields(body, MOTION_FIELDS, limits)
        with self._lock:
            self._require_not_latched()
            self._motion = command
            self._do_motion(command)
            if self._moving_locked():
                self._safety = "running"
            return {**command, "safety": {"state": self._safety}}

    def _set_head(self, body: dict[str, Any]) -> dict[str, Any]:
        limits = self._require_control("head.pose")
        pose = parse_fields(body, HEAD_FIELDS, limits)
        with self._lock:
            self._require_not_latched()
            self._head = pose
            self._do_head(pose)
            return {**pose, "safety": {"state": self._safety}}

    def _set_walk(self, body: dict[str, Any]) -> dict[str, Any]:
        """0.1 alias: one message carrying motion and head together."""
        self._require_control("walk.velocity")
        motion_body: dict[str, Any] = {}
        head_body: dict[str, Any] = {}
        for name, value in body.items():
            if name not in _LEGACY_WALK:
                continue
            op, field_name = _LEGACY_WALK[name]
            (motion_body if op == "motion.velocity" else head_body)[field_name] = value
        with self._lock:
            motion = self._set_motion(motion_body)
            head = self._set_head(head_body) if "head.pose" in self._controls else {}
        result = {name: motion[field] for name, (op, field) in _LEGACY_WALK.items() if op == "motion.velocity"}
        for name, (op, field) in _LEGACY_WALK.items():
            if op == "head.pose":
                result[name] = head.get(field, 0.0)
        result["safety"] = motion["safety"]
        return result

    def stop(self) -> dict[str, Any]:
        with self._lock:
            self._motion = {name: 0.0 for name in self._motion}
            self._head = {name: 0.0 for name in self._head}
            self._do_stop()
            if self._safety != "estop":
                self._safety = "ready"
            return {"safety": {"state": self._safety}}

    def reset(self) -> dict[str, Any]:
        with self._lock:
            self._motion = {name: 0.0 for name in self._motion}
            self._head = {name: 0.0 for name in self._head}
            self._do_reset()
            if self._safety != "estop":
                self._safety = "ready"
            return {"safety": {"state": self._safety}}

    def estop(self) -> dict[str, Any]:
        with self._lock:
            self._motion = {name: 0.0 for name in self._motion}
            self._head = {name: 0.0 for name in self._head}
            self._do_stop()
            self._safety = "estop"
            return {"safety": {"state": "estop"}}

    def clear_estop(self) -> dict[str, Any]:
        with self._lock:
            if self._safety == "estop":
                self._safety = "ready"
            return {"safety": {"state": self._safety}}

    # ---- devices -----------------------------------------------------------

    def _servo_position(self, body: dict[str, Any]) -> dict[str, Any]:
        device_id = device_id_of(body)
        low, high = self.servo_range
        position = parse_number(body.get("position"), "position", low, high)
        with self._lock:
            self._require_not_latched()
            self.require_device(device_id, "servo", "position")
            self._do_servo_position(device_id, position)
            return {"id": device_id, "position": position, "safety": {"state": self._safety}}

    def _servo_read(self, body: dict[str, Any]) -> dict[str, Any]:
        device_id = device_id_of(body)
        with self._lock:
            self.require_device(device_id, "servo", "position")
            return {"id": device_id, "position": self._do_servo_read(device_id)}

    def _motor_velocity(self, body: dict[str, Any]) -> dict[str, Any]:
        device_id = device_id_of(body)
        with self._lock:
            self._require_not_latched()
            self.require_device(device_id, "motor", "velocity")
            low, high = self._motor_limits(device_id)
            velocity = parse_number(body.get("velocity"), "velocity", low, high)
            self._do_motor_velocity(device_id, velocity)
            if self._moving_locked():
                self._safety = "running"
            return {"id": device_id, "velocity": velocity, "safety": {"state": self._safety}}

    def _encoder_read(self, body: dict[str, Any]) -> dict[str, Any]:
        device_id = device_id_of(body)
        with self._lock:
            self.require_device(device_id, "encoder", "ticks")
            return {"id": device_id, "ticks": int(self._do_encoder_read(device_id))}

    def _sensor_read(self, body: dict[str, Any]) -> dict[str, Any]:
        device_id = device_id_of(body)
        with self._lock:
            device = self.require_device(device_id, "sensor")
            return {"id": device_id, "value": float(self._do_sensor_read(device_id)), "unit": device.capabilities[0]}

    def require_device(self, device_id: str, device_type: str, capability: str | None = None) -> Device:
        matches = [device for device in self._devices if device.id == device_id]
        if not matches:
            raise RobotError("unknown_device", f"no device named {device_id}")
        typed = [device for device in matches if device.type == device_type]
        if not typed:
            found = ", ".join(device.type for device in matches)
            raise RobotError("unsupported", f"{device_id} is {found}, not a {device_type}")
        if capability is not None and capability not in typed[0].capabilities:
            raise RobotError("unsupported", f"{device_type} {device_id} cannot {capability}")
        return typed[0]

    # ---- hooks for concrete bodies ---------------------------------------------

    def _do_motion(self, command: dict[str, float]) -> None:
        pass

    def _do_head(self, pose: dict[str, float]) -> None:
        pass

    def _do_stop(self) -> None:
        pass

    def _do_reset(self) -> None:
        self._do_stop()

    def _do_integrate(self, dt: float) -> None:
        pass

    def _do_snapshot(self) -> dict[str, Any]:
        return {}

    def _do_servo_position(self, device_id: str, position: float) -> None:
        raise RobotError("unsupported", f"servo {device_id} cannot position")

    def _do_servo_read(self, device_id: str) -> float:
        raise RobotError("unsupported", f"servo {device_id} cannot be read")

    def _do_motor_velocity(self, device_id: str, velocity: float) -> None:
        raise RobotError("unsupported", f"motor {device_id} cannot velocity")

    def _motor_limits(self, device_id: str) -> tuple[float, float]:
        return (-math.inf, math.inf)

    def _do_encoder_read(self, device_id: str) -> int:
        raise RobotError("unsupported", f"encoder {device_id} cannot be read")

    def _do_sensor_read(self, device_id: str) -> float:
        raise RobotError("unsupported", f"sensor {device_id} cannot be read")

    def _moving_locked(self) -> bool:
        return any(abs(value) > 1e-6 for value in self._motion.values())

    # ---- helpers --------------------------------------------------------------

    def _require_control(self, op: str) -> Limits:
        if op not in self._controls:
            raise RobotError("unsupported", f"{self.robot_name} does not accept {op}")
        return self._controls[op]

    def _require_not_latched(self) -> None:
        if self._safety == "estop":
            raise RobotError("safety_blocked", "emergency stop is latched")


def parse_fields(body: dict[str, Any], known: tuple[str, ...], limits: Limits) -> dict[str, float]:
    """Read the announced fields; omitted ones are 0. Unannounced non-zero fields are refused."""
    command: dict[str, float] = {}
    for name in known:
        if name in limits:
            low, high = limits[name]
            command[name] = parse_number(body.get(name, 0.0), name, low, high)
        elif name in body:
            value = body[name]
            if isinstance(value, bool) or not isinstance(value, (int, float)) or abs(float(value)) > 1e-9:
                raise RobotError("out_of_range", f"{name} is not supported by this robot")
    return command


def parse_number(value: object, name: str, low: float, high: float) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise RobotError("out_of_range", f"{name} must be from {low:g} to {high:g}")
    number = float(value)
    if not math.isfinite(number) or number < low or number > high:
        raise RobotError("out_of_range", f"{name} must be from {low:g} to {high:g}")
    return number


def parse_hz(body: dict[str, Any]) -> float:
    if "hz" not in body:
        return DEFAULT_TELEMETRY_HZ
    value = body["hz"]
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise RobotError("out_of_range", "telemetry hz must be greater than 0 and at most 50")
    hz = float(value)
    if not math.isfinite(hz) or hz <= 0 or hz > MAX_TELEMETRY_HZ:
        raise RobotError("out_of_range", "telemetry hz must be greater than 0 and at most 50")
    return hz


def device_id_of(body: dict[str, Any]) -> str:
    device_id = body.get("id")
    if not isinstance(device_id, str) or not device_id:
        raise RobotError("unknown_device", "no device named in the request")
    return device_id
