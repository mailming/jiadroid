"""Check the RL environment: a random rollout with timing.

    python -m jiadroid.rl
    python -m jiadroid.rl --policy runs/duck_ppo.zip --episodes 3
    mjpython -m jiadroid.rl --policy runs/duck_ppo.zip --view   # macOS viewer
"""

from __future__ import annotations

import argparse
import time

import numpy as np

import jiadroid.rl  # noqa: F401  registers the environment
from jiadroid.rl.duck_env import DuckEnvConfig, DuckJoystickEnv
from jiadroid.sim.policy import load_policy


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Roll out the Open Duck Mini environment")
    parser.add_argument("--policy", help=".onnx or .zip; random actions when omitted")
    parser.add_argument("--episodes", type=int, default=1)
    parser.add_argument("--steps", type=int, default=1000, help="max control steps per episode")
    parser.add_argument("--clean", action="store_true", help="no noise, delays, or pushes")
    parser.add_argument("--command", type=float, nargs=3, metavar=("FWD", "LAT", "YAW"), help="fixed velocity command")
    parser.add_argument("--view", action="store_true", help="MuJoCo viewer (macOS: run with mjpython)")
    parser.add_argument("--seed", type=int, default=0)
    args = parser.parse_args(argv)

    config = DuckEnvConfig(episode_length=args.steps)
    if args.clean:
        config = config.clean()
    if args.command is not None:
        config.fixed_command = (*args.command, 0.0, 0.0, 0.0, 0.0)
    env = DuckJoystickEnv(config, render_mode="human" if args.view else None)
    policy = load_policy(args.policy) if args.policy else None
    print(f"observation {env.observation_space.shape}, action {env.action_space.shape}, policy {args.policy or 'random'}")

    for episode in range(args.episodes):
        obs, info = env.reset(seed=args.seed + episode)
        total = 0.0
        steps = 0
        start = time.perf_counter()
        while True:
            if policy is None:
                action = env.action_space.sample() * 0.3
            else:
                action = policy.act(obs)
            obs, reward, terminated, truncated, info = env.step(action)
            total += reward
            steps += 1
            if args.view:
                time.sleep(env.config.ctrl_dt)
            if terminated or truncated:
                break
        elapsed = time.perf_counter() - start
        x, y, _z = info["base_position"]
        print(
            f"episode {episode + 1}: {steps} steps, return {total:.1f}, "
            f"{'fell' if terminated else 'stood'}, moved {np.hypot(x, y):.2f} m, "
            f"command {np.round(info['command'][:3], 2)}, {steps / elapsed:.0f} steps/s"
        )
    env.close()


if __name__ == "__main__":
    main()
