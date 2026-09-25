"""Message types for protocol 0.1."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

PROTOCOL_NAME = "jiadroid"
PROTOCOL_VERSION = "0.1"
ENVELOPE_VERSION = 1
MAX_LINE_BYTES = 8192
DEFAULT_PORT = 8765
DEFAULT_TELEMETRY_HZ = 10.0
MAX_TELEMETRY_HZ = 50.0
SAFETY_STATES = frozenset({"ready", "running", "estop"})

_KINDS = frozenset({"req", "res", "evt"})
_ENVELOPE_FIELDS = frozenset({"v", "kind", "op", "id", "body", "error"})


@dataclass(frozen=True)
class Device:
    type: str
    id: str
    capabilities: tuple[str, ...]

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": self.type,
            "id": self.id,
            "capabilities": list(self.capabilities),
        }


@dataclass(frozen=True)
class ErrorInfo:
    code: str
    message: str

    def to_dict(self) -> dict[str, str]:
        return {"code": self.code, "message": self.message}


@dataclass(frozen=True)
class Message:
    v: int
    kind: str
    op: str
    id: str | None = None
    body: dict[str, Any] | None = None
    error: ErrorInfo | None = None

    def to_dict(self) -> dict[str, Any]:
        if self.error is not None and self.body is not None:
            raise ValueError("error response cannot include a body")
        payload: dict[str, Any] = {"v": self.v, "kind": self.kind, "op": self.op}
        if self.id is not None:
            payload["id"] = self.id
        if self.error is not None:
            payload["error"] = self.error.to_dict()
        elif self.body is not None:
            payload["body"] = self.body
        return payload


@dataclass(frozen=True)
class Hello:
    protocol: str
    version: str
    robot_id: str
    robot_name: str
    safety: str
    devices: tuple[Device, ...]


@dataclass(frozen=True)
class TelemetrySample:
    safety: str
    commands: dict[str, float]
    servos: dict[str, float]


def request(op: str, body: dict[str, Any], msg_id: str) -> Message:
    return Message(v=ENVELOPE_VERSION, kind="req", op=op, id=msg_id, body=body)


def response(op: str, msg_id: str, body: dict[str, Any]) -> Message:
    return Message(v=ENVELOPE_VERSION, kind="res", op=op, id=msg_id, body=body)


def event(op: str, body: dict[str, Any]) -> Message:
    return Message(v=ENVELOPE_VERSION, kind="evt", op=op, body=body)


def error_response(op: str, msg_id: str, code: str, message: str) -> Message:
    return Message(
        v=ENVELOPE_VERSION,
        kind="res",
        op=op,
        id=msg_id,
        error=ErrorInfo(code, message),
    )


def parse_device(data: object) -> Device:
    from jiadroid.errors import ProtocolError

    if not isinstance(data, dict):
        raise ProtocolError("device must be an object")
    device_type = data.get("type")
    device_id = data.get("id")
    capabilities = data.get("capabilities")
    if not isinstance(device_type, str) or not device_type:
        raise ProtocolError("device type is missing")
    if not isinstance(device_id, str) or not device_id:
        raise ProtocolError("device id is missing")
    if not isinstance(capabilities, list) or not all(
        isinstance(item, str) and item for item in capabilities
    ):
        raise ProtocolError("device capabilities must be strings")
    return Device(device_type, device_id, tuple(capabilities))


def parse_devices(data: object) -> tuple[Device, ...]:
    from jiadroid.errors import ProtocolError

    if not isinstance(data, list):
        raise ProtocolError("devices must be a list")
    return tuple(parse_device(item) for item in data)


def parse_safety_state(body: dict[str, Any]) -> str:
    from jiadroid.errors import ProtocolError

    safety = body.get("safety")
    if not isinstance(safety, dict):
        raise ProtocolError("missing safety state")
    state = safety.get("state")
    if state not in SAFETY_STATES:
        raise ProtocolError("unknown safety state")
    return state


def parse_hello(body: dict[str, Any]) -> Hello:
    from jiadroid.errors import ProtocolError

    if body.get("protocol") != PROTOCOL_NAME:
        raise ProtocolError("unexpected protocol")
    if body.get("version") != PROTOCOL_VERSION:
        raise ProtocolError("unexpected protocol version")
    robot = body.get("robot")
    if not isinstance(robot, dict):
        raise ProtocolError("missing robot")
    robot_id = robot.get("id")
    robot_name = robot.get("name")
    if not isinstance(robot_id, str) or not robot_id:
        raise ProtocolError("invalid robot identity")
    if not isinstance(robot_name, str) or not robot_name:
        raise ProtocolError("invalid robot identity")
    return Hello(
        protocol=PROTOCOL_NAME,
        version=PROTOCOL_VERSION,
        robot_id=robot_id,
        robot_name=robot_name,
        safety=parse_safety_state(body),
        devices=parse_devices(body.get("devices")),
    )


def parse_telemetry(body: dict[str, Any]) -> TelemetrySample:
    from jiadroid.errors import ProtocolError

    return TelemetrySample(
        safety=parse_safety_state(body),
        commands=_parse_number_map(body.get("commands"), "commands"),
        servos=_parse_number_map(body.get("servos"), "servos"),
    )


def _parse_number_map(data: object, label: str) -> dict[str, float]:
    from jiadroid.errors import ProtocolError

    if not isinstance(data, dict):
        raise ProtocolError(f"telemetry is missing {label}")
    values: dict[str, float] = {}
    for key, value in data.items():
        if not isinstance(key, str) or isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ProtocolError(f"invalid {label} telemetry")
        values[key] = float(value)
    return values


def validate_envelope(data: object) -> dict[str, Any]:
    from jiadroid.errors import ProtocolError

    if not isinstance(data, dict):
        raise ProtocolError("message must be a JSON object")
    extra = set(data) - _ENVELOPE_FIELDS
    if extra:
        raise ProtocolError("unknown envelope field")
    if data.get("v") != ENVELOPE_VERSION:
        raise ProtocolError("unsupported envelope version")
    kind = data.get("kind")
    if kind not in _KINDS:
        raise ProtocolError("invalid kind")
    op = data.get("op")
    if not isinstance(op, str) or not op:
        raise ProtocolError("invalid operation")
    msg_id = data.get("id")
    if "id" in data and (not isinstance(msg_id, str) or not msg_id):
        raise ProtocolError("invalid message id")
    if "body" in data and not isinstance(data["body"], dict):
        raise ProtocolError("body must be an object")
    error = data.get("error")
    if "error" in data:
        if not isinstance(error, dict):
            raise ProtocolError("error must be an object")
        code = error.get("code")
        message = error.get("message")
        if not isinstance(code, str) or not code or not isinstance(message, str) or not message:
            raise ProtocolError("invalid error")
        if set(error) - {"code", "message"}:
            raise ProtocolError("invalid error")

    if kind == "req":
        if "id" not in data:
            raise ProtocolError("request is missing an id")
        if "body" not in data:
            raise ProtocolError("request is missing a body")
        if "error" in data:
            raise ProtocolError("request cannot include an error")
    elif kind == "evt":
        if "body" not in data:
            raise ProtocolError("event is missing a body")
        if "error" in data:
            raise ProtocolError("event cannot include an error")
    elif "error" in data:
        if "id" not in data:
            raise ProtocolError("response is missing an id")
        if "body" in data:
            raise ProtocolError("error response cannot include a body")
    else:
        if "id" not in data:
            raise ProtocolError("response is missing an id")
        if "body" not in data:
            raise ProtocolError("response is missing a body")
    return data


def message_from_dict(data: dict[str, Any]) -> Message:
    error_data = data.get("error")
    error = None
    if isinstance(error_data, dict):
        error = ErrorInfo(error_data["code"], error_data["message"])
    return Message(
        v=data["v"],
        kind=data["kind"],
        op=data["op"],
        id=data.get("id"),
        body=data.get("body"),
        error=error,
    )
