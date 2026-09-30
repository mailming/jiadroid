"""Hello and telemetry parsing across protocol versions."""

import pytest

from jiadroid.errors import ProtocolError
from jiadroid.protocol.messages import parse_hello, parse_telemetry


def hello_0_2(**extra):
    body = {
        "protocol": "jiadroid",
        "version": "0.2",
        "robot": {"id": "rover", "name": "Rover", "kind": "wheeled"},
        "safety": {"state": "ready"},
        "controls": {"motion.velocity": {"forward": [-0.5, 0.5], "yaw": [-2, 2]}},
        "devices": [{"type": "motor", "id": "left_wheel", "capabilities": ["velocity"]}],
    }
    body.update(extra)
    return body


def test_parse_hello_0_2_with_controls() -> None:
    hello = parse_hello(hello_0_2())
    assert hello.kind == "wheeled"
    assert hello.supports("motion.velocity")
    assert hello.controls["motion.velocity"]["forward"] == (-0.5, 0.5)
    assert not hello.supports("head.pose")


def test_parse_hello_0_1_assumes_the_duck() -> None:
    body = {
        "protocol": "jiadroid",
        "version": "0.1",
        "robot": {"id": "open-duck-mini", "name": "Open Duck Mini"},
        "safety": {"state": "ready"},
        "devices": [],
    }
    hello = parse_hello(body)
    assert hello.version == "0.1"
    assert hello.kind == "biped"
    assert hello.supports("walk.velocity")
    assert hello.controls["motion.velocity"]["forward"] == (-0.15, 0.15)


def test_parse_hello_rejects_bad_versions_kinds_and_limits() -> None:
    with pytest.raises(ProtocolError):
        parse_hello(hello_0_2(version="0.3"))
    with pytest.raises(ProtocolError):
        parse_hello(hello_0_2(robot={"id": "x", "name": "X", "kind": "spaceship"}))
    with pytest.raises(ProtocolError):
        parse_hello(hello_0_2(controls={"motion.velocity": {"forward": [1, -1]}}))
    with pytest.raises(ProtocolError):
        parse_hello(hello_0_2(controls={"motion.velocity": {"forward": [0, "fast"]}}))
    with pytest.raises(ProtocolError):
        parse_hello(hello_0_2(controls=[]))


def test_parse_telemetry_0_2_optional_sections() -> None:
    sample = parse_telemetry(
        {
            "safety": {"state": "running"},
            "motion": {"forward": 0.2, "yaw": 0.0},
            "servos": {},
            "motors": {"left_wheel": 5.7},
            "base": {"x": 1.0, "y": 0.0, "yaw": 0.1},
        }
    )
    assert sample.motion["forward"] == 0.2
    assert sample.motors["left_wheel"] == 5.7
    assert sample.base["x"] == 1.0
    assert sample.head == {}
    assert sample.sensors == {}


def test_parse_telemetry_0_1_commands_split_into_motion_and_head() -> None:
    sample = parse_telemetry(
        {
            "safety": {"state": "running"},
            "commands": {"forward": 0.1, "lateral": 0, "yaw": 0.4, "neck_pitch": 0, "head_pitch": 0, "head_yaw": 0.2, "head_roll": 0},
            "servos": {"left_knee": 1.4},
        }
    )
    assert sample.motion == {"forward": 0.1, "lateral": 0.0, "yaw": 0.4}
    assert sample.head["yaw"] == 0.2
    assert sample.head["neck_pitch"] == 0.0
    assert sample.commands["head_yaw"] == 0.2


def test_parse_telemetry_requires_motion_and_servos() -> None:
    with pytest.raises(ProtocolError):
        parse_telemetry({"safety": {"state": "ready"}, "servos": {}})
    with pytest.raises(ProtocolError):
        parse_telemetry({"safety": {"state": "ready"}, "motion": {}})
    with pytest.raises(ProtocolError):
        parse_telemetry({"safety": {"state": "ready"}, "motion": {"forward": True}, "servos": {}})
