#include <Arduino.h>
#include <ArduinoBLE.h>
#include <Wire.h>
#include "LSM6DS3.h"
#include <NRF52_MBED_TimerInterrupt.h>
#ifndef USE_SHT
#define USE_SHT 1
#endif

#if USE_SHT
#include <DFRobot_SHT3x.h>
#endif
#include <Servo.h>
#include <math.h>
#include <string.h>
#include "imu_tsfresh_minimal_features.h"

static const int METHANE_PIN = A0;
static const int FAN_PIN = A1;
static const int SERVO_PIN = A2;

#include <Paper_scientific_reports_inferencing.h>

// DefiniciÃ³n de constantes para la configuraciÃ³n del timer y muestras
#define TIMER3_FREQ_HZ  10.0     // Frecuencia del timer: 10 Hz 
#define BUFFER_SIZE 2000         // TamaÃ±o del buffer FIFO para los sensores
#define BYTE_SIZE 32             // TamaÃ±o de Bytes que se envian (12 IMU + 16 SHT + 2 metano + 2 ventilador = 32)
#define COMMAND_SIZE 32          // TamaÃ±o maximo de comandos ASCII recibidos por BLE
#define ACTUATOR_STATUS_SIZE 2
#define FAN_ON_TIME_MS 40000UL
#define WAIT_BEFORE_SERVO_MS 5000UL
#define SERVO_ACTIVE_TIME_MS 5000UL
#define CLEAN_WEARME 20000UL
#define SERVO_HOME_DEG 0
#define SERVO_ACTIVE_DEG 7
#define SERVO_STEP_DEG 3
#define SERVO_STEP_INTERVAL_MS 20UL
#define SERVO_SETTLE_TIME_MS 120UL
#define SERVO_POSITION_UNKNOWN 255
#define METHANE_MEDIAN_SAMPLE_COUNT 5
#define METHANE_MEDIAN_SAMPLE_DELAY_US 500UL
#define METHANE_MAX_STEP 8
#define METHANE_SERVO_MAX_STEP 3
#define METHANE_SERVO_FILTER_MS 1000UL

constexpr size_t INFERENCE_FEATURE_COUNT = EI_TSFRESH_MINIMAL_TOTAL_FEATURES;
constexpr uint16_t INFERENCE_STRIDE_SAMPLES = 10;  // 10 muestras a 10 Hz = 1 prediccion/s
constexpr uint16_t INFERENCE_WARMUP_PRINT_INTERVAL_SAMPLES = 10;
constexpr float FEATURE_SCALER_MIN_STD = 1.0e-12f;
constexpr float ACTUATOR_TRIGGER_PROBABILITY = 0.70f;
constexpr bool IMU_AXIS_ROTATION_ENABLED = true;
static_assert(EI_TSFRESH_MINIMAL_TOTAL_FEATURES == EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE,
              "El extractor IMU debe generar la misma cantidad de features que espera el modelo");

// Fixed axis rotation calibrated against methane peaks in sessions 6 and 8.
// Matrix candidate: S6 gravity alignment + yaw 170 deg. In offline validation it
// also covered most methane rises in S8, so it is the fixed firmware candidate.
static const float IMU_AXIS_ROTATION[3][3] = {
  { -0.44260462f, -0.34811246f,  0.82638905f },
  { -0.48221718f, -0.68457716f, -0.54664495f },
  {  0.75602099f, -0.64044658f,  0.13513121f }
};

// Buffers FIFO para almacenar los datos de los sensores (IMUs)
int16_t IMU3_AccelX[BUFFER_SIZE], IMU3_AccelY[BUFFER_SIZE], IMU3_AccelZ[BUFFER_SIZE];
int16_t IMU3_GyroX[BUFFER_SIZE], IMU3_GyroY[BUFFER_SIZE], IMU3_GyroZ[BUFFER_SIZE];


uint16_t bufferIndex = 0;  // Ãndice de escritura del buffer circular

float inferenceFeatures[INFERENCE_FEATURE_COUNT];
uint32_t inferenceSamplesCollected = 0;
uint16_t samplesSinceLastInference = 0;
float lastPredictionValue = NAN;
EI_IMPULSE_ERROR lastInferenceError = EI_IMPULSE_OK;
bool actuatorTriggersEnabled = false;
bool actuatorTriggerArmed = true;

static const float featureScalerMean[INFERENCE_FEATURE_COUNT] = {
  3.56790472e+05f, 3.50216065e+03f, 3.56790472e+03f, 1.00000000e+02f,
  6.18798710e+02f, 9.27051835e+05f, 3.68423887e+03f, 5.60446390e+03f,
  5.61202037e+03f, 1.89241315e+03f, 2.81792130e+05f, 2.84954325e+03f,
  2.81792130e+03f, 1.00000000e+02f, 6.11432236e+02f, 9.09565740e+05f,
  2.99203572e+03f, 4.81465475e+03f, 4.88014439e+03f, 7.48274482e+02f,
  -6.25789269e+05f, -6.29098231e+03f, -6.25789269e+03f, 1.00000000e+02f,
  4.64833001e+02f, 5.38731812e+05f, 6.30637786e+03f, -4.52261294e+03f,
  7.72526483e+03f, -7.72470479e+03f, 1.33196676e+03f, -2.79027877e+01f,
  1.33196676e+01f, 1.00000000e+02f, 9.27524053e+02f, 2.11790852e+06f,
  9.34292360e+02f, 3.07305254e+03f, 3.41453610e+03f, -2.75005611e+03f,
  -7.05424732e+03f, -8.44061830e+01f, -7.05424732e+01f, 1.00000000e+02f,
  8.24140812e+02f, 1.61898609e+06f, 8.46584566e+02f, 2.90387134e+03f,
  3.20532988e+03f, -2.30112366e+03f, -4.93755182e+02f, -1.68107577e+01f,
  -4.93755182e+00f, 1.00000000e+02f, 8.58672111e+02f, 1.69316275e+06f,
  8.72855682e+02f, 2.50005718e+03f, 3.00027055e+03f, -2.44968263e+03f
};

