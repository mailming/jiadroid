#pragma once

// First-boot Wi‑Fi fallback only. After the phone sends wifi.set over USB,
// credentials live in NVS and override these. Leave as placeholders to skip
// Wi‑Fi until the phone configures it.
#define WIFI_SSID "your-network"
#define WIFI_PASSWORD "your-password"

#define ROBOT_PORT 8765

// Same chassis numbers as jiadroid.sim.rover. Measure them on the real plate
// before trusting odometry.
constexpr double WHEEL_RADIUS_M = 0.0325;
constexpr double WHEEL_BASE_M = 0.15;
constexpr int ENCODER_SLOTS = 20;
constexpr int GEAR_RATIO = 48;
constexpr int TICKS_PER_REV = ENCODER_SLOTS * GEAR_RATIO;
constexpr double MAX_FORWARD_M_S = 0.40;
constexpr double MAX_YAW_RAD_S = 1.5;
constexpr double MAX_WHEEL_RAD_S = MAX_FORWARD_M_S / WHEEL_RADIUS_M;
constexpr double RANGE_MAX_M = 2.0;

// Phone standing on the top plate. Matches the `top` mount in the simulator.
constexpr double MOUNT_X_M = 0.06;
constexpr double MOUNT_Y_M = 0.0;
constexpr double MOUNT_Z_M = 0.0975;
constexpr double MOUNT_MAX_MASS_KG = 0.30;

// Header pins on the ESP32-S3-DevKitC-1. GPIO 19 and 20 are the USB
// connector, and GPIO 26–32 belong to the module's flash, so none of
// those are used here. Set a side to -1 if that wheel turns the wrong way.
constexpr int PIN_LEFT_A = 4;
constexpr int PIN_LEFT_B = 5;
constexpr int PIN_RIGHT_A = 6;
constexpr int PIN_RIGHT_B = 7;
constexpr int MOTOR_INVERT_LEFT = 1;
constexpr int MOTOR_INVERT_RIGHT = 1;

// Encoder channel A is the counted edge. Channel B sets the direction.
// Set a side to -1 if that encoder counts backward.
constexpr int PIN_LEFT_ENC_A = 15;
constexpr int PIN_LEFT_ENC_B = 16;
constexpr int PIN_RIGHT_ENC_A = 17;
constexpr int PIN_RIGHT_ENC_B = 18;
constexpr int ENC_SIGN_LEFT = 1;
constexpr int ENC_SIGN_RIGHT = 1;

// Switch to ground. Internal pull-up, so an open pin means not pressed.
constexpr int PIN_BUMP = 21;

// Optional HC-SR04. Leave both at -1 and range_front stays at RANGE_MAX_M.
constexpr int PIN_RANGE_TRIG = -1;
constexpr int PIN_RANGE_ECHO = -1;

// Onboard WS2812 RGB: GPIO 38 on DevKitC-1 v1.1, GPIO 48 on v1.0. Set -1 to disable.
constexpr int PIN_STATUS_RGB = 38;
