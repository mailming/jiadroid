"""Python client for a phone-to-robot controller."""

from __future__ import annotations

import itertools
import queue
import socket
import threading
from collections.abc import Iterator
from urllib.parse import urlparse

from jiadroid.errors import ProtocolError, RobotError
from jiadroid.protocol.messages import (
    Device,
    Hello,
    Message,
    TelemetrySample,
    parse_hello,
    parse_safety_state,
    parse_telemetry,
    request,
)
from jiadroid.transport.tcp import TcpStream


class Session:
    """Correlates responses and delivers events from one connection."""

    def __init__(self, stream: TcpStream) -> None:
        self._stream = stream
        self._pending: dict[str, queue.Queue[Message | None]] = {}
        self._pending_lock = threading.Lock()
        self._ids = itertools.count(1)
        self._hello: queue.Queue[Message | None] = queue.Queue()
        self._events: queue.Queue[Message | None] = queue.Queue()
        self._stashed: list[Message] = []
        self._thread: threading.Thread | None = None
        self._closed = False

    def start(self) -> None:
        self._thread = threading.Thread(target=self._read_loop, name="jiadroid-read", daemon=True)
        self._thread.start()

    def request(self, op: str, body: dict, timeout: float) -> Message:
        msg_id = str(next(self._ids))
        waiter: queue.Queue[Message | None] = queue.Queue(maxsize=1)
        with self._pending_lock:
            self._pending[msg_id] = waiter
        try:
            self._stream.send(request(op, body, msg_id))
            try:
                result = waiter.get(timeout=timeout)
            except queue.Empty as exc:
                raise TimeoutError(f"timed out waiting for {op}") from exc
        finally:
            with self._pending_lock:
                self._pending.pop(msg_id, None)
        if result is None:
            raise ConnectionError("connection closed")
        if result.error is not None:
            raise RobotError(result.error.code, result.error.message)
        return result

    def wait_hello(self, timeout: float) -> Message:
        try:
            message = self._hello.get(timeout=timeout)
        except queue.Empty as exc:
            raise TimeoutError("timed out waiting for session.hello") from exc
        if message is None:
            raise ConnectionError("connection closed")
        return message

    def next_event(self, op: str, timeout: float) -> Message:
        import time

        deadline = time.monotonic() + timeout
        while True:
            for index, message in enumerate(self._stashed):
                if message.op == op:
                    return self._stashed.pop(index)
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f"timed out waiting for {op}")
            try:
                message = self._events.get(timeout=remaining)
            except queue.Empty as exc:
                raise TimeoutError(f"timed out waiting for {op}") from exc
            if message is None:
                raise ConnectionError("connection closed")
            if message.op == op:
                return message
            self._stashed.append(message)

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        self._stream.close()
        self._wake()
        if self._thread is not None:
            self._thread.join(timeout=1)

    def _read_loop(self) -> None:
        try:
            while True:
                message = self._stream.recv()
                if message is None:
                    break
                self._dispatch(message)
        except (OSError, ProtocolError):
            pass
        finally:
            self._wake()

    def _dispatch(self, message: Message) -> None:
        if message.kind == "res":
            with self._pending_lock:
                waiter = self._pending.get(message.id or "")
            if waiter is not None:
                waiter.put(message)
            return
        if message.kind == "evt" and message.op == "session.hello":
            self._hello.put(message)
            return
        self._events.put(message)

    def _wake(self) -> None:
        with self._pending_lock:
            waiters = list(self._pending.values())
        for waiter in waiters:
            waiter.put(None)
        self._hello.put(None)
        self._events.put(None)


class Servo:
    def __init__(self, robot: Robot, device_id: str) -> None:
        self._robot = robot
        self.id = device_id

    def position(self, radians: float) -> None:
        result = self._robot._request("servo.position", {"id": self.id, "position": radians})
        self._robot._note_safety(result.body or {})

    def read(self) -> float:
        result = self._robot._request("servo.read", {"id": self.id})
        value = (result.body or {}).get("position")
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ProtocolError("servo position must be a number")
        return float(value)


class Motor:
    def __init__(self, robot: Robot, device_id: str) -> None:
        self._robot = robot
        self.id = device_id

    def velocity(self, value: float) -> None:
        result = self._robot._request("motor.velocity", {"id": self.id, "velocity": value})
        self._robot._note_safety(result.body or {})


class Encoder:
    def __init__(self, robot: Robot, device_id: str) -> None:
        self._robot = robot
        self.id = device_id

    def read(self) -> int:
        result = self._robot._request("encoder.read", {"id": self.id})
        ticks = (result.body or {}).get("ticks")
        if isinstance(ticks, bool) or not isinstance(ticks, int):
            raise ProtocolError("encoder ticks must be an integer")
        return ticks