static const float featureScalerStd[INFERENCE_FEATURE_COUNT] = {
  1.35574918e+05f, 1.45870070e+03f, 1.35574918e+03f, 0.00000000e+00f,
  7.37658452e+02f, 6.19906875e+06f, 1.38615862e+03f, 3.06871116e+03f,
  3.10926589e+03f, 2.18498787e+03f, 1.32737539e+05f, 1.36585322e+03f,
  1.32737539e+03f, 0.00000000e+00f, 7.31926472e+02f, 2.04068294e+06f,
  1.28836868e+03f, 3.77640950e+03f, 3.82695808e+03f, 3.02126556e+03f,
  9.76977648e+04f, 1.02249402e+03f, 9.76977648e+02f, 0.00000000e+00f,
  5.68033532e+02f, 2.30804883e+06f, 9.40232083e+02f, 2.66346425e+03f,
  2.45008225e+03f, 2.44959247e+03f, 1.21773468e+04f, 1.80654444e+02f,
  1.21773468e+02f, 0.00000000e+00f, 1.12143107e+03f, 3.66883421e+06f,
  1.12250278e+03f, 4.49857562e+03f, 4.73003700e+03f, 3.74387241e+03f,
  1.46085118e+04f, 1.46368906e+02f, 1.46085118e+02f, 0.00000000e+00f,
  9.69421485e+02f, 2.86419877e+06f, 9.63637779e+02f, 3.74348922e+03f,
  3.81393545e+03f, 2.67048379e+03f, 2.49640652e+04f, 3.16233885e+02f,
  2.49640652e+02f, 0.00000000e+00f, 9.77673235e+02f, 3.08471761e+06f,
  9.96810183e+02f, 3.03896642e+03f, 3.85219286e+03f, 3.45498379e+03f
};

// Servicios y caracterÃ­sticas BLE para enviar datos de sensores e inferencias
BLEService SensorService("645b8880-fe33-4f14-847f-c7f5f238690c");  // DefiniciÃ³n del servicio BLE
BLEFloatCharacteristic inferCharacteristic("7c0e56ae-dc0f-45e2-8b03-4a27a4b1f3cb", BLERead | BLENotify);
BLECharacteristic SensorCharacteristic("54190178-caf3-4b54-9152-c8c27cf089e5", BLERead | BLENotify, BYTE_SIZE);
BLECharacteristic CommandCharacteristic("54190179-caf3-4b54-9152-c8c27cf089e5", BLEWrite, COMMAND_SIZE);
BLECharacteristic ActuatorStatusCharacteristic("5419017a-caf3-4b54-9152-c8c27cf089e5", BLERead | BLENotify, ACTUATOR_STATUS_SIZE);


struct __attribute__((packed)) PacketStruct {
  uint8_t imu[12];     
  float   sht_ref_t;  
  float   sht_ref_h;  
  float   sht_fig_t;  
  float   sht_fig_h; 
  uint8_t methane_low; 
  uint8_t methane_high;
  uint8_t fan_on;
  uint8_t fan_reason;
};

union PacketUnion {
  PacketStruct data;
  uint8_t      bytes[sizeof(PacketStruct)];
};

static PacketUnion packet;
static_assert(sizeof(PacketStruct) == BYTE_SIZE, "PacketStruct debe medir BYTE_SIZE bytes");

// Objeto del timer para manejar interrupciones periÃ³dicas
NRF52_MBED_Timer myTimer(NRF_TIMER_3);

// Ticks pendientes generados por el timer (consumidos en loop)
volatile uint32_t pendingSampleTicks = 0;

enum ActuatorSequenceState : uint8_t {
  ACTUATOR_FAN_ON = 0,
  ACTUATOR_WAIT_BEFORE_SERVO,
  ACTUATOR_SERVO_ACTIVE
};

ActuatorSequenceState actuatorSequenceState = ACTUATOR_FAN_ON;
bool actuatorSequenceRunning = false;
uint32_t actuatorDeadlineMs = 0;
Servo cycleServo;
bool servoSignalAttached = false;
uint8_t lastServoAngle = SERVO_POSITION_UNKNOWN;
uint8_t targetServoAngle = SERVO_POSITION_UNKNOWN;
uint32_t servoDetachDeadlineMs = 0;
uint32_t servoStepDeadlineMs = 0;
bool servoMovementActive = false;
uint32_t methaneServoFilterUntilMs = 0;
uint16_t filteredMethaneValue = 0;
bool methaneFilterInitialized = false;
bool actuatorFanActive = false;
bool cleanFanActive = false;
bool constantFanEnabled = false;
uint32_t cleanFanDeadlineMs = 0;
bool bleServiceReady = false;
uint8_t lastActuatorFanOnStatus = 255;
uint8_t lastActuatorFanReasonStatus = 255;

enum FanReasonCode : uint8_t {
  FAN_REASON_OFF = 0,
  FAN_REASON_CONSTANT = 1,
  FAN_REASON_CLEANING = 2,
  FAN_REASON_MODEL = 3
};

// Direcciones y configuraciÃ³n del IMU
const uint8_t IMU1_ADDRESS = 0x6B;
const uint8_t IMU2_ADDRESS = 0x6A;
const uint8_t WHO_AM_I = 0x0F;
const uint8_t CTRL1_XL = 0x10;
const uint8_t CTRL2_G = 0x11;
const uint8_t OUTX_L_G = 0x22;
const uint8_t ACC_CONFIG = 0x4B;
const uint8_t GYRO_CONFIG = 0x44;


