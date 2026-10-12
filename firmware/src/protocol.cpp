#include "protocol.h"

#include <Arduino.h>
#include <ArduinoJson.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

#include <Update.h>

#include "chassis.h"
#include "config.h"
#include "status_led.h"
#include "wifi_config.h"

static Stream *logStream = nullptr;
static bool usbLogMirror = false;

void protocolSetLogStream(Stream *stream) {
    logStream = stream;
}

void protocolSetUsbLogMirror(bool enabled) {
    usbLogMirror = enabled;
}

static void logLine(const char *line) {
    if (logStream != nullptr) {
        logStream->println(line);
    }
    // Mirror to USB CDC so `pio device monitor` on the USB port shows cmds.
    if (usbLogMirror) {
        Serial.println(line);
    }
}

static void logCmd(const char *op) {
    char line[64];
    snprintf(line, sizeof(line), "cmd %s", op);
    logLine(line);
}

static void logMotion(const char *op, double forward, double yaw) {
    char line[96];
    snprintf(line, sizeof(line), "cmd %s forward=%.3f yaw=%.3f", op, forward, yaw);
    logLine(line);
}

static const char *safety = "ready";
static double motionForward = 0;
static double motionYaw = 0;
static bool telemetryOn = false;
static double telemetryHz = 10;
static unsigned long telemetryDueMs = 0;

static bool payloadOn = false;
static double payloadMass = 0;
static double payloadSize[3] = {0, 0, 0};
static double payloadOffset[3] = {0, 0, 0};
static double payloadPosition[3] = {0, 0, 0};
static char payloadName[65] = "";

static char errorMessage[96];

static bool otaActive = false;
static size_t otaRemaining = 0;
static size_t otaTotal = 0;
static char otaRequestId[32] = "";

static bool sendDocument(Stream &peer, JsonDocument &doc) {
    static char out[3072];
    size_t need = measureJson(doc);
    if (need + 1 >= sizeof(out)) {
        return false;
    }
    size_t written = serializeJson(doc, out, sizeof(out));
    out[written++] = '\n';
    // USB CDC often accepts only part of a buffer; keep sending until done.
    size_t sent = 0;
    unsigned long startMs = millis();
    while (sent < written) {
        size_t n = peer.write(reinterpret_cast<const uint8_t *>(out + sent), written - sent);
        if (n > 0) {
            sent += n;
            continue;
        }
        if ((millis() - startMs) > 500) {
            return false;
        }
        delay(1);
    }
    peer.flush();
    return true;
}

static void addSafety(JsonObject body) {
    JsonObject safetyBody = body["safety"].to<JsonObject>();
    safetyBody["state"] = safety;
}

static bool wheelsMoving() {
    return fabs(chassisLeft()) > 1e-6 || fabs(chassisRight()) > 1e-6;
}

static void applyWheels(double left, double right) {
    chassisSetWheels(left, right);
    if (wheelsMoving() && strcmp(safety, "estop") != 0) {
        safety = "running";
    }
}

static void commandWheels(double forward, double yaw) {
    double left = (forward - yaw * WHEEL_BASE_M / 2) / WHEEL_RADIUS_M;
    double right = (forward + yaw * WHEEL_BASE_M / 2) / WHEEL_RADIUS_M;
    applyWheels(left, right);
}

static void motionFromWheels() {
    double left = chassisLeft();
    double right = chassisRight();
    motionForward = (left + right) / 2 * WHEEL_RADIUS_M;
    motionYaw = (right - left) * WHEEL_RADIUS_M / WHEEL_BASE_M;
}

static void stopMotion() {
    motionForward = 0;
    motionYaw = 0;
    chassisStopMotors();
    if (strcmp(safety, "estop") != 0) {
        safety = "ready";
    }
}

static bool latched(const char **code) {
    if (strcmp(safety, "estop") != 0) {
        return false;
    }
    *code = "safety_blocked";
    snprintf(errorMessage, sizeof(errorMessage), "emergency stop is latched");
    return true;
}

