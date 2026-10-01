"""One phone command, a very different body."""

import math
import time

import pytest

from jiadroid import RobotError, connect
from jiadroid.follow import decide, fit_to_robot, observe
from jiadroid.sim.controller import SimulatorServer
from jiadroid.sim.rover import MAX_FORWARD_M_S, WALL_X, RoverBody


@pytest.fixture
def server():
    sim = SimulatorServer("127.0.0.1", 0, RoverBody())
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


def test_rover_announces_wheels_not_legs(robot) -> None:
    assert robot.name == "2WD Chassis"
    assert robot.kind == "wheeled"
    assert robot.supports("motion.velocity")
    assert not robot.supports("head.pose")
    assert not robot.supports("walk.velocity")
    assert "lateral" not in robot.controls["motion.velocity"]
    types = {device.type for device in robot.devices()}
    assert types == {"motor", "encoder", "sensor"}


def test_motion_drives_both_wheels_and_encoders_count(robot) -> None:
    robot.move(forward=0.2)
    assert robot.safety == "running"
    time.sleep(0.25)
    left = robot.encoder("left_wheel").read()
    right = robot.encoder("right_wheel").read()
    assert left > 0 and right > 0
    assert abs(left - right) <= 2
    sample = next(robot.telemetry(hz=20, count=1))
    assert sample.base["x"] > 0
    assert sample.motors["left_wheel"] == pytest.approx(sample.motors["right_wheel"])
    assert sample.head == {}


def test_turning_spins_wheels_in_opposite_directions(robot) -> None:
    robot.move(yaw=1.0)
    sample = next(robot.telemetry(hz=20, count=1))
    assert sample.motors["left_wheel"] < 0 < sample.motors["right_wheel"]


def test_rover_refuses_what_it_does_not_have(robot) -> None:
    with pytest.raises(RobotError) as head:
        robot.look(yaw=0.1)
    assert head.value.code == "unsupported"
    with pytest.raises(RobotError) as lateral:
        robot.move(lateral=0.1)
    assert lateral.value.code == "out_of_range"
    with pytest.raises(RobotError) as servo:
        robot.servo("left_wheel")
    assert servo.value.code == "unsupported"
    # The duck-style `walk` falls back to move() and skips the head the rover lacks.
    robot.walk(forward=0.1, head_yaw=0.3)
    assert robot.safety == "running"


def test_individual_motor_and_range_sensor(robot) -> None:
    robot.motor("left_wheel").velocity(4.0)
    robot.motor("right_wheel").velocity(4.0)
    assert robot.safety == "running"
    assert robot.sensor("bump").read() == 0.0
    assert robot.sensor("range_front").read() == pytest.approx(2.0)
    with pytest.raises(RobotError) as fast:
        robot.motor("left_wheel").velocity(100.0)
    assert fast.value.code == "out_of_range"


def test_rover_body_bumps_into_the_wall() -> None:
    body = RoverBody()
    body.dispatch("motion.velocity", {"forward": MAX_FORWARD_M_S})
    for _ in range(int(WALL_X / MAX_FORWARD_M_S / 0.02) + 50):
        body.integrate(0.02)
    sample = body.snapshot()
    assert sample["sensors"]["bump"] == 1.0
    assert sample["sensors"]["range_front"] == pytest.approx(0.0)
    body.dispatch("motion.velocity", {"forward": -MAX_FORWARD_M_S})
    for _ in range(10):
        body.integrate(0.02)
    assert body.snapshot()["sensors"]["bump"] == 0.0
    body.reset()
    assert body.pose == (0.0, 0.0, 0.0)


def test_follow_decision_rescales_to_the_rover() -> None:
    decision = decide(observe(0, 0, math.pi / 2, 0, 300))
    assert decision.command == "FORWARD"
    limits = RoverBody().controls["motion.velocity"]
    fitted = fit_to_robot(decision, limits)
    assert fitted.forward == pytest.approx(decision.forward / 0.15 * limits["forward"][1])
    assert fitted.lateral == 0.0
    turn = fit_to_robot(decide(observe(0, 0, math.pi / 2, -87, 180)), limits)
    assert turn.yaw == pytest.approx(0.8 / 1.0 * limits["yaw"][1])
