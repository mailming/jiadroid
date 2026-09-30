"""The Gymnasium environment. Skipped without MuJoCo and Gymnasium."""

import pytest

pytest.importorskip("mujoco")
np = pytest.importorskip("numpy")
gym = pytest.importorskip("gymnasium")

import jiadroid.rl  # noqa: E402, F401
from jiadroid.rl.duck_env import DuckEnvConfig, DuckJoystickEnv  # noqa: E402
from jiadroid.sim.physics import NUM_JOINTS, OBS_SIZE  # noqa: E402


def test_registered_and_passes_the_gymnasium_checker() -> None:
    from gymnasium.utils.env_checker import check_env

    env = gym.make(jiadroid.rl.ENV_ID)
    assert env.observation_space.shape == (OBS_SIZE,)
    assert env.action_space.shape == (NUM_JOINTS,)
    check_env(env.unwrapped, skip_render_check=True)
    env.close()


def test_zero_action_stands_with_zero_command() -> None:
    config = DuckEnvConfig(fixed_command=(0,) * 7).clean()
    env = DuckJoystickEnv(config)
    obs, info = env.reset(seed=1)
    assert obs.shape == (OBS_SIZE,)
    total = 0.0
    for step in range(300):
        obs, reward, terminated, truncated, info = env.step(np.zeros(NUM_JOINTS))
        total += reward
        assert not terminated, f"fell at step {step}"
    assert info["upright"]
    assert total > 100
    assert info["rewards"]["alive"] == pytest.approx(20.0)
    assert info["rewards"]["tracking_lin_vel"] > 2.0
    env.close()


def test_episode_truncates_and_terminates() -> None:
    env = DuckJoystickEnv(DuckEnvConfig(episode_length=20, fixed_command=(0,) * 7).clean())
    env.reset(seed=0)
    truncated = False
    for _ in range(20):
        _obs, _reward, terminated, truncated, _info = env.step(np.zeros(NUM_JOINTS))
    assert truncated and not terminated

    env.reset(seed=0)
    # Fling every joint to the limit: it falls within a couple of seconds.
    for _ in range(150):
        _obs, _reward, terminated, _truncated, _info = env.step(np.ones(NUM_JOINTS))
        if terminated:
            break
    assert terminated
    env.close()


def test_reset_is_seeded_and_commands_are_sampled() -> None:
    env = DuckJoystickEnv(DuckEnvConfig(zero_command_prob=0.0))
    obs_a, info_a = env.reset(seed=7)
    obs_b, info_b = env.reset(seed=7)
    assert obs_a == pytest.approx(obs_b)
    assert info_a["command"] == pytest.approx(info_b["command"])
    _obs, info_c = env.reset(seed=8)
    assert info_c["command"] != pytest.approx(info_a["command"])
    env.set_command([0.1, 0, 0, 0, 0, 0, 0])
    obs, *_ = env.step(np.zeros(NUM_JOINTS))
    assert obs[6] == pytest.approx(0.1)
    env.close()


def test_head_command_moves_the_head_joints() -> None:
    env = DuckJoystickEnv(DuckEnvConfig(fixed_command=(0, 0, 0, 0, 0, 0.4, 0)).clean())
    env.reset(seed=0)
    for _ in range(50):
        env.step(np.zeros(NUM_JOINTS))
    head_yaw = env.physics.joint_positions()[7]
    assert head_yaw == pytest.approx(0.4, abs=0.05)
    env.close()


def test_rgb_render() -> None:
    env = DuckJoystickEnv(DuckEnvConfig().clean(), render_mode="rgb_array")
    env.reset(seed=0)
    try:
        frame = env.render()
    except Exception as exc:  # pragma: no cover - no OpenGL on this machine
        pytest.skip(f"no offscreen renderer: {exc}")
    assert frame.shape == (480, 640, 3)
    env.close()
