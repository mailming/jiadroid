"""Simulated Open Duck Mini controller."""

from __future__ import annotations

import math
import socket
import threading
import time

from jiadroid.errors import ProtocolError, RobotError
from jiadroid.protocol.messages import (
    DEFAULT_PORT,
    DEFAULT_TELEMETRY_HZ,
    MAX_TELEMETRY_HZ,
    PROTOCOL_NAME,
    PROTOCOL_VERSION,
    Device,
    Message,
    error_response,
    event,
    response,
)
from jiadroid.sim.duck import (
    COMMAND_LIMITS,
    HEAD_JOINTS,
    JOINTS,
    LEG_SWING,
    LOCOMOTION,
    ROBOT_ID,
    ROBOT_NAME,
    STANDING,
)
from jiadroid.transport.tcp import TcpStream

TICK_PERIOD_S = 0.02
GAIT_RATE = 7.0
SWING_RAD = 0.18

REFERENCE_DEVICES = tuple(Device("servo", name, ("position",)) for name, _position in JOINTS)


class SimulatedRobot:
    """Open Duck Mini joints plus the walk command its policy expects."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._devices = REFERENCE_DEVICES
        self._positions = dict(STANDING)
        self._command = {name: 0.0 for name in COMMAND_LIMITS}
        self._phase = 0.0
        self._safety = "ready"

    def hello_body(self) -> dict:
        with self._lock:
            state = self._safety
        return {
            "protocol": PROTOCOL_NAME,
            "version": PROTOCOL_VERSION,
            "robot": {"id": ROBOT_ID, "name": ROBOT_NAME},
            "safety": {"state": state},
            "devices": [device.to_dict() for device in self._devices],
        }

    def handle(self, request_message: Message) -> Message:
        msg_id = request_message.id or "0"
        body = request_message.body or {}
        try:
            result = self._dispatch(request_message.op, body)
        except RobotError as exc:
            return error_response(request_message.op, msg_id, exc.code, exc.message)
        return response(request_message.op, msg_id, result)

    def integrate(self, dt: float) -> None:
        with self._lock:
            moving = self._moving_locked()
            if moving:
                self._phase += dt * GAIT_RATE
                swing = math.sin(self._phase) * SWING_RAD
                for name, sign in LEG_SWING.items():
                    self._positions[name] = STANDING[name] + sign * swing
            for name in HEAD_JOINTS:
                self._positions[name] = self._command[name]

    def snapshot(self) -> dict:
        with self._lock:
            return {
                "safety": {"state": self._safety},
                "commands": dict(self._command),
                "servos": {name: round(position, 4) for name, position in self._positions.items()},
            }

    def _dispatch(self, op: str, body: dict) -> dict:
        if op == "session.ping" or op == "session.bye":
            return {"ok": True}
        if op == "devices.list":
            return {"devices": [device.to_dict() for device in self._devices]}
        if op == "walk.velocity":
            return self._set_walk(body)
        if op == "servo.position":
            return self._set_position(body)
        if op == "servo.read":
            return self._read_position(body)
        if op == "robot.stop":
            return self._stop()
        if op == "telemetry.subscribe":
            return {"hz": _parse_hz(body)}
        if op == "safety.estop":
            return self._estop()
        if op == "safety.clear":
            return self._clear_estop()
        raise RobotError("unsupported", f"unknown operation {op}")

    def _set_walk(self, body: dict) -> dict:
        command = {name: _parse_limited(name, body.get(name, 0.0)) for name in COMMAND_LIMITS}
        with self._lock:
            if self._safety == "estop":
                raise RobotError("safety_blocked", "emergency stop is latched")
            self._command = command
            if self._moving_locked():
                self._safety = "running"
            for name in HEAD_JOINTS:
                self._positions[name] = command[name]
            state = self._safety
        return {**command, "safety": {"state": state}}

    def _set_position(self, body: dict) -> dict:
        device_id = _device_id(body)
        position = _parse_position(body.get("position"))
        with self._lock:
            if self._safety == "estop":
                raise RobotError("safety_blocked", "emergency stop is latched")
            self._require_locked(device_id, "servo", "position")
            self._positions[device_id] = position
            if device_id in HEAD_JOINTS:
                self._command[device_id] = position
            state = self._safety
        return {"id": device_id, "position": position, "safety": {"state": state}}

    def _read_position(self, body: dict) -> dict:
        device_id = _device_id(body)
        with self._lock:
            self._require_locked(device_id, "servo", "position")
            position = self._positions[device_id]
        return {"id": device_id, "position": position}

    def _stop(self) -> dict:
        with self._lock:
            self._command = {name: 0.0 for name in COMMAND_LIMITS}
            self._positions = dict(STANDING)
            self._phase = 0.0
            if self._safety != "estop":
                self._safety = "ready"
            state = self._safety
        return {"safety": {"state": state}}

    def _estop(self) -> dict:
        with self._lock:
            self._command = {name: 0.0 for name in COMMAND_LIMITS}
            self._positions = dict(STANDING)
            self._phase = 0.0
            self._safety = "estop"
        return {"safety": {"state": "estop"}}

    def _clear_estop(self) -> dict:
        with self._lock:
            if self._safety == "estop":
                self._safety = "ready"
            state = self._safety
        return {"safety": {"state": state}}

    def _moving_locked(self) -> bool:
        return any(abs(self._command[name]) > 1e-6 for name in LOCOMOTION)

    def _require_locked(self, device_id: str, device_type: str, capability: str) -> None:
        matches = [device for device in self._devices if device.id == device_id]
        if not matches:
            raise RobotError("unknown_device", f"no device named {device_id}")
        typed = [device for device in matches if device.type == device_type]
        if not typed:
            found = ", ".join(device.type for device in matches)
            raise RobotError("unsupported", f"{device_id} is {found}, not a {device_type}")
        if capability not in typed[0].capabilities:
            raise RobotError("unsupported", f"{device_type} {device_id} cannot {capability}")


class TelemetryPump:
    def __init__(self, stream: TcpStream, robot: SimulatedRobot) -> None:
        self._stream = stream
        self._robot = robot
        self._lock = threading.Lock()
        self._hz = 0.0
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def set_rate(self, hz: float) -> None:
        with self._lock:
            self._hz = hz
            if self._thread is None:
                self._thread = threading.Thread(target=self._run, name="jiadroid-telemetry", daemon=True)
                self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=1)

    def _rate(self) -> float:
        with self._lock:
            return self._hz

    def _run(self) -> None:
        while not self._stop.is_set():
            hz = self._rate()
            if hz <= 0:
                if self._stop.wait(0.05):
                    return
                continue
            try:
                self._stream.send(event("telemetry.sample", self._robot.snapshot()))
            except OSError:
                return
            if self._stop.wait(1.0 / hz):
                return


class SimulatorServer:
    """TCP host for one shared simulated robot."""

    def __init__(self, host: str = "127.0.0.1", port: int = DEFAULT_PORT) -> None:
        self.host = host
        self.port = port
        self._robot = SimulatedRobot()
        self._stop = threading.Event()
        self._listen: socket.socket | None = None
        self._accept_thread: threading.Thread | None = None
        self._tick_thread: threading.Thread | None = None

    def start(self) -> None:
        if self._listen is not None:
            return
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind((self.host, self.port))
        sock.listen(5)
        sock.settimeout(0.2)
        self.port = sock.getsockname()[1]
        self._listen = sock
        self._stop.clear()
        self._tick_thread = threading.Thread(target=self._tick_loop, name="jiadroid-tick", daemon=True)
        self._accept_thread = threading.Thread(target=self._accept_loop, name="jiadroid-accept", daemon=True)
        self._tick_thread.start()
        self._accept_thread.start()

    def serve_forever(self) -> None:
        self.start()
        print(f"jiadroid simulator listening on tcp://{self.host}:{self.port}", flush=True)
        if self._accept_thread is not None:
            self._accept_thread.join()

    def close(self) -> None:
        self._stop.set()
        if self._listen is not None:
            try:
                self._listen.close()
            except OSError:
                pass
        if self._accept_thread is not None:
            self._accept_thread.join(timeout=2)
        if self._tick_thread is not None:
            self._tick_thread.join(timeout=2)

    def _tick_loop(self) -> None:
        last = time.monotonic()
        while not self._stop.wait(TICK_PERIOD_S):
            now = time.monotonic()
            dt = now - last
            last = now
            self._robot.integrate(dt)

    def _accept_loop(self) -> None:
        assert self._listen is not None
        while not self._stop.is_set():
            try:
                conn, _addr = self._listen.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            threading.Thread(
                target=self._serve_connection,
                args=(conn,),
                name="jiadroid-conn",
                daemon=True,
            ).start()

    def _serve_connection(self, sock: socket.socket) -> None:
        stream = TcpStream(sock)
        pump = TelemetryPump(stream, self._robot)
        try:
            stream.send(event("session.hello", self._robot.hello_body()))
            while not self._stop.is_set():
                incoming = stream.recv()
                if incoming is None:
                    break
                if incoming.kind != "req" or not incoming.id:
                    continue
                reply = self._robot.handle(incoming)
                stream.send(reply)
                if reply.error is None and incoming.op == "telemetry.subscribe":
                    hz = DEFAULT_TELEMETRY_HZ if reply.body is None else float(reply.body["hz"])
                    pump.set_rate(hz)
                if incoming.op == "session.bye":
                    break
        except (OSError, ProtocolError):
            pass
        finally:
            pump.stop()
            stream.close()


def _device_id(body: dict) -> str:
    device_id = body.get("id")
    if not isinstance(device_id, str) or not device_id:
        raise RobotError("unknown_device", "no device named in the request")
    return device_id


def _parse_limited(name: str, value: object) -> float:
    low, high = COMMAND_LIMITS[name]
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise RobotError("out_of_range", f"{name} must be from {low} to {high}")
    number = float(value)
    if not math.isfinite(number) or number < low or number > high:
        raise RobotError("out_of_range", f"{name} must be from {low} to {high}")
    return number


def _parse_position(value: object) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise RobotError("out_of_range", "position must be from -2.5 to 2.5 radians")
    position = float(value)
    if not math.isfinite(position) or position < -2.5 or position > 2.5:
        raise RobotError("out_of_range", "position must be from -2.5 to 2.5 radians")
    return position


def _parse_hz(body: dict) -> float:
    if "hz" not in body:
        return DEFAULT_TELEMETRY_HZ
    value = body["hz"]
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise RobotError("out_of_range", "telemetry hz must be greater than 0 and at most 50")
    hz = float(value)
    if not math.isfinite(hz) or hz <= 0 or hz > MAX_TELEMETRY_HZ:
        raise RobotError("out_of_range", "telemetry hz must be greater than 0 and at most 50")
    return hz
