"""Send a short walk command to an Open Duck Mini simulator.

Start it first:

    python -m jiadroid.sim
"""

import time

from jiadroid import connect


def main() -> None:
    with connect("tcp://127.0.0.1:8765") as robot:
        servos = [device.id for device in robot.devices() if device.type == "servo"]
        print(f"{robot.name}: {len(servos)} servos")
        robot.walk(forward=0.1, yaw=0.3, head_yaw=0.2)
        time.sleep(0.3)
        print(f"left knee: {robot.servo('left_knee').read():.3f} rad")
        robot.stop()


if __name__ == "__main__":
    main()
