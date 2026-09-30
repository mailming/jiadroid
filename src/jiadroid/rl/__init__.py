"""Reinforcement learning on the simulated bodies.

    import gymnasium as gym
    import jiadroid.rl  # registers the environments

    env = gym.make("Jiadroid/OpenDuckMini-v0")

Needs the `rl` extra: `pip install -e ".[rl]"`. Training with
Stable-Baselines3 needs `.[train]`; see `examples/train_duck.py`.
"""

from __future__ import annotations

ENV_ID = "Jiadroid/OpenDuckMini-v0"

try:
    from gymnasium.envs.registration import register, registry
except ImportError:  # pragma: no cover - depends on the install
    register = None  # type: ignore[assignment]
    registry = {}  # type: ignore[assignment]

if register is not None and ENV_ID not in registry:
    register(
        id=ENV_ID,
        entry_point="jiadroid.rl.duck_env:DuckJoystickEnv",
        max_episode_steps=None,  # the env truncates itself at config.episode_length
    )

__all__ = ["ENV_ID"]