static bool envelopeOk(JsonDocument &req) {
    if (!req.is<JsonObject>()) {
        return false;
    }
    for (JsonPair pair : req.as<JsonObject>()) {
        const char *key = pair.key().c_str();
        if (strcmp(key, "v") != 0 && strcmp(key, "kind") != 0 && strcmp(key, "op") != 0 &&
            strcmp(key, "id") != 0 && strcmp(key, "body") != 0 && strcmp(key, "error") != 0) {
            return false;
        }
    }
    if (req["v"].as<int>() != 1) {
        return false;
    }
    const char *kind = req["kind"].as<const char *>();
    const char *op = req["op"].as<const char *>();
    if (kind == nullptr || op == nullptr || op[0] == '\0') {
        return false;
    }
    if (strcmp(kind, "req") != 0 && strcmp(kind, "res") != 0 && strcmp(kind, "evt") != 0) {
        return false;
    }
    if (req["id"].is<const char *>()) {
        const char *id = req["id"].as<const char *>();
        if (id[0] == '\0') {
            return false;
        }
    } else if (!req["id"].isNull()) {
        return false;
    }
    if (!req["body"].isNull() && !req["body"].is<JsonObject>()) {
        return false;
    }
    if (strcmp(kind, "req") == 0) {
        if (!req["id"].is<const char *>() || !req["body"].is<JsonObject>() || !req["error"].isNull()) {
            return false;
        }
    }
    return true;
}

static bool readNumber(JsonVariant value, const char *name, double low, double high, bool omitIsZero, double *out) {
    if (value.isNull()) {
        if (omitIsZero) {
            *out = 0;
            return true;
        }
        snprintf(errorMessage, sizeof(errorMessage), "%s must be from %g to %g", name, low, high);
        return false;
    }
    if (value.is<bool>() || !value.is<float>()) {
        snprintf(errorMessage, sizeof(errorMessage), "%s must be from %g to %g", name, low, high);
        return false;
    }
    double number = value.as<double>();
    if (!isfinite(number) || number < low || number > high) {
        snprintf(errorMessage, sizeof(errorMessage), "%s must be from %g to %g", name, low, high);
        return false;
    }
    *out = number;
    return true;
}

static bool readVec3(JsonVariant value, const char *name, double low, double high, bool positive, double out[3]) {
    JsonArray items = value.as<JsonArray>();
    if (!value.is<JsonArray>() || items.size() != 3) {
        snprintf(errorMessage, sizeof(errorMessage), "%s must be three numbers", name);
        return false;
    }
    for (int i = 0; i < 3; i++) {
        JsonVariant item = items[i];
        if (item.is<bool>() || !item.is<float>()) {
            snprintf(errorMessage, sizeof(errorMessage), "%s must be three numbers", name);
            return false;
        }
        double number = item.as<double>();
        if (!isfinite(number) || number < low || number > high || (positive && number <= 0)) {
            snprintf(errorMessage, sizeof(errorMessage), "%s must be from %g to %g", name, low, high);
            return false;
        }
        out[i] = number;
    }
    return true;
}

struct DeviceMatch {
    bool known;
    bool motor;
    bool encoder;
    bool sensor;
    const char *unit;
};

static DeviceMatch findDevice(const char *id) {
    DeviceMatch match = {};
    if (strcmp(id, "left_wheel") == 0 || strcmp(id, "right_wheel") == 0) {
        match.known = true;
        match.motor = true;
        match.encoder = true;
    } else if (strcmp(id, "bump") == 0) {
        match.known = true;
        match.sensor = true;
        match.unit = "boolean";
    } else if (strcmp(id, "range_front") == 0) {
        match.known = true;
        match.sensor = true;
        match.unit = "meters";
    }
    return match;
}

static bool requireDevice(JsonObject body, const char *type, const char **id, const char **unit) {
    if (!body["id"].is<const char *>() || body["id"].as<const char *>()[0] == '\0') {
        snprintf(errorMessage, sizeof(errorMessage), "no device named in the request");
        return false;
    }
    *id = body["id"].as<const char *>();
    DeviceMatch match = findDevice(*id);
    if (!match.known) {
        snprintf(errorMessage, sizeof(errorMessage), "no device named %s", *id);
        return false;
    }
    bool ok = false;
    if (strcmp(type, "motor") == 0) {
        ok = match.motor;
    } else if (strcmp(type, "encoder") == 0) {
        ok = match.encoder;
    } else if (strcmp(type, "sensor") == 0) {
        ok = match.sensor;
    }
    if (!ok) {
        char found[48] = "";
        if (match.motor) {
            strlcat(found, "motor", sizeof(found));
        }
        if (match.encoder) {
            if (found[0] != '\0') {
                strlcat(found, ", ", sizeof(found));
            }
            strlcat(found, "encoder", sizeof(found));
        }
        if (match.sensor) {
            if (found[0] != '\0') {
                strlcat(found, ", ", sizeof(found));
            }
            strlcat(found, "sensor", sizeof(found));
        }
        snprintf(errorMessage, sizeof(errorMessage), "%s is %s, not a %s", *id, found, type);
        return false;
    }
    if (unit != nullptr) {
        *unit = match.unit;
    }
    return true;
}

