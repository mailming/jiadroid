"""Gymnasium joystick task for the Open Duck Mini v2.

The duck is told a velocity (forward, lateral, yaw) and a head pose, and is
rewarded for walking at that velocity without falling. This follows the
`Joystick` task in Open Duck Playground, itself after Berkeley Humanoid:

- 50 Hz control, 500 Hz physics.
- Action: 14 joint offsets in [-1, 1], scaled by 0.25 rad and added to the
  standing pose, then rate-limited to 5.24 rad/s like the real servos.
- Observation: the runtime's 101 values, so a policy trained here runs on
  the real duck with `Open_Duck_Mini_Runtime`, and vice versa.
- Reward: velocity tracking, plus costs on torque, action rate, standing
  still with a zero command, and leaving the standing pose. The upstream
  imitation reward needs reference motions this repository does not ship;
  `pose` and `feet_air_time` stand in for it.
- Randomisation: sensor noise, action and IMU delay, periodic pushes,
  floor friction, and the phone on the robot (which model, which mount,
  and a small shift from the mount point). Set `carry_phone=False` to
  train the bare duck. Set `noise_level=0` and `push=False` for a clean sim.

Requires the `rl` extra: `pip install -e ".[rl]"`.
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from typing import Any

import numpy as np

from jiadroid.protocol.phones import PHONES
from jiadroid.sim.duck import DUCK_MOUNTS
from jiadroid.sim.physics import (
    ACTION_SCALE,
    COMMAND_SIZE,
    CTRL_DT,
    GAIT_PERIOD_S,
    HEAD_SLICE,
    MAX_MOTOR_VELOCITY,
    NUM_JOINTS,
    OBS_SIZE,
    SIM_DT,
    ActionHistory,
    DuckPhysics,
    gait_phase,
    motor_targets_from_action,
    observation,
)

try:
    import gymnasium as gym
    from gymnasium import spaces
except ImportError as exc:  # pragma: no cover - depends on the install
    raise ImportError("the RL environment needs gymnasium: pip install -e '.[rl]'") from exc


Range = tuple[float, float]


@dataclass
class RewardScales:
    tracking_lin_vel: float = 2.5
    tracking_ang_vel: float = 6.0
    torques: float = -1.0e-3
    action_rate: float = -0.5
    stand_still: float = -0.2
    alive: float = 20.0
    pose: float = -0.5
    feet_air_time: float = 1.0


@dataclass
class NoiseScales:
    hip_pos: float = 0.03
    knee_pos: float = 0.05
    ankle_pos: float = 0.08
    joint_vel: float = 2.5
    gyro: float = 0.1
    accelerometer: float = 0.05


@dataclass
class DuckEnvConfig:
    episode_length: int = 1000
    ctrl_dt: float = CTRL_DT
    sim_dt: float = SIM_DT
    action_scale: float = ACTION_SCALE
    max_motor_velocity: float = MAX_MOTOR_VELOCITY
    gait_period_s: float = GAIT_PERIOD_S
    head_from_command: bool = True

    # Command sampling, the runtime's units and limits.
    lin_vel_x: Range = (-0.15, 0.15)
    lin_vel_y: Range = (-0.2, 0.2)
    ang_vel_yaw: Range = (-1.0, 1.0)
    neck_pitch: Range = (-0.34, 1.1)
    head_pitch: Range = (-0.78, 0.78)
    head_yaw: Range = (-1.5, 1.5)
    head_roll: Range = (-0.5, 0.5)
    zero_command_prob: float = 0.1
    resample_command_every: int = 500
    fixed_command: tuple[float, ...] | None = None

    # Randomisation.
    noise_level: float = 1.0
    noise: NoiseScales = field(default_factory=NoiseScales)
    action_delay_steps: int = 3
    imu_delay_steps: int = 3
    push: bool = True
    push_interval_s: Range = (5.0, 10.0)
    push_magnitude: Range = (0.1, 1.0)
    floor_friction: Range = (0.5, 0.8)
    # Open Duck Playground uses (0.5, 1.5); at that range the duck falls in
    # about a third of episodes before the policy acts, which a GPU budget of
    # hundreds of millions of steps absorbs and a laptop budget does not.
    init_joint_scale: Range = (0.8, 1.2)
    # The phone is part of the robot, not sensor noise, so `clean()` keeps it.
    # `carry_phone=False` is the bare duck the upstream policies were trained on.
    carry_phone: bool = True
    phone_offset: float = 0.02

    rewards: RewardScales = field(default_factory=RewardScales)
    tracking_sigma: float = 0.01
    reward_clip: Range = (0.0, 10000.0)
    feet_air_time_range: Range = (0.1, 0.5)

    def clean(self) -> "DuckEnvConfig":
        """No noise, delay, or pushes. For evaluation."""
        return replace(self, noise_level=0.0, action_delay_steps=0, imu_delay_steps=0, push=False)


def _uniform(rng: np.random.Generator, span: Range, size: int | None = None) -> np.ndarray | float:
    return rng.uniform(span[0], span[1], size)


class DuckJoystickEnv(gym.Env):
    metadata = {"render_modes": ["human", "rgb_array"], "render_fps": 50}

    def __init__(self, config: DuckEnvConfig | None = None, render_mode: str | None = None, **overrides: Any) -> None:
        super().__init__()
        self.config = replace(config or DuckEnvConfig(), **overrides) if overrides else (config or DuckEnvConfig())
        cfg = self.config
        self.physics = DuckPhysics(sim_dt=cfg.sim_dt, ctrl_dt=cfg.ctrl_dt)
        self.standing = self.physics.standing.copy()
        self.steps_per_period = max(1.0, cfg.gait_period_s / cfg.ctrl_dt)
        self.render_mode = render_mode
        self._viewer = None
        self._renderer = None

        self.observation_space = spaces.Box(-np.inf, np.inf, shape=(OBS_SIZE,), dtype=np.float32)
        self.action_space = spaces.Box(-1.0, 1.0, shape=(NUM_JOINTS,), dtype=np.float32)

        names = self.physics.joint_names
        self._pos_noise = np.zeros(NUM_JOINTS)
        for i, name in enumerate(names):
            if "_hip" in name:
                self._pos_noise[i] = cfg.noise.hip_pos
            elif "_knee" in name:
                self._pos_noise[i] = cfg.noise.knee_pos
            elif "_ankle" in name:
                self._pos_noise[i] = cfg.noise.ankle_pos
        # Standing-pose cost weights: upstream's for the legs, 1 for the head.
        self._pose_weights = np.ones(NUM_JOINTS)
        for i, name in enumerate(names):
            if name.endswith(("_hip_pitch", "_knee")):
                self._pose_weights[i] = 0.01

        self.command = np.zeros(COMMAND_SIZE)
        self._phone_mass = 0.0
        self._phone_mount = ""
        self.history = ActionHistory()
        self.motor_targets = self.standing.copy()
        self._action_buffer = np.zeros((max(1, cfg.action_delay_steps), NUM_JOINTS))
        self._imu_buffer = np.zeros((max(1, cfg.imu_delay_steps), 3))
        self._step = 0
        self._gait_step = 0.0
        self._push_step = 0
        self._push_interval_steps = 1
        self._feet_air_time = np.zeros(2)
        self._last_contact = np.zeros(2, dtype=bool)

    # ---- Gymnasium API -----------------------------------------------------------

    def reset(self, *, seed: int | None = None, options: dict[str, Any] | None = None):
        super().reset(seed=seed)
        cfg = self.config
        rng = self.np_random
        p = self.physics

        p.reset()
        self._place_phone(rng)
        if cfg.floor_friction is not None:
            p.set_floor_friction(float(_uniform(rng, cfg.floor_friction)))
        p.set_base(xy=_uniform(rng, (-0.05, 0.05), 2), yaw=float(_uniform(rng, (-np.pi, np.pi))))
        p.set_joint_positions(self.standing * _uniform(rng, cfg.init_joint_scale, NUM_JOINTS))
        p.set_base_velocity(_uniform(rng, (-0.05, 0.05), 6))
        p.forward()

        command = None if options is None else options.get("command")
        if command is not None:
            self.command = np.asarray(command, dtype=np.float64)
        elif cfg.fixed_command is not None:
            self.command = np.asarray(cfg.fixed_command, dtype=np.float64)
        else:
            self.command = self.sample_command()

        self.history.reset()
        self.motor_targets = self.standing.copy()
        self._action_buffer[:] = 0.0
        self._imu_buffer[:] = 0.0
        self._step = 0
        self._gait_step = 0.0
        self._push_step = 0
        self._push_interval_steps = max(1, int(round(float(_uniform(rng, cfg.push_interval_s)) / cfg.ctrl_dt)))
        self._feet_air_time[:] = 0.0
        self._last_contact[:] = False

        obs = self._observe(p.feet_contacts())
        return obs, self._info({}, obs)

    def step(self, action: np.ndarray):
        cfg = self.config
        rng = self.np_random
        p = self.physics
        action = np.clip(np.asarray(action, dtype=np.float64).reshape(-1), -1.0, 1.0)

        if np.linalg.norm(self.command[:3]) > 1e-6:
            self._gait_step += 1

        # Action delay: the servo may see an action from up to N steps ago.
        self._action_buffer = np.roll(self._action_buffer, 1, axis=0)
        self._action_buffer[0] = action
        delay = int(rng.integers(0, cfg.action_delay_steps + 1)) if cfg.action_delay_steps > 0 else 0
        applied_action = self._action_buffer[min(delay, len(self._action_buffer) - 1)]

        # Push the base every `push_interval` seconds.
        if cfg.push and (self._push_step + 1) % self._push_interval_steps == 0:
            theta = float(_uniform(rng, (0.0, 2 * np.pi)))
            magnitude = float(_uniform(rng, cfg.push_magnitude))
            p.push(np.cos(theta) * magnitude, np.sin(theta) * magnitude)
        self._push_step += 1

        targets = motor_targets_from_action(
            applied_action,
            self.standing,
            self.motor_targets,
            ctrl_dt=cfg.ctrl_dt,
            action_scale=cfg.action_scale,
            max_motor_velocity=cfg.max_motor_velocity,
        )
        self.motor_targets = targets
        applied = targets.copy()
        if cfg.head_from_command:
            applied[HEAD_SLICE] = targets[HEAD_SLICE] + self.command[3:7]
        applied = np.clip(applied, p.joint_lower, p.joint_upper)
        p.step(applied)

        contacts = p.feet_contacts()
        first_contact = (self._feet_air_time > 0.0) & (contacts | self._last_contact)
        self._feet_air_time += cfg.ctrl_dt

        obs = self._observe(contacts)
        terminated = self._fell()
        terms = self._reward_terms(action, first_contact)
        scaled = {name: value * getattr(cfg.rewards, name) for name, value in terms.items()}
        reward = float(np.clip(sum(scaled.values()) * cfg.ctrl_dt, *cfg.reward_clip))

        self.history.push(action)
        self._feet_air_time *= ~contacts
        self._last_contact = contacts
        self._step += 1
        truncated = self._step >= cfg.episode_length
        if cfg.fixed_command is None and cfg.resample_command_every and self._step % cfg.resample_command_every == 0:
            self.command = self.sample_command()

        if self.render_mode == "human":
            self.render()
        return obs, reward, bool(terminated), bool(truncated), self._info(scaled, obs)

    def render(self):
        if self.render_mode == "human":
            if self._viewer is None:
                import mujoco.viewer

                self._viewer = mujoco.viewer.launch_passive(self.physics.model, self.physics.data)
            self._viewer.sync()
            return None
        if self.render_mode == "rgb_array":
            import mujoco

            if self._renderer is None:
                self._renderer = mujoco.Renderer(self.physics.model, height=480, width=640)
                self._camera = mujoco.MjvCamera()
                self._camera.type = mujoco.mjtCamera.mjCAMERA_TRACKING
                self._camera.trackbodyid = self.physics.model.body("trunk_assembly").id
                self._camera.distance = 1.0
                self._camera.elevation = -20
                self._camera.azimuth = 160
            self._renderer.update_scene(self.physics.data, camera=self._camera)
            return self._renderer.render()
        return None

    def close(self) -> None:
        if self._viewer is not None:
            self._viewer.close()
            self._viewer = None
        if self._renderer is not None:
            self._renderer.close()
            self._renderer = None

    # ---- task ------------------------------------------------------------------

    def set_command(self, command: np.ndarray | list[float]) -> None:
        """Change the joystick command mid-episode (forward, lateral, yaw, neck_pitch, head_pitch, head_yaw, head_roll)."""
        self.command = np.asarray(command, dtype=np.float64)

    def sample_command(self) -> np.ndarray:
        cfg = self.config
        rng = self.np_random
        if rng.random() < cfg.zero_command_prob:
            return np.zeros(COMMAND_SIZE)
        return np.array(
            [
                _uniform(rng, cfg.lin_vel_x),
                _uniform(rng, cfg.lin_vel_y),
                _uniform(rng, cfg.ang_vel_yaw),
                _uniform(rng, cfg.neck_pitch),
                _uniform(rng, cfg.head_pitch),
                _uniform(rng, cfg.head_yaw),
                _uniform(rng, cfg.head_roll),
            ]
        )

    def _fell(self) -> bool:
        p = self.physics
        return (not p.is_upright()) or bool(np.isnan(p.data.qpos).any() or np.isnan(p.data.qvel).any())

    def _observe(self, contacts: np.ndarray) -> np.ndarray:
        cfg = self.config
        rng = self.np_random
        p = self.physics
        level = cfg.noise_level

        gyro = p.gyro() + self._noise(rng, 3, level * cfg.noise.gyro)
        accel = p.accelerometer() + self._noise(rng, 3, level * cfg.noise.accelerometer)
        # IMU delay, applied to the gyro like upstream applies it to gravity.
        self._imu_buffer = np.roll(self._imu_buffer, 1, axis=0)
        self._imu_buffer[0] = gyro
        delay = int(rng.integers(0, cfg.imu_delay_steps + 1)) if cfg.imu_delay_steps > 0 else 0
        gyro = self._imu_buffer[min(delay, len(self._imu_buffer) - 1)]

        joint_pos = p.joint_positions() + (2 * rng.random(NUM_JOINTS) - 1) * level * self._pos_noise
        joint_vel = p.joint_velocities() + self._noise(rng, NUM_JOINTS, level * cfg.noise.joint_vel)
        return observation(
            gyro,
            accel,
            self.command,
            joint_pos,
            joint_vel,
            self.history,
            self.motor_targets,
            contacts,
            gait_phase(self._gait_step, self.steps_per_period),
            self.standing,
        )

    @staticmethod
    def _noise(rng: np.random.Generator, size: int, scale: float) -> np.ndarray:
        if scale <= 0:
            return np.zeros(size)
        return (2 * rng.random(size) - 1) * scale

    def _reward_terms(self, action: np.ndarray, first_contact: np.ndarray) -> dict[str, float]:
        cfg = self.config
        p = self.physics
        cmd = self.command
        local_vel = p.local_linvel()
        gyro = p.gyro()
        qpos = p.joint_positions()
        qvel = p.joint_velocities()

        # Tracking, with the upstream's 0.1 m/s tolerance on lateral speed.
        error_x = (cmd[0] - local_vel[0]) ** 2
        error_y = max(abs(local_vel[1] - cmd[1]) - 0.1, 0.0) ** 2
        tracking_lin = float(np.exp(-(error_x + error_y) / cfg.tracking_sigma))
        tracking_ang = float(np.exp(-((cmd[2] - gyro[2]) ** 2) / cfg.tracking_sigma))

        moving = np.linalg.norm(cmd[:3]) >= 0.01
        stand_still = 0.0 if moving else float(np.sum(np.abs(qpos - self.standing)) + np.sum(np.abs(qvel)))

        target_pose = self.standing.copy()
        if cfg.head_from_command:
            target_pose[HEAD_SLICE] += cmd[3:7]
        pose = float(np.sum(((qpos - target_pose) ** 2) * self._pose_weights))

        low, high = cfg.feet_air_time_range
        air = np.clip((self._feet_air_time - low) * first_contact, None, high - low)
        feet_air_time = float(np.sum(air)) if moving else 0.0

        return {
            "tracking_lin_vel": tracking_lin,
            "tracking_ang_vel": tracking_ang,
            "torques": float(np.sum(p.actuator_force() ** 2)),
            "action_rate": float(np.sum((action - self.history.last) ** 2)),
            "stand_still": stand_still,
            "alive": 1.0,
            "pose": pose,
            "feet_air_time": feet_air_time,
        }

    def _info(self, rewards: dict[str, float], obs: np.ndarray) -> dict[str, Any]:
        p = self.physics
        return {
            "command": self.command.copy(),
            "rewards": rewards,
            "base_position": p.base_position(),
            "base_yaw": p.base_yaw(),
            "local_linvel": p.local_linvel(),
            "gyro": p.gyro(),
            "upright": p.is_upright(),
            "step": self._step,
            "phone_mass": self._phone_mass,
            "phone_mount": self._phone_mount,
        }

    def _place_phone(self, rng: np.random.Generator) -> None:
        cfg = self.config
        if not cfg.carry_phone:
            self.physics.clear_phone()
            self._phone_mass = 0.0
            self._phone_mount = ""
            return
        phone = PHONES[int(rng.integers(len(PHONES)))]
        mount = DUCK_MOUNTS[int(rng.integers(len(DUCK_MOUNTS)))]
        offset = rng.uniform(-cfg.phone_offset, cfg.phone_offset, 3)
        position = np.array(mount.position) + offset
        self.physics.set_phone(mount.id, position, np.array(phone.size_in_body(mount.id)), phone.mass)
        self._phone_mass = phone.mass
        self._phone_mount = mount.id
