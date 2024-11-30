package com.samsung.health.multisensortracking;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.samsung.android.service.health.tracking.HealthTracker;
import com.samsung.android.service.health.tracking.data.DataPoint;
import com.samsung.android.service.health.tracking.data.ValueKey;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;



public class HeartRateListener extends BaseListener {
    private static final String APP_TAG = "HeartRateListener";
    private DatabaseReference databaseReference;  // Firebase Database Reference
    private List<Integer> heartRateList = new ArrayList<>();  // 심박수 목록 (HRV 계산을 위해)
    private List<String> timestampList = new ArrayList<>(); // 타임스탬프 목록 (첫 번째 데이터의 타임스탬프를 기록)
    private boolean shouldUploadData = false; // 데이터 업로드 여부
    private int dataIndex = 0;  // 데이터를 순차적으로 저장할 인덱스
    private int measureCount = 0;
    private int errorCount = 0;  // 잘못된 심박수 데이터 카운트
    private List<Double> rrIntervalList = new ArrayList<>();  // R-R 간격 목록

    // 업데이트 리스너: 심박수 데이터를 UI나 다른 컴포넌트로 전달
    private HeartRateUpdateListener updateListener;

    public void setHeartRateUpdateListener(HeartRateUpdateListener listener) {
        this.updateListener = listener;
    }

    public interface HeartRateUpdateListener {
        void onHeartRateUpdate(int heartRate);
    }


    HeartRateListener() {
        // Firebase Database 초기화
        databaseReference = FirebaseDatabase.getInstance().getReference("HeartRateData");

        // Firebase 데이터 전체 삭제
        clearExistingData();

        // HealthTracker 이벤트 리스너
        // Samsung HealthTracker에서 발생하는 이벤트를 처리하기 위해 TrackerEventListener를 구현합니다.
        final HealthTracker.TrackerEventListener trackerEventListener = new HealthTracker.TrackerEventListener() {
            @Override
            public void onDataReceived(@NonNull List<DataPoint> list) {
                // 헬스 트래커에서 데이터를 수신했을 때 호출됩니다.
                // 매개변수 `list`는 수신된 DataPoint 객체들의 리스트입니다.

                // 수신된 각 DataPoint를 처리하기 위한 반복문
                for (DataPoint data : list) {
                    updateHeartRate(data);
                }
            }

            @Override
            public void onFlushCompleted() {
                Log.i(APP_TAG, "onFlushCompleted called");
            }

            @Override
            public void onError(HealthTracker.TrackerError trackerError) {
                Log.e(APP_TAG, "onError called: " + trackerError);
                setHandlerRunning(false);
            }
        };
        setTrackerEventListener(trackerEventListener);
    }

    private void clearExistingData() {
        // Firebase 데이터베이스에서 기존 데이터 삭제
        databaseReference.removeValue()
                .addOnSuccessListener(aVoid -> Log.d(APP_TAG, "All existing Heart Rate data cleared"))
                .addOnFailureListener(e -> Log.e(APP_TAG, "Failed to clear existing Heart Rate data", e));
    }

    public void updateHeartRate(DataPoint dataPoint) {
        if (!shouldUploadData) return; // 업로드가 활성화되지 않으면 리턴

        // ValueKey를 사용하여 DataPoint에서 심박수 데이터를 가져옵니다.
        int heartRate = dataPoint.getValue(ValueKey.HeartRateSet.HEART_RATE);

        // 현재 시간을 "yyyy-MM-dd HH:mm:ss.SSS" 형식으로 저장
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());

        double rrInterval = 0;
        // UI 업데이트를 위해 MainActivity에 데이터 전달
        if (updateListener != null) {
            updateListener.onHeartRateUpdate(heartRate);
        }
        // 심박수가 비정상적이면(너무 낮거나 높음) 에러로 처리
        if (heartRate <= 40 || heartRate >= 150) {
            errorCount++;
        } else {
            // R-R 간격 계산 (1분(60000ms) 나누기 심박수)
            rrInterval = 60000.0 / heartRate;
            // 동기화된 방식으로 두 리스트에 추가
            rrIntervalList.add(rrInterval);
            timestampList.add(timestamp);
        }

        measureCount++;

        // 일정 데이터가 모이면 Firebase에 업로드
        if (measureCount >= 120) { // 120개의 데이터가 모이면 업로드
//            double hrv = calculateHRV(); // HRV 계산
//            uploadHeartRateData(hrv, timestamp);
            uploadRrDataToFirebase();
        }
    }


    // HRV 계산: 최근 N개의 심박수 간격(RR Interval)의 표준편차 또는 RMSSD 방식으로 계산
    private double calculateHRV() {
        if (rrIntervalList.size() < 2) {
            return 0.0;  // 유효한 데이터가 부족하면 HRV 값으로 0을 반환
        }

        // 평균 계산: RR 간격 리스트의 평균값을 계산
        double mean = rrIntervalList.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0); // 데이터가 없으면 기본값 0.0 반환

        // 분산 계산: RR 간격 값에서 평균을 뺀 값의 제곱을 이용하여 분산 계산
        double variance = rrIntervalList.stream()
                .mapToDouble(interval -> Math.pow(interval - mean, 2))
                .average()
                .orElse(0.0); // 데이터가 없으면 기본값 0.0 반환

        return Math.sqrt(variance); // 표준편차(SDNN)를 계산하여 반환

        // RMSSD(Root Mean Square of Successive Differences) 방식 계산