static const char *deviceErrorCode(const char *message) {
    return strstr(message, "no device") != nullptr ? "unknown_device" : "unsupported";
}

static void fillDevices(JsonArray devices) {
    struct Item {
        const char *type;
        const char *id;
        const char *capability;
    };
    const Item items[] = {
        {"motor", "left_wheel", "velocity"},
        {"motor", "right_wheel", "velocity"},
        {"encoder", "left_wheel", "ticks"},
        {"encoder", "right_wheel", "ticks"},
        {"sensor", "bump", "boolean"},
        {"sensor", "range_front", "meters"},
    };
    for (const Item &item : items) {
        JsonObject device = devices.add<JsonObject>();
        device["type"] = item.type;
        device["id"] = item.id;
        JsonArray capabilities = device["capabilities"].to<JsonArray>();
        capabilities.add(item.capability);
    }
}

static void fillHello(JsonObject body) {
    body["protocol"] = "jiadroid";
    body["version"] = "0.2";
    JsonObject robot = body["robot"].to<JsonObject>();
    robot["id"] = "2wd-chassis";
    robot["name"] = "2WD Chassis";
    robot["kind"] = "wheeled";
    addSafety(body);
    wifiConfigFillStatus(body["wifi"].to<JsonObject>());
    JsonObject controls = body["controls"].to<JsonObject>();
    JsonObject motion = controls["motion.velocity"].to<JsonObject>();
    JsonArray forward = motion["forward"].to<JsonArray>();
    forward.add(-MAX_FORWARD_M_S);
    forward.add(MAX_FORWARD_M_S);
    JsonArray yaw = motion["yaw"].to<JsonArray>();
    yaw.add(-MAX_YAW_RAD_S);
    yaw.add(MAX_YAW_RAD_S);
    controls["wifi.set"].to<JsonObject>();
    controls["wifi.clear"].to<JsonObject>();
    controls["wifi.status"].to<JsonObject>();
    controls["firmware.begin"].to<JsonObject>();
    JsonArray mounts = body["mounts"].to<JsonArray>();
    JsonObject mount = mounts.add<JsonObject>();
    mount["id"] = "top";
    JsonArray position = mount["position"].to<JsonArray>();
    position.add(MOUNT_X_M);
    position.add(MOUNT_Y_M);
    position.add(MOUNT_Z_M);
    mount["max_mass"] = MOUNT_MAX_MASS_KG;
    fillDevices(body["devices"].to<JsonArray>());
}

static void abortOta() {
    if (otaActive) {
        Update.abort();
    }
    otaActive = false;
    otaRemaining = 0;
    otaTotal = 0;
    otaRequestId[0] = '\0';
}

static void fillPayload(JsonObject body) {
    body["mount"] = "top";
    body["mass"] = payloadMass;
    JsonArray size = body["size"].to<JsonArray>();
    JsonArray offset = body["offset"].to<JsonArray>();
    JsonArray position = body["position"].to<JsonArray>();
    for (int i = 0; i < 3; i++) {
        size.add(payloadSize[i]);
        offset.add(payloadOffset[i]);
        position.add(payloadPosition[i]);
    }
    body["name"] = payloadName;
}

