#include <WiFi.h>

#include "chassis.h"
#include "config.h"
#include "protocol.h"

// USB CDC (Serial) carries the protocol for the phone. UART0 (Serial0) is the
// COM port on the DevKit — use that for debug logs so they do not corrupt USB.
static WiFiServer server(ROBOT_PORT);
static WiFiClient wifiClient;
static Stream *peer = nullptr;
static bool peerIsWifi = false;
static bool wifiServerStarted = false;
static bool usbSession = false;
static bool usbHostWas = false;
static char line[8192];
static size_t lineLength = 0;

static void logMsg(const char *message) {
    Serial0.println(message);
}

static void dropPeer(const char *reason) {
    if (peer == nullptr) {
        return;
    }
    if (reason != nullptr) {
        logMsg(reason);
    }
    if (peerIsWifi && wifiClient) {
        wifiClient.stop();
    }
    peer = nullptr;
    peerIsWifi = false;
    usbSession = false;
    lineLength = 0;
    protocolOnDisconnect();
}

static bool beginPeer(Stream *stream, bool wifi, const char *label) {
    if (peer != nullptr) {
        return false;
    }
    peer = stream;
    peerIsWifi = wifi;
    usbSession = !wifi;
    lineLength = 0;
    protocolResetSession();
    if (!protocolSendHello(*peer)) {
        dropPeer("Failed to send session.hello.");
        return false;
    }
    logMsg(label);
    return true;
}

static void joinWifi() {
    if (WIFI_SSID[0] == '\0' || strcmp(WIFI_SSID, "your-network") == 0) {
        logMsg("Wi-Fi skipped (set WIFI_SSID in include/config.h to enable).");
        return;
    }
    WiFi.mode(WIFI_STA);
    WiFi.setSleep(false);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    Serial0.print("Joining ");
    Serial0.println(WIFI_SSID);
    for (int attempt = 0; attempt < 40 && WiFi.status() != WL_CONNECTED; attempt++) {
        delay(500);
        Serial0.print(".");
    }
    Serial0.println();
    if (WiFi.status() != WL_CONNECTED) {
        logMsg("Wi-Fi join failed.");
        return;
    }
    if (!wifiServerStarted) {
        server.begin();
        wifiServerStarted = true;
    }
    Serial0.print("Listening on ");
    Serial0.print(WiFi.localIP());
    Serial0.print(":");
    Serial0.println(ROBOT_PORT);
}

static void acceptWifi() {
    if (!wifiServerStarted || WiFi.status() != WL_CONNECTED) {
        return;
    }
    WiFiClient incoming = server.accept();
    if (!incoming) {
        return;
    }
    if (peer != nullptr) {
        incoming.stop();
        return;
    }
    wifiClient = incoming;
    wifiClient.setNoDelay(true);
    beginPeer(&wifiClient, true, "Phone connected over Wi-Fi.");
}

static void pollUsbSession() {
    // HWCDC's operator bool is true while a USB host has the CDC port open.
    bool host = static_cast<bool>(Serial) || Serial.available() > 0;
    if (peer != nullptr && usbSession) {
        if (usbHostWas && !host) {
            dropPeer("Phone disconnected (USB).");
        }
        usbHostWas = host;
        return;
    }
    if (peer != nullptr) {
        usbHostWas = host;
        return;
    }
    if (host) {
        beginPeer(&Serial, false, "Phone connected over USB.");
    }
    usbHostWas = host;
}

static void readPeer(unsigned long nowMs) {
    if (peer == nullptr) {
        return;
    }
    while (peer->available()) {
        int next = peer->read();
        if (next < 0) {
            break;
        }
        char ch = (char)next;
        if (ch != '\n') {
            if (lineLength + 1 >= sizeof(line)) {
                dropPeer("Closing a message that exceeds 8192 bytes.");
                return;
            }
            line[lineLength++] = ch;
            continue;
        }
        if (lineLength > 0 && line[lineLength - 1] == '\r') {
            lineLength--;
        }
        line[lineLength] = '\0';
        if (lineLength == 0 || !protocolHandleLine(*peer, line)) {
            dropPeer(lineLength == 0 ? "Closing an empty message." : "Phone disconnected.");
            return;
        }
        lineLength = 0;
    }
    if (peerIsWifi && !wifiClient.connected()) {
        dropPeer("Phone disconnected.");
        return;
    }
    if (!protocolPollTelemetry(*peer, nowMs)) {
        dropPeer("Phone disconnected.");
    }
}

void setup() {
    Serial.begin(115200);
    Serial0.begin(115200);
    delay(200);
    protocolSetLogStream(&Serial0);
    Serial0.println("Jiadroid 2WD chassis (USB-C + optional Wi-Fi)");
    Serial0.println("Protocol on USB. Debug on UART/COM.");
    chassisBegin();
    joinWifi();
}

void loop() {
    unsigned long nowMs = millis();
    pollUsbSession();
    if (WiFi.status() == WL_CONNECTED) {
        acceptWifi();
    } else if (peerIsWifi && peer != nullptr) {
        dropPeer("Wi-Fi dropped.");
        static unsigned long retryMs = 0;
        if (nowMs - retryMs > 5000) {
            retryMs = nowMs;
            joinWifi();
        }
    } else {
        static unsigned long retryMs = 0;
        if (WIFI_SSID[0] != '\0' && strcmp(WIFI_SSID, "your-network") != 0 && nowMs - retryMs > 5000) {
            retryMs = nowMs;
            if (WiFi.status() != WL_CONNECTED) {
                joinWifi();
            }
        }
    }
    if (peer != nullptr) {
        readPeer(nowMs);
    }
    chassisPoll(nowMs);
}
