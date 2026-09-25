"""Phone-to-robot protocol client."""

from jiadroid.client.robot import connect
from jiadroid.errors import RobotError

__all__ = ["connect", "RobotError"]
