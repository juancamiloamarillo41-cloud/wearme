# WearMe — TinyML para monitoreo de metano entérico en ganado

<p align="center">
  <img src="android/WearMe_01/app/src/main/res/drawable/logo_app.png" alt="Logo de WearMe" width="120">
</p>

<p align="center">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack_Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white">
  <img alt="TinyML" src="https://img.shields.io/badge/TinyML-Edge_Impulse-2B6CB0?style=flat-square">
  <img alt="Hardware" src="https://img.shields.io/badge/Hardware-nRF52840-00A9CE?style=flat-square">
  <img alt="License" src="https://img.shields.io/badge/License-MIT-2F855A?style=flat-square">
</p>

**WearMe** es un dispositivo *wearable* de extremo a extremo para la **detección y monitoreo en tiempo real de metano entérico (eructos)** en ganado bovino, desarrollado en el **Instituto iOMICAS** de la Pontificia Universidad Javeriana.

El sistema integra **hardware propio (PCB)**, **firmware con inferencia de Machine Learning embebido (TinyML)** y una **aplicación móvil Android** que recibe los datos por Bluetooth Low Energy (BLE) y graba video sincronizado de cada sesión para validación.

> El metano entérico del ganado es una de las mayores fuentes de gases de efecto invernadero del sector agropecuario. WearMe busca medirlo en campo, de forma no invasiva y en tiempo real.

## Resultados clave

| Indicador | Resultado |
|---|---|
| Datos de validación | Aproximadamente **8 horas** de sesiones reales de campo |
| Desempeño del modelo | Cerca de **70 % de precisión** en condiciones de campo |
| Inferencia | Alrededor de **1 predicción por segundo**, ejecutada en el dispositivo |
| Hardware | Validación entre **2 generaciones** de hardware |
| Aplicación móvil | Adquisición BLE, visualización y video sincronizado en Android |

## Mi contribución

- Desarrollo de la aplicación Android en Kotlin y Jetpack Compose, incluyendo comunicación BLE/GATT, visualización de sensores, historial de sesiones y grabación de video con CameraX.
- Procesamiento de datos de IMU y apoyo en la preparación y validación del modelo TinyML.
- Integración de la inferencia de Edge Impulse en el XIAO nRF52840 y verificación del modelo con datos reales de campo.

---

## 🧩 Arquitectura del sistema

```
┌──────────────────────────┐        BLE         ┌───────────────────────────┐
│   Dispositivo WearMe      │  ───────────────►  │   App Android (Kotlin)    │
│   (Seeed XIAO nRF52840)   │   32 B @ 10 Hz     │   Jetpack Compose         │
│                           │                    │                           │
│  • IMU LSM6DS3 (acc+gyro) │                    │  • Escaneo / conexión BLE │
│  • Sensor de metano (MQ)  │                    │  • Dashboard en vivo      │
│  • SHT3x (temp / humedad) │                    │  • Historial de sesiones  │
│  • Inferencia TinyML      │  ◄───── comandos   │  • Grabación de video      │
│  • Actuadores: fan, servo │                    │    sincronizada (CameraX) │
└──────────────────────────┘                    └───────────────────────────┘
```

---

## 🔧 Firmware (`/firmware`)

Firmware en C/C++ (framework Arduino) para el microcontrolador **Seeed Studio XIAO nRF52840** (Nordic nRF52840).

| Subsistema | Detalle |
|---|---|
| **IMU** | LSM6DS3 de 6 ejes (acelerómetro + giroscopio), muestreo a 10 Hz |
| **Gas** | Sensor de metano analógico (entrada `A0`) con filtrado por mediana |
| **Ambiente** | Sensor de temperatura y humedad SHT3x (DFRobot, I²C) |
| **Actuadores** | Ventilador (`A1`) y servomotor (`A2`) para limpieza/respuesta del sensor |
| **Comunicación** | BLE con `ArduinoBLE`; paquetes de **32 bytes** (12 IMU + 16 SHT + 2 metano + 2 ventilador) |
| **Temporización** | Timer por interrupción a 10 Hz (`NRF52_MBED_TimerInterrupt`) + buffers FIFO circulares |

