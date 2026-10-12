#include "chassis.h"

#include <Arduino.h>
#include <math.h>

#include "config.h"

static portMUX_TYPE tickMux = portMUX_INITIALIZER_UNLOCKED;
static volatile int32_t leftTicks = 0;
static volatile int32_t rightTicks = 0;

static double leftCommand = 0;
static double rightCommand = 0;
static int32_t leftTicksPrev = 0;
static int32_t rightTicksPrev = 0;
static double poseX = 0;
static double poseY = 0;
static double poseYaw = 0;
static double rangeM = RANGE_MAX_M;
static unsigned long rangeCheckedMs = 0;

static const int kLeftAChannel = 0;
static const int kLeftBChannel = 1;
static const int kRightAChannel = 2;
static const int kRightBChannel = 3;

static bool leftWasDriven = false;
static bool rightWasDriven = false;
static unsigned long leftKickUntilMs = 0;
static unsigned long rightKickUntilMs = 0;

void IRAM_ATTR leftEncoderIsr() {
    int direction = gpio_get_level((gpio_num_t)PIN_LEFT_ENC_B) ? 1 : -1;
    portENTER_CRITICAL_ISR(&tickMux);
    leftTicks += direction * ENC_SIGN_LEFT;
    portEXIT_CRITICAL_ISR(&tickMux);
}

void IRAM_ATTR rightEncoderIsr() {
    int direction = gpio_get_level((gpio_num_t)PIN_RIGHT_ENC_B) ? 1 : -1;
    portENTER_CRITICAL_ISR(&tickMux);
    rightTicks += direction * ENC_SIGN_RIGHT;
    portEXIT_CRITICAL_ISR(&tickMux);
}

static int32_t readTicks(volatile int32_t *ticks) {
    portENTER_CRITICAL(&tickMux);
    int32_t value = *ticks;
    portEXIT_CRITICAL(&tickMux);
    return value;
}

static int speedToDuty(double radPerSec, int invert, double *signedVel) {
    double velocity = radPerSec * invert;
    if (!isfinite(velocity)) {
        velocity = 0;
    }
    *signedVel = velocity;
    double magnitude = fabs(velocity);
    if (magnitude > MAX_WHEEL_RAD_S) {
        magnitude = MAX_WHEEL_RAD_S;
    }
    int duty = (int)lround(magnitude / MAX_WHEEL_RAD_S * 255.0);
    if (duty < 0) {
        return 0;
    }
    if (duty > 255) {
        return 255;
    }
    return duty;
}

static void driveChannels(int channelA, int channelB, double signedVel, int duty) {
    if (duty <= 0 || fabs(signedVel) < 1e-9) {
        ledcWrite(channelA, 0);
        ledcWrite(channelB, 0);
        return;
    }
    if (signedVel > 0) {
        ledcWrite(channelA, duty);
        ledcWrite(channelB, 0);
    } else {
        ledcWrite(channelA, 0);
        ledcWrite(channelB, duty);
    }
}

static void scaleDuties(int *leftDuty, int *rightDuty, double scale) {
    int left = (int)lround(*leftDuty * scale);
    int right = (int)lround(*rightDuty * scale);
    if (left > 255) {
        left = 255;
    }
    if (right > 255) {
        right = 255;
    }
    *leftDuty = left;
    *rightDuty = right;
}

