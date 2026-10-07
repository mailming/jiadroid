#pragma once

#include <WiFi.h>

void protocolResetSession();
bool protocolSendHello(WiFiClient &client);
// false means the peer violated the framing rules, or the link should close.
bool protocolHandleLine(WiFiClient &client, const char *line);
bool protocolPollTelemetry(WiFiClient &client, unsigned long nowMs);
void protocolOnDisconnect();
