"""TCP host for one simulated robot body."""

from __future__ import annotations

import socket
import threading
import time
from typing import Callable

from jiadroid.errors import ProtocolError
from jiadroid.protocol.messages import DEFAULT_PORT, DEFAULT_TELEMETRY_HZ, event
from jiadroid.sim.body import Body
from jiadroid.transport.tcp import TcpStream

TICK_PERIOD_S = 0.02

BodyFactory = Callable[[], Body]


def make_body(name: str, **options: object) -> Body:
    """Bodies the command line can start: `duck`, `rover`, `duck-physics`."""
    if name == "duck":
        from jiadroid.sim.duck import DuckBody

        return DuckBody()
    if name == "rover":
        from jiadroid.sim.rover import RoverBody

        return RoverBody()
    if name == "duck-physics":
        from jiadroid.sim.duck_physics import DuckPhysicsBody

        return DuckPhysicsBody(**options)  # type: ignore[arg-type]
    raise ValueError(f"unknown body {name!r}; expected duck, rover, or duck-physics")


class TelemetryPump:
    def __init__(self, stream: TcpStream, body: Body) -> None:
        self._stream = stream
        self._body = body
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
                self._stream.send(event("telemetry.sample", self._body.snapshot()))
            except OSError:
                return
            if self._stop.wait(1.0 / hz):
                return


class SimulatorServer:
    """Listens on TCP and lets any number of phones share one body."""

    def __init__(self, host: str = "127.0.0.1", port: int = DEFAULT_PORT, body: Body | None = None) -> None:
        self.host = host
        self.port = port
        if body is None:
            from jiadroid.sim.duck import DuckBody

            body = DuckBody()
        self.body = body
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

    def announce(self) -> None:
        print(
            f"jiadroid simulator: {self.body.robot_name} ({self.body.kind}) listening on tcp://{self.host}:{self.port}",
            flush=True,
        )
        if self.host == "0.0.0.0":
            ip = _lan_ip()
            if ip:
                print(f"from the phone, connect to {ip}:{self.port}", flush=True)

    def serve_forever(self) -> None:
        self.start()
        self.announce()
        if self._accept_thread is not None:
            self._accept_thread.join()

    def wait(self, timeout: float | None = None) -> None:
        if self._accept_thread is not None:
            self._accept_thread.join(timeout)

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
        closer = getattr(self.body, "close", None)
        if callable(closer):
            closer()

    def _tick_loop(self) -> None:
        last = time.monotonic()
        while not self._stop.wait(TICK_PERIOD_S):
            now = time.monotonic()
            dt = now - last
            last = now
            self.body.integrate(dt)

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
        pump = TelemetryPump(stream, self.body)
        try:
            stream.send(event("session.hello", self.body.hello_body()))
            while not self._stop.is_set():
                incoming = stream.recv()
                if incoming is None:
                    break
                if incoming.kind != "req" or not incoming.id:
                    continue
                reply = self.body.handle(incoming)
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


def _lan_ip() -> str | None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    except OSError:
        return None
    finally:
        sock.close()
