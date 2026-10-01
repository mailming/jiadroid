# Training the duck to walk with reinforcement learning

How to teach the simulated Open Duck Mini to walk, and how to put that walk behind the phone.

The phone never learns to walk. It decides where to go: forward, turn, stop. Walking is a reflex that lives on the robot. This guide is about training that reflex.

## What learning means here

The duck is dropped into a physics simulator (MuJoCo) with a random walk command: some forward speed, some sideways speed, some turn rate. Fifty times a second it reads its sensors and picks an offset for each of its 14 servos. It gets points for moving at the commanded speed and staying upright, and loses points for jerky motion, high torque, and slouching away from its standing pose. If it falls, the episode ends.

Millions of these attempts, with the policy nudged toward the ones that scored well, produce a small neural network that walks. That network is the policy. Training happens on a computer. The finished policy runs on the robot, or in the simulator.

The environment in this repository matches the real duck's runtime and the upstream training project ([Open Duck Playground](https://github.com/apirrone/Open_Duck_Playground)) in every number that matters:

| | Value | Where |
| --- | --- | --- |
| Control rate | 50 Hz, 500 Hz physics | `jiadroid.sim.physics.CTRL_DT`, `SIM_DT` |
| Observation | 101 values: gyro, accelerometer, 7-number command, joint positions and velocities, last 3 actions, motor targets, foot contacts, gait phase | `jiadroid.sim.physics.observation` |
| Action | 14 joint offsets in [-1, 1], times 0.25 rad, added to the standing pose, rate-limited to 5.24 rad/s | `motor_targets_from_action` |
| Command | forward, lateral, yaw, neck pitch, head pitch, head yaw, head roll, in the runtime's units and limits | `jiadroid.sim.duck.COMMAND_LIMITS` |
| Robot model | Open Duck Mini v2 MJCF from Open Duck Playground | `src/jiadroid/sim/assets/open_duck_mini_v2` |