//    if (rrIntervalList.size() < 2) {
//        return 0.0;  // 유효한 RR 간격이 2개 이상이어야 RMSSD 계산 가능
//    }
//
//    double sumSquaredDifferences = 0.0;
//
//    // 연속된 RR 간격 차이의 제곱합 계산
//    for (int i = 1; i < rrIntervalList.size(); i++) {
//        double diff = rrIntervalList.get(i) - rrIntervalList.get(i - 1);
//        sumSquaredDifferences += Math.pow(diff, 2);
//    }
//
//    // 평균의 제곱근을 계산하여 RMSSD 반환
//    double meanSquaredDifferences = sumSquaredDifferences / (rrIntervalList.size() - 1);
//    return Math.sqrt(meanSquaredDifferences);
    }

    private void uploadHeartRateData(double hrv, String timestamp) {
        // 심박수 및 RR 간격의 최저 및 최대 값을 계산
        int minHeartRate = heartRateList.stream().min(Integer::compare).orElse(0); // 심박수의 최저값
        int maxHeartRate = heartRateList.stream().max(Integer::compare).orElse(0); // 심박수의 최대값
        double minRRInterval = rrIntervalList.stream().min(Double::compare).orElse(0.0); // R-R 간격의 최저값
        double maxRRInterval = rrIntervalList.stream().max(Double::compare).orElse(0.0); // R-R 간격의 최대값

        // 데이터 맵 구성
        Map<String, Object> heartRateData = new HashMap<>();
        heartRateData.put("errorCount", errorCount);
        heartRateData.put("hrv", hrv); // 계산된 HRV
        heartRateData.put("minHeartRate", minHeartRate); // 심박수 최저값
        heartRateData.put("maxHeartRate", maxHeartRate); // 심박수 최대값
        heartRateData.put("minRRInterval", minRRInterval); // R-R 간격 최저값
        heartRateData.put("maxRRInterval", maxRRInterval); // R-R 간격 최대값
        heartRateData.put("timestamp", timestamp); // 타임스탬프

        // Firebase에 업로드
        String index = String.valueOf(getNextIndex());
        databaseReference.child(index).setValue(heartRateData)
                .addOnSuccessListener(aVoid -> Log.d(APP_TAG, "Heart Rate and HRV data uploaded successfully"))
                .addOnFailureListener(e -> Log.e(APP_TAG, "Failed to upload Heart Rate and HRV data", e));

        // 로그 출력
        Log.d(APP_TAG, "Data uploaded. HRV: " + hrv +
                ", Min Heart Rate: " + minHeartRate +
                ", Max Heart Rate: " + maxHeartRate +
                ", Min R-R Interval: " + minRRInterval +
                ", Max R-R Interval: " + maxRRInterval +
                ", Timestamp: " + timestamp);
    }

    private void uploadRrDataToFirebase() {
//        if (rrIntervalList.isEmpty() || timestampList.isEmpty()) {
//            Log.e(APP_TAG, "No data to upload.");
//            return; // 데이터가 없으면 업로드하지 않음
//        }
//
//        if (rrIntervalList.size() != timestampList.size()) {
//            Log.e(APP_TAG, "Mismatch in rrIntervalList and timestampList sizes!");
//            Log.e(APP_TAG, "rrIntervalList size: " + rrIntervalList.size());
//            Log.e(APP_TAG, "timestampList size: " + timestampList.size());
//            return; // 리스트 크기가 다르면 업로드하지 않음
//        }

        // 2분 간격의 데이터 시작 타임스탬프 (리스트의 첫 번째 타임스탬프 사용)
//        String startTimestamp = timestampList.get(0);
        String startTimestamp = timestampList.isEmpty() ?
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date()) :
                timestampList.get(0);

        // Firebase에서 허용되지 않는 문자(`.` 등)를 안전한 문자로 치환
        String validTimestampKey = startTimestamp.replace(":", "-").replace(".", "-");

        // RR 간격 데이터를 Firebase에 업로드하기 위한 리스트 구성
        List<Map<String, Object>> validData = new ArrayList<>();
        for (int i = 0; i < rrIntervalList.size(); i++) {
            Map<String, Object> data = new HashMap<>();
            data.put("rrInterval", rrIntervalList.get(i)); // RR 간격 값
            data.put("timestamp", timestampList.get(i));  // 해당 데이터의 타임스탬프
            validData.add(data);
        }

        // Firebase 업로드 데이터 구조 생성
        Map<String, Object> firebaseData = new HashMap<>();
        firebaseData.put("rrIntervals", validData);      // RR 간격 데이터 리스트
        firebaseData.put("errorCount", errorCount);     // 에러 카운트

        // Firebase에 업로드 (validTimestampKey를 노드 이름으로 사용)
        databaseReference.child(validTimestampKey).setValue(firebaseData)
                .addOnSuccessListener(aVoid -> Log.d(APP_TAG, "RR Interval data uploaded successfully"))
                .addOnFailureListener(e -> Log.e(APP_TAG, "Failed to upload RR Interval data", e));

        // 로그 출력 (디버깅용)
        Log.d(APP_TAG, "Data uploaded with start timestamp: " + validTimestampKey +
                ", rrIntervals size: " + validData.size() +
                ", errorCount: " + errorCount);
        resetData();
    }





    // 순차적인 인덱스를 반환
    private int getNextIndex() {
        return dataIndex++;  // 순차적으로 0, 1, 2...가 반환됨
    }

    // 데이터를 초기화
    private void resetData() {
        rrIntervalList.clear();  // R-R 간격 리스트 초기화
        timestampList.clear();   // 타임스탬프 리스트 초기화
        errorCount = 0;          // 에러 카운트 초기화
        measureCount = 0;        // 측정 카운트 초기화
    }

    public void startDataUpload() {
        shouldUploadData = true;
    }

    public void stopDataUpload() {
        shouldUploadData = false;
    }
}
