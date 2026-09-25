"""Follow-me decisions for an Open Duck Mini.

Distances are in the demo's pixel world. Angle is radians, positive when the
person is to the duck's right. Commands use the duck's walk units: forward
and lateral in meters per second, yaw and head_yaw in radians per second and
radians. Positive yaw turns the duck counterclockwise.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

ALIGN_RAD = 0.28
CLOSE_PX = 90.0
SLOW_PX = 150.0
FAR_PX = 260.0
FOV_RAD = math.radians(70)
VIEW_RANGE_PX = 340.0
PIXELS_PER_METER = 900.0


@dataclass(frozen=True)
class Scene:
    visible: bool
    angle: float
    distance: float


@dataclass(frozen=True)
class FollowDecision:
    situation: str
    command: str
    forward: float
    lateral: float
    yaw: float
    head_yaw: float


@dataclass
class Pose:
    x: float
    y: float
    heading: float


def wrap(angle: float) -> float:
    return (angle + math.pi) % (2 * math.pi) - math.pi


def observe(
    robot_x: float,
    robot_y: float,
    heading: float,
    person_x: float,
    person_y: float,
) -> Scene:
    dx = person_x - robot_x
    dy = person_y - robot_y
    distance = math.hypot(dx, dy)
    if distance < 1e-6:
        return Scene(True, 0.0, 0.0)
    forward_x = math.cos(heading)
    forward_y = math.sin(heading)
    right_x = math.sin(heading)
    right_y = -math.cos(heading)
    ahead = dx * forward_x + dy * forward_y
    side = dx * right_x + dy * right_y
    angle = math.atan2(side, ahead)
    visible = ahead > 0 and abs(angle) <= FOV_RAD / 2 and distance <= VIEW_RANGE_PX
    return Scene(visible, angle, distance)


def decide(scene: Scene) -> FollowDecision:
    if not scene.visible:
        return FollowDecision("Person is lost", "STOP", 0.0, 0.0, 0.0, 0.0)
    if scene.distance < CLOSE_PX:
        return FollowDecision("Person is too close", "STOP", 0.0, 0.0, 0.0, 0.0)
    head_yaw = max(-0.5, min(0.5, scene.angle))
    if scene.angle < -ALIGN_RAD:
        return FollowDecision("Person is left", "TURN LEFT", 0.04, 0.0, 0.8, head_yaw)
    if scene.angle > ALIGN_RAD:
        return FollowDecision("Person is right", "TURN RIGHT", 0.04, 0.0, -0.8, head_yaw)
    if scene.distance > FAR_PX:
        return FollowDecision("Person is too far", "FORWARD", 0.12, 0.0, 0.0, head_yaw)
    if scene.distance < SLOW_PX:
        return FollowDecision("Person is centered", "SLOW DOWN", 0.05, 0.0, 0.0, head_yaw)
    return FollowDecision("Person is centered", "FORWARD", 0.08, 0.0, 0.0, head_yaw)


def step_pose(
    pose: Pose,
    forward: float,
    lateral: float,
    yaw: float,
    dt: float,
    pixels_per_meter: float = PIXELS_PER_METER,
) -> None:
    facing_x = math.cos(pose.heading)
    facing_y = math.sin(pose.heading)
    left_x = -math.sin(pose.heading)
    left_y = math.cos(pose.heading)
    pose.x += (forward * facing_x + lateral * left_x) * pixels_per_meter * dt
    pose.y += (forward * facing_y + lateral * left_y) * pixels_per_meter * dt
    pose.heading = wrap(pose.heading + yaw * dt)