Because the numbers match, a policy trained here runs on a real duck through [Open Duck Mini Runtime](https://github.com/apirrone/Open_Duck_Mini_Runtime), and a policy trained upstream runs in this simulator.

## Install

Training needs Python 3.11 or 3.12 and about 3 GB of disk for PyTorch.

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[train]"
```

The extras, if you need less:

| Extra | Gives you |
| --- | --- |
| `sim` | MuJoCo duck in the simulator: `python -m jiadroid.sim --robot duck-physics` |
| `onnx` | Load an `.onnx` policy |
| `rl` | The Gymnasium environment: `import jiadroid.rl` |
| `train` | All of the above plus Stable-Baselines3, PyTorch, TensorBoard, and ONNX export |

## Check the environment

Before training, run a random rollout. The duck flails and falls; the point is that the environment steps and reports speed.

```bash
python -m jiadroid.rl
```

```text
observation (101,), action (14,), policy random
episode 1: 42 steps, return 16.8, fell, moved 0.17 m, command [-0.03  0.2   0.96], 5006 steps/s
```

To watch it, open the viewer. On macOS the viewer has to run under MuJoCo's launcher:

```bash
mjpython -m jiadroid.rl --view          # macOS
python -m jiadroid.rl --view            # Linux
```

## Train

```bash
python examples/train_duck.py --steps 2000000 --envs 8 --out runs/duck_ppo --export-onnx
```

This trains with PPO from Stable-Baselines3 across 8 parallel simulators and saves:

- `runs/duck_ppo.zip`: the Stable-Baselines3 policy, which can be resumed
- `runs/duck_ppo.onnx`: the same policy as ONNX, for the simulator and for the real duck
- `runs/checkpoints/`: a save every 500,000 steps
- `runs/tensorboard/`: training curves

Flags worth knowing:

| Flag | Default | Notes |
| --- | --- | --- |
| `--steps` | 2,000,000 | Total simulator steps. 2 million gives a duck that stands and shuffles toward the command. 20 to 50 million gives a real walk. |
| `--envs` | 8 | Parallel simulators. Set it to the number of CPU cores you have. |
| `--device` | `cpu` | The network is small; the CPU is fine and the simulator is the bottleneck. `cuda` or `mps` works but rarely helps. |
| `--resume runs/duck_ppo.zip` | | Continue training a saved policy. |
| `--checkpoint-every` | 500,000 | Steps between saves. |

Watch progress:

```bash
tensorboard --logdir runs/tensorboard
```

The number to watch is `rollout/ep_rew_mean`. It rises as the duck learns to stay up, then keeps rising as it learns to move. `rollout/ep_len_mean` reaching the episode length (1,000 steps, 20 seconds) means the duck stops falling.

### How long it takes

The simulator runs at roughly 3,000 to 5,000 steps per second per core on a recent laptop; PPO's own updates take some of that back. On a laptop with 8 cores:

| Steps | Time | What you get |
| --- | --- | --- |
| 2 million | About 10 minutes | Stands, leans toward the command, takes shuffling steps |
| 10 million | Under an hour | Walks slowly, turns, falls under strong pushes |
| 50 million | A few hours | A steady walk with pushes and noise turned on |

A desktop with more cores is a proportional speedup. The upstream project trains on a GPU with thousands of parallel simulators at once, and gets to 300 million steps in a few hours. That path is described at the end.

## Evaluate

Run the policy on a clean simulator, with noise, delays, and pushes turned off, and read the numbers:

```bash
python examples/train_duck.py --eval runs/duck_ppo.zip --eval-episodes 5
```

```text
episode 1: return 812.4, 1000 steps, stood, moved 1.85 m
...
mean return 790.1, mean distance 1.62 m
```

`stood` for every episode and a distance that grows with `--steps` means it is learning. Watch it walk:

```bash
mjpython -m jiadroid.rl --policy runs/duck_ppo.zip --view --clean --command 0.1 0 0   # macOS
python -m jiadroid.rl --policy runs/duck_ppo.zip --view --clean --command 0.1 0 0     # Linux
```

`--command FWD LAT YAW` fixes the walk command instead of sampling one, so you can ask for a straight walk, a sidestep, or a spin. Drop `--clean` to see how it copes with pushes and sensor noise.

## Put it behind the phone

The simulator can drive the physics duck with your policy. The phone connects to it exactly as it does to the kinematic duck. Its `motion.velocity` becomes the first three numbers of the policy's command and its `head.pose` the other four, the same seven numbers the real duck's runtime keeps in `RLWalk.last_commands` (see [protocol.md](protocol.md)).

```bash
python -m jiadroid.sim --robot duck-physics --policy runs/duck_ppo.onnx --host 0.0.0.0
```

Now Follow Me on the phone, or the laptop demo, moves a duck that is balancing on two simulated legs. Add `--view` (with `mjpython` on macOS) to watch.

Without `--policy` the physics duck stands still and only moves its head. That is what runs before anything is trained.

## Put it on the real duck

`runs/duck_ppo.onnx` has the same input (`obs`, shape `[1, 101]`) and output (14 actions) as the policies Open Duck Mini Runtime ships. Copy it to the Raspberry Pi on the duck and start the runtime with it:

```bash
python v2_rl_walk_mujoco.py --onnx_model_path duck_ppo.onnx
```

The runtime reads the same observation and applies the same action scaling this environment was trained with. Start with the duck held off the ground, and be ready to pause with the A button. A policy trained only on a flat floor with a few million steps will not walk as well on carpet as the upstream policy does.

## Tune the task

Everything about the task is in one dataclass, `DuckEnvConfig` in `src/jiadroid/rl/duck_env.py`. To change it, edit the defaults or pass a config to `DuckJoystickEnv`.

**Rewards** (`RewardScales`). Positive terms pay for tracking the commanded velocity, staying alive, and lifting the feet. Negative terms charge for torque, action rate, standing still with a nonzero command, and drifting from the standing pose. If the duck learns to stand and never walk, raise `tracking_lin_vel` or lower `pose`. If it learns to walk in a way that would shake the real servos apart, raise the size of `action_rate` and `torques`.

**Randomisation.** Sensor noise (`noise`), a random 0 to 3 step delay on actions and IMU readings, a random push every 5 to 10 seconds, random floor friction, and a scaled starting pose (`init_joint_scale`, each joint times 0.8 to 1.2; upstream uses 0.5 to 1.5, which knocks the duck over in a third of episodes before the policy acts and is only worth it with a GPU-sized budget). These are what make a simulated walk survive contact with a real floor. Turn them off with `config.clean()` for evaluation only. Training without them produces a policy that walks in the simulator and falls in the world.

**The phone.** `carry_phone` (on by default) puts a real phone on the duck at every reset: one of the models in `jiadroid.protocol.phones`, on the `head` or the `back` mount, shifted by up to `phone_offset` (2 cm) from the mount point. The mass is not in the observation. The policy feels it through the IMU and the way the body falls. `clean()` keeps the phone, because the phone is part of the robot. `carry_phone=False` is the bare duck that upstream policies were trained on.

**Commands.** `lin_vel_x`, `lin_vel_y`, `ang_vel_yaw`, and the head ranges control what the duck practices. A command is resampled every 500 steps, and 10% of commands are zero so the duck also learns to stand.

**Episode.** `episode_length` is 1,000 steps (20 seconds). The episode ends early when the duck is no longer upright.

The environment is a normal Gymnasium environment, so any library that speaks Gymnasium can train on it:

```python
import gymnasium as gym
import jiadroid.rl  # registers the environment

env = gym.make("Jiadroid/OpenDuckMini-v0")
obs, info = env.reset(seed=0)
obs, reward, terminated, truncated, info = env.step(env.action_space.sample())
```

`info["rewards"]` holds each scaled reward term for the step, which is the first thing to look at when a policy learns something strange.

## Going further: the upstream trainer

The best duck walks come from [Open Duck Playground](https://github.com/apirrone/Open_Duck_Playground). It trains the same robot on the same observation with Brax PPO on MuJoCo MJX, thousands of simulators at once on one GPU, and adds an imitation reward that pulls the gait toward a reference motion. That reward needs reference motions this repository does not ship.

Its policies load here without changes:

```bash
python -m jiadroid.sim --robot duck-physics --policy BEST_WALK_ONNX_2.onnx
python -m jiadroid.rl --policy BEST_WALK_ONNX_2.onnx --view --clean
```

A reasonable path is: learn the loop with `examples/train_duck.py` on a laptop, then, when you want the best walk for the real duck, train in Open Duck Playground on an NVIDIA GPU (a 12 GB card is enough; a 24 GB card such as an RTX 4090 is comfortable) and bring the `.onnx` back to this simulator and to the phone.

## Where the pieces are

| Path | What |
| --- | --- |
| `src/jiadroid/sim/physics.py` | MuJoCo wrapper, observation builder, action scaling |
| `src/jiadroid/sim/policy.py` | Loads `.onnx` and `.zip` policies; `WalkRunner` runs one in the simulator |
| `src/jiadroid/sim/duck_physics.py` | The physics duck behind the protocol |
| `src/jiadroid/rl/duck_env.py` | The Gymnasium task: commands, rewards, randomisation |
| `src/jiadroid/rl/__main__.py` | Roll out the environment, with or without a policy |
| `examples/train_duck.py` | Train with PPO, evaluate, export ONNX |
| `src/jiadroid/sim/assets/open_duck_mini_v2/` | Robot model, from Open Duck Playground |
