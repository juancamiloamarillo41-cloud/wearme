#ifndef SENSOR_FEATURES_H
#define SENSOR_FEATURES_H

#include <stddef.h>
#include <stdint.h>
#include <math.h>  // For math functions

// Constants
#define RAD_TO_DEG (57.295779513082320876798154814105f) // 180 / PI


// Function Prototypes (Integer Version)
int16_t compute_minimum(int16_t *data, size_t length);
int16_t compute_maximum(int16_t *data, size_t length);
int32_t compute_sumvalues(int16_t *data, size_t length);
float compute_mean(int16_t *data, size_t length);
float compute_median(int16_t *data, size_t length);
void merge(int16_t *data, int16_t *temp, int left, int mid, int right);
void merge_sort(int16_t *data, int16_t *temp, int left, int right);
float compute_variance(int16_t *data, size_t length);
float compute_standarddeviation(int16_t *data, size_t length);
float compute_rootmeansquare(int16_t *data, size_t length);
int16_t compute_absolutemaximum(int16_t *data, size_t length);

// Function Prototypes (Float Version)
float compute_minimum_float(float *data, size_t length);
float compute_maximum_float(float *data, size_t length);
float compute_sumvalues_float(float *data, size_t length);
float compute_mean_float(float *data, size_t length);
float compute_median_float(float *data, size_t length);
void merge_float(float *data, float *temp, int left, int mid, int right);
void merge_sort_float(float *data, float *temp, int left, int right);
float compute_variance_float(float *data, size_t length);
float compute_standarddeviation_float(float *data, size_t length);
float compute_rootmeansquare_float(float *data, size_t length);
float compute_absolutemaximum_float(float *data, size_t length);


void compute_magnitude(int16_t *x, int16_t *y, int16_t *z, float *magnitude, size_t length);
void compute_pitch(int16_t *accelX, int16_t *accelY, int16_t *accelZ, float *pitch, size_t length);
void compute_roll(int16_t *accelX, int16_t *accelY, int16_t *accelZ, float *roll, size_t length);


// Function to compute the minimum value in an array (int16_t data)
inline int16_t compute_minimum(int16_t *data, size_t length) {
    if (length == 0) return 0;
    int16_t min = data[0];
    for (size_t i = 1; i < length; ++i) {
        if (data[i] < min) min = data[i];
    }
    return min;
}

// Function to compute the maximum value in an array (int16_t data)
inline int16_t compute_maximum(int16_t *data, size_t length) {
    if (length == 0) return 0;
    int16_t max = data[0];
    for (size_t i = 1; i < length; ++i) {
        if (data[i] > max) max = data[i];
    }
    return max;
}

// Function to compute the sum of values in an array (int16_t data)
inline int32_t compute_sumvalues(int16_t *data, size_t length) {
    int32_t sum = 0;
    for (size_t i = 0; i < length; ++i) {
        sum += data[i];
    }
    return sum;
}

// Function to compute the mean of values in an array (int16_t data)
inline float compute_mean(int16_t *data, size_t length) {
    if (length == 0) return 0.0f;
    return (float)compute_sumvalues(data, length) / length;
}

// Function to compute the median of values in an array (int16_t data)
inline float compute_median(int16_t *data, size_t length) {
    if (length == 0) return 0.0f;

    int16_t *temp = (int16_t *)malloc(length * sizeof(int16_t));
    if (!temp) return 0.0f;

    // Perform the merge sort
    merge_sort(data, temp, 0, length - 1);
    free(temp);

    // Calculate the median
    if (length % 2 == 0) {
        return ((float)data[length / 2 - 1] + (float)data[length / 2]) / 2.0f;
    } else {
        return (float)data[length / 2];
    }
}

// Function to compute the variance of values in an array (int16_t data)
inline float compute_variance(int16_t *data, size_t length) {
    if (length == 0) return 0.0f;
    float mean = compute_mean(data, length);
    float variance = 0.0f;
    for (size_t i = 0; i < length; ++i) {
        float diff = (float)data[i] - mean;
        variance += diff * diff;
    }
    return variance / length;
}

// Function to compute the standard deviation of values in an array (int16_t data)
inline float compute_standarddeviation(int16_t *data, size_t length) {
    return sqrtf(compute_variance(data, length));
}

// Function to compute the root mean square (RMS) of values in an array (int16_t data)
inline float compute_rootmeansquare(int16_t *data, size_t length) {
    if (length == 0) return 0.0f;
    int32_t sum_sq = 0;
    for (size_t i = 0; i < length; ++i) {
        sum_sq += data[i] * data[i];
    }
    return sqrt((float)sum_sq / length);
}

// Function to compute the absolute maximum value in an array (int16_t data)
inline int16_t compute_absolutemaximum(int16_t *data, size_t length) {
    if (length == 0) return 0;
    int16_t abs_max = abs(data[0]);
    for (size_t i = 1; i < length; ++i) {
        int16_t abs_val = abs(data[i]);
        if (abs_val > abs_max) abs_max = abs_val;
    }
    return abs_max;
}

// Helper functions for merge sort
void merge(int16_t *data, int16_t *temp, int left, int mid, int right) {
    int i = left, j = mid + 1, k = left;
    while (i <= mid && j <= right) {
        if (data[i] <= data[j]) {
            temp[k++] = data[i++];
        } else {
            temp[k++] = data[j++];
        }
    }
    while (i <= mid) temp[k++] = data[i++];
    while (j <= right) temp[k++] = data[j++];
    for (i = left; i <= right; i++) data[i] = temp[i];
}