// Direcciones y configuraciÃ³n de los sensores SHT31
#if USE_SHT
const uint8_t SHT_REF = 0x44;   //Sensor de referencia
const uint8_t SHT_FIG = 0x45;   //Sensor del Figaro

// Instancias: mismo Wire, direcciones distintas
DFRobot_SHT3x sht_44(&Wire, SHT_REF /*ADR=GND*/, 4 /*RST no usado*/);
DFRobot_SHT3x sht_45(&Wire, SHT_FIG /*ADR=VDD*/, 4 /*RST no usado*/);
bool sht44Available = false;
bool sht45Available = false;
float lastShtRefTemperature = NAN;
float lastShtRefHumidity = NAN;
float lastShtFigTemperature = NAN;
float lastShtFigHumidity = NAN;
#endif

//IMU3
LSM6DS3Core myIMU(I2C_MODE, IMU2_ADDRESS);


// Manejador del timer: se ejecuta cada 0.1 segundos (10 Hz)
void timerHandler() {
  // SaturaciÃ³n para evitar overflow si loop se atrasa.
  if (pendingSampleTicks < BUFFER_SIZE) {
    pendingSampleTicks++;
  }
}


// FunciÃ³n para agregar nuevos datos a los buffers FIFO
void addToFIFO(int16_t *buffer, uint16_t index, int16_t newValue) {
  buffer[index] = newValue;  // Escribir todos los canales en el mismo Ã­ndice de muestra
}

int16_t floatToInt16Saturated(float value) {
  if (value > 32767.0f) {
    return 32767;
  }
  if (value < -32768.0f) {
    return -32768;
  }
  return (int16_t)lroundf(value);
}

void rotateImuVector(int16_t x, int16_t y, int16_t z,
                     int16_t *rotatedX, int16_t *rotatedY, int16_t *rotatedZ) {
  const float fx = (float)x;
  const float fy = (float)y;
  const float fz = (float)z;

  *rotatedX = floatToInt16Saturated(
    IMU_AXIS_ROTATION[0][0] * fx +
    IMU_AXIS_ROTATION[0][1] * fy +
    IMU_AXIS_ROTATION[0][2] * fz
  );
  *rotatedY = floatToInt16Saturated(
    IMU_AXIS_ROTATION[1][0] * fx +
    IMU_AXIS_ROTATION[1][1] * fy +
    IMU_AXIS_ROTATION[1][2] * fz
  );
  *rotatedZ = floatToInt16Saturated(
    IMU_AXIS_ROTATION[2][0] * fx +
    IMU_AXIS_ROTATION[2][1] * fy +
    IMU_AXIS_ROTATION[2][2] * fz
  );
}

void writeInt16LittleEndian(uint8_t *destination, int16_t value) {
  destination[0] = (uint8_t)(value & 0xFF);
  destination[1] = (uint8_t)((value >> 8) & 0xFF);
}

uint16_t currentMethaneValue() {
  return ((uint16_t)packet.data.methane_high << 8) | packet.data.methane_low;
}

int inferenceFeatureGetData(size_t offset, size_t length, float *out_ptr) {
  if (out_ptr == NULL || (offset + length) > INFERENCE_FEATURE_COUNT) {
    return -1;
  }

  memcpy(out_ptr, inferenceFeatures + offset, length * sizeof(float));
  return 0;
}

void printPredictionHeader() {
  Serial.println("prediction_header,millis,samples,methane,prediction,error,dsp_ms,inference_ms,anomaly_ms");
}

void resetInferenceState() {
  memset(inferenceFeatures, 0, sizeof(inferenceFeatures));
  inferenceSamplesCollected = 0;
  samplesSinceLastInference = 0;
  lastPredictionValue = NAN;
  lastInferenceError = EI_IMPULSE_OK;
  actuatorTriggerArmed = true;
}

bool manualActuatorPriorityActive() {
  return constantFanEnabled || cleanFanActive;
}

void applyInferenceFeatureStandardScaler() {
  for (size_t feature = 0; feature < INFERENCE_FEATURE_COUNT; ++feature) {
    const float stdDev = featureScalerStd[feature];

    if (fabsf(stdDev) <= FEATURE_SCALER_MIN_STD) {
      inferenceFeatures[feature] = 0.0f;
    } else {
      inferenceFeatures[feature] = (inferenceFeatures[feature] - featureScalerMean[feature]) / stdDev;
    }
  }
}

bool runInferenceFromImu(uint16_t newestSampleIndex) {
  ei_tsfresh_extract_imu_features_minimal_circular_i16(
    IMU3_AccelX,
    IMU3_AccelY,
    IMU3_AccelZ,
    IMU3_GyroX,
    IMU3_GyroY,
    IMU3_GyroZ,
    BUFFER_SIZE,
    newestSampleIndex,
    inferenceFeatures
  );
  applyInferenceFeatureStandardScaler();

  signal_t inferenceSignal;
  inferenceSignal.total_length = INFERENCE_FEATURE_COUNT;
  inferenceSignal.get_data = &inferenceFeatureGetData;

  ei_impulse_result_t result = { 0 };
  lastInferenceError = run_classifier(&inferenceSignal, &result, false);

  Serial.print(lastInferenceError == EI_IMPULSE_OK ? "prediction_csv," : "prediction_error,");
  Serial.print(millis());
  Serial.print(",");
  Serial.print(inferenceSamplesCollected);
  Serial.print(",");
  Serial.print(currentMethaneValue());
  Serial.print(",");

  if (lastInferenceError != EI_IMPULSE_OK) {
    lastPredictionValue = NAN;
    Serial.print("nan,");
    Serial.print((int)lastInferenceError);
    Serial.println(",0,0,0");
    inferCharacteristic.writeValue(lastPredictionValue);
    return false;
  }

  lastPredictionValue = result.classification[0].value;
  inferCharacteristic.writeValue(lastPredictionValue);

  Serial.print(lastPredictionValue, 6);
  Serial.print(",");
  Serial.print((int)lastInferenceError);
  Serial.print(",");
  Serial.print(result.timing.dsp);
  Serial.print(",");
  Serial.print(result.timing.classification);
  Serial.print(",");
  Serial.println(result.timing.anomaly);

  if (lastPredictionValue < ACTUATOR_TRIGGER_PROBABILITY) {
    actuatorTriggerArmed = true;
  }

  if (actuatorTriggersEnabled &&
      actuatorTriggerArmed &&
      lastPredictionValue >= ACTUATOR_TRIGGER_PROBABILITY &&
      !manualActuatorPriorityActive() &&
      !actuatorSequenceRunning) {
    actuatorTriggerArmed = false;
    startActuatorSequence();
  }

  return true;
}