static void fillSample(JsonObject body) {
    addSafety(body);
    JsonObject motion = body["motion"].to<JsonObject>();
    motion["forward"] = motionForward;
    motion["yaw"] = motionYaw;
    body["servos"].to<JsonObject>();
    if (payloadOn) {
        fillPayload(body["payload"].to<JsonObject>());
    }
    JsonObject motors = body["motors"].to<JsonObject>();
    motors["left_wheel"] = chassisLeft();
    motors["right_wheel"] = chassisRight();
    JsonObject sensors = body["sensors"].to<JsonObject>();
    sensors["bump"] = chassisBump() ? 1.0 : 0.0;
    sensors["range_front"] = chassisRange();
    sensors["left_wheel"] = chassisLeftTicks();
    sensors["right_wheel"] = chassisRightTicks();
    double x = 0;
    double y = 0;
    double yaw = 0;
    chassisPose(&x, &y, &yaw);
    JsonObject base = body["base"].to<JsonObject>();
    base["x"] = x;
    base["y"] = y;
    base["yaw"] = yaw;
}

static bool sendResult(Stream &peer, const char *kind, const char *op, const char *id, JsonDocument &body, bool failed) {
    JsonDocument doc;
    doc["v"] = 1;
    doc["kind"] = kind;
    doc["op"] = op;
    if (id != nullptr) {
        doc["id"] = id;
    }
    if (failed) {
        JsonObject error = doc["error"].to<JsonObject>();
        error["code"] = body["code"].as<const char *>();
        error["message"] = body["message"].as<const char *>();
    } else {
        doc["body"] = body.as<JsonObject>();
    }
    return sendDocument(peer, doc);
}

static bool fail(Stream &peer, const char *op, const char *id, const char *code) {
    JsonDocument body;
    body["code"] = code;
    body["message"] = errorMessage;
    return sendResult(peer, "res", op, id, body, true);
}

static bool succeed(Stream &peer, const char *kind, const char *op, const char *id, JsonDocument &body) {
    return sendResult(peer, kind, op, id, body, false);
}

void protocolResetSession() {
    telemetryOn = false;
    telemetryHz = 10;
    abortOta();
}

bool protocolOtaActive() {
    return otaActive;
}

bool protocolOtaConsume(Stream &peer, const uint8_t *data, size_t len, bool *restart) {
    *restart = false;
    if (!otaActive || data == nullptr || len == 0) {
        return true;
    }
    if (len > otaRemaining) {
        snprintf(errorMessage, sizeof(errorMessage), "firmware image sent too many bytes");
        abortOta();
        return false;
    }
    size_t written = Update.write(const_cast<uint8_t *>(data), len);
    if (written != len) {
        snprintf(errorMessage, sizeof(errorMessage), "firmware write failed");
        abortOta();
        return false;
    }
    otaRemaining -= len;
    if (otaRemaining > 0) {
        return true;
    }
    bool ok = Update.end(true);
    otaActive = false;
    otaRequestId[0] = '\0';
    JsonDocument result;
    JsonObject resultBody = result.to<JsonObject>();
    if (!ok) {
        snprintf(errorMessage, sizeof(errorMessage), "firmware verify failed");
        resultBody["ok"] = false;
        resultBody["message"] = errorMessage;
        succeed(peer, "evt", "firmware.complete", nullptr, result);
        return false;
    }
    resultBody["ok"] = true;
    resultBody["bytes"] = (double)otaTotal;
    resultBody["rebooting"] = true;
    if (!succeed(peer, "evt", "firmware.complete", nullptr, result)) {
        return false;
    }
    logCmd("firmware.complete");
    *restart = true;
    return true;
}

bool protocolSendHello(Stream &peer) {
    JsonDocument body;
    fillHello(body.to<JsonObject>());
    return succeed(peer, "evt", "session.hello", nullptr, body);
}

void protocolOnDisconnect() {
    abortOta();
    protocolResetSession();
    stopMotion();
}

bool protocolPollTelemetry(Stream &peer, unsigned long nowMs) {
    if (!telemetryOn) {
        return true;
    }
    if ((long)(nowMs - telemetryDueMs) < 0) {
        return true;
    }
    unsigned long period = (unsigned long)(1000.0 / telemetryHz);
    if (period < 1) {
        period = 1;
    }
    telemetryDueMs = nowMs + period;
    JsonDocument body;
    fillSample(body.to<JsonObject>());
    if (!succeed(peer, "evt", "telemetry.sample", nullptr, body)) {
        protocolOnDisconnect();
        return false;
    }
    return true;
}

