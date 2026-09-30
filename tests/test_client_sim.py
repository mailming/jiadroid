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
    assert robot.kind == "biped"
    devices = [(device.type, device.id) for device in robot.devices()]
    assert devices == [("servo", name) for name, _position in JOINTS]
    knee = next(device for device in robot.devices() if device.id == "left_knee")
    assert knee.type == "servo"
    assert "position" in knee.capabilities
    assert robot.servo("left_knee").read() == pytest.approx(STANDING["left_knee"])
    robot.ping()


def test_hello_announces_controls_with_limits(robot) -> None:
    assert robot.supports("motion.velocity")
    assert robot.supports("head.pose")
    assert robot.supports("walk.velocity")
    assert robot.controls["motion.velocity"]["forward"] == (-0.15, 0.15)
    assert robot.controls["motion.velocity"]["yaw"] == (-1.0, 1.0)
    assert robot.controls["head.pose"]["yaw"] == (-0.5, 0.5)
    assert robot.controls["head.pose"]["neck_pitch"] == (-0.34, 1.1)


def test_move_look_and_telemetry(robot) -> None:
    robot.move(forward=0.1, yaw=-0.4)
    robot.look(yaw=0.2)
    assert robot.safety == "running"
    sample = next(robot.telemetry(hz=20, count=1))
    assert sample.safety == "running"
    assert sample.motion["forward"] == pytest.approx(0.1)
    assert sample.motion["yaw"] == pytest.approx(-0.4)
    assert sample.head["yaw"] == pytest.approx(0.2)
    assert sample.commands["head_yaw"] == pytest.approx(0.2)
    assert "left_knee" in sample.servos
    assert sample.servos["head_yaw"] == pytest.approx(0.2)


def test_legacy_walk_still_works(robot) -> None:
    robot.walk(forward=0.1, yaw=-0.4, head_yaw=0.2)
    assert robot.safety == "running"
    sample = next(robot.telemetry(hz=20, count=1))
    assert sample.motion["forward"] == pytest.approx(0.1)
    assert sample.head["yaw"] == pytest.approx(0.2)


def test_walking_swings_a_leg(robot) -> None:
    standing = robot.servo("left_knee").read()
    robot.move(forward=0.12)
    deadline = time.monotonic() + 1.0
    moved = standing
    while time.monotonic() < deadline:
        moved = robot.servo("left_knee").read()
        if abs(moved - standing) > 0.02:
            break
        time.sleep(0.02)
    assert abs(moved - standing) > 0.02


def test_stop_stands_and_holds_pose(robot) -> None:
    robot.move(forward=0.12)
    robot.look(yaw=0.3)
    time.sleep(0.15)
    robot.stop()
    assert robot.safety == "ready"
    sample = next(robot.telemetry(hz=20, count=1))
    assert sample.motion["forward"] == 0
    assert sample.motion["yaw"] == 0
    assert sample.head["yaw"] == 0
    assert robot.servo("left_knee").read() == pytest.approx(STANDING["left_knee"])
    time.sleep(0.2)
    assert robot.servo("left_knee").read() == pytest.approx(STANDING["left_knee"])


def test_reset_returns_to_ready(robot) -> None:
    robot.move(forward=0.1)
    robot.reset()
    assert robot.safety == "ready"
    assert robot.servo("left_knee").read() == pytest.approx(STANDING["left_knee"])


def test_estop_rejects_motion_until_cleared(robot) -> None:
    robot.move(forward=0.08)
    robot.estop()
    assert robot.safety == "estop"
    with pytest.raises(RobotError) as exc:
        robot.move(forward=0.08)
    assert exc.value.code == "safety_blocked"
    with pytest.raises(RobotError) as head:
        robot.look(yaw=0.1)
    assert head.value.code == "safety_blocked"
    assert robot.servo("left_knee").read() == pytest.approx(STANDING["left_knee"])
    robot.clear_estop()
    assert robot.safety == "ready"
    robot.move(yaw=0.4)
    assert robot.safety == "running"


def test_unknown_servo_wrong_type_and_range(robot) -> None:
    with pytest.raises(RobotError) as missing:
        robot.servo("nope")
    assert missing.value.code == "unknown_device"
    with pytest.raises(RobotError) as wrong_type:
        robot.motor("left_knee")
    assert wrong_type.value.code == "unsupported"
    with pytest.raises(RobotError) as out_of_range:
        robot.move(forward=1.0)
    assert out_of_range.value.code == "out_of_range"
    with pytest.raises(RobotError) as head_range:
        robot.look(yaw=0.9)
    assert head_range.value.code == "out_of_range"


def test_head_servo_position_updates_head_pose(robot) -> None:
    robot.servo("head_yaw").position(0.25)
    sample = next(robot.telemetry(hz=20, count=1))
    assert sample.head["yaw"] == pytest.approx(0.25)
