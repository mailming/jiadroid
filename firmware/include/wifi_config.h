#pragma once

#include <ArduinoJson.h>

// SSID/password live in NVS after the phone sets them. Compile-time values in
// config.h are only a first-boot fallback when NVS is empty.
void wifiConfigBegin();
bool wifiConfigConfigured();
const char *wifiConfigSsid();
const char *wifiConfigPassword();
bool wifiConfigSet(const char *ssid, const char *password);
void wifiConfigClear();
void wifiConfigFillStatus(JsonObject body);
// True after wifi.set / wifi.clear until main applies the change.
bool wifiConfigTakePendingRestart();