void updateInferenceFromLatestSample(uint16_t newestSampleIndex) {
  inferenceSamplesCollected++;

  if (samplesSinceLastInference < UINT16_MAX) {
    samplesSinceLastInference++;
  }

  if (inferenceSamplesCollected < EI_TSFRESH_MINIMAL_SAMPLE_COUNT) {
    if ((inferenceSamplesCollected % INFERENCE_WARMUP_PRINT_INTERVAL_SAMPLES) == 0) {
      Serial.print("prediction_warmup,");
      Serial.print(inferenceSamplesCollected);
      Serial.print(",");
      Serial.println(EI_TSFRESH_MINIMAL_SAMPLE_COUNT - inferenceSamplesCollected);
    }
    return;
  }

  if (samplesSinceLastInference < INFERENCE_STRIDE_SAMPLES) {
    return;
  }

  samplesSinceLastInference = 0;
  runInferenceFromImu(newestSampleIndex);
}

void processSensorTick(bool notifyBle) {
  const uint16_t sampleIndex = bufferIndex;
  bufferIndex = (bufferIndex + 1) % BUFFER_SIZE;

  uploadIMU(sampleIndex);
  readMethaneSensor();
  readVarios();
  updateInferenceFromLatestSample(sampleIndex);

  if (notifyBle) {
    updatePacketActuatorStatus();
    SensorCharacteristic.writeValue(packet.bytes, BYTE_SIZE, false);
  }
}

bool processPendingSampleTicks(bool notifyBle) {
  uint32_t ticksToProcess = 0;
  noInterrupts();
  ticksToProcess = pendingSampleTicks;
  pendingSampleTicks = 0;
  interrupts();

  bool processedTicks = false;
  while (ticksToProcess--) {
    processedTicks = true;
    updateActuatorSequence();
    processSensorTick(notifyBle);
  }

  return processedTicks;
}

// FunciÃ³n para encender el LED rojo
void setLEDRed() {
  digitalWrite(LED_RED, LOW);
  digitalWrite(LED_BLUE, HIGH);  // Apagar el LED azul
}

// FunciÃ³n para encender el LED azul
void setLEDBlue() {
  digitalWrite(LED_RED, HIGH);
  digitalWrite(LED_BLUE, LOW);  // Apagar el LED rojo
}

bool fanOutputEnabled() {
  return constantFanEnabled || cleanFanActive || actuatorFanActive;
}

uint8_t currentFanReason() {
  if (constantFanEnabled) {
    return FAN_REASON_CONSTANT;
  }

  if (cleanFanActive) {
    return FAN_REASON_CLEANING;
  }

  if (actuatorFanActive) {
    return FAN_REASON_MODEL;
  }

  return FAN_REASON_OFF;
}

void updatePacketActuatorStatus() {
  packet.data.fan_on = fanOutputEnabled() ? 1 : 0;
  packet.data.fan_reason = packet.data.fan_on ? currentFanReason() : FAN_REASON_OFF;
}

void publishActuatorStatus(bool force) {
  if (!bleServiceReady) {
    updatePacketActuatorStatus();
    return;
  }

  updatePacketActuatorStatus();
  const uint8_t fanOn = packet.data.fan_on;
  const uint8_t fanReason = packet.data.fan_reason;

  if (!force &&
      fanOn == lastActuatorFanOnStatus &&
      fanReason == lastActuatorFanReasonStatus) {
    return;
  }

  uint8_t payload[ACTUATOR_STATUS_SIZE] = {fanOn, fanReason};
  ActuatorStatusCharacteristic.writeValue(payload, ACTUATOR_STATUS_SIZE);
  lastActuatorFanOnStatus = fanOn;
  lastActuatorFanReasonStatus = fanReason;
}

void applyFanOutput() {
  const bool fanEnabled = fanOutputEnabled();
  digitalWrite(FAN_PIN, fanEnabled ? HIGH : LOW);
  publishActuatorStatus(false);
}

void setActuatorFanActive(bool enabled) {
  actuatorFanActive = enabled;
  applyFanOutput();
}

void setCleanFanActive(bool enabled) {
  cleanFanActive = enabled;
  if (!enabled) {
    cleanFanDeadlineMs = 0;
  }
  applyFanOutput();
}

void resetBleCommandState() {
  constantFanEnabled = false;
  setCleanFanActive(false);
  actuatorTriggerArmed = true;
  applyFanOutput();
}

void attachServoSignal() {
  if (!servoSignalAttached) {
    cycleServo.attach(SERVO_PIN);
    servoSignalAttached = true;
  }
}

void detachServoSignal() {
  if (servoSignalAttached) {
    cycleServo.detach();
    servoSignalAttached = false;
  }
  servoDetachDeadlineMs = 0;
}

bool timeReached(uint32_t now, uint32_t deadline) {
  return (int32_t)(now - deadline) >= 0;
}

void writeServoAngle(uint8_t angle) {
  attachServoSignal();
  cycleServo.write(angle);
  lastServoAngle = angle;
}

