#pragma once

#include <Stream.h>
#include <stddef.h>

void protocolResetSession();
bool protocolSendHello(Stream &peer);
// false means the peer asked to close (session.bye) or the link must drop.
bool protocolHandleLine(Stream &peer, const char *line);
bool protocolPollTelemetry(Stream &peer, unsigned long nowMs);
void protocolOnDisconnect();

// After firmware.begin, main feeds raw sketch bytes here until finished.
bool protocolOtaActive();
// Consume raw OTA bytes. false = link must drop. *restart set when reboot needed.
bool protocolOtaConsume(Stream &peer, const uint8_t *data, size_t len, bool *restart);

// Debug lines go here. Use UART0 (COM port) when USB CDC carries the protocol.
void protocolSetLogStream(Stream *stream);
// Also print cmd logs on USB Serial (the usual monitor port) when Wi-Fi is the phone link.
void protocolSetUsbLogMirror(bool enabled);
