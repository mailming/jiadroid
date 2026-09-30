"""Walking policies for the physics duck, and the loop that runs them.

A policy maps the 101-value observation to 14 joint actions in about
[-1, 1]. Three kinds are supported:

- `StandPolicy`: zero action. The duck stands and moves its head. This is
  what runs before anyone has trained anything.
- `OnnxPolicy`: an `.onnx` file, either exported from Open Duck Playground
  or from `examples/train_duck.py`. Needs `onnxruntime`.
- `SB3Policy`: a Stable-Baselines3 `.zip`. Needs `stable-baselines3`.

`WalkRunner` does what `RLWalk.run` does on the real duck: build the
observation, ask the policy, scale and rate-limit the targets, add the
head command on top of the head joints, and step the servos.
"""

from __future__ import annotations

from pathlib import Path
from typing import Protocol

import numpy as np

from jiadroid.sim.physics import (
    ACTION_SCALE,
    CTRL_DT,
    GAIT_PERIOD_S,
    HEAD_SLICE,
    MAX_MOTOR_VELOCITY,
    NUM_JOINTS,
    ActionHistory,
    DuckPhysics,
    gait_phase,
    motor_targets_from_action,
    observation,
)


class Policy(Protocol):
    def act(self, obs: np.ndarray) -> np.ndarray: ...


class StandPolicy:
    """No walking. Every joint holds the standing pose."""

    name = "stand"

    def act(self, obs: np.ndarray) -> np.ndarray:
        return np.zeros(NUM_JOINTS)


class OnnxPolicy:
    def __init__(self, path: str | Path, input_name: str | None = None) -> None:
        try:
            import onnxruntime
        except ImportError as exc:  # pragma: no cover - depends on the install
            raise ImportError("loading an .onnx policy needs onnxruntime: pip install -e '.[onnx]'") from exc
        self.name = Path(path).name
        self._session = onnxruntime.InferenceSession(str(path), providers=["CPUExecutionProvider"])
        inputs = self._session.get_inputs()
        self._input = input_name or inputs[0].name
        shape = inputs[0].shape
        # Playground exports take a batch: [1, obs]. Ours too.
        self._batched = len(shape) == 2

    def act(self, obs: np.ndarray) -> np.ndarray:
        feed = obs.astype(np.float32)
        if self._batched:
            feed = feed[None, :]
        output = self._session.run(None, {self._input: feed})[0]
        return np.asarray(output, dtype=np.float64).reshape(-1)[:NUM_JOINTS]


class SB3Policy:
    def __init__(self, path: str | Path, deterministic: bool = True) -> None:
        try:
            from stable_baselines3 import PPO
        except ImportError as exc:  # pragma: no cover - depends on the install
            raise ImportError("loading a .zip policy needs stable-baselines3: pip install -e '.[train]'") from exc
        self.name = Path(path).name
        self._model = PPO.load(str(path), device="cpu")
        self._deterministic = deterministic

    def act(self, obs: np.ndarray) -> np.ndarray:
        action, _state = self._model.predict(obs.astype(np.float32), deterministic=self._deterministic)
        return np.asarray(action, dtype=np.float64).reshape(-1)


def load_policy(path: str | Path | None) -> Policy:
    if path is None:
        return StandPolicy()
    suffix = Path(path).suffix.lower()
    if suffix == ".onnx":
        return OnnxPolicy(path)
    if suffix == ".zip":
        return SB3Policy(path)
    raise ValueError(f"don't know how to load a policy from {path!s}; expected .onnx or .zip")


class WalkRunner:
    """Drives `DuckPhysics` from a 7-number command with a policy, one control step at a time."""

    def __init__(
        self,
        physics: DuckPhysics,
        policy: Policy,
        action_scale: float = ACTION_SCALE,
        max_motor_velocity: float = MAX_MOTOR_VELOCITY,
        gait_period_s: float = GAIT_PERIOD_S,
        head_from_command: bool = True,
    ) -> None:
        self.physics = physics
        self.policy = policy
        self.action_scale = action_scale
        self.max_motor_velocity = max_motor_velocity
        self.steps_per_period = max(1.0, gait_period_s / physics.ctrl_dt)
        self.head_from_command = head_from_command
        self.history = ActionHistory()
        self.motor_targets = physics.standing.copy()
        self.step_index = 0.0
        self.last_action = np.zeros(NUM_JOINTS)

    def reset(self) -> None:
        self.physics.reset()
        self.history.reset()
        self.motor_targets = self.physics.standing.copy()
        self.step_index = 0.0
        self.last_action = np.zeros(NUM_JOINTS)

    def observe(self, command: np.ndarray) -> np.ndarray:
        p = self.physics
        return observation(
            p.gyro(),
            p.accelerometer(),
            np.asarray(command, dtype=np.float64),
            p.joint_positions(),
            p.joint_velocities(),
            self.history,
            self.motor_targets,
            p.feet_contacts(),
            gait_phase(self.step_index, self.steps_per_period),
            p.standing,
        )

    def step(self, command: np.ndarray, overrides: dict[int, float] | None = None) -> np.ndarray:
        """One 20 ms control step. Returns the joint targets that were applied.

        `overrides` pins joints (by index in `JOINT_NAMES`) to a position, on top
        of whatever the policy asked for. `servo.position` uses this.
        """
        command = np.asarray(command, dtype=np.float64)
        moving = np.linalg.norm(command[:3]) > 1e-6
        if moving:
            self.step_index += 1
        obs = self.observe(command)
        action = np.asarray(self.policy.act(obs), dtype=np.float64).reshape(-1)
        if action.shape != (NUM_JOINTS,):
            raise ValueError(f"policy returned {action.shape}, expected ({NUM_JOINTS},)")
        self.history.push(action)
        self.last_action = action
        targets = motor_targets_from_action(
            action,
            self.physics.standing,
            self.motor_targets,
            ctrl_dt=self.physics.ctrl_dt,
            action_scale=self.action_scale,
            max_motor_velocity=self.max_motor_velocity,
        )
        self.motor_targets = targets
        applied = targets.copy()
        if self.head_from_command:
            # The runtime adds the head command on top of what the policy asked for.
            applied[HEAD_SLICE] = command[3:7] + targets[HEAD_SLICE]
        if overrides:
            for index, position in overrides.items():
                applied[index] = position
        applied = np.clip(applied, self.physics.joint_lower, self.physics.joint_upper)
        self.physics.step(applied)
        return applied