void finishServoMovement(uint32_t now) {
  servoMovementActive = false;
  servoStepDeadlineMs = 0;
  targetServoAngle = lastServoAngle;
  servoDetachDeadlineMs = now + SERVO_SETTLE_TIME_MS;
}

void advanceServoStep(uint32_t now) {
  if (!servoMovementActive) {
    return;
  }

  if (lastServoAngle == SERVO_POSITION_UNKNOWN) {
    writeServoAngle(targetServoAngle);
    finishServoMovement(now);
    return;
  }

  const int16_t diff = (int16_t)targetServoAngle - (int16_t)lastServoAngle;
  if (diff == 0) {
    finishServoMovement(now);
    return;
  }

  const uint8_t step = abs(diff) > SERVO_STEP_DEG ? SERVO_STEP_DEG : abs(diff);
  const uint8_t nextAngle = diff > 0 ? lastServoAngle + step : lastServoAngle - step;
  writeServoAngle(nextAngle);

  if (nextAngle == targetServoAngle) {
    finishServoMovement(now);
  } else {
    servoStepDeadlineMs = now + SERVO_STEP_INTERVAL_MS;
  }
}

void updateServoSignal() {
  const uint32_t now = millis();

  if (servoMovementActive && timeReached(now, servoStepDeadlineMs)) {
    advanceServoStep(now);
    return;
  }

  if (servoSignalAttached && servoDetachDeadlineMs != 0 &&
      timeReached(now, servoDetachDeadlineMs)) {
    detachServoSignal();
  }
}

void moveServoTo(uint8_t angle) {
  if (!servoMovementActive && lastServoAngle == angle) {
    return;
  }

  const uint32_t now = millis();
  targetServoAngle = angle;
  servoDetachDeadlineMs = 0;
  methaneServoFilterUntilMs = now + METHANE_SERVO_FILTER_MS;

  if (lastServoAngle == SERVO_POSITION_UNKNOWN) {
    writeServoAngle(angle);
    finishServoMovement(now);
    return;
  }

  servoMovementActive = true;
  servoStepDeadlineMs = now;
  advanceServoStep(now);
}

void servoToActive() {
  moveServoTo(SERVO_ACTIVE_DEG);
}

void servoToHome() {
  moveServoTo(SERVO_HOME_DEG);
}

void startActuatorSequence() {
  if (actuatorSequenceRunning || manualActuatorPriorityActive()) {
    return;
  }

  actuatorSequenceRunning = true;
  actuatorSequenceState = ACTUATOR_FAN_ON;
  actuatorDeadlineMs = millis() + FAN_ON_TIME_MS;
  setActuatorFanActive(true);
  servoToHome();
  Serial.print("actuator_trigger,");
  Serial.print(millis());
  Serial.print(",");
  Serial.println(lastPredictionValue, 6);
}

void stopActuatorSequence() {
  actuatorSequenceRunning = false;
  actuatorSequenceState = ACTUATOR_FAN_ON;
  actuatorDeadlineMs = 0;
  setActuatorFanActive(false);
  servoToHome();
}

void advanceActuatorSequence() {
  switch (actuatorSequenceState) {
    case ACTUATOR_FAN_ON:
      setActuatorFanActive(false);
      actuatorSequenceState = ACTUATOR_WAIT_BEFORE_SERVO;
      actuatorDeadlineMs += WAIT_BEFORE_SERVO_MS;
      break;

    case ACTUATOR_WAIT_BEFORE_SERVO:
      servoToActive();
      actuatorSequenceState = ACTUATOR_SERVO_ACTIVE;
      actuatorDeadlineMs += SERVO_ACTIVE_TIME_MS;
      break;

    case ACTUATOR_SERVO_ACTIVE:
      servoToHome();
      setActuatorFanActive(false);
      actuatorSequenceRunning = false;
      actuatorDeadlineMs = 0;
      Serial.print("actuator_done,");
      Serial.println(millis());
      break;
  }
}

void updateCleaningCycle(uint32_t now) {
  if (!cleanFanActive || cleanFanDeadlineMs == 0) {
    return;
  }

  if (timeReached(now, cleanFanDeadlineMs)) {
    setCleanFanActive(false);
    actuatorTriggerArmed = true;
    Serial.print("ble_command_done,CLEAN,");
    Serial.println(millis());
  }
}

void updateActuatorSequence() {
  updateServoSignal();

  const uint32_t now = millis();
  updateCleaningCycle(now);

  if (!actuatorSequenceRunning) {
    return;
  }

  // Usa un deadline absoluto para evitar deriva acumulada por latencia de loop.
  uint8_t transitions = 0;
  while (actuatorSequenceRunning && timeReached(now, actuatorDeadlineMs) && transitions < 3) {
    advanceActuatorSequence();
    transitions++;
  }
}

void trimBleCommandLine(char *command) {
  size_t length = strlen(command);
  while (length > 0 && (command[length - 1] == '\n' || command[length - 1] == '\r')) {
    command[length - 1] = '\0';
    length--;
  }
}

void startCleaningCommand() {
  stopActuatorSequence();
  servoToHome();
  cleanFanDeadlineMs = millis() + CLEAN_WEARME;
  setCleanFanActive(true);
  actuatorTriggerArmed = true;
  Serial.print("ble_command,CLEAN,");
  Serial.println(millis());
}

void setConstantFanCommand(bool enabled) {
  if (enabled) {
    constantFanEnabled = true;
    setCleanFanActive(false);
    stopActuatorSequence();
    servoToHome();
    actuatorTriggerArmed = false;
  } else {
    constantFanEnabled = false;
    actuatorTriggerArmed = true;
  }

  applyFanOutput();
  Serial.print("ble_command,");
  Serial.print(enabled ? "FAN_CONSTANT_ON" : "FAN_CONSTANT_OFF");
  Serial.print(",");
  Serial.println(millis());
}

