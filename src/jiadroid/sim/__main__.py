"""Run the reference robot on tcp://127.0.0.1:8765."""

from jiadroid.sim.controller import SimulatorServer


def main() -> None:
    server = SimulatorServer()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.close()


if __name__ == "__main__":
    main()
