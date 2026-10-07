#pragma once

// Onboard WS2812 RGB feedback for debugging commands and link state.

enum StatusLedMode {
    STATUS_LED_OFF = 0,
    STATUS_LED_BOOT,       // brief white
    STATUS_LED_WIFI_WAIT,  // slow blue blink
    STATUS_LED_WIFI_OK,    // dim blue
    STATUS_LED_WIFI_FAIL,  // slow red blink
    STATUS_LED_IDLE,       // dim green — linked, waiting
    STATUS_LED_MOTION,     // green / cyan / magenta by direction (pulse)
    STATUS_LED_STOP,       // white flash
    STATUS_LED_ESTOP,      // fast red blink
    STATUS_LED_CLEAR,      // green flash
    STATUS_LED_BYE,        // purple flash
};

void statusLedBegin();
void statusLedSet(StatusLedMode mode);
// Motion cue: forward and yaw in robot units. Shows briefly, then returns to idle.
void statusLedMotion(double forward, double yaw);
void statusLedPoll(unsigned long nowMs);
