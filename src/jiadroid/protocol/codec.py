"""Newline-delimited JSON framing."""

from __future__ import annotations

import json

from jiadroid.errors import ProtocolError
from jiadroid.protocol.messages import (
    MAX_LINE_BYTES,
    Message,
    message_from_dict,
    validate_envelope,
)


def encode(message: Message) -> bytes:
    """Serialize one message, including the trailing newline."""
    try:
        payload = message.to_dict()
    except ValueError as exc:
        raise ProtocolError(str(exc)) from exc
    validate_envelope(payload)
    raw = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    frame = raw + b"\n"
    if len(frame) > MAX_LINE_BYTES:
        raise ProtocolError("message exceeds 8192 bytes")
    return frame


def decode_line(line: bytes) -> Message:
    """Parse one framed message. `line` may include the trailing newline."""
    if len(line) > MAX_LINE_BYTES:
        raise ProtocolError("message exceeds 8192 bytes")
    try:
        text = line.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ProtocolError("message is not UTF-8") from exc
    if text.endswith("\n"):
        text = text[:-1]
    if text.endswith("\r"):
        text = text[:-1]
    if text == "":
        raise ProtocolError("empty message")
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ProtocolError("invalid JSON") from exc
    return message_from_dict(validate_envelope(data))
