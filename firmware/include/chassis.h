#pragma once

#include <stdint.h>

void chassisBegin();
void chassisSetWheels(double leftRadS, double rightRadS);
void chassisStopMotors();
double chassisLeft();
double chassisRight();
int32_t chassisLeftTicks();
int32_t chassisRightTicks();
void chassisZeroEncoders();
bool chassisBump();
double chassisRange();
void chassisPoll(unsigned long nowMs);
void chassisPose(double *x, double *y, double *yaw);
void chassisZeroPose();