void handleBleCommand(const char *command) {
  if (strcmp(command, "CLEAN") == 0) {
    startCleaningCommand();
  } else if (strcmp(command, "FAN_CONSTANT_ON") == 0) {
    setConstantFanCommand(true);
  } else if (strcmp(command, "FAN_CONSTANT_OFF") == 0) {
    setConstantFanCommand(false);
  } else {
    Serial.print("ble_command_unknown,");
    Serial.println(command);
  }
}

void processBleCommands() {
  if (!CommandCharacteristic.written()) {
    return;
  }

  char command[COMMAND_SIZE + 1];
  const int valueLength = CommandCharacteristic.valueLength();
  const int bytesToRead = valueLength < COMMAND_SIZE ? valueLength : COMMAND_SIZE;
  const int bytesRead = CommandCharacteristic.readValue((uint8_t *)command, bytesToRead);
  command[bytesRead] = '\0';

  trimBleCommandLine(command);
  handleBleCommand(command);
}

bool methaneServoFilterActive() {
  if (methaneServoFilterUntilMs == 0) {
    return false;
  }

  const uint32_t now = millis();
  if (timeReached(now, methaneServoFilterUntilMs)) {
    methaneServoFilterUntilMs = 0;
    return false;
  }

  return true;
}

uint16_t readMethaneMedian() {
  uint16_t samples[METHANE_MEDIAN_SAMPLE_COUNT];

  for (uint8_t i = 0; i < METHANE_MEDIAN_SAMPLE_COUNT; i++) {
    samples[i] = (uint16_t)analogRead(METHANE_PIN);
    if (i + 1 < METHANE_MEDIAN_SAMPLE_COUNT) {
      delayMicroseconds(METHANE_MEDIAN_SAMPLE_DELAY_US);
    }
  }

  for (uint8_t i = 1; i < METHANE_MEDIAN_SAMPLE_COUNT; i++) {
    uint16_t value = samples[i];
    int8_t j = i - 1;
    while (j >= 0 && samples[j] > value) {
      samples[j + 1] = samples[j];
      j--;
    }
    samples[j + 1] = value;
  }

  return samples[METHANE_MEDIAN_SAMPLE_COUNT / 2];
}

uint16_t filterMethaneValue(uint16_t rawValue, uint16_t maxStep) {
  if (!methaneFilterInitialized) {
    filteredMethaneValue = rawValue;
    methaneFilterInitialized = true;
    return filteredMethaneValue;
  }

  const int32_t diff = (int32_t)rawValue - (int32_t)filteredMethaneValue;
  if (diff > (int32_t)maxStep) {
    filteredMethaneValue += maxStep;
  } else if (diff < -(int32_t)maxStep) {
    filteredMethaneValue -= maxStep;
  } else {
    filteredMethaneValue = rawValue;
  }

  return filteredMethaneValue;
}

void readMethaneSensor() {
  const uint16_t rawMethaneValue = readMethaneMedian();
  const uint16_t maxStep = methaneServoFilterActive() ? METHANE_SERVO_MAX_STEP : METHANE_MAX_STEP;
  const uint16_t methaneValue = filterMethaneValue(rawMethaneValue, maxStep);
  packet.data.methane_low = methaneValue & 0xFF;        // Asignar el byte menos significativo
  packet.data.methane_high = (methaneValue >> 8) & 0xFF; // Asignar el byte mÃ¡s significativo
}


// Inicializa el IMU en la direcciÃ³n especificada
bool initIMU(uint8_t address) {
  Wire.beginTransmission(address);
  Wire.write(WHO_AM_I);
  Wire.endTransmission();
  Wire.requestFrom(address, 1);
  
  // Verificar que el dispositivo estÃ© conectado y responda correctamente
  if (Wire.available() && Wire.read() == 0x69) {
    // Configurar el acelerÃ³metro
    Wire.beginTransmission(address);
    Wire.write(CTRL1_XL);
    Wire.write(ACC_CONFIG);
    Wire.endTransmission();
    
    // Configurar el giroscopio
    Wire.beginTransmission(address);
    Wire.write(CTRL2_G);
    Wire.write(GYRO_CONFIG);
    Wire.endTransmission();
    
    return true;  // InicializaciÃ³n exitosa
  }
  return false;  // InicializaciÃ³n fallida
}

// FunciÃ³n para leer todos los IMUs
void uploadIMU(uint16_t sampleIndex) {
    // Leer Accel (0x28, 6 bytes)
    myIMU.readRegisterRegion(&packet.bytes[0], LSM6DS3_ACC_GYRO_OUTX_L_XL, 6);
    // Leer Gyro (0x22, 6 bytes)
    myIMU.readRegisterRegion(&packet.bytes[6], LSM6DS3_ACC_GYRO_OUTX_L_G, 6);

    int16_t accelX = (int16_t)(packet.bytes[0] | (packet.bytes[1] << 8));
    int16_t accelY = (int16_t)(packet.bytes[2] | (packet.bytes[3] << 8));
    int16_t accelZ = (int16_t)(packet.bytes[4] | (packet.bytes[5] << 8));
    int16_t gyroX = (int16_t)(packet.bytes[6] | (packet.bytes[7] << 8));
    int16_t gyroY = (int16_t)(packet.bytes[8] | (packet.bytes[9] << 8));
    int16_t gyroZ = (int16_t)(packet.bytes[10] | (packet.bytes[11] << 8));

    if (IMU_AXIS_ROTATION_ENABLED) {
      rotateImuVector(accelX, accelY, accelZ, &accelX, &accelY, &accelZ);
      rotateImuVector(gyroX, gyroY, gyroZ, &gyroX, &gyroY, &gyroZ);

      writeInt16LittleEndian(&packet.bytes[0], accelX);
      writeInt16LittleEndian(&packet.bytes[2], accelY);
      writeInt16LittleEndian(&packet.bytes[4], accelZ);
      writeInt16LittleEndian(&packet.bytes[6], gyroX);
      writeInt16LittleEndian(&packet.bytes[8], gyroY);
      writeInt16LittleEndian(&packet.bytes[10], gyroZ);
    }

    addToFIFO(IMU3_AccelX, sampleIndex, accelX);
    addToFIFO(IMU3_AccelY, sampleIndex, accelY);
    addToFIFO(IMU3_AccelZ, sampleIndex, accelZ);
    addToFIFO(IMU3_GyroX,  sampleIndex, gyroX);
    addToFIFO(IMU3_GyroY,  sampleIndex, gyroY);
    addToFIFO(IMU3_GyroZ,  sampleIndex, gyroZ);
}

