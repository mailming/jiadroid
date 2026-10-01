"""The MuJoCo duck, alone and behind the protocol. Skipped without MuJoCo."""

import time

import pytest

mujoco = pytest.importorskip("mujoco")
np = pytest.importorskip("numpy")

from jiadroid import connect  # noqa: E402
from jiadroid.sim.controller import SimulatorServer  # noqa: E402
from jiadroid.sim.duck import JOINT_NAMES, STANDING  # noqa: E402
from jiadroid.sim.duck_physics import DuckPhysicsBody  # noqa: E402
from jiadroid.sim.physics import NUM_JOINTS, OBS_SIZE, DuckPhysics  # noqa: E402
from jiadroid.sim.policy import StandPolicy, WalkRunner, load_policy  # noqa: E402


@pytest.fixture(scope="module")
def physics():
    return DuckPhysics()


def test_model_matches_the_runtime(physics) -> None:
    assert physics.joint_names == JOINT_NAMES
    assert physics.model.nq == 21
    assert physics.model.nu == NUM_JOINTS
    assert physics.n_substeps == 10
    for i, name in enumerate(JOINT_NAMES):
        assert physics.standing[i] == pytest.approx(STANDING[name])


def test_duck_stands_for_five_seconds(physics) -> None:
    physics.reset()
    for _ in range(250):
        physics.step(physics.standing)
    assert physics.is_upright()
    assert 0.12 < physics.base_height() < 0.2
    assert physics.feet_contacts().all()
    assert np.abs(physics.joint_positions() - physics.standing).max() < 0.1
    assert np.linalg.norm(physics.base_position()[:2]) < 0.05


def test_duck_falls_when_legs_fold(physics) -> None:
    physics.reset()
    folded = physics.standing.copy()
    folded[3] = folded[12] = 1.5  # both knees
    folded[2] = -1.2
    folded[11] = 1.2
    for _ in range(300):
        physics.step(folded)
    assert physics.base_height() < 0.12


def test_walk_runner_observation_layout(physics) -> None:
    runner = WalkRunner(physics, StandPolicy())
    runner.reset()
    command = np.array([0.1, 0.0, 0.3, 0.0, 0.0, 0.2, 0.0])
    obs = runner.observe(command)
    assert obs.shape == (OBS_SIZE,)
    assert obs.dtype == np.float32
    assert obs[6:13] == pytest.approx(command)
    assert obs[3] == pytest.approx(physics.accelerometer()[0] + 1.3, abs=1e-5)
    assert obs[-2:] == pytest.approx([1.0, 0.0])  # phase starts at cos 0, sin 0
    applied = runner.step(command)
    # Head joints get the command on top of the standing pose.
    assert applied[7] == pytest.approx(0.2, abs=1e-6)
    assert obs[-2:] == pytest.approx([1.0, 0.0])
    assert runner.observe(command)[-2:] != pytest.approx([1.0, 0.0])


def test_load_policy_defaults_to_standing() -> None:
    assert isinstance(load_policy(None), StandPolicy)
    with pytest.raises(ValueError):
        load_policy("policy.txt")


@pytest.fixture
def server():
    sim = SimulatorServer("127.0.0.1", 0, DuckPhysicsBody())
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


def test_phone_mass_rides_on_the_link() -> None:
    physics = DuckPhysics()
    physics.set_phone("head", np.array([0.02, 0.0, 0.22]), np.array([0.0078, 0.0716, 0.1476]), 0.170)
    assert physics.phone_mass("head") == pytest.approx(0.170)
    assert physics.phone_mass("back") == pytest.approx(1e-4)
    assert physics.model.nq == 21
    head = physics.data.xpos[physics._phone_ids["head"]].copy()
    back = physics.data.xpos[physics._phone_ids["back"]].copy()
    physics.data.qpos[physics._qpos_adr[6]] = 0.5  # head_pitch
    physics.forward()
    assert np.linalg.norm(physics.data.xpos[physics._phone_ids["head"]] - head) > 0.01
    assert np.linalg.norm(physics.data.xpos[physics._phone_ids["back"]] - back) < 1e-6
    physics.data.qpos[physics._qpos_adr[6]] = 0.0
    physics.forward()
    for _ in range(100):
        physics.step(physics.standing)
    assert physics.is_upright()
    physics.clear_phone()
    assert physics.phone_mass("head") == pytest.approx(1e-4)


def test_physics_duck_behind_the_protocol(robot, server) -> None:
    assert robot.name == "Open Duck Mini"
    assert robot.kind == "biped"
    assert robot.supports("motion.velocity") and robot.supports("head.pose")
    assert [mount.id for mount in robot.mounts] == ["head", "back"]
    placed = robot.set_payload("head", 0.170, (0.0078, 0.0716, 0.1476), name="iPhone 16")
    assert placed["name"] == "iPhone 16"
    assert server.body.physics.phone_mass("head") == pytest.approx(0.170)
    servos = [d for d in robot.devices() if d.type == "servo"]
    assert [d.id for d in servos] == list(JOINT_NAMES)
    assert robot.sensor("upright").read() == 1.0
    robot.move(forward=0.1)
    robot.look(yaw=0.3)
    time.sleep(0.5)
    sample = next(robot.telemetry(hz=20, count=1))
    assert sample.motion["forward"] == pytest.approx(0.1)
    assert sample.sensors["upright"] == 1.0
    assert sample.base["z"] > 0.1
    # The stand policy holds the legs but the head follows the command.
    assert robot.servo("head_yaw").read() == pytest.approx(0.3, abs=0.08)
    assert abs(robot.servo("left_knee").read() - STANDING["left_knee"]) < 0.1
    robot.stop()
    robot.reset()
    assert robot.safety == "ready"
    assert server.body.falls == 0


def test_physics_duck_auto_resets_after_a_fall() -> None:
    body = DuckPhysicsBody(auto_reset=True)
    body.physics.set_base(yaw=0.0)
    # Flip it over.
    body.physics.data.qpos[3:7] = (0.0, 1.0, 0.0, 0.0)
    body.physics.data.qpos[2] = 0.3
    body.physics.forward()
    assert not body.physics.is_upright()
    body.dispatch("motion.velocity", {"forward": 0.1})
    for _ in range(80):  # 1.6 s
        body.integrate(0.02)
    assert body.falls == 1
    assert body.physics.is_upright()
    assert body.motion["forward"] == 0.0
    assert body.safety == "ready"