static bool setPayload(JsonObject body) {
    const char *mount = body["mount"].as<const char *>();
    if (mount == nullptr || strcmp(mount, "top") != 0) {
        snprintf(errorMessage, sizeof(errorMessage), "no mount named %s", mount == nullptr ? "" : mount);
        return false;
    }
    if (body["mass"].is<bool>() || !body["mass"].is<float>()) {
        snprintf(errorMessage, sizeof(errorMessage), "mass must be above 0 and at most %g kg", MOUNT_MAX_MASS_KG);
        return false;
    }
    double mass = body["mass"].as<double>();
    if (!isfinite(mass) || !(mass > 0 && mass <= MOUNT_MAX_MASS_KG)) {
        snprintf(errorMessage, sizeof(errorMessage), "mass must be above 0 and at most %g kg", MOUNT_MAX_MASS_KG);
        return false;
    }
    double size[3];
    double offset[3] = {0, 0, 0};
    if (!readVec3(body["size"], "size", 0, 0.25, true, size)) {
        return false;
    }
    if (!body["offset"].isNull() && !readVec3(body["offset"], "offset", -0.05, 0.05, false, offset)) {
        return false;
    }
    const char *name = "";
    if (!body["name"].isNull()) {
        if (!body["name"].is<const char *>()) {
            snprintf(errorMessage, sizeof(errorMessage), "name must be a short string");
            return false;
        }
        name = body["name"].as<const char *>();
        size_t length = strlen(name);
        if (length > 64) {
            snprintf(errorMessage, sizeof(errorMessage), "name must be a short string");
            return false;
        }
        for (size_t i = 0; i < length; i++) {
            if ((unsigned char)name[i] < 32) {
                snprintf(errorMessage, sizeof(errorMessage), "name must be a short string");
                return false;
            }
        }
    }
    payloadOn = true;
    payloadMass = mass;
    memcpy(payloadSize, size, sizeof(size));
    memcpy(payloadOffset, offset, sizeof(offset));
    payloadPosition[0] = MOUNT_X_M + offset[0];
    payloadPosition[1] = MOUNT_Y_M + offset[1];
    payloadPosition[2] = MOUNT_Z_M + offset[2];
    strlcpy(payloadName, name, sizeof(payloadName));
    return true;
}

static const char *payloadErrorCode() {
    return strstr(errorMessage, "no mount") != nullptr ? "unknown_device" : "out_of_range";
}

