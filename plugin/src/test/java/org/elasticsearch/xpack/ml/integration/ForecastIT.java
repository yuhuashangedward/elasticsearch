/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.integration;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.xpack.ml.job.config.AnalysisConfig;
import org.elasticsearch.xpack.ml.job.config.DataDescription;
import org.elasticsearch.xpack.ml.job.config.Detector;
import org.elasticsearch.xpack.ml.job.config.Job;
import org.elasticsearch.xpack.ml.job.results.Bucket;
import org.elasticsearch.xpack.ml.job.results.Forecast;
import org.elasticsearch.xpack.ml.job.results.ForecastRequestStats;
import org.junit.After;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;

public class ForecastIT extends MlNativeAutodetectIntegTestCase {

    @After
    public void tearDownData() throws Exception {
        cleanUp();
    }

    public void testSingleSeries() throws Exception {
        Detector.Builder detector = new Detector.Builder("mean", "value");

        TimeValue bucketSpan = TimeValue.timeValueHours(1);
        AnalysisConfig.Builder analysisConfig = new AnalysisConfig.Builder(Collections.singletonList(detector.build()));
        analysisConfig.setBucketSpan(bucketSpan);
        DataDescription.Builder dataDescription = new DataDescription.Builder();
        dataDescription.setTimeFormat("epoch");
        Job.Builder job = new Job.Builder("forecast-it-test-single-series");
        job.setAnalysisConfig(analysisConfig);
        job.setDataDescription(dataDescription);

        registerJob(job);
        putJob(job);
        openJob(job.getId());

        long now = Instant.now().getEpochSecond();
        long timestamp = now - 50 * bucketSpan.seconds();
        List<String> data = new ArrayList<>();
        while (timestamp < now) {
            data.add(createJsonRecord(createRecord(timestamp, 10.0)));
            data.add(createJsonRecord(createRecord(timestamp, 30.0)));
            timestamp += bucketSpan.seconds();
        }

        postData(job.getId(), data.stream().collect(Collectors.joining()));
        flushJob(job.getId(), false);

        // Now we can start doing forecast requests

        String forecastIdDefaultDurationDefaultExpiry = forecast(job.getId(), null, null);
        String forecastIdDuration1HourNoExpiry = forecast(job.getId(), TimeValue.timeValueHours(1), TimeValue.ZERO);
        String forecastIdDuration3HoursExpiresIn24Hours = forecast(job.getId(), TimeValue.timeValueHours(3), TimeValue.timeValueHours(24));

        waitForecastToFinish(job.getId(), forecastIdDefaultDurationDefaultExpiry);
        waitForecastToFinish(job.getId(), forecastIdDuration1HourNoExpiry);
        waitForecastToFinish(job.getId(), forecastIdDuration3HoursExpiresIn24Hours);
        closeJob(job.getId());

        List<Bucket> buckets = getBuckets(job.getId());
        Bucket lastBucket = buckets.get(buckets.size() - 1);
        long lastBucketTime = lastBucket.getTimestamp().getTime();

        // Now let's verify forecasts
        double expectedForecastValue = 20.0;

        List<ForecastRequestStats> forecastStats = getForecastStats();
        assertThat(forecastStats.size(), equalTo(3));
        Map<String, ForecastRequestStats> idToForecastStats = new HashMap<>();
        forecastStats.stream().forEach(f -> idToForecastStats.put(f.getForecastId(), f));

        {
            ForecastRequestStats forecastDefaultDurationDefaultExpiry = idToForecastStats.get(forecastIdDefaultDurationDefaultExpiry);
            assertThat(forecastDefaultDurationDefaultExpiry.getExpiryTime().toEpochMilli(),
                    equalTo(forecastDefaultDurationDefaultExpiry.getCreateTime().toEpochMilli()
                            + TimeValue.timeValueHours(14 * 24).getMillis()));
            List<Forecast> forecasts = getForecasts(job.getId(), forecastDefaultDurationDefaultExpiry);
            assertThat(forecastDefaultDurationDefaultExpiry.getRecordCount(), equalTo(24L));
            assertThat(forecasts.size(), equalTo(24));
            assertThat(forecasts.get(0).getTimestamp().getTime(), equalTo(lastBucketTime));
            for (int i = 0; i < forecasts.size(); i++) {
                Forecast forecast = forecasts.get(i);
                assertThat(forecast.getTimestamp().getTime(), equalTo(lastBucketTime + i * bucketSpan.getMillis()));
                assertThat(forecast.getBucketSpan(), equalTo(bucketSpan.getSeconds()));
                assertThat(forecast.getForecastPrediction(), closeTo(expectedForecastValue, 0.01));
            }
        }

        {
            ForecastRequestStats forecastDuration1HourNoExpiry = idToForecastStats.get(forecastIdDuration1HourNoExpiry);
            assertThat(forecastDuration1HourNoExpiry.getExpiryTime(), equalTo(Instant.EPOCH));
            List<Forecast> forecasts = getForecasts(job.getId(), forecastDuration1HourNoExpiry);
            assertThat(forecastDuration1HourNoExpiry.getRecordCount(), equalTo(1L));
            assertThat(forecasts.size(), equalTo(1));
            assertThat(forecasts.get(0).getTimestamp().getTime(), equalTo(lastBucketTime));
            for (int i = 0; i < forecasts.size(); i++) {
                Forecast forecast = forecasts.get(i);
                assertThat(forecast.getTimestamp().getTime(), equalTo(lastBucketTime + i * bucketSpan.getMillis()));
                assertThat(forecast.getBucketSpan(), equalTo(bucketSpan.getSeconds()));
                assertThat(forecast.getForecastPrediction(), closeTo(expectedForecastValue, 0.01));
            }
        }

        {
            ForecastRequestStats forecastDuration3HoursExpiresIn24Hours = idToForecastStats.get(forecastIdDuration3HoursExpiresIn24Hours);
            assertThat(forecastDuration3HoursExpiresIn24Hours.getExpiryTime().toEpochMilli(),
                    equalTo(forecastDuration3HoursExpiresIn24Hours.getCreateTime().toEpochMilli()
                            + TimeValue.timeValueHours(24).getMillis()));
            List<Forecast> forecasts = getForecasts(job.getId(), forecastDuration3HoursExpiresIn24Hours);
            assertThat(forecastDuration3HoursExpiresIn24Hours.getRecordCount(), equalTo(3L));
            assertThat(forecasts.size(), equalTo(3));
            assertThat(forecasts.get(0).getTimestamp().getTime(), equalTo(lastBucketTime));
            for (int i = 0; i < forecasts.size(); i++) {
                Forecast forecast = forecasts.get(i);
                assertThat(forecast.getTimestamp().getTime(), equalTo(lastBucketTime + i * bucketSpan.getMillis()));
                assertThat(forecast.getBucketSpan(), equalTo(bucketSpan.getSeconds()));
                assertThat(forecast.getForecastPrediction(), closeTo(expectedForecastValue, 0.01));
            }
        }
    }

