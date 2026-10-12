#include "status_led.h"

#include <Arduino.h>
#include <math.h>

#include "config.h"

#include <esp32-hal-rgb-led.h>
#define STATUS_LED_HAS_RGB 1

static StatusLedMode mode = STATUS_LED_OFF;
static StatusLedMode afterPulse = STATUS_LED_IDLE;
static unsigned long pulseUntilMs = 0;
static unsigned long phaseMs = 0;
static uint8_t motionR = 0;
static uint8_t motionG = 0;
static uint8_t motionB = 0;

static void writeRgb(uint8_t r, uint8_t g, uint8_t b) {
#if STATUS_LED_HAS_RGB
    if (PIN_STATUS_RGB < 0) {
        return;
    }
    neopixelWrite(static_cast<uint8_t>(PIN_STATUS_RGB), r, g, b);
#else
    (void)r;
    (void)g;
    (void)b;
#endif
}

static void applySteady(StatusLedMode m) {
    switch (m) {
        case STATUS_LED_OFF:
            writeRgb(0, 0, 0);
            break;
        case STATUS_LED_BOOT:
            writeRgb(40, 40, 40);
            break;
        case STATUS_LED_WIFI_OK:
            writeRgb(0, 0, 24);
            break;
        case STATUS_LED_IDLE:
            writeRgb(0, 28, 0);
            break;
        case STATUS_LED_MOTION:
            writeRgb(motionR, motionG, motionB);
            break;
        case STATUS_LED_STOP:
            writeRgb(48, 48, 48);
            break;
        case STATUS_LED_CLEAR:
            writeRgb(0, 48, 0);
            break;
        case STATUS_LED_BYE:
            writeRgb(36, 0, 48);
            break;
        default:
            break;
    }
}

void statusLedBegin() {
#if STATUS_LED_HAS_RGB
    if (PIN_STATUS_RGB >= 0) {
        pinMode(PIN_STATUS_RGB, OUTPUT);
    }
#endif
    mode = STATUS_LED_BOOT;
    phaseMs = millis();
    pulseUntilMs = millis() + 400;
    afterPulse = STATUS_LED_WIFI_WAIT;
    applySteady(STATUS_LED_BOOT);
}

void statusLedSet(StatusLedMode next) {
    mode = next;
    phaseMs = millis();
    pulseUntilMs = 0;
    if (next == STATUS_LED_STOP || next == STATUS_LED_CLEAR || next == STATUS_LED_BYE || next == STATUS_LED_BOOT) {
        pulseUntilMs = millis() + 350;
        afterPulse = (next == STATUS_LED_BYE) ? STATUS_LED_WIFI_OK : STATUS_LED_IDLE;
        if (next == STATUS_LED_BOOT) {
            afterPulse = STATUS_LED_WIFI_WAIT;
        }
        if (next == STATUS_LED_BYE) {
            afterPulse = STATUS_LED_WIFI_OK;
        }
        applySteady(next);
        return;
    }
    applySteady(next);
}

void statusLedMotion(double forward, double yaw) {
    // Forward → green, reverse → red, left yaw → blue tint, right yaw → yellow tint.
    double f = fabs(forward);
    double y = fabs(yaw);
    auto clampU8 = [](double v, double hi) -> uint8_t {
        if (v < 0) {
            v = 0;
        }
        if (v > hi) {
            v = hi;
        }
        return static_cast<uint8_t>(v);
    };
    uint8_t strength = clampU8(f / MAX_FORWARD_M_S * 80.0 + y / MAX_YAW_RAD_S * 40.0 + 20.0, 120.0);
    if (forward >= 0) {
        motionG = strength;
        motionR = clampU8(y / MAX_YAW_RAD_S * 50.0, 60.0);
        motionB = (yaw > 0) ? clampU8(y / MAX_YAW_RAD_S * 70.0, 80.0) : 0;
    } else {
        motionR = strength;
        motionG = 0;
        motionB = (yaw > 0) ? clampU8(y / MAX_YAW_RAD_S * 50.0, 60.0) : 0;
        if (yaw < 0) {
            motionG = clampU8(y / MAX_YAW_RAD_S * 40.0, 50.0);
        }
    }
    if (f < 1e-4 && y < 1e-4) {
        statusLedSet(STATUS_LED_STOP);
        return;
    }
    mode = STATUS_LED_MOTION;
    applySteady(STATUS_LED_MOTION);
    pulseUntilMs = millis() + 220;
    afterPulse = STATUS_LED_IDLE;
}

void statusLedPoll(unsigned long nowMs) {
    if (pulseUntilMs != 0 && (long)(nowMs - pulseUntilMs) >= 0) {
        pulseUntilMs = 0;
        mode = afterPulse;
        applySteady(mode);
        phaseMs = nowMs;
    }

    unsigned long elapsed = nowMs - phaseMs;
    switch (mode) {
        case STATUS_LED_WIFI_WAIT: {
            bool on = ((elapsed / 400) % 2) == 0;
            writeRgb(0, 0, on ? 36 : 0);
            break;
        }
        case STATUS_LED_WIFI_FAIL: {
            bool on = ((elapsed / 700) % 2) == 0;
            writeRgb(on ? 48 : 0, 0, 0);
            break;
        }
        case STATUS_LED_ESTOP: {
            bool on = ((elapsed / 120) % 2) == 0;
            writeRgb(on ? 80 : 0, 0, 0);
            break;
        }
        case STATUS_LED_MOTION:
            // Soft pulse while the motion cue is held.
            if (pulseUntilMs != 0) {
                writeRgb(motionR, motionG, motionB);
            }
            break;
        default:
            break;
    }
}
