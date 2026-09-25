"""TCP byte stream of framed protocol messages."""

from __future__ import annotations

import socket
import threading

from jiadroid.errors import ProtocolError
from jiadroid.protocol.codec import decode_line, encode
from jiadroid.protocol.messages import MAX_LINE_BYTES, Message


class TcpStream:
    """One connected TCP socket. Send and receive may run on different threads."""

    def __init__(self, sock: socket.socket) -> None:
        self._sock = sock
        self._send_lock = threading.Lock()
        self._buffer = bytearray()

    def send(self, message: Message) -> None:
        frame = encode(message)
        with self._send_lock:
            self._sock.sendall(frame)

    def recv(self) -> Message | None:
        """Return the next message, or None when the peer closes the socket."""
        while True:
            newline = self._buffer.find(b"\n")
            if newline != -1:
                line = bytes(self._buffer[: newline + 1])
                del self._buffer[: newline + 1]
                return decode_line(line)
            if len(self._buffer) > MAX_LINE_BYTES:
                raise ProtocolError("message exceeds 8192 bytes")
            chunk = self._sock.recv(4096)
            if not chunk:
                if self._buffer:
                    raise ProtocolError("connection closed mid-message")
                return None
            self._buffer.extend(chunk)

    def close(self) -> None:
        try:
            self._sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self._sock.close()
        except OSError:
            pass
