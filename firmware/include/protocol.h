#pragma once

#include <Stream.h>

void protocolResetSession();
bool protocolSendHello(Stream &peer);
// false means the peer violated the framing rules, or the link should close.
bool protocolHandleLine(Stream &peer, const char *line);
bool protocolPollTelemetry(Stream &peer, unsigned long nowMs);
void protocolOnDisconnect();

// Debug lines go here. Use UART0 (COM port) when USB CDC carries the protocol.
void protocolSetLogStream(Stream *stream);
