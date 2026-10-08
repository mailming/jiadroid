#include <WiFi.h>

#include "chassis.h"
#include "config.h"
#include "protocol.h"
#include "status_led.h"

// USB CDC (Serial) is the phone protocol link (OTG). UART0 (Serial0) is debug.
// Optional Wi-Fi is a fallback when USB is free.
static WiFiServer server(ROBOT_PORT);
static WiFiClient wifiClient;
static Stream *peer = nullptr;
static bool peerIsWifi = false;
static bool wifiServerStarted = false;
static bool wifiStarted = false;
static bool usbSession = false;
static bool usbHostWas = false;
static bool usbAwaitingClient = false;
static unsigned long usbHostGoneMs = 0;
static unsigned long usbHostSinceMs = 0;
static unsigned long nextUsbHelloMs = 0;
static unsigned long nextWifiAttemptMs = 0;
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
    usbAwaitingClient = false;
    lineLength = 0;
    protocolOnDisconnect();
    protocolSetUsbLogMirror(false);
    if (WiFi.status() == WL_CONNECTED) {
        statusLedSet(STATUS_LED_WIFI_OK);
    } else {
        statusLedSet(STATUS_LED_WIFI_WAIT);
    }
}

static bool beginPeer(Stream *stream, bool wifi, const char *label) {
    if (peer != nullptr) {
        return false;
    }
    peer = stream;
    peerIsWifi = wifi;
    usbSession = !wifi;
    usbAwaitingClient = !wifi;
    lineLength = 0;
    protocolResetSession();
    protocolSetUsbLogMirror(wifi);
    // USB host may not be reading yet right after open/reset — keep the peer
    // and let pollUsbSession retry hello instead of dropping the session.
    if (!protocolSendHello(*peer)) {
        logMsg("session.hello deferred.");
        nextUsbHelloMs = millis() + 200;
    } else {
        nextUsbHelloMs = millis() + 1000;
    }
    statusLedSet(STATUS_LED_IDLE);
    logMsg(label);
    return true;
}

static void startWifiNonBlocking() {
    if (WIFI_SSID[0] == '\0' || strcmp(WIFI_SSID, "your-network") == 0) {
        return;
    }
    if (wifiStarted) {
        return;
    }
    wifiStarted = true;
    statusLedSet(STATUS_LED_WIFI_WAIT);
    WiFi.mode(WIFI_STA);
    WiFi.setSleep(false);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    Serial0.print("Joining ");
    Serial0.println(WIFI_SSID);
}

static void pollWifi(unsigned long nowMs) {
    if (WIFI_SSID[0] == '\0' || strcmp(WIFI_SSID, "your-network") == 0) {
        return;
    }
    if (!wifiStarted) {
        startWifiNonBlocking();
        return;
    }
    if (WiFi.status() != WL_CONNECTED) {
        if (nowMs > nextWifiAttemptMs) {
            nextWifiAttemptMs = nowMs + 5000;
            WiFi.disconnect(false);
            WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
            logMsg("Wi-Fi retry…");
        }
        return;
    }
    if (!wifiServerStarted) {
        server.begin();
        wifiServerStarted = true;
        Serial0.print("Listening on ");
        Serial0.print(WiFi.localIP());
        Serial0.print(":");
        Serial0.println(ROBOT_PORT);
        if (peer == nullptr) {
            statusLedSet(STATUS_LED_WIFI_OK);
        }
    }
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

static void pollUsbSession(unsigned long nowMs) {
    bool host = static_cast<bool>(Serial) || Serial.available() > 0;

    if (peer != nullptr && usbSession) {
        if (!host) {
            if (usbHostGoneMs == 0) {
                usbHostGoneMs = nowMs;
            } else if (nowMs - usbHostGoneMs > 5000) {
                // Phone USB open often glitches the host briefly around a reset.
                dropPeer("Phone disconnected (USB).");
                usbHostGoneMs = 0;
            }
        } else {
            usbHostGoneMs = 0;
            // Keep advertising hello until the phone app opens and speaks.
            if (usbAwaitingClient && (long)(nowMs - nextUsbHelloMs) >= 0) {
                if (protocolSendHello(*peer)) {
                    nextUsbHelloMs = nowMs + 1000;
                } else {
                    nextUsbHelloMs = nowMs + 250;
                }
            }
        }
        usbHostWas = host;
        return;
    }

    // USB phone wins over Wi-Fi when the cable/host appears.
    if (host && peer != nullptr && peerIsWifi && !usbHostWas) {
        dropPeer("Yielding Wi-Fi to USB phone.");
    }
    // Wait for the host to stay up briefly so we do not hello into a reset.
    if (host) {
        if (usbHostSinceMs == 0) {
            usbHostSinceMs = nowMs;
        }
        if (peer == nullptr && (nowMs - usbHostSinceMs) >= 400) {
            beginPeer(&Serial, false, "Phone connected over USB.");
        }
    } else {
        usbHostSinceMs = 0;
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
        if (lineLength == 0) {
            // Ignore blank lines (phone/OS can send them on open).
            continue;
        }
        if (!protocolHandleLine(*peer, line)) {
            dropPeer("Phone disconnected.");
            return;
        }
        if (usbSession) {
            usbAwaitingClient = false;
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
    delay(300);
    protocolSetLogStream(&Serial0);
    protocolSetUsbLogMirror(false);
    statusLedBegin();
    logMsg("Jiadroid 2WD chassis (USB-C primary)");
    logMsg("Plug phone OTG into the USB port, then tap USB in the app.");
    chassisBegin();

    // Prefer USB, but let pollUsbSession start the peer after a short settle.
    if (static_cast<bool>(Serial) || Serial.available() > 0) {
        usbHostWas = true;
        usbHostSinceMs = millis();
    }
    startWifiNonBlocking();
}

void loop() {
    unsigned long nowMs = millis();
    statusLedPoll(nowMs);
    pollUsbSession(nowMs);
    pollWifi(nowMs);
    if (peer == nullptr) {
        acceptWifi();
    }
    if (peer != nullptr) {
        readPeer(nowMs);
    }
    chassisPoll(nowMs);
}
