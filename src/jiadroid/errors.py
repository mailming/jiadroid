"""Errors reported by a robot controller or the protocol codec."""


class RobotError(Exception):
    """A controller rejected a command, or the client already knows it cannot be sent."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


class ProtocolError(RobotError):
    """A frame or message broke the protocol rules."""

    def __init__(self, message: str) -> None:
        super().__init__("protocol", message)
