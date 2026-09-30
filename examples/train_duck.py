"""Train the Open Duck Mini to follow a velocity command, with PPO.

    pip install -e ".[train]"
    python examples/train_duck.py --steps 2000000 --envs 8 --out runs/duck_ppo

Then watch it, or put it behind the protocol so the phone can drive it:

    mjpython -m jiadroid.rl --policy runs/duck_ppo.zip --view --clean
    python -m jiadroid.sim --robot duck-physics --policy runs/duck_ppo.zip --host 0.0.0.0

`--export-onnx` also writes `runs/duck_ppo.onnx`, which the simulator and
Open Duck Mini Runtime load the same way as a Playground policy (input
`obs`, shape [1, 101]).

A few million steps on a laptop gets a duck that stands and shuffles toward
the command. Walking well takes more: longer training, and the imitation
reward from Open Duck Playground's reference motions, which this repository
does not ship. The environment and reward scales live in
`jiadroid.rl.duck_env.DuckEnvConfig`.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np

import jiadroid.rl  # noqa: F401  registers the environment
from jiadroid.rl.duck_env import DuckEnvConfig, DuckJoystickEnv


def _has_tensorboard() -> bool:
    try:
        import tensorboard  # noqa: F401
    except ImportError:
        return False
    return True


def make_env(config: DuckEnvConfig):
    def factory():
        return DuckJoystickEnv(config)

    return factory


def train(args: argparse.Namespace) -> Path:
    from stable_baselines3 import PPO
    from stable_baselines3.common.callbacks import CheckpointCallback
    from stable_baselines3.common.vec_env import DummyVecEnv, SubprocVecEnv, VecMonitor, VecNormalize

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    config = DuckEnvConfig()
    factories = [make_env(config) for _ in range(args.envs)]
    vec = SubprocVecEnv(factories) if args.envs > 1 else DummyVecEnv(factories)
    vec = VecMonitor(vec)
    vec = VecNormalize(vec, norm_obs=False, norm_reward=True, clip_reward=10.0)

    if args.resume:
        model = PPO.load(args.resume, env=vec, device=args.device)
    else:
        model = PPO(
            "MlpPolicy",
            vec,
            n_steps=args.n_steps,
            batch_size=args.batch_size,
            n_epochs=5,
            learning_rate=3e-4,
            gamma=0.97,
            gae_lambda=0.95,
            clip_range=0.2,
            ent_coef=1e-3,
            policy_kwargs={"net_arch": {"pi": [512, 256, 128], "vf": [512, 256, 128]}},
            tensorboard_log=str(out.parent / "tensorboard") if _has_tensorboard() else None,
            device=args.device,
            verbose=1,
        )
    callback = CheckpointCallback(
        save_freq=max(1, args.checkpoint_every // args.envs),
        save_path=str(out.parent / "checkpoints"),
        name_prefix=out.name,
    )
    model.learn(total_timesteps=args.steps, callback=callback, progress_bar=False)
    saved = out.with_suffix(".zip")
    model.save(str(saved))
    vec.close()
    print(f"saved {saved}")
    if args.export_onnx:
        export_onnx(model, saved.with_suffix(".onnx"))
    return saved


def export_onnx(model, path: Path) -> None:
    """Write the deterministic actor as ONNX, input `obs` [1, 101] -> action [1, 14]."""
    import torch

    class Actor(torch.nn.Module):
        def __init__(self, policy):
            super().__init__()
            self.policy = policy

        def forward(self, obs):
            features = self.policy.extract_features(obs, self.policy.pi_features_extractor)
            latent = self.policy.mlp_extractor.forward_actor(features)
            return self.policy.action_net(latent)

    actor = Actor(model.policy).eval()
    dummy = torch.zeros(1, model.observation_space.shape[0], dtype=torch.float32)
    torch.onnx.export(
        actor,
        dummy,
        str(path),
        input_names=["obs"],
        output_names=["action"],
        opset_version=17,
        dynamo=False,
    )
    print(f"exported {path}")


def evaluate(args: argparse.Namespace) -> None:
    from jiadroid.sim.policy import load_policy

    policy = load_policy(args.eval)
    config = DuckEnvConfig().clean()
    env = DuckJoystickEnv(config)
    returns = []
    distances = []
    for episode in range(args.eval_episodes):
        obs, info = env.reset(seed=episode)
        total = 0.0
        while True:
            obs, reward, terminated, truncated, info = env.step(policy.act(obs))
            total += reward
            if terminated or truncated:
                break
        returns.append(total)
        distances.append(float(np.hypot(*info["base_position"][:2])))
        print(
            f"episode {episode + 1}: return {total:.1f}, {info['step']} steps, "
            f"{'fell' if terminated else 'stood'}, moved {distances[-1]:.2f} m"
        )
    print(f"mean return {np.mean(returns):.1f}, mean distance {np.mean(distances):.2f} m")
    env.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Train or evaluate a PPO walking policy for the Open Duck Mini")
    parser.add_argument("--steps", type=int, default=2_000_000, help="total environment steps")
    parser.add_argument("--envs", type=int, default=8, help="parallel environments")
    parser.add_argument("--n-steps", type=int, default=1024, help="rollout length per environment")
    parser.add_argument("--batch-size", type=int, default=2048)
    parser.add_argument("--out", default="runs/duck_ppo", help="where to save the policy (.zip is added)")
    parser.add_argument("--resume", help="continue from a saved .zip")
    parser.add_argument("--checkpoint-every", type=int, default=500_000)
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--export-onnx", action="store_true", help="also write an .onnx next to the .zip")
    parser.add_argument("--eval", help="skip training; evaluate this .zip or .onnx on a clean sim")
    parser.add_argument("--eval-episodes", type=int, default=5)
    args = parser.parse_args()
    if args.eval:
        evaluate(args)
    else:
        train(args)


if __name__ == "__main__":
    main()