void merge_sort(int16_t *data, int16_t *temp, int left, int right) {
    if (left < right) {
        int mid = (left + right) / 2;
        merge_sort(data, temp, left, mid);
        merge_sort(data, temp, mid + 1, right);
        merge(data, temp, left, mid, right);
    }
}


// Float Version Functions

// Function to compute the minimum value in an array (float data)
inline float compute_minimum_float(float *data, size_t length) {
    if (length == 0) return 0.0f;
    float min = data[0];
    for (size_t i = 1; i < length; ++i) {
        if (data[i] < min) min = data[i];
    }
    return min;
}

// Function to compute the maximum value in an array (float data)
inline float compute_maximum_float(float *data, size_t length) {
    if (length == 0) return 0.0f;
    float max = data[0];
    for (size_t i = 1; i < length; ++i) {
        if (data[i] > max) max = data[i];
    }
    return max;
}

// Function to compute the sum of values in an array (float data)
inline float compute_sumvalues_float(float *data, size_t length) {
    float sum = 0.0f;
    for (size_t i = 0; i < length; ++i) {
        sum += data[i];
    }
    return sum;
}

// Function to compute the mean of values in an array (float data)
inline float compute_mean_float(float *data, size_t length) {
    if (length == 0) return 0.0f;
    return compute_sumvalues_float(data, length) / length;
}

// Function to compute the median of values in an array (float data)
inline float compute_median_float(float *data, size_t length) {
    if (length == 0) return 0.0f;

    float *temp = (float *)malloc(length * sizeof(float));
    if (!temp) return 0.0f;

    // Perform the merge sort
    merge_sort_float(data, temp, 0, length - 1);
    free(temp);

    // Calculate the median
    if (length % 2 == 0) {
        return (data[length / 2 - 1] + data[length / 2]) / 2.0f;
    } else {
        return data[length / 2];
    }
}

// Function to compute the variance of values in an array (float data)
inline float compute_variance_float(float *data, size_t length) {
    if (length == 0) return 0.0f;
    float mean = compute_mean_float(data, length);
    float variance = 0.0f;
    for (size_t i = 0; i < length; ++i) {
        float diff = data[i] - mean;
        variance += diff * diff;
    }
    return variance / length;
}

// Function to compute the standard deviation of values in an array (float data)
inline float compute_standarddeviation_float(float *data, size_t length) {
    return sqrt(compute_variance_float(data, length));
}

// Function to compute the root mean square (RMS) of values in an array (float data)
inline float compute_rootmeansquare_float(float *data, size_t length) {
    if (length == 0) return 0.0f;
    float sum_sq = 0.0f;
    for (size_t i = 0; i < length; ++i) {
        sum_sq += data[i] * data[i];
    }
    return sqrt(sum_sq / length);
}

// Function to compute the absolute maximum value in an array (float data)
inline float compute_absolutemaximum_float(float *data, size_t length) {
    if (length == 0) return 0.0f;
    float abs_max = fabsf(data[0]);
    for (size_t i = 1; i < length; ++i) {
        float abs_val = fabsf(data[i]);
        if (abs_val > abs_max) abs_max = abs_val;
    }
    return abs_max;
}

// Helper functions for merge sort (float version)
void merge_float(float *data, float *temp, int left, int mid, int right) {
    int i = left, j = mid + 1, k = left;
    while (i <= mid && j <= right) {
        if (data[i] <= data[j]) {
            temp[k++] = data[i++];
        } else {
            temp[k++] = data[j++];
        }
    }
    while (i <= mid) temp[k++] = data[i++];
    while (j <= right) temp[k++] = data[j++];
    for (i = left; i <= right; i++) data[i] = temp[i];
}

void merge_sort_float(float *data, float *temp, int left, int right) {
    if (left < right) {
        int mid = (left + right) / 2;
        merge_sort_float(data, temp, left, mid);
        merge_sort_float(data, temp, mid + 1, right);
        merge_float(data, temp, left, mid, right);
    }
}



// Function to compute the magnitude of 3D vectors (int16_t data)
inline void compute_magnitude( int16_t *x,  int16_t *y, int16_t *z, float *magnitude, size_t length) {
    for (size_t i = 0; i < length; ++i) {
        magnitude[i] = sqrt((float)(x[i] * x[i]) + (float)(y[i] * y[i]) + (float)(z[i] * z[i]));
    }
}

inline void compute_pitch(int16_t *accelX, int16_t *accelY, int16_t *accelZ, float *pitch, size_t length) {
    for (size_t i = 0; i < length; ++i) {
        // Convert accelerometer readings to floats for precise calculations
        float ax = (float)accelX[i];
        float ay = (float)accelY[i];
        float az = (float)accelZ[i];

        // Compute pitch using the formula, ensuring stability
        pitch[i] = atan2f(ax, sqrt(ay * ay + az * az));  // Result in radians
    }
}

// Function to compute Roll angles from accelerometer data
inline void compute_roll(int16_t *accelX, int16_t *accelY, int16_t *accelZ, float *roll, size_t length) {
    for (size_t i = 0; i < length; ++i) {
        // Convert accelerometer readings to floats for precise calculations
        float ax = (float)accelX[i];
        float ay = (float)accelY[i];
        float az = (float)accelZ[i];

        // Compute pitch using the formula, ensuring stability
        roll[i] = atan2f(ay, az);  // Result in radians
    }
}

#endif // SENSOR_FEATURES_H
