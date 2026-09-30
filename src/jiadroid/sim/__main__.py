"""Run a simulated robot body that phones can connect to.

    python -m jiadroid.sim                       # kinematic Open Duck Mini, no physics
    python -m jiadroid.sim --robot rover         # two-wheel rover
    python -m jiadroid.sim --robot duck-physics  # MuJoCo duck, stands until given a policy
    python -m jiadroid.sim --robot duck-physics --policy runs/duck_ppo.zip
    python -m jiadroid.sim --host 0.0.0.0        # let a phone on the same Wi-Fi connect

Add `--view` to open the MuJoCo viewer next to the physics duck. On macOS
the viewer must be started with MuJoCo's launcher:

    mjpython -m jiadroid.sim --robot duck-physics --view
"""

from __future__ import annotations

import argparse
import sys
import time

from jiadroid.protocol.messages import DEFAULT_PORT
from jiadroid.sim.controller import SimulatorServer, make_body
from jiadroid.sim.physics import GAIT_PERIOD_S


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Jiadroid robot simulator")
    parser.add_argument("--host", default="127.0.0.1", help="default 127.0.0.1; use 0.0.0.0 for a phone")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument(
        "--robot",
        default="duck",
        choices=("duck", "rover", "duck-physics"),
        help="which body to simulate (default duck)",
    )
    physics = parser.add_argument_group("duck-physics")
    physics.add_argument("--policy", help="walking policy: .onnx (Playground or ours) or .zip (Stable-Baselines3)")
    physics.add_argument(
        "--gait-period",
        type=float,
        default=GAIT_PERIOD_S,
        help=f"seconds per gait cycle for the phase clock the policy sees (default {GAIT_PERIOD_S})",
    )
    physics.add_argument("--no-auto-reset", action="store_true", help="leave the duck down when it falls")
    physics.add_argument("--view", action="store_true", help="open the MuJoCo viewer (macOS: run with mjpython)")
    args = parser.parse_args(argv)
    if not 0 <= args.port <= 65535:
        parser.error("port must be from 0 to 65535")

    options: dict[str, object] = {}
    if args.robot == "duck-physics":
        options = {
            "policy": args.policy,
            "gait_period_s": args.gait_period,
            "auto_reset": not args.no_auto_reset,
        }
    elif args.policy or args.view or args.no_auto_reset:
        parser.error("--policy, --view, and --no-auto-reset only apply to --robot duck-physics")

    body = make_body(args.robot, **options)
    server = SimulatorServer(args.host, args.port, body)
    try:
        server.start()
        server.announce()
        if args.robot == "duck-physics":
            print(f"walking policy: {body.policy_name}", flush=True)  # type: ignore[attr-defined]
        if args.view:
            _view(body)  # type: ignore[arg-type]
        else:
            server.wait()
    except KeyboardInterrupt:
        pass
    finally:
        server.close()


def _view(body) -> None:
    """Passive MuJoCo viewer on the main thread. The server ticks the body underneath."""
    try:
        import mujoco.viewer
    except ImportError:
        print("the viewer needs MuJoCo: pip install -e '.[sim]'", file=sys.stderr)
        return
    try:
        viewer = mujoco.viewer.launch_passive(body.physics.model, body.physics.data, show_left_ui=False, show_right_ui=False)
    except RuntimeError as exc:
        if sys.platform == "darwin":
            print(f"{exc}\n\nOn macOS run the viewer with: mjpython -m jiadroid.sim --robot duck-physics --view", file=sys.stderr)
            return
        raise
    with viewer:
        while viewer.is_running():
            with body.lock:
                viewer.sync()
            time.sleep(1 / 60)


if __name__ == "__main__":
    main()