class Sensor:
    def __init__(self, robot: Robot, device_id: str) -> None:
        self._robot = robot
        self.id = device_id

    def read(self) -> float:
        result = self._robot._request("sensor.read", {"id": self.id})
        value = (result.body or {}).get("value")
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ProtocolError("sensor value must be a number")
        return float(value)


class Robot:
    def __init__(self, session: Session, hello: Hello) -> None:
        self._session = session
        self._hello = hello
        self._devices = list(hello.devices)
        self._safety = hello.safety
        self._closed = False

    def __enter__(self) -> Robot:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    @property
    def safety(self) -> str:
        return self._safety

    @property
    def name(self) -> str:
        return self._hello.robot_name

    def devices(self) -> list[Device]:
        result = self._request("devices.list", {})
        self._devices = list(parse_devices_from_body(result.body or {}))
        return list(self._devices)

    def servo(self, device_id: str) -> Servo:
        self._require(device_id, "servo")
        return Servo(self, device_id)

    def walk(
        self,
        forward: float = 0.0,
        lateral: float = 0.0,
        yaw: float = 0.0,
        neck_pitch: float = 0.0,
        head_pitch: float = 0.0,
        head_yaw: float = 0.0,
        head_roll: float = 0.0,
    ) -> None:
        result = self._request(
            "walk.velocity",
            {
                "forward": forward,
                "lateral": lateral,
                "yaw": yaw,
                "neck_pitch": neck_pitch,
                "head_pitch": head_pitch,
                "head_yaw": head_yaw,
                "head_roll": head_roll,
            },
        )
        self._note_safety(result.body or {})

    def motor(self, device_id: str) -> Motor:
        self._require(device_id, "motor")
        return Motor(self, device_id)

    def encoder(self, device_id: str) -> Encoder:
        self._require(device_id, "encoder")
        return Encoder(self, device_id)

    def sensor(self, device_id: str) -> Sensor:
        self._require(device_id, "sensor")
        return Sensor(self, device_id)

    def stop(self) -> None:
        result = self._request("robot.stop", {})
        self._note_safety(result.body or {})

    def estop(self) -> None:
        result = self._request("safety.estop", {})
        self._note_safety(result.body or {})

    def clear_estop(self) -> None:
        result = self._request("safety.clear", {})
        self._note_safety(result.body or {})

    def ping(self) -> None:
        self._request("session.ping", {})

    def telemetry(self, hz: float = 10.0, count: int | None = None) -> Iterator[TelemetrySample]:
        self._request("telemetry.subscribe", {"hz": hz})
        produced = 0
        while count is None or produced < count:
            message = self._session.next_event("telemetry.sample", timeout=2.0)
            if message.body is None:
                raise ProtocolError("telemetry sample is missing a body")
            yield parse_telemetry(message.body)
            produced += 1

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            self._session.request("session.bye", {}, timeout=1.0)
        except (OSError, TimeoutError, RobotError, ConnectionError):
            pass
        self._session.close()

    def _request(self, op: str, body: dict, timeout: float = 2.0) -> Message:
        return self._session.request(op, body, timeout)

    def _note_safety(self, body: dict) -> None:
        if "safety" in body:
            self._safety = parse_safety_state(body)

    def _require(self, device_id: str, device_type: str) -> Device:
        matches = [device for device in self._devices if device.id == device_id]
        if not matches:
            raise RobotError("unknown_device", f"no device named {device_id}")
        typed = [device for device in matches if device.type == device_type]
        if not typed:
            found = ", ".join(device.type for device in matches)
            raise RobotError("unsupported", f"{device_id} is {found}, not a {device_type}")
        return typed[0]


def parse_devices_from_body(body: dict) -> tuple[Device, ...]:
    from jiadroid.protocol.messages import parse_devices

    return parse_devices(body.get("devices"))


def connect(url: str, timeout: float = 5.0) -> Robot:
    """Open a `tcp://host:port` connection and wait for `session.hello`."""
    host, port = _parse_tcp_url(url)
    sock = socket.create_connection((host, port), timeout=timeout)
    sock.settimeout(None)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    session = Session(TcpStream(sock))
    session.start()
    try:
        hello_message = session.wait_hello(timeout)
    except Exception:
        session.close()
        raise
    if hello_message.body is None:
        session.close()
        raise ProtocolError("session.hello is missing a body")
    try:
        hello = parse_hello(hello_message.body)
    except ProtocolError:
        session.close()
        raise
    return Robot(session, hello)


def _parse_tcp_url(url: str) -> tuple[str, int]:
    parsed = urlparse(url)
    if parsed.scheme != "tcp" or not parsed.hostname or parsed.port is None:
        raise ValueError("url must look like tcp://127.0.0.1:8765")
    return parsed.hostname, parsed.port
