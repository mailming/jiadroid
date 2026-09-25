import json
from pathlib import Path

import pytest

from jiadroid.errors import ProtocolError
from jiadroid.protocol.codec import decode_line, encode
from jiadroid.protocol.messages import error_response, event, request

ROOT = Path(__file__).resolve().parents[1]


def test_schema_file_is_json() -> None:
    schema = json.loads((ROOT / "schema" / "message.schema.json").read_text(encoding="utf-8"))
    assert schema["properties"]["v"]["const"] == 1


def test_roundtrip_request() -> None:
    message = request("motor.velocity", {"id": "left", "velocity": 0.5}, "1")
    assert decode_line(encode(message)) == message


def test_roundtrip_event_omits_id() -> None:
    message = event("session.hello", {"protocol": "jiadroid"})
    frame = encode(message)
    assert b'"id"' not in frame
    assert decode_line(frame) == message


def test_error_response_has_no_body() -> None:
    message = error_response("motor.velocity", "1", "safety_blocked", "emergency stop is latched")
    frame = encode(message)
    assert b'"body"' not in frame
    assert decode_line(frame) == message


def test_reject_request_without_id() -> None:
    with pytest.raises(ProtocolError):
        decode_line(b'{"v":1,"kind":"req","op":"session.ping","body":{}}\n')


def test_reject_error_response_with_body() -> None:
    frame = (
        b'{"v":1,"kind":"res","op":"motor.velocity","id":"1",'
        b'"body":{},"error":{"code":"safety_blocked","message":"estop"}}\n'
    )
    with pytest.raises(ProtocolError):
        decode_line(frame)


def test_reject_invalid_json() -> None:
    with pytest.raises(ProtocolError):
        decode_line(b"{not json}\n")


def test_reject_oversized_line() -> None:
    message = event("telemetry.sample", {"pad": "x" * 9000})
    with pytest.raises(ProtocolError):
        encode(message)
    raw = b'{"v":1,"kind":"evt","op":"x","body":{"pad":"' + (b"a" * 9000) + b'"}}\n'
    with pytest.raises(ProtocolError):
        decode_line(raw)
