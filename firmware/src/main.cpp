#include <WiFi.h>

#include "chassis.h"
#include "config.h"
#include "protocol.h"

static WiFiServer server(ROBOT_PORT);
static WiFiClient client;
static bool clientOpen = false;
static bool serverStarted = false;
static char line[8192];
static size_t lineLength = 0;

static void dropClient() {
    if (client) {
        client.stop();
    }
    clientOpen = false;
    lineLength = 0;
    protocolOnDisconnect();
}

static void joinWifi() {
    WiFi.mode(WIFI_STA);
    WiFi.setSleep(false);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    Serial.print("Joining ");
    Serial.println(WIFI_SSID);
    for (int attempt = 0; attempt < 40 && WiFi.status() != WL_CONNECTED; attempt++) {
        delay(500);
        Serial.print(".");
    }
    Serial.println();
    if (WiFi.status() != WL_CONNECTED) {
        Serial.println("Wi-Fi join failed. Check WIFI_SSID and WIFI_PASSWORD in include/config.h.");
        return;
    }
    if (!serverStarted) {
        server.begin();
        serverStarted = true;
    }
    Serial.print("Listening on ");
    Serial.print(WiFi.localIP());
    Serial.print(":");
    Serial.println(ROBOT_PORT);
}

static void acceptClient() {
    WiFiClient incoming = server.accept();
    if (!incoming) {
        return;
    }
    if (clientOpen && client.connected()) {
        incoming.stop();
        return;
    }
    client = incoming;
    client.setNoDelay(true);
    clientOpen = true;
    lineLength = 0;
    protocolResetSession();
    if (!protocolSendHello(client)) {
        dropClient();
        return;
    }
    Serial.print("Phone connected from ");
    Serial.println(client.remoteIP());
}

static void readClient(unsigned long nowMs) {
    while (client.connected() && client.available()) {
        int next = client.read();
        if (next < 0) {
            break;
        }
        char ch = (char)next;
        if (ch != '\n') {
            if (lineLength + 1 >= sizeof(line)) {
                Serial.println("Closing a message that exceeds 8192 bytes.");
                dropClient();
                return;
            }
            line[lineLength++] = ch;
            continue;
        }
        if (lineLength > 0 && line[lineLength - 1] == '\r') {
            lineLength--;
        }
        line[lineLength] = '\0';
        if (lineLength == 0 || !protocolHandleLine(client, line)) {
            if (lineLength == 0) {
                Serial.println("Closing an empty message.");
            }
            dropClient();
            return;
        }
        lineLength = 0;
    }
    if (!client.connected()) {
        Serial.println("Phone disconnected.");
        dropClient();
        return;
    }
    if (!protocolPollTelemetry(client, nowMs)) {
        Serial.println("Phone disconnected.");
        dropClient();
    }
}

void setup() {
    Serial.begin(115200);
    delay(200);
    Serial.println("Jiadroid 2WD chassis");
    chassisBegin();
    joinWifi();
}

void loop() {
    unsigned long nowMs = millis();
    if (WiFi.status() != WL_CONNECTED) {
        if (clientOpen) {
            Serial.println("Wi-Fi dropped.");
            dropClient();
        }
        static unsigned long retryMs = 0;
        if (nowMs - retryMs > 5000) {
            retryMs = nowMs;
            joinWifi();
        }
        chassisPoll(nowMs);
        return;
    }
    acceptClient();
    if (clientOpen) {
        readClient(nowMs);
    }
    chassisPoll(nowMs);
}
