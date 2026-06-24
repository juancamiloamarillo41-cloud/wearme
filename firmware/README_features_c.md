# Extractor C de features IMU

Archivo principal para Arduino:

`BLEWear_INF_test_nordic/imu_tsfresh_minimal_features.h`

Este header traduce `tsfresh.feature_extraction.MinimalFCParameters()` para una
ventana de 100 muestras y 6 canales de la IMU de la XIAO nRF:

1. `acc_x`
2. `acc_y`
3. `acc_z`
4. `gyro_x`
5. `gyro_y`
6. `gyro_z`

Por cada canal entrega 10 features, en el mismo orden de `tsfresh`:

1. `sum_values`
2. `median`
3. `mean`
4. `length`
5. `standard_deviation`
6. `variance`
7. `root_mean_square`
8. `maximum`
9. `absolute_maximum`
10. `minimum`

Total: `6 * 10 = 60` floats.

Uso directo con buffers contiguos de 100 muestras:

```c
float features[EI_TSFRESH_MINIMAL_TOTAL_FEATURES];

ei_tsfresh_extract_imu_features_minimal_i16(
    IMU3_AccelX,
    IMU3_AccelY,
    IMU3_AccelZ,
    IMU3_GyroX,
    IMU3_GyroY,
    IMU3_GyroZ,
    EI_TSFRESH_MINIMAL_SAMPLE_COUNT,
    features
);
```

Uso con el FIFO circular actual del sketch:

```c
float features[EI_TSFRESH_MINIMAL_TOTAL_FEATURES];

ei_tsfresh_extract_imu_features_minimal_circular_i16(
    IMU3_AccelX,
    IMU3_AccelY,
    IMU3_AccelZ,
    IMU3_GyroX,
    IMU3_GyroY,
    IMU3_GyroZ,
    BUFFER_SIZE,
    newestSampleIndex,
    features
);
```

En `BLEWear_INF_test_nordic.ino`, si llamas al extractor justo despues de
`uploadIMU(sampleIndex)`, pasa `sampleIndex` como `newestSampleIndex`. Espera a
tener al menos 100 muestras reales antes de inferir.

El sketch `BLEWear_INF_test_nordic.ino` ya usa este extractor, aplica el
`StandardScaler` entrenado a los 60 features y luego ejecuta el modelo
`Paper_scientific_reports_inferencing`. En el Serial Monitor a `115200` baudios
veras:

```text
prediction_header,millis,samples,methane,prediction,error,dsp_ms,inference_ms,anomaly_ms
prediction_warmup,10,90
prediction_csv,12345,100,381,0.123456,0,1,42,0
```

`prediction_csv` sale cada 10 muestras despues de completar la primera ventana
de 100 muestras. A 10 Hz equivale a una prediccion por segundo.

Con BLE conectado, cuando `prediction` cruza a mayor o igual que `0.70`, el
actuador ejecuta un unico ciclo: ventilador encendido 40 s, espera 5 s, servo a
7 grados, regreso a home y ventilador apagado. Mientras el ciclo esta activo no
se dispara otro ciclo, y despues de terminar solo se rearma cuando la prediccion
baja de `0.70` y luego vuelve a subir. Sin BLE, se siguen imprimiendo
predicciones por Serial, pero el actuador permanece apagado.
