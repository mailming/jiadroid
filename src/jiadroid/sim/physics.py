"""MuJoCo physics for the Open Duck Mini v2.

`DuckPhysics` wraps the vendored MJCF in `assets/open_duck_mini_v2` and
exposes what a walking policy needs: joint state, IMU, foot contacts,
the base pose, and a fixed-rate `step`. It mirrors what
`Open_Duck_Playground` and `Open_Duck_Mini_Runtime` read, so a policy
trained here, trained upstream, or run on the real duck sees the same
numbers.

`observation()` builds the 101-value policy input in the exact order the
runtime uses (`v2_rl_walk_mujoco.py`):

    gyro 3 | accelerometer 3 | command 7 | joint pos - standing 14 |
    joint vel * 0.05 14 | last action 14 | 2nd last 14 | 3rd last 14 |
    motor targets 14 | feet contacts 2 | gait phase (cos, sin) 2

Requires the `sim` extra: `pip install -e ".[sim]"`.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING

import numpy as np

from jiadroid.sim.duck import JOINT_NAMES

if TYPE_CHECKING:
    import mujoco

ASSETS_DIR = Path(__file__).with_name("assets") / "open_duck_mini_v2"
FLAT_TERRAIN_XML = ASSETS_DIR / "scene_flat_terrain.xml"

SIM_DT = 0.002
CTRL_DT = 0.02
ACTION_SCALE = 0.25
MAX_MOTOR_VELOCITY = 5.24  # rad/s, the runtime's clamp
DOF_VEL_SCALE = 0.05
# The runtime adds 1.3 m/s^2 to the accelerometer x axis before inference.
ACCELEROMETER_X_BIAS = 1.3
# One gait cycle in the reference motion is about 0.40 s: 20 control steps.
GAIT_PERIOD_S = 0.4
NUM_JOINTS = len(JOINT_NAMES)
COMMAND_SIZE = 7
OBS_SIZE = 3 + 3 + COMMAND_SIZE + NUM_JOINTS * 6 + 2 + 2
HEAD_SLICE = slice(5, 9)  # neck_pitch, head_pitch, head_yaw, head_roll in JOINT_NAMES

ROOT_BODY = "trunk_assembly"
IMU_SITE = "imu"
FEET_SITES = ("left_foot", "right_foot")
FEET_GEOMS = ("left_foot_bottom_tpu", "right_foot_bottom_tpu")
FLOOR_GEOM = "floor"
# Mount id -> body fixed to that link. A phone there moves with the link.
PHONE_BODIES = {"head": "phone_head", "back": "phone_back"}
_EMPTY_PHONE_MASS = 1e-4


def _require_mujoco() -> "mujoco":
    try:
        import mujoco
    except ImportError as exc:  # pragma: no cover - depends on the install
        raise ImportError(
            "the physics simulator needs MuJoCo; install it with: pip install -e '.[sim]'"
        ) from exc
    return mujoco


@dataclass
class ActionHistory:
    """What the policy saw last, carried between control steps."""

    last: np.ndarray = field(default_factory=lambda: np.zeros(NUM_JOINTS))
    second: np.ndarray = field(default_factory=lambda: np.zeros(NUM_JOINTS))
    third: np.ndarray = field(default_factory=lambda: np.zeros(NUM_JOINTS))

    def push(self, action: np.ndarray) -> None:
        self.third = self.second
        self.second = self.last
        self.last = np.asarray(action, dtype=np.float64).copy()

    def reset(self) -> None:
        self.last = np.zeros(NUM_JOINTS)
        self.second = np.zeros(NUM_JOINTS)
        self.third = np.zeros(NUM_JOINTS)


def observation(
    gyro: np.ndarray,
    accelerometer: np.ndarray,
    command: np.ndarray,
    joint_positions: np.ndarray,
    joint_velocities: np.ndarray,
    history: ActionHistory,
    motor_targets: np.ndarray,
    contacts: np.ndarray,
    phase: np.ndarray,
    standing: np.ndarray,
) -> np.ndarray:
    accel = np.asarray(accelerometer, dtype=np.float64).copy()
    accel[0] += ACCELEROMETER_X_BIAS
    obs = np.concatenate(
        [
            gyro,
            accel,
            command,
            joint_positions - standing,
            joint_velocities * DOF_VEL_SCALE,
            history.last,
            history.second,
            history.third,
            motor_targets,
            contacts.astype(np.float64),
            phase,
        ]
    ).astype(np.float32)
    assert obs.shape == (OBS_SIZE,), obs.shape
    return obs


def gait_phase(step_index: float, steps_per_period: float) -> np.ndarray:
    angle = (step_index % steps_per_period) / steps_per_period * 2 * np.pi
    return np.array([np.cos(angle), np.sin(angle)])


def motor_targets_from_action(
    action: np.ndarray,
    standing: np.ndarray,
    previous_targets: np.ndarray,
    ctrl_dt: float = CTRL_DT,
    action_scale: float = ACTION_SCALE,
    max_motor_velocity: float = MAX_MOTOR_VELOCITY,
) -> np.ndarray:
    """Runtime rule: standing pose + scaled action, moving no faster than the servo can."""
    targets = standing + np.asarray(action, dtype=np.float64) * action_scale
    limit = max_motor_velocity * ctrl_dt
    return np.clip(targets, previous_targets - limit, previous_targets + limit)


class DuckPhysics:
    def __init__(
        self,
        xml_path: str | Path = FLAT_TERRAIN_XML,
        sim_dt: float = SIM_DT,
        ctrl_dt: float = CTRL_DT,
    ) -> None:
        mujoco = _require_mujoco()
        self._mujoco = mujoco
        self.model = mujoco.MjModel.from_xml_path(str(xml_path))
        self.model.opt.timestep = sim_dt
        self.data = mujoco.MjData(self.model)
        self.sim_dt = sim_dt
        self.ctrl_dt = ctrl_dt
        self.n_substeps = max(1, int(round(ctrl_dt / sim_dt)))

        actuators = [self.model.actuator(i).name for i in range(self.model.nu)]
        if tuple(actuators) != JOINT_NAMES:
            raise RuntimeError(f"actuator order {actuators} differs from the runtime's joint order")
        self.joint_names = JOINT_NAMES
        joint_ids = [self.model.joint(name).id for name in JOINT_NAMES]
        self._qpos_adr = np.array([self.model.jnt_qposadr[j] for j in joint_ids])
        self._qvel_adr = np.array([self.model.jnt_dofadr[j] for j in joint_ids])
        free_joint = int(np.where(self.model.jnt_type == mujoco.mjtJoint.mjJNT_FREE)[0][0])
        self._base_qpos = int(self.model.jnt_qposadr[free_joint])
        self._base_qvel = int(self.model.jnt_dofadr[free_joint])

        home = self.model.keyframe("home")
        self.home_qpos = home.qpos.copy()
        self.standing = home.ctrl.copy()
        self.joint_lower, self.joint_upper = self.model.jnt_range[joint_ids].T.copy()

        self._sensor = {
            name: self._sensor_slice(name)
            for name in ("gyro", "accelerometer", "upvector", "local_linvel", "global_linvel", "global_angvel")
        }
        self._imu_site = self.model.site(IMU_SITE).id
        self._floor_geom = self.model.geom(FLOOR_GEOM).id
        self._feet_geoms = np.array([self.model.geom(name).id for name in FEET_GEOMS])
        self._feet_sites = np.array([self.model.site(name).id for name in FEET_SITES])
        self._root_body = self.model.body(ROOT_BODY).id
        self._phone_ids = {name: int(self.model.body(body).id) for name, body in PHONE_BODIES.items()}
        self._phone_geoms = {name: int(self.model.geom(body).id) for name, body in PHONE_BODIES.items()}
        self.reset()
        trunk = self._root_body
        self._trunk_home_pos = self.data.xpos[trunk].copy()
        self._trunk_home_rot = self.data.xmat[trunk].reshape(3, 3).copy()
        self._phone_parent_home: dict[str, tuple[np.ndarray, np.ndarray]] = {}
        for name, body_id in self._phone_ids.items():
            parent = int(self.model.body_parentid[body_id])
            self._phone_parent_home[name] = (
                self.data.xpos[parent].copy(),
                self.data.xmat[parent].reshape(3, 3).copy(),
            )

    # ---- state ------------------------------------------------------------------

    def reset(self, qpos: np.ndarray | None = None, qvel: np.ndarray | None = None) -> None:
        # Clears solver warmstart and other carried-over state, so two resets
        # to the same pose behave identically.
        self._mujoco.mj_resetData(self.model, self.data)
        self.data.qpos[:] = self.home_qpos if qpos is None else qpos
        self.data.qvel[:] = 0.0 if qvel is None else qvel
        self.data.ctrl[:] = self.standing
        self.data.act[:] = 0.0
        self.data.time = 0.0
        self._mujoco.mj_forward(self.model, self.data)

    def step(self, ctrl: np.ndarray) -> None:
        """Hold `ctrl` (joint targets, radians) for one control period."""
        self.data.ctrl[:] = ctrl
        self._mujoco.mj_step(self.model, self.data, nstep=self.n_substeps)

    def forward(self) -> None:
        self._mujoco.mj_forward(self.model, self.data)

    # ---- readings ---------------------------------------------------------------

    def joint_positions(self) -> np.ndarray:
        return self.data.qpos[self._qpos_adr].copy()

    def joint_velocities(self) -> np.ndarray:
        return self.data.qvel[self._qvel_adr].copy()

    def actuator_force(self) -> np.ndarray:
        return self.data.actuator_force.copy()

    def gyro(self) -> np.ndarray:
        return self.data.sensordata[self._sensor["gyro"]].copy()

    def accelerometer(self) -> np.ndarray:
        return self.data.sensordata[self._sensor["accelerometer"]].copy()

    def up_vector(self) -> np.ndarray:
        """World z axis of the IMU. Its z component goes negative when the duck is upside down."""
        return self.data.sensordata[self._sensor["upvector"]].copy()

    def gravity_in_body(self) -> np.ndarray:
        return self.data.site_xmat[self._imu_site].reshape(3, 3).T @ np.array([0.0, 0.0, -1.0])

    def local_linvel(self) -> np.ndarray:
        return self.data.sensordata[self._sensor["local_linvel"]].copy()

    def global_linvel(self) -> np.ndarray:
        return self.data.sensordata[self._sensor["global_linvel"]].copy()

    def global_angvel(self) -> np.ndarray:
        return self.data.sensordata[self._sensor["global_angvel"]].copy()

    def base_position(self) -> np.ndarray:
        return self.data.qpos[self._base_qpos : self._base_qpos + 3].copy()

    def base_quaternion(self) -> np.ndarray:
        """w, x, y, z"""
        return self.data.qpos[self._base_qpos + 3 : self._base_qpos + 7].copy()

    def base_yaw(self) -> float:
        w, x, y, z = self.base_quaternion()
        return float(np.arctan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z)))

    def base_height(self) -> float:
        return float(self.data.qpos[self._base_qpos + 2])

    def is_upright(self) -> bool:
        return bool(self.up_vector()[2] > 0.0)

    def feet_contacts(self) -> np.ndarray:
        touching = np.zeros(2, dtype=bool)
        for i in range(self.data.ncon):
            contact = self.data.contact[i]
            pair = (contact.geom1, contact.geom2)
            if self._floor_geom not in pair:
                continue
            other = pair[1] if pair[0] == self._floor_geom else pair[0]
            for foot, geom in enumerate(self._feet_geoms):
                if other == geom:
                    touching[foot] = True
        return touching

    def feet_positions(self) -> np.ndarray:
        return self.data.site_xpos[self._feet_sites].copy()

    # ---- disturbances -----------------------------------------------------------

    def push(self, vx: float, vy: float) -> None:
        self.data.qvel[self._base_qvel : self._base_qvel + 2] += (vx, vy)

    def set_floor_friction(self, mu: float) -> None:
        self.model.geom_friction[self._floor_geom, 0] = mu

    def set_base(self, xy: np.ndarray | None = None, yaw: float | None = None) -> None:
        if xy is not None:
            self.data.qpos[self._base_qpos : self._base_qpos + 2] = xy
        if yaw is not None:
            half = yaw / 2
            self.data.qpos[self._base_qpos + 3 : self._base_qpos + 7] = (np.cos(half), 0.0, 0.0, np.sin(half))
        self._mujoco.mj_forward(self.model, self.data)

    def set_joint_positions(self, positions: np.ndarray) -> None:
        self.data.qpos[self._qpos_adr] = positions
        self._mujoco.mj_forward(self.model, self.data)

    def set_base_velocity(self, qvel6: np.ndarray) -> None:
        self.data.qvel[self._base_qvel : self._base_qvel + 6] = qvel6

    def set_phone(self, mount: str, position: np.ndarray, size: np.ndarray, mass: float) -> None:
        """Hang a box on `mount`. `position` is its center in the trunk frame at the standing pose.

        The box is a child of that mount's link, so a head phone pitches with the head.
        `size` is the full extents in meters, x forward, y left, z up.
        """
        self.clear_phone()
        body_id = self._phone_ids[mount]
        parent_pos, parent_rot = self._phone_parent_home[mount]
        world = self._trunk_home_pos + self._trunk_home_rot @ np.asarray(position, dtype=np.float64)
        local = parent_rot.T @ (world - parent_pos)
        self.model.body_pos[body_id] = local
        self.model.body_mass[body_id] = mass
        half = np.asarray(size, dtype=np.float64) / 2
        # Diagonal inertia of a solid box about its center, in the body frame.
        self.model.body_inertia[body_id] = mass * np.array(
            [
                (half[1] ** 2 + half[2] ** 2) / 3,
                (half[0] ** 2 + half[2] ** 2) / 3,
                (half[0] ** 2 + half[1] ** 2) / 3,
            ]
        )
        geom = self._phone_geoms[mount]
        self.model.geom_size[geom] = half
        self.model.geom_rgba[geom, 3] = 0.9
        self._recompute_constants()

    def clear_phone(self) -> None:
        for body_id in self._phone_ids.values():
            self.model.body_mass[body_id] = _EMPTY_PHONE_MASS
            self.model.body_inertia[body_id] = 1e-8
        for geom in self._phone_geoms.values():
            self.model.geom_size[geom] = 0.001
            self.model.geom_rgba[geom, 3] = 0.0
        self._recompute_constants()

    def _recompute_constants(self) -> None:
        # mj_setConst writes qpos0 into qpos. Keep the pose the duck already has.
        qpos = self.data.qpos.copy()
        qvel = self.data.qvel.copy()
        act = self.data.act.copy()
        ctrl = self.data.ctrl.copy()
        time = float(self.data.time)
        self._mujoco.mj_setConst(self.model, self.data)
        self.data.qpos[:] = qpos
        self.data.qvel[:] = qvel
        self.data.act[:] = act
        self.data.ctrl[:] = ctrl
        self.data.time = time
        self._mujoco.mj_forward(self.model, self.data)

    def phone_mass(self, mount: str) -> float:
        return float(self.model.body_mass[self._phone_ids[mount]])

    # ---- helpers ----------------------------------------------------------------

    def _sensor_slice(self, name: str) -> slice:
        sensor = self.model.sensor(name)
        start = int(sensor.adr[0])
        return slice(start, start + int(sensor.dim[0]))
