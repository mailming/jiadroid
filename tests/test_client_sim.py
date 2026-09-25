import time

import pytest

from jiadroid import RobotError, connect
from jiadroid.sim.controller import SimulatorServer
from jiadroid.sim.duck import JOINTS, STANDING


@pytest.fixture
def server():
    sim = SimulatorServer("127.0.0.1", 0)
    sim.start()
    try:
        yield sim
    finally:
        sim.close()


@pytest.fixture
def robot(server):
    bot = connect(f"tcp://{server.host}:{server.port}")
    try:
        yield bot
    finally:
        bot.close()


def test_discovery_lists_open_duck_servos(robot) -> None:
    assert robot.name == "Open Duck Mini"
    devices = [(device.type, device.id) for device in robot.devices()]
    assert devices == [("servo", name) for name, _position in JOINTS]
    knee = next(device for device in robot.devices() if device.id == "left_knee")
    assert knee.type == "servo"
    assert "position" in knee.capabilities
    assert robot.servo("left_knee").read() == pytest.approx(STANDING["left_knee"])
    robot.ping()


def test_walk_command_and_telemetry(robot) -> None:
    robot.walk(forward=0.1, yaw=-0.4, head_yaw=0.2)
    assert robot.safety == "running"
    sample = next(robot.telemetry(hz=20, count=1))
    assert sample.safety == "running"
    assert sample.commands["forward"] == pytest.approx(0.1)
    assert sample.commands["yaw"] == pytest.approx(-0.4)
    assert sample.commands["head_yaw"] == pytest.approx(0.2)
    assert "left_knee" in sample.servos


def test_walking_swings_a_leg(robot) -> None:
    standing = robot.servo("left_knee").read()
    robot.walk(forward=0.12)
    deadline = time.monotonic() + 1.0
    moved = standing
    while time.monotonic() < deadline:
        moved = robot.servo("left_knee").read()
        if abs(moved - standing) > 0.02:
            break
        time.sleep(0.02)
    assert abs(moved - standing) > 0.02


def test_stop_stands_and_holds_pose(robot) -> None:
    robot.walk(forward=0.12)
    time.sleep(0.15)
    robot.stop()
    assert robot.safety == "ready"
    sample = next(robot.telemetry(hz=20, count=1))
    assert sample.commands["forward"] == 0
    assert sample.commands["yaw"] == 0
    assert robot.servo("left_knee").read() == pytest.approx(STANDING["left_knee"])
    time.sleep(0.2)
    assert robot.servo("left_knee").read() == pytest.approx(STANDING["left_knee"])


def test_estop_rejects_walk_until_cleared(robot) -> None:
    robot.walk(forward=0.08)
    robot.estop()
    assert robot.safety == "estop"
    with pytest.raises(RobotError) as exc:
        robot.walk(forward=0.08)
    assert exc.value.code == "safety_blocked"
    assert robot.servo("left_knee").read() == pytest.approx(STANDING["left_knee"])
    robot.clear_estop()
    assert robot.safety == "ready"
    robot.walk(yaw=0.4)
    assert robot.safety == "running"


def test_unknown_servo_wrong_type_and_range(robot) -> None:
    with pytest.raises(RobotError) as missing:
        robot.servo("nope")
    assert missing.value.code == "unknown_device"
    with pytest.raises(RobotError) as wrong_type:
        robot.motor("left_knee")
    assert wrong_type.value.code == "unsupported"
    with pytest.raises(RobotError) as out_of_range:
        robot.walk(forward=1.0)
    assert out_of_range.value.code == "out_of_range"