bool protocolHandleLine(Stream &peer, const char *line) {
    JsonDocument req;
    // USB hosts often spit noise on open. Ignore junk; do not drop the session.
    if (deserializeJson(req, line) != DeserializationError::Ok || !envelopeOk(req)) {
        logLine("ignored bad line");
        return true;
    }
    const char *kind = req["kind"].as<const char *>();
    if (strcmp(kind, "req") != 0) {
        return true;
    }
    const char *op = req["op"].as<const char *>();
    const char *id = req["id"].as<const char *>();
    JsonObject body = req["body"].as<JsonObject>();
    JsonDocument result;
    JsonObject resultBody = result.to<JsonObject>();
    const char *code = "unsupported";

    if (strcmp(op, "session.ping") == 0 || strcmp(op, "session.bye") == 0) {
        if (strcmp(op, "session.bye") == 0) {
            logCmd(op);
            statusLedSet(STATUS_LED_BYE);
        }
        resultBody["ok"] = true;
        if (!succeed(peer, "res", op, id, result)) {
            return false;
        }
        return strcmp(op, "session.bye") != 0;
    }
    if (strcmp(op, "devices.list") == 0) {
        logCmd(op);
        fillDevices(resultBody["devices"].to<JsonArray>());
        return succeed(peer, "res", op, id, result);
    }
    if (strcmp(op, "motion.velocity") == 0) {
        double forward = 0;
        double yaw = 0;
        if (!readNumber(body["forward"], "forward", -MAX_FORWARD_M_S, MAX_FORWARD_M_S, true, &forward) ||
            !readNumber(body["yaw"], "yaw", -MAX_YAW_RAD_S, MAX_YAW_RAD_S, true, &yaw)) {
            return fail(peer, op, id, "out_of_range");
        }
        if (!body["lateral"].isNull()) {
            if (body["lateral"].is<bool>() || !body["lateral"].is<float>() || !isfinite(body["lateral"].as<double>()) ||
                fabs(body["lateral"].as<double>()) > 1e-9) {
                snprintf(errorMessage, sizeof(errorMessage), "lateral is not supported by this robot");
                return fail(peer, op, id, "out_of_range");
            }
        }
        if (latched(&code)) {
            return fail(peer, op, id, code);
        }
        motionForward = forward;
        motionYaw = yaw;
        commandWheels(forward, yaw);
        logMotion(op, forward, yaw);
        statusLedMotion(forward, yaw);
        resultBody["forward"] = forward;
        resultBody["yaw"] = yaw;
        addSafety(resultBody);
        return succeed(peer, "res", op, id, result);
    }
    if (strcmp(op, "head.pose") == 0 || strcmp(op, "walk.velocity") == 0 || strcmp(op, "servo.position") == 0 ||
        strcmp(op, "servo.read") == 0) {
        snprintf(errorMessage, sizeof(errorMessage), "2WD Chassis does not accept %s", op);
        return fail(peer, op, id, "unsupported");
    }
    if (strcmp(op, "motor.velocity") == 0) {
        const char *deviceId = nullptr;
        if (!requireDevice(body, "motor", &deviceId, nullptr)) {
            return fail(peer, op, id, deviceErrorCode(errorMessage));
        }
        if (latched(&code)) {
            return fail(peer, op, id, code);
        }
        double velocity = 0;
        if (!readNumber(body["velocity"], "velocity", -MAX_WHEEL_RAD_S, MAX_WHEEL_RAD_S, false, &velocity)) {
            return fail(peer, op, id, "out_of_range");
        }
        if (strcmp(deviceId, "left_wheel") == 0) {
            applyWheels(velocity, chassisRight());
        } else {
            applyWheels(chassisLeft(), velocity);
        }
        motionFromWheels();
        {
            char line[96];
            snprintf(line, sizeof(line), "cmd %s %s=%.3f", op, deviceId, velocity);
            logLine(line);
        }
        resultBody["id"] = deviceId;
        resultBody["velocity"] = velocity;
        addSafety(resultBody);
        return succeed(peer, "res", op, id, result);
    }
    if (strcmp(op, "encoder.read") == 0) {
        const char *deviceId = nullptr;
        if (!requireDevice(body, "encoder", &deviceId, nullptr)) {
            return fail(peer, op, id, deviceErrorCode(errorMessage));
        }
        resultBody["id"] = deviceId;
        resultBody["ticks"] = strcmp(deviceId, "left_wheel") == 0 ? chassisLeftTicks() : chassisRightTicks();
        return succeed(peer, "res", op, id, result);
    }
    if (strcmp(op, "sensor.read") == 0) {
        const char *deviceId = nullptr;
        const char *unit = nullptr;
        if (!requireDevice(body, "sensor", &deviceId, &unit)) {
            return fail(peer, op, id, deviceErrorCode(errorMessage));
        }
        resultBody["id"] = deviceId;
        resultBody["value"] = strcmp(deviceId, "bump") == 0 ? (chassisBump() ? 1.0 : 0.0) : chassisRange();
        resultBody["unit"] = unit;
        return succeed(peer, "res", op, id, result);
    }
    if (strcmp(op, "payload.set") == 0) {
        if (!setPayload(body)) {
            return fail(peer, op, id, payloadErrorCode());
        }
        fillPayload(resultBody);
        return succeed(peer, "res", op, id, result);
    }
    if (strcmp(op, "payload.clear") == 0) {
        payloadOn = false;
        resultBody["cleared"] = true;
        return succeed(peer, "res", op, id, result);
    }
    if (strcmp(op, "robot.stop") == 0) {
        logCmd(op);
        stopMotion();
        statusLedSet(STATUS_LED_STOP);
        addSafety(resultBody);
        return succeed(peer, "res", op, id, result);
    }
    if (strcmp(op, "robot.reset") == 0) {
        logCmd(op);
        stopMotion();
        chassisZeroEncoders();
        chassisZeroPose();
        statusLedSet(STATUS_LED_STOP);
        addSafety(resultBody);
        return succeed(peer, "res", op, id, result);
    }
    if (strcmp(op, "telemetry.subscribe") == 0) {
        double hz = 10;
        if (!body["hz"].isNull()) {
            if (body["hz"].is<bool>() || !body["hz"].is<float>()) {
                snprintf(errorMessage, sizeof(errorMessage), "telemetry hz must be greater than 0 and at most 50");
                return fail(peer, op, id, "out_of_range");
            }
            hz = body["hz"].as<double>();
            if (!isfinite(hz) || hz <= 0 || hz > 50) {
                snprintf(errorMessage, sizeof(errorMessage), "telemetry hz must be greater than 0 and at most 50");
                return fail(peer, op, id, "out_of_range");
            }
        }
        telemetryHz = hz;
        telemetryOn = true;
        telemetryDueMs = millis();
        resultBody["hz"] = hz;
        return succeed(peer, "res", op, id, result);
    }
    if (strcmp(op, "safety.estop") == 0) {
        logCmd(op);
        stopMotion();
        safety = "estop";
        statusLedSet(STATUS_LED_ESTOP);
        addSafety(resultBody);
        return succeed(peer, "res", op, id, result);
    }
    if (strcmp(op, "safety.clear") == 0) {
        logCmd(op);
        if (strcmp(safety, "estop") == 0) {
            safety = "ready";
        }
        statusLedSet(STATUS_LED_CLEAR);
        addSafety(resultBody);
        return succeed(peer, "res", op, id, result);
    }
    if (strcmp(op, "wifi.status") == 0) {
        wifiConfigFillStatus(resultBody);
        return succeed(peer, "res", op, id, result);
    }
    if (strcmp(op, "wifi.set") == 0) {
        const char *ssid = body["ssid"].as<const char *>();
        const char *password = body["password"].isNull() ? "" : body["password"].as<const char *>();
        if (ssid == nullptr || password == nullptr || !wifiConfigSet(ssid, password)) {
            snprintf(errorMessage, sizeof(errorMessage), "ssid must be 1–32 characters; password at most 64");
            return fail(peer, op, id, "out_of_range");
        }
        logCmd(op);
        wifiConfigFillStatus(resultBody);
        resultBody["applied"] = true;
        return succeed(peer, "res", op, id, result);
    }
    if (strcmp(op, "wifi.clear") == 0) {
        logCmd(op);
        wifiConfigClear();
        resultBody["cleared"] = true;
        wifiConfigFillStatus(resultBody);
        return succeed(peer, "res", op, id, result);
    }
    if (strcmp(op, "firmware.begin") == 0) {
        if (otaActive) {
            snprintf(errorMessage, sizeof(errorMessage), "firmware update already in progress");
            return fail(peer, op, id, "out_of_range");
        }
        if (!body["size"].is<float>() && !body["size"].is<int>()) {
            snprintf(errorMessage, sizeof(errorMessage), "firmware size must be a positive byte count");
            return fail(peer, op, id, "out_of_range");
        }
        double sizeNumber = body["size"].as<double>();
        if (!isfinite(sizeNumber) || sizeNumber < 1024 || sizeNumber > 4.0 * 1024 * 1024) {
            snprintf(errorMessage, sizeof(errorMessage), "firmware size must be from 1024 to 4194304 bytes");
            return fail(peer, op, id, "out_of_range");
        }
        size_t size = (size_t)sizeNumber;
        size_t maxSpace = ESP.getFreeSketchSpace();
        if (maxSpace > 0 && size > maxSpace) {
            snprintf(errorMessage, sizeof(errorMessage), "firmware is larger than the free OTA slot (%u bytes)", (unsigned)maxSpace);
            return fail(peer, op, id, "out_of_range");
        }
        stopMotion();
        if (!Update.begin(size)) {
            snprintf(errorMessage, sizeof(errorMessage), "cannot start firmware update");
            return fail(peer, op, id, "out_of_range");
        }
        otaActive = true;
        otaRemaining = size;
        otaTotal = size;
        strlcpy(otaRequestId, id == nullptr ? "" : id, sizeof(otaRequestId));
        logCmd(op);
        resultBody["ok"] = true;
        resultBody["size"] = (double)size;
        resultBody["max"] = (double)(maxSpace > 0 ? maxSpace : size);
        // Response acknowledges begin; firmware.complete arrives after the raw bytes.
        return succeed(peer, "res", op, id, result);
    }
    snprintf(errorMessage, sizeof(errorMessage), "unknown operation %s", op);
    return fail(peer, op, id, "unsupported");
}
