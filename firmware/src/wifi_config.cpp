#include "wifi_config.h"

#include <Preferences.h>
#include <WiFi.h>
#include <string.h>

#include "config.h"

static Preferences prefs;
static char ssidBuf[33] = "";
static char passBuf[65] = "";
static bool pendingRestart = false;

static bool compileTimeConfigured() {
    return WIFI_SSID[0] != '\0' && strcmp(WIFI_SSID, "your-network") != 0;
}

void wifiConfigBegin() {
    prefs.begin("jiadroid", false);
    if (prefs.isKey("ssid")) {
        prefs.getString("ssid", ssidBuf, sizeof(ssidBuf));
        prefs.getString("pass", passBuf, sizeof(passBuf));
        return;
    }
    if (compileTimeConfigured()) {
        strlcpy(ssidBuf, WIFI_SSID, sizeof(ssidBuf));
        strlcpy(passBuf, WIFI_PASSWORD, sizeof(passBuf));
    }
}

bool wifiConfigConfigured() {
    return ssidBuf[0] != '\0';
}

const char *wifiConfigSsid() {
    return ssidBuf;
}

const char *wifiConfigPassword() {
    return passBuf;
}

bool wifiConfigSet(const char *ssid, const char *password) {
    if (ssid == nullptr || ssid[0] == '\0' || strlen(ssid) > 32) {
        return false;
    }
    if (password == nullptr || strlen(password) > 64) {
        return false;
    }
    for (const char *p = ssid; *p; p++) {
        if ((unsigned char)*p < 32) {
            return false;
        }
    }
    for (const char *p = password; *p; p++) {
        if ((unsigned char)*p < 32) {
            return false;
        }
    }
    strlcpy(ssidBuf, ssid, sizeof(ssidBuf));
    strlcpy(passBuf, password, sizeof(passBuf));
    prefs.putString("ssid", ssidBuf);
    prefs.putString("pass", passBuf);
    pendingRestart = true;
    return true;
}

void wifiConfigClear() {
    ssidBuf[0] = '\0';
    passBuf[0] = '\0';
    prefs.remove("ssid");
    prefs.remove("pass");
    pendingRestart = true;
}

void wifiConfigFillStatus(JsonObject body) {
    body["configured"] = wifiConfigConfigured();
    body["ssid"] = ssidBuf;
    bool connected = WiFi.status() == WL_CONNECTED;
    body["connected"] = connected;
    if (connected) {
        body["ip"] = WiFi.localIP().toString();
        body["rssi"] = WiFi.RSSI();
    } else {
        body["ip"] = "";
        body["rssi"] = 0;
    }
}

bool wifiConfigTakePendingRestart() {
    if (!pendingRestart) {
        return false;
    }
    pendingRestart = false;
    return true;
}
