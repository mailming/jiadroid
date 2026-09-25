import math

from jiadroid.follow import Pose, decide, observe, step_pose


def test_person_on_the_left_turns_left() -> None:
    scene = observe(0, 0, math.pi / 2, -87, 180)
    assert scene.visible
    decision = decide(scene)
    assert decision.situation == "Person is left"
    assert decision.command == "TURN LEFT"
    assert decision.yaw > 0
    assert decision.head_yaw < 0


def test_person_on_the_right_turns_right() -> None:
    scene = observe(0, 0, math.pi / 2, 87, 180)
    assert scene.visible
    decision = decide(scene)
    assert decision.situation == "Person is right"
    assert decision.command == "TURN RIGHT"
    assert decision.yaw < 0
    assert decision.head_yaw > 0


def test_person_ahead_and_far_walks_forward() -> None:
    scene = observe(0, 0, math.pi / 2, 0, 300)
    assert scene.visible
    decision = decide(scene)
    assert decision.situation == "Person is too far"
    assert decision.command == "FORWARD"
    assert decision.forward > 0
    assert decision.yaw == 0


def test_centered_and_near_slows_down() -> None:
    decision = decide(observe(0, 0, math.pi / 2, 0, 120))
    assert decision.situation == "Person is centered"
    assert decision.command == "SLOW DOWN"
    assert 0 < decision.forward < 0.12


def test_too_close_stops() -> None:
    decision = decide(observe(0, 0, math.pi / 2, 0, 40))
    assert decision.situation == "Person is too close"
    assert decision.command == "STOP"
    assert decision.forward == decision.yaw == decision.head_yaw == 0


def test_person_behind_is_lost_and_stops() -> None:
    scene = observe(0, 0, math.pi / 2, 0, -120)
    assert not scene.visible
    decision = decide(scene)
    assert decision.situation == "Person is lost"
    assert decision.command == "STOP"


def test_duck_approaches_person_ahead() -> None:
    pose = Pose(0, 0, math.pi / 2)
    person = (0.0, 300.0)
    start = math.hypot(person[0] - pose.x, person[1] - pose.y)
    for _ in range(80):
        decision = decide(observe(pose.x, pose.y, pose.heading, person[0], person[1]))
        step_pose(pose, decision.forward, decision.lateral, decision.yaw, 0.05)
    end = math.hypot(person[0] - pose.x, person[1] - pose.y)
    assert end < start * 0.5


def test_duck_closes_distance_when_person_is_left() -> None:
    pose = Pose(0, 0, math.pi / 2)
    person = (-87.0, 180.0)
    start = math.hypot(person[0] - pose.x, person[1] - pose.y)
    for _ in range(100):
        decision = decide(observe(pose.x, pose.y, pose.heading, person[0], person[1]))
        step_pose(pose, decision.forward, decision.lateral, decision.yaw, 0.05)
    end = math.hypot(person[0] - pose.x, person[1] - pose.y)
    assert end < start
