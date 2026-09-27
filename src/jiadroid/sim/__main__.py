"""Run the reference robot.

    python -m jiadroid.sim
    python -m jiadroid.sim --host 0.0.0.0
"""

import argparse

from jiadroid.protocol.messages import DEFAULT_PORT
from jiadroid.sim.controller import SimulatorServer


def main() -> None:
    parser = argparse.ArgumentParser(description="Open Duck Mini reference simulator")
    parser.add_argument("--host", default="127.0.0.1", help="default 127.0.0.1; use 0.0.0.0 for a phone")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    args = parser.parse_args()
    if not 0 <= args.port <= 65535:
        parser.error("port must be from 0 to 65535")
    server = SimulatorServer(args.host, args.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.close()


if __name__ == "__main__":
    main()