// Drive both wheels together so min-PWM / kick boost keep any L/R ratio (yaw).
static void writeWheels(double leftRadS, double rightRadS, unsigned long nowMs) {
    double leftVel = 0;
    double rightVel = 0;
    int leftDuty = speedToDuty(leftRadS, MOTOR_INVERT_LEFT, &leftVel);
    int rightDuty = speedToDuty(rightRadS, MOTOR_INVERT_RIGHT, &rightVel);

    if (leftDuty == 0) {
        leftWasDriven = false;
        leftKickUntilMs = 0;
    }
    if (rightDuty == 0) {
        rightWasDriven = false;
        rightKickUntilMs = 0;
    }
    if (leftDuty == 0 && rightDuty == 0) {
        driveChannels(kLeftAChannel, kLeftBChannel, 0, 0);
        driveChannels(kRightAChannel, kRightBChannel, 0, 0);
        return;
    }

    bool sameDir =
        (leftRadS > 1e-6 && rightRadS > 1e-6) || (leftRadS < -1e-6 && rightRadS < -1e-6);
    if (sameDir && leftDuty > 0 && rightDuty > 0) {
        int lo = leftDuty < rightDuty ? leftDuty : rightDuty;
        int hi = leftDuty < rightDuty ? rightDuty : leftDuty;
        if (hi < MIN_MOTOR_DUTY) {
            scaleDuties(&leftDuty, &rightDuty, (double)MIN_MOTOR_DUTY / hi);
        } else if (lo < MIN_MOTOR_DUTY) {
            double scale = (double)MIN_MOTOR_DUTY / lo;
            if (hi * scale <= 255.0) {
                scaleDuties(&leftDuty, &rightDuty, scale);
            } else {
                scaleDuties(&leftDuty, &rightDuty, 255.0 / hi);
            }
        }
    } else {
        if (leftDuty > 0 && leftDuty < MIN_MOTOR_DUTY) {
            leftDuty = MIN_MOTOR_DUTY;
        }
        if (rightDuty > 0 && rightDuty < MIN_MOTOR_DUTY) {
            rightDuty = MIN_MOTOR_DUTY;
        }
    }

    if (leftDuty > 0 && !leftWasDriven) {
        leftWasDriven = true;
        leftKickUntilMs = nowMs + MOTOR_KICK_MS;
    }
    if (rightDuty > 0 && !rightWasDriven) {
        rightWasDriven = true;
        rightKickUntilMs = nowMs + MOTOR_KICK_MS;
    }
    bool kicking = (leftKickUntilMs != 0 && (long)(nowMs - leftKickUntilMs) < 0) ||
                   (rightKickUntilMs != 0 && (long)(nowMs - rightKickUntilMs) < 0);
    if (kicking && sameDir && leftDuty > 0 && rightDuty > 0) {
        int hi = leftDuty > rightDuty ? leftDuty : rightDuty;
        if (hi > 0 && hi < MOTOR_KICK_DUTY) {
            scaleDuties(&leftDuty, &rightDuty, (double)MOTOR_KICK_DUTY / hi);
        }
    } else if (kicking) {
        if (leftDuty > 0 && leftDuty < MOTOR_KICK_DUTY) {
            leftDuty = MOTOR_KICK_DUTY;
        }
        if (rightDuty > 0 && rightDuty < MOTOR_KICK_DUTY) {
            rightDuty = MOTOR_KICK_DUTY;
        }
    }

    driveChannels(kLeftAChannel, kLeftBChannel, leftVel, leftDuty);
    driveChannels(kRightAChannel, kRightBChannel, rightVel, rightDuty);
}

static void setupPwm(int channel, int pin) {
    // ~1 kHz: many L9110S modules stall or only hiss at 20 kHz.
    ledcSetup(channel, 1000, 8);
    ledcAttachPin(pin, channel);
    ledcWrite(channel, 0);
}

static double wrapPi(double angle) {
    const double turn = 2 * PI;
    angle = fmod(angle + PI, turn);
    if (angle < 0) {
        angle += turn;
    }
    return angle - PI;
}

static void integrateEncoders() {
    int32_t leftNow = readTicks(&leftTicks);
    int32_t rightNow = readTicks(&rightTicks);
    double leftDistance = (leftNow - leftTicksPrev) * (2 * PI / TICKS_PER_REV) * WHEEL_RADIUS_M;
    double rightDistance = (rightNow - rightTicksPrev) * (2 * PI / TICKS_PER_REV) * WHEEL_RADIUS_M;
    leftTicksPrev = leftNow;
    rightTicksPrev = rightNow;
    double forward = (leftDistance + rightDistance) / 2;
    double yaw = (rightDistance - leftDistance) / WHEEL_BASE_M;
    poseX += forward * cos(poseYaw);
    poseY += forward * sin(poseYaw);
    poseYaw = wrapPi(poseYaw + yaw);
}