// FunciÃ³n para leer todos los IMUs
void readVarios() {

#if USE_SHT
  readSHTRef();    // Leer los datos SHT31 REF
  readSHTFig();    // Leer los datos SHT31 FIG
#endif
  
}

void setSHTUnavailable() {
  packet.data.sht_ref_t = NAN;
  packet.data.sht_ref_h = NAN;
  packet.data.sht_fig_t = NAN;
  packet.data.sht_fig_h = NAN;
  
}

// FunciÃ³n para leer datos de un IMU especÃ­fico
void readIMU(uint8_t address, uint8_t* buffer) {
  Wire.beginTransmission(address);
  Wire.write(OUTX_L_G);  // DirecciÃ³n del registro desde el que se leerÃ¡n los datos
  Wire.endTransmission(false);
  
  // Solicitar 12 bytes de datos del IMU (acelerÃ³metro y giroscopio)
  Wire.requestFrom(address, 12);
  int i = 0;
  while (Wire.available() && i < 12) {
    buffer[i++] = Wire.read();  // Leer los datos y almacenarlos en el buffer
  }
}

// Helper para inicializar un sensor
#if USE_SHT
bool i2cDevicePresent(uint8_t address) {
  Wire.beginTransmission(address);
  return Wire.endTransmission() == 0;
}

bool initSHT(DFRobot_SHT3x& dev, const char* name) {
  const uint8_t address = (&dev == &sht_44) ? SHT_REF : SHT_FIG;
  if (!i2cDevicePresent(address)) {
    Serial.print("[");
    Serial.print(name);
    Serial.print("] sin ACK en 0x");
    Serial.println(address, HEX);
    return false;
  }

  if (dev.begin() != 0) {
    Serial.print("[");
    Serial.print(name);
    Serial.println("] no responde");
    return false;
  }

  Serial.print("[");
  Serial.print(name);
  Serial.print("] SN: ");
  Serial.println(dev.readSerialNumber());
  if (!dev.softReset()) {
    Serial.print("[");
    Serial.print(name);
    Serial.println("] soft reset fallo, se intenta continuar");
  }
  delay(15);

  // 10 Hz y baja repetibilidad para reducir autocalentamiento
  if (!dev.startPeriodicMode(DFRobot_SHT3x::eMeasureFreq_10Hz,
                             DFRobot_SHT3x::eRepeatability_Low)) {
    Serial.print("[");
    Serial.print(name);
    Serial.println("] error modo periodico");
    return false;
  }

  Serial.print("[");
  Serial.print(name);
  Serial.println("] OK: 10 Hz, Low");
  return true;
}

void readSHTSensor(DFRobot_SHT3x& dev, bool available, float *temperature, float *humidity, const char* name) {
  if (!available) {
    *temperature = NAN;
    *humidity = NAN;
    return;
  }

  DFRobot_SHT3x::sRHAndTemp_t measurement = dev.readTemperatureAndHumidity();
  if (measurement.ERR != 0 || isnan(measurement.TemperatureC) || isnan(measurement.Humidity)) {
    *temperature = NAN;
    *humidity = NAN;
    Serial.print("sht_read_error,");
    Serial.println(name);
    return;
  }

  *temperature = measurement.TemperatureC;
  *humidity = measurement.Humidity;
}

void writeSHTValuesOrLast(
  float temperature,
  float humidity,
  float *lastTemperature,
  float *lastHumidity,
  float *packetTemperature,
  float *packetHumidity
) {
  if (!isnan(temperature) && !isnan(humidity)) {
    *lastTemperature = temperature;
    *lastHumidity = humidity;
  }

  if (!isnan(*lastTemperature) && !isnan(*lastHumidity)) {
    *packetTemperature = *lastTemperature;
    *packetHumidity = *lastHumidity;
  } else {
    *packetTemperature = NAN;
    *packetHumidity = NAN;
  }
}


// FunciÃ³n para cargar 4 bytes de datos de juguete para SHT31 REF
void readSHTRef() {
    float temp = NAN;
    float hum = NAN;
    readSHTSensor(sht_44, sht44Available, &temp, &hum, "SHT_0x44");
    
    //Serial.println("soy el sensor de referencia");
    //Serial.print("Temp: ");
    //Serial.print(temp);
    //Serial.print(" hum: ");
    //Serial.println(hum);
    writeSHTValuesOrLast(
      temp,
      hum,
      &lastShtRefTemperature,
      &lastShtRefHumidity,
      &packet.data.sht_ref_t,
      &packet.data.sht_ref_h
    );
}

// FunciÃ³n para cargar 4 bytes de datos de juguete para SHT31 Figaro
void readSHTFig() {
    float temp = NAN;
    float hum = NAN;
    readSHTSensor(sht_45, sht45Available, &temp, &hum, "SHT_0x45");

    //Serial.println("soy el sensor del figaro");
    //Serial.print("Temp: ");
    //Serial.print(temp);
    //Serial.print(" hum: ");
    //Serial.println(hum);

    writeSHTValuesOrLast(
      temp,
      hum,
      &lastShtFigTemperature,
      &lastShtFigHumidity,
      &packet.data.sht_fig_t,
      &packet.data.sht_fig_h
    );
}
#endif

