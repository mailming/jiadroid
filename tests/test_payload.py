"""Phone mounts and payload.set, without physics."""

import pytest

from jiadroid.errors import ProtocolError, RobotError
from jiadroid.protocol.messages import parse_hello, parse_telemetry
from jiadroid.protocol.phones import lookup_phone
from jiadroid.sim.duck import DuckBody
from jiadroid.sim.rover import RoverBody


def hello_0_2(**extra):
    body = {
        "protocol": "jiadroid",
        "version": "0.2",
        "robot": {"id": "rover", "name": "Rover", "kind": "wheeled"},
        "safety": {"state": "ready"},
        "controls": {"motion.velocity": {"forward": [-0.5, 0.5], "yaw": [-2, 2]}},
        "devices": [],
    }
    body.update(extra)
    return body


def test_parse_hello_reads_mounts() -> None:
    hello = parse_hello(
        hello_0_2(
            mounts=[{"id": "deck", "position": [0, 0, 0.06], "max_mass": 0.5}],
        )
    )
    assert hello.mounts[0].id == "deck"
    assert hello.mounts[0].position == (0.0, 0.0, 0.06)
    assert hello.mounts[0].max_mass == 0.5


def test_parse_hello_without_mounts_and_rejects_a_bad_one() -> None:
    assert parse_hello(hello_0_2()).mounts == ()
    with pytest.raises(ProtocolError):
        parse_hello(hello_0_2(mounts=[{"id": "deck", "position": [0, 0], "max_mass": 0.5}]))
    with pytest.raises(ProtocolError):
        parse_hello(hello_0_2(mounts=[{"id": "deck", "position": [0, 0, 0], "max_mass": 0}]))


def test_lookup_phone_prefers_the_longer_name() -> None:
    assert lookup_phone("iPhone 16 Pro Max").mass == 0.227
    assert lookup_phone("iPhone17,2").name == "iPhone 16 Pro Max"
    assert lookup_phone("Pixel 9").mass == 0.198
    assert lookup_phone("something else").name == "generic"
    assert lookup_phone("iPhone 16 Pro Max").size_in_body("head")[0] == 0.0083
    assert lookup_phone("iPhone 16").size_in_body("deck")[2] == 0.0078


def test_duck_announces_two_mounts_and_accepts_a_phone() -> None:
    duck = DuckBody()
    ids = [mount.id for mount in duck.mounts]
    assert ids == ["head", "back"]
    placed = duck.dispatch(
        "payload.set",
        {"mount": "head", "mass": 0.170, "size": [0.0078, 0.0716, 0.1476], "name": "iPhone 16"},
    )
    assert placed["position"] == [0.02, 0.0, 0.22]
    assert placed["offset"] == [0.0, 0.0, 0.0]
    assert duck.snapshot()["payload"]["mass"] == 0.170
    sample = parse_telemetry(duck.snapshot())
    assert sample.payload["name"] == "iPhone 16"
    assert duck.dispatch("payload.clear", {}) == {"cleared": True}
    assert "payload" not in duck.snapshot()


def test_payload_is_refused_when_it_does_not_fit() -> None:
    duck = DuckBody()
    with pytest.raises(RobotError) as unknown:
        duck.dispatch("payload.set", {"mount": "deck", "mass": 0.17, "size": [0.01, 0.07, 0.15]})
    assert unknown.value.code == "unknown_device"
    with pytest.raises(RobotError) as heavy:
        duck.dispatch("payload.set", {"mount": "head", "mass": 0.5, "size": [0.01, 0.07, 0.15]})
    assert heavy.value.code == "out_of_range"
    with pytest.raises(RobotError):
        duck.dispatch("payload.set", {"mount": "head", "mass": 0.17, "size": [0.01, 0.07, 0.15], "offset": [0.2, 0, 0]})


def test_chassis_phone_stands_on_top_and_survives_estop() -> None:
    rover = RoverBody()
    assert [mount.id for mount in rover.mounts] == ["top"]
    rover.estop()
    placed = rover.dispatch(
        "payload.set",
        {"mount": "top", "mass": 0.221, "size": [0.0085, 0.0765, 0.1628], "offset": [0.01, 0, 0], "name": "Pixel 9 Pro XL"},
    )
    mount = rover.mounts[0]
    assert placed["position"] == [
        pytest.approx(mount.position[0] + 0.01),
        pytest.approx(mount.position[1]),
        pytest.approx(mount.position[2]),
    ]
    assert rover.safety == "estop"