static double readRange() {
    if (PIN_RANGE_TRIG < 0 || PIN_RANGE_ECHO < 0) {
        return RANGE_MAX_M;
    }
    digitalWrite(PIN_RANGE_TRIG, LOW);
    delayMicroseconds(2);
    digitalWrite(PIN_RANGE_TRIG, HIGH);
    delayMicroseconds(10);
    digitalWrite(PIN_RANGE_TRIG, LOW);
    unsigned long echoUs = pulseIn(PIN_RANGE_ECHO, HIGH, 12000);
    if (echoUs == 0) {
        return RANGE_MAX_M;
    }
    double meters = echoUs * 1e-6 * 343.0 / 2;
    if (meters < 0) {
        return 0;
    }
    if (meters > RANGE_MAX_M) {
        return RANGE_MAX_M;
    }
    return meters;
}

void chassisBegin() {
    setupPwm(kLeftAChannel, PIN_LEFT_A);
    setupPwm(kLeftBChannel, PIN_LEFT_B);
    setupPwm(kRightAChannel, PIN_RIGHT_A);
    setupPwm(kRightBChannel, PIN_RIGHT_B);

    pinMode(PIN_LEFT_ENC_A, INPUT_PULLUP);
    pinMode(PIN_LEFT_ENC_B, INPUT_PULLUP);
    pinMode(PIN_RIGHT_ENC_A, INPUT_PULLUP);
    pinMode(PIN_RIGHT_ENC_B, INPUT_PULLUP);
    attachInterrupt(digitalPinToInterrupt(PIN_LEFT_ENC_A), leftEncoderIsr, RISING);
    attachInterrupt(digitalPinToInterrupt(PIN_RIGHT_ENC_A), rightEncoderIsr, RISING);

    pinMode(PIN_BUMP, INPUT_PULLUP);
    if (PIN_RANGE_TRIG >= 0 && PIN_RANGE_ECHO >= 0) {
        pinMode(PIN_RANGE_TRIG, OUTPUT);
        pinMode(PIN_RANGE_ECHO, INPUT);
        digitalWrite(PIN_RANGE_TRIG, LOW);
    }
    chassisStopMotors();
}

void chassisSetWheels(double leftRadS, double rightRadS) {
    if (leftRadS > MAX_WHEEL_RAD_S) {
        leftRadS = MAX_WHEEL_RAD_S;
    } else if (leftRadS < -MAX_WHEEL_RAD_S) {
        leftRadS = -MAX_WHEEL_RAD_S;
    }
    if (rightRadS > MAX_WHEEL_RAD_S) {
        rightRadS = MAX_WHEEL_RAD_S;
    } else if (rightRadS < -MAX_WHEEL_RAD_S) {
        rightRadS = -MAX_WHEEL_RAD_S;
    }
    leftCommand = leftRadS;
    rightCommand = rightRadS;
}

void chassisStopMotors() {
    leftCommand = 0;
    rightCommand = 0;
    writeWheels(0, 0, millis());
}

double chassisLeft() { return leftCommand; }

double chassisRight() { return rightCommand; }

int32_t chassisLeftTicks() { return readTicks(&leftTicks); }

int32_t chassisRightTicks() { return readTicks(&rightTicks); }

void chassisZeroEncoders() {
    portENTER_CRITICAL(&tickMux);
    leftTicks = 0;
    rightTicks = 0;
    portEXIT_CRITICAL(&tickMux);
    leftTicksPrev = 0;
    rightTicksPrev = 0;
}

bool chassisBump() { return digitalRead(PIN_BUMP) == LOW; }

double chassisRange() { return rangeM; }

void chassisPoll(unsigned long nowMs) {
    integrateEncoders();
    if (nowMs - rangeCheckedMs >= 100) {
        rangeCheckedMs = nowMs;
        rangeM = readRange();
    }

    double left = leftCommand;
    double right = rightCommand;
    if (chassisBump()) {
        double forward = (left + right) / 2 * WHEEL_RADIUS_M;
        double yaw = (right - left) * WHEEL_RADIUS_M / WHEEL_BASE_M;
        if (forward > 0) {
            left = (0 - yaw * WHEEL_BASE_M / 2) / WHEEL_RADIUS_M;
            right = (0 + yaw * WHEEL_BASE_M / 2) / WHEEL_RADIUS_M;
        }
    }
    writeWheels(left, right, nowMs);
}

void chassisPose(double *x, double *y, double *yaw) {
    *x = poseX;
    *y = poseY;
    *yaw = poseYaw;
}

void chassisZeroPose() {
    poseX = 0;
    poseY = 0;
    poseYaw = 0;
}