/**
 * @brief      FunciÃ³n de configuraciÃ³n de Arduino (setup)
 */
void setup() {
  // InicializaciÃ³n de la comunicaciÃ³n serial
  Serial.begin(115200);
  resetInferenceState();
  printPredictionHeader();

  // ConfiguraciÃ³n de pines de actuadores y salidas auxiliares
  pinMode(FAN_PIN, OUTPUT);
  pinMode(D8, OUTPUT);
  pinMode(P0_13, OUTPUT);

  pinMode(LED_RED, OUTPUT);
  pinMode(LED_BLUE, OUTPUT);
  servoToHome();
  setLEDRed();  // Encender el LED rojo inicialmente

  digitalWrite(FAN_PIN, LOW);
  digitalWrite(D8, HIGH);
  digitalWrite(P0_13, LOW);
  // Control por transistor: ventilador apagado hasta conexion BLE.
  // El ventilador solo se habilita cuando la app BLE se conecta.
  stopActuatorSequence();
  
  memset(IMU3_AccelX, 0, sizeof(IMU3_AccelX));
  memset(IMU3_AccelY, 0, sizeof(IMU3_AccelY));
  memset(IMU3_AccelZ, 0, sizeof(IMU3_AccelZ));
  memset(IMU3_GyroX, 0, sizeof(IMU3_GyroX));
  memset(IMU3_GyroY, 0, sizeof(IMU3_GyroY));
  memset(IMU3_GyroZ, 0, sizeof(IMU3_GyroZ));
  setSHTUnavailable();
  
  // InicializaciÃ³n de I2C para comunicaciÃ³n con IMUs
  Wire.begin();
  Wire.setClock(400000);  // ConfiguraciÃ³n de la velocidad del reloj I2C

  // InicializaciÃ³n del servicio BLE
  
  if (!BLE.begin()) {
      digitalWrite(LED_RED, LOW);
      digitalWrite(LED_BLUE, LOW); 
    while (1){
        Serial.println("Â¡FallÃ³ el inicio de BLE!");
        delay(250);
    }
  }
  

  // ConfiguraciÃ³n de los servicios y caracterÃ­sticas BLE
  BLE.setLocalName("SensorHub");
  BLE.setAdvertisedService(SensorService);
  SensorService.addCharacteristic(inferCharacteristic);
  SensorService.addCharacteristic(SensorCharacteristic);
  SensorService.addCharacteristic(CommandCharacteristic);
  SensorService.addCharacteristic(ActuatorStatusCharacteristic);
  BLE.addService(SensorService);
  bleServiceReady = true;
  publishActuatorStatus(true);
  BLE.advertise();


  // InicializaciÃ³n de los IMUs

  if (myIMU.beginCore() != 0) {
    digitalWrite(LED_RED, LOW);
    digitalWrite(LED_BLUE, LOW); 
    while (1){
      Serial.println("Â¡FallÃ³ la inicializaciÃ³n de IMU!");
      delay(250);
    }
    
  }

#if USE_SHT
  Serial.println("\n=== Doble SHT3x: 0x44 y 0x45 ===");
  sht44Available = initSHT(sht_44, "SHT_0x44");
  sht45Available = initSHT(sht_45, "SHT_0x45");

  if (!sht44Available && !sht45Available) {
      digitalWrite(LED_RED, LOW);
       digitalWrite(LED_BLUE, LOW);
    while (1){
         Serial.println("Â¡FallÃ³ la inicializaciÃ³n de los sensores SHT31!");
         delay(250);
    }
	     
	  }
#else
  Serial.println("SHT31 deshabilitado: se envian NaN en los campos SHT.");
#endif


  // Configure the accelerometer for the third IMU
  uint8_t dataToWrite = 0;
  dataToWrite |= LSM6DS3_ACC_GYRO_BW_XL_400Hz;
  dataToWrite |= LSM6DS3_ACC_GYRO_FS_XL_4g;
  dataToWrite |= LSM6DS3_ACC_GYRO_ODR_XL_833Hz;
  myIMU.writeRegister(LSM6DS3_ACC_GYRO_CTRL1_XL, dataToWrite);

  // Configure the gyroscope for the third IMU
  dataToWrite = 0;
  dataToWrite |= LSM6DS3_ACC_GYRO_FS_G_500dps;
  dataToWrite |= LSM6DS3_ACC_GYRO_ODR_G_833Hz;
  myIMU.writeRegister(LSM6DS3_ACC_GYRO_CTRL2_G, dataToWrite);

  // ConfiguraciÃ³n del timer para interrupciones periÃ³dicas
  myTimer.attachInterrupt(TIMER3_FREQ_HZ, timerHandler);
}

/**
* @brief      FunciÃ³n principal de Arduino (loop)
*/
void loop() {
  updateActuatorSequence();

  BLEDevice central = BLE.central();  // Detectar si hay un dispositivo central BLE conectado
  if (central) {
    noInterrupts();
    pendingSampleTicks = 0;  // Descartar backlog previo a la conexiÃ³n
    interrupts();

    actuatorTriggersEnabled = true;
    setLEDBlue();  // Cambiar el LED a azul cuando un dispositivo BLE estÃ¡ conectado
    publishActuatorStatus(true);
    while (central.connected()) {
      updateActuatorSequence();
      BLE.poll();
      processBleCommands();

      if (!processPendingSampleTicks(true)) {
        delay(1);
      }
    }
    actuatorTriggersEnabled = false;
    stopActuatorSequence();
    resetBleCommandState();
    setLEDRed();  // Cambiar el LED a rojo cuando el dispositivo BLE se desconecta
  } else {
    actuatorTriggersEnabled = false;
    stopActuatorSequence();
    resetBleCommandState();
    if (!processPendingSampleTicks(false)) {
      delay(1);
    }
  }
}