    public void testDurationCannotBeLessThanBucketSpan() throws Exception {
        Detector.Builder detector = new Detector.Builder("mean", "value");

        TimeValue bucketSpan = TimeValue.timeValueHours(1);
        AnalysisConfig.Builder analysisConfig = new AnalysisConfig.Builder(Collections.singletonList(detector.build()));
        analysisConfig.setBucketSpan(bucketSpan);
        DataDescription.Builder dataDescription = new DataDescription.Builder();
        dataDescription.setTimeFormat("epoch");
        Job.Builder job = new Job.Builder("forecast-it-test-duration-bucket-span");
        job.setAnalysisConfig(analysisConfig);
        job.setDataDescription(dataDescription);

        registerJob(job);
        putJob(job);
        openJob(job.getId());
        ElasticsearchException e = expectThrows(ElasticsearchException.class,() -> forecast(job.getId(),
                TimeValue.timeValueMinutes(10), null));
        assertThat(e.getMessage(),
                equalTo("java.lang.IllegalArgumentException: [duration] must be greater or equal to the bucket span: [10m/1h]"));
    }

    private static Map<String, Object> createRecord(long timestamp, double value) {
        Map<String, Object> record = new HashMap<>();
        record.put("time", timestamp);
        record.put("value", value);
        return record;
    }
}
