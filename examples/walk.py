"""Drive whatever robot is listening, using only what it announced.

Start a body first, any of:

    python -m jiadroid.sim
    python -m jiadroid.sim --robot rover
    python -m jiadroid.sim --robot duck-physics --policy runs/duck_ppo.zip
"""

import time

from jiadroid import connect


def main() -> None:
    with connect("tcp://127.0.0.1:8765") as robot:
        print(f"{robot.name} is a {robot.kind} with {len(robot.devices())} devices")
        for op, limits in robot.controls.items():
            print(f"  accepts {op} {limits}")

        limits = robot.controls["motion.velocity"]
        forward = limits["forward"][1] / 2
        yaw = limits["yaw"][1] / 3
        robot.move(forward=forward, yaw=yaw)
        if robot.supports("head.pose"):
            robot.look(yaw=0.2)
        time.sleep(0.5)

        sample = next(robot.telemetry(hz=10, count=1))
        print(f"  motion {sample.motion}")
        if sample.base:
            print(f"  base {sample.base}")
        for device in robot.devices():
            if device.type == "servo" and device.id in ("left_knee",):
                print(f"  {device.id}: {robot.servo(device.id).read():.3f} rad")
            elif device.type == "encoder":
                print(f"  {device.id}: {robot.encoder(device.id).read()} ticks")
            elif device.type == "sensor":
                print(f"  {device.id}: {robot.sensor(device.id).read():g} {device.capabilities[0]}")
        robot.stop()


if __name__ == "__main__":
    main()
