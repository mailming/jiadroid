"""Simulated robot bodies that speak the Jiadroid protocol.

- `DuckBody`: Open Duck Mini, kinematic, no dependencies.
- `RoverBody`: two-wheel rover, kinematic, no dependencies.
- `DuckPhysicsBody`: Open Duck Mini in MuJoCo (`pip install -e ".[sim]"`).

`SimulatorServer` hosts any of them over TCP.
"""

from jiadroid.sim.body import Body
from jiadroid.sim.controller import SimulatorServer, make_body
from jiadroid.sim.duck import DuckBody
from jiadroid.sim.rover import RoverBody

__all__ = ["Body", "DuckBody", "RoverBody", "SimulatorServer", "make_body"]
