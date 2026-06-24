#ifndef IMU_TSFRESH_MINIMAL_FEATURES_H
#define IMU_TSFRESH_MINIMAL_FEATURES_H

#include <math.h>
#include <stddef.h>
#include <stdint.h>

/*
 * C translation of:
 *   tsfresh.feature_extraction.MinimalFCParameters()
 *
 * Expected Python column order for names:
 *   acc_x, acc_y, acc_z, gyro_x, gyro_y, gyro_z
 *
 * For every channel the 10 generated features are:
 *   sum_values, median, mean, length, standard_deviation,
 *   variance, root_mean_square, maximum, absolute_maximum, minimum
 *
 * Total: 6 channels * 10 features = 60 floats.
 */

#define EI_TSFRESH_MINIMAL_SAMPLE_COUNT          100U
#define EI_TSFRESH_MINIMAL_CHANNEL_COUNT         6U
#define EI_TSFRESH_MINIMAL_FEATURES_PER_CHANNEL 10U
#define EI_TSFRESH_MINIMAL_TOTAL_FEATURES \
    (EI_TSFRESH_MINIMAL_CHANNEL_COUNT * EI_TSFRESH_MINIMAL_FEATURES_PER_CHANNEL)

#define EI_TSFRESH_FEATURE_SUM_VALUES        0U
#define EI_TSFRESH_FEATURE_MEDIAN            1U
#define EI_TSFRESH_FEATURE_MEAN              2U
#define EI_TSFRESH_FEATURE_LENGTH            3U
#define EI_TSFRESH_FEATURE_STANDARD_DEVIATION 4U
#define EI_TSFRESH_FEATURE_VARIANCE          5U
#define EI_TSFRESH_FEATURE_ROOT_MEAN_SQUARE  6U
#define EI_TSFRESH_FEATURE_MAXIMUM           7U
#define EI_TSFRESH_FEATURE_ABSOLUTE_MAXIMUM  8U
#define EI_TSFRESH_FEATURE_MINIMUM           9U