### 🤖 ML embebido (TinyML)

El firmware ejecuta **inferencia a bordo (~1 predicción/segundo)** sin depender de la nube:

- Extracción de características en C equivalente a `tsfresh.feature_extraction.MinimalFCParameters()`
  sobre ventanas de **100 muestras × 6 canales de la IMU** → **60 features** (ver [`firmware/README_features_c.md`](firmware/README_features_c.md)).
- Clasificador exportado con **Edge Impulse**, integrado en el sketch.
- Calibración de orientación de la IMU mediante una **matriz de rotación de ejes** ajustada contra los picos de metano de las sesiones de prueba.
- Disparo de actuadores cuando la probabilidad de inferencia supera un umbral (`0.70`).

---

## 📱 Aplicación Android (`/android/WearMe_01`)

Aplicación nativa moderna en **Kotlin + Jetpack Compose (Material 3)**.

| Componente | Descripción |
|---|---|
| `BleConnectionManager.kt` | Gestor BLE/GATT propio: escaneo, conexión, parseo de paquetes binarios y exposición de estado con `StateFlow` (Kotlin Coroutines/Flow) |
| `SensorDashboard.kt` | Visualización en tiempo real de IMU, metano y variables ambientales |
| `ObservationHistory.kt` | Historial de observaciones/sesiones |
| `SessionVideoRecorder.kt` | Grabación de video sincronizada con la sesión usando **CameraX** (`camera2`, `video`, `lifecycle`, `view`) para validar las detecciones |
| `SensorData.kt` | Modelo de datos de los paquetes de sensores |

**Stack:** Kotlin · Jetpack Compose · Material 3 · CameraX · BLE (GATT) · Coroutines / Flow · `minSdk 24`, `targetSdk 36`.

### Compilar

```bash
cd android/WearMe_01
./gradlew assembleDebug      # genera el APK de depuración
```

> Nota: `local.properties` (ruta del Android SDK) no se versiona; Android Studio lo regenera al abrir el proyecto.

---

## 📈 Resultados y validación

- **Tarea:** clasificación **binaria** (evento de eructo vs. no evento) a partir de la señal de la IMU, altamente dependiente del movimiento del animal.
- **Datos:** ~**8 horas** de sesiones reales de campo con ganado bovino.
- **Inferencia on-device en tiempo real** (~1 predicción/s) sobre el nRF52840, sin dependencia de la nube.
- **Validación cruzada de hardware:** el modelo se entrenó con un dispositivo de generación anterior y se verificó en el dispositivo actual, confirmando su generalización entre dos generaciones de hardware.
- **Precisión:** ~**70 %** en condiciones de campo (señal ruidosa, sujeto en movimiento) — un escenario considerablemente más exigente que un dataset controlado de laboratorio.

---

## 📊 Datos

Las sesiones de captura en campo (CSV con datos de IMU, metano y ambiente etiquetados por animal) se encuentran en el repositorio relacionado [`Codigo_tesis`](https://github.com/juancamiloamarillo41-cloud/Codigo_tesis).

---

## 🛠️ Tecnologías

`Kotlin` · `Jetpack Compose` · `CameraX` · `BLE` · `C/C++` · `Arduino` · `Nordic nRF52840` · `Edge Impulse` · `TinyML` · `tsfresh` · `Diseño de PCB` · `Sistemas embebidos` · `IoT`

## 👤 Autor

**Juan Camilo Amarillo Morales** — Estudiante de Ingeniería Mecatrónica · Instituto iOMICAS, Pontificia Universidad Javeriana
📧 juancamiloamarillo41@gmail.com

## 📄 Licencia

Publicado con fines académicos y de portafolio bajo licencia [MIT](LICENSE). Los datos de investigación pueden estar sujetos a las políticas del Instituto iOMICAS.