#ifdef __cplusplus
extern "C" {
#endif

static inline void ei_tsfresh_sort_f32(float *values, size_t length) {
    for (size_t i = 1; i < length; ++i) {
        const float key = values[i];
        size_t j = i;

        while (j > 0 && values[j - 1] > key) {
            values[j] = values[j - 1];
            --j;
        }

        values[j] = key;
    }
}

static inline float ei_tsfresh_median_from_scratch(float *scratch, size_t length) {
    ei_tsfresh_sort_f32(scratch, length);

    if ((length & 1U) == 0U) {
        const size_t upper = length / 2U;
        return (scratch[upper - 1U] + scratch[upper]) * 0.5f;
    }

    return scratch[length / 2U];
}

static inline void ei_tsfresh_zero_features(float out_features[EI_TSFRESH_MINIMAL_FEATURES_PER_CHANNEL]) {
    for (size_t i = 0; i < EI_TSFRESH_MINIMAL_FEATURES_PER_CHANNEL; ++i) {
        out_features[i] = 0.0f;
    }
}

static inline void ei_tsfresh_compute_series_features_f32(
    const float *data,
    size_t length,
    float scratch[EI_TSFRESH_MINIMAL_SAMPLE_COUNT],
    float out_features[EI_TSFRESH_MINIMAL_FEATURES_PER_CHANNEL]
) {
    if (data == NULL || scratch == NULL || out_features == NULL ||
        length == 0U || length > EI_TSFRESH_MINIMAL_SAMPLE_COUNT) {
        if (out_features != NULL) {
            ei_tsfresh_zero_features(out_features);
        }
        return;
    }

    float sum = 0.0f;
    float sum_sq = 0.0f;
    float min_value = data[0];
    float max_value = data[0];
    float absolute_maximum = fabsf(data[0]);

    for (size_t i = 0; i < length; ++i) {
        const float value = data[i];
        const float abs_value = fabsf(value);

        scratch[i] = value;
        sum += value;
        sum_sq += value * value;

        if (value < min_value) {
            min_value = value;
        }
        if (value > max_value) {
            max_value = value;
        }
        if (abs_value > absolute_maximum) {
            absolute_maximum = abs_value;
        }
    }

    const float length_f = (float)length;
    const float mean = sum / length_f;
    float variance = 0.0f;

    for (size_t i = 0; i < length; ++i) {
        const float diff = data[i] - mean;
        variance += diff * diff;
    }

    variance /= length_f;

    out_features[EI_TSFRESH_FEATURE_SUM_VALUES] = sum;
    out_features[EI_TSFRESH_FEATURE_MEDIAN] = ei_tsfresh_median_from_scratch(scratch, length);
    out_features[EI_TSFRESH_FEATURE_MEAN] = mean;
    out_features[EI_TSFRESH_FEATURE_LENGTH] = length_f;
    out_features[EI_TSFRESH_FEATURE_STANDARD_DEVIATION] = sqrtf(variance);
    out_features[EI_TSFRESH_FEATURE_VARIANCE] = variance;
    out_features[EI_TSFRESH_FEATURE_ROOT_MEAN_SQUARE] = sqrtf(sum_sq / length_f);
    out_features[EI_TSFRESH_FEATURE_MAXIMUM] = max_value;
    out_features[EI_TSFRESH_FEATURE_ABSOLUTE_MAXIMUM] = absolute_maximum;
    out_features[EI_TSFRESH_FEATURE_MINIMUM] = min_value;
}

static inline void ei_tsfresh_compute_series_features_i16(
    const int16_t *data,
    size_t length,
    float scratch[EI_TSFRESH_MINIMAL_SAMPLE_COUNT],
    float out_features[EI_TSFRESH_MINIMAL_FEATURES_PER_CHANNEL]
) {
    if (data == NULL || scratch == NULL || out_features == NULL ||
        length == 0U || length > EI_TSFRESH_MINIMAL_SAMPLE_COUNT) {
        if (out_features != NULL) {
            ei_tsfresh_zero_features(out_features);
        }
        return;
    }

    float sum = 0.0f;
    float sum_sq = 0.0f;
    float min_value = (float)data[0];
    float max_value = (float)data[0];
    float absolute_maximum = fabsf((float)data[0]);

    for (size_t i = 0; i < length; ++i) {
        const float value = (float)data[i];
        const float abs_value = fabsf(value);

        scratch[i] = value;
        sum += value;
        sum_sq += value * value;

        if (value < min_value) {
            min_value = value;
        }
        if (value > max_value) {
            max_value = value;
        }
        if (abs_value > absolute_maximum) {
            absolute_maximum = abs_value;
        }
    }

    const float length_f = (float)length;
    const float mean = sum / length_f;
    float variance = 0.0f;

    for (size_t i = 0; i < length; ++i) {
        const float diff = (float)data[i] - mean;
        variance += diff * diff;
    }

    variance /= length_f;

    out_features[EI_TSFRESH_FEATURE_SUM_VALUES] = sum;
    out_features[EI_TSFRESH_FEATURE_MEDIAN] = ei_tsfresh_median_from_scratch(scratch, length);
    out_features[EI_TSFRESH_FEATURE_MEAN] = mean;
    out_features[EI_TSFRESH_FEATURE_LENGTH] = length_f;
    out_features[EI_TSFRESH_FEATURE_STANDARD_DEVIATION] = sqrtf(variance);
    out_features[EI_TSFRESH_FEATURE_VARIANCE] = variance;
    out_features[EI_TSFRESH_FEATURE_ROOT_MEAN_SQUARE] = sqrtf(sum_sq / length_f);
    out_features[EI_TSFRESH_FEATURE_MAXIMUM] = max_value;
    out_features[EI_TSFRESH_FEATURE_ABSOLUTE_MAXIMUM] = absolute_maximum;
    out_features[EI_TSFRESH_FEATURE_MINIMUM] = min_value;
}

static inline void ei_tsfresh_extract_imu_features_minimal_i16(
    const int16_t *accel_x,
    const int16_t *accel_y,
    const int16_t *accel_z,
    const int16_t *gyro_x,
    const int16_t *gyro_y,
    const int16_t *gyro_z,
    size_t length,
    float feature_vector[EI_TSFRESH_MINIMAL_TOTAL_FEATURES]
) {
    if (feature_vector == NULL) {
        return;
    }

    float scratch[EI_TSFRESH_MINIMAL_SAMPLE_COUNT];
    const int16_t *channels[EI_TSFRESH_MINIMAL_CHANNEL_COUNT] = {
        accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z
    };

    for (size_t channel = 0; channel < EI_TSFRESH_MINIMAL_CHANNEL_COUNT; ++channel) {
        ei_tsfresh_compute_series_features_i16(
            channels[channel],
            length,
            scratch,
            &feature_vector[channel * EI_TSFRESH_MINIMAL_FEATURES_PER_CHANNEL]
        );
    }
}

static inline void ei_tsfresh_extract_imu_features_minimal_window(
    const float data[EI_TSFRESH_MINIMAL_SAMPLE_COUNT][EI_TSFRESH_MINIMAL_CHANNEL_COUNT],
    const char *const names[EI_TSFRESH_MINIMAL_CHANNEL_COUNT],
    float feature_vector[EI_TSFRESH_MINIMAL_TOTAL_FEATURES]
) {
    (void)names;

    if (data == NULL || feature_vector == NULL) {
        return;
    }

    float channel_values[EI_TSFRESH_MINIMAL_SAMPLE_COUNT];
    float scratch[EI_TSFRESH_MINIMAL_SAMPLE_COUNT];

    for (size_t channel = 0; channel < EI_TSFRESH_MINIMAL_CHANNEL_COUNT; ++channel) {
        for (size_t sample = 0; sample < EI_TSFRESH_MINIMAL_SAMPLE_COUNT; ++sample) {
            channel_values[sample] = data[sample][channel];
        }

        ei_tsfresh_compute_series_features_f32(
            channel_values,
            EI_TSFRESH_MINIMAL_SAMPLE_COUNT,
            scratch,
            &feature_vector[channel * EI_TSFRESH_MINIMAL_FEATURES_PER_CHANNEL]
        );
    }
}

static inline void ei_tsfresh_copy_circular_i16(
    const int16_t *source,
    size_t fifo_length,
    size_t newest_sample_index,
    int16_t destination[EI_TSFRESH_MINIMAL_SAMPLE_COUNT]
) {
    const size_t start_index =
        (newest_sample_index + fifo_length + 1U - EI_TSFRESH_MINIMAL_SAMPLE_COUNT) % fifo_length;

    for (size_t sample = 0; sample < EI_TSFRESH_MINIMAL_SAMPLE_COUNT; ++sample) {
        const size_t source_index = (start_index + sample) % fifo_length;
        destination[sample] = source[source_index];
    }
}

static inline void ei_tsfresh_extract_imu_features_minimal_circular_i16(
    const int16_t *accel_x,
    const int16_t *accel_y,
    const int16_t *accel_z,
    const int16_t *gyro_x,
    const int16_t *gyro_y,
    const int16_t *gyro_z,
    size_t fifo_length,
    size_t newest_sample_index,
    float feature_vector[EI_TSFRESH_MINIMAL_TOTAL_FEATURES]
) {
    if (feature_vector == NULL || fifo_length < EI_TSFRESH_MINIMAL_SAMPLE_COUNT) {
        return;
    }

    int16_t channel_values[EI_TSFRESH_MINIMAL_SAMPLE_COUNT];
    float scratch[EI_TSFRESH_MINIMAL_SAMPLE_COUNT];
    const int16_t *channels[EI_TSFRESH_MINIMAL_CHANNEL_COUNT] = {
        accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z
    };

    for (size_t channel = 0; channel < EI_TSFRESH_MINIMAL_CHANNEL_COUNT; ++channel) {
        if (channels[channel] == NULL) {
            ei_tsfresh_zero_features(
                &feature_vector[channel * EI_TSFRESH_MINIMAL_FEATURES_PER_CHANNEL]
            );
            continue;
        }

        ei_tsfresh_copy_circular_i16(
            channels[channel],
            fifo_length,
            newest_sample_index,
            channel_values
        );

        ei_tsfresh_compute_series_features_i16(
            channel_values,
            EI_TSFRESH_MINIMAL_SAMPLE_COUNT,
            scratch,
            &feature_vector[channel * EI_TSFRESH_MINIMAL_FEATURES_PER_CHANNEL]
        );
    }
}

#ifdef __cplusplus
}
#endif

#endif  // IMU_TSFRESH_MINIMAL_FEATURES_H
