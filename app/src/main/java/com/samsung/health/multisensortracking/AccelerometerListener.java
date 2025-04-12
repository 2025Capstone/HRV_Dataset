package com.samsung.health.multisensortracking;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.samsung.android.service.health.tracking.HealthTracker;
import com.samsung.android.service.health.tracking.data.DataPoint;
import com.samsung.android.service.health.tracking.data.ValueKey;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AccelerometerListener extends BaseListener {

    // 가속도계 데이터를 저장할 내부 데이터 클래스 (x,y,z)
    public class AccelData {
        public long timestamp;
        public float ax;
        public float ay;
        public float az;

        public AccelData(long timestamp, float ax, float ay, float az) {
            this.timestamp = timestamp;
            this.ax = ax;
            this.ay = ay;
            this.az = az;
        }
    }
    private static final String APP_TAG = "Accelerometer_Listener";
    private DatabaseReference databaseReference;
    private boolean shouldUploadData = false;
    private List<AccelData> accelDataList = new ArrayList<>();

    // flush 호출 주기 (예: 0.5초)
    private static final long FLUSH_INTERVAL_MS = 500;
    private final android.os.Handler flushHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    // flush를 주기적으로 호출하는 Runnable
    private final Runnable flushRunnable = new Runnable() {
        @Override
        public void run() {
            if (!shouldUploadData) {
                return ;
            }
            // HealthTracker가 null이 아니면 flush() 호출 (연결된 상태여야 함)
            HealthTracker tracker = getHealthTracker();
            if (tracker != null) {
                Log.d(APP_TAG, "Calling flush() on HealthTracker");
                tracker.flush();
            }
            // 다음 flush 호출 예약
            flushHandler.postDelayed(this, FLUSH_INTERVAL_MS);
        }
    };


    // 생성자: Firebase의 AccelerometerData 경로를 참조하거나 필요에 따라 초기화
    AccelerometerListener() {
        databaseReference = FirebaseDatabase.getInstance().getReference("AccelerometerData");

        clearExistingData();

        final HealthTracker.TrackerEventListener trackerEventListener = new HealthTracker.TrackerEventListener() {
            @Override
            public void onDataReceived(@NonNull List<DataPoint> dataPoints) {
                Log.e(APP_TAG, "Accelerometer Data is received.");
                for (DataPoint data : dataPoints) {
                    updateAccelerometerData(data);
                }
            }

            @Override
            public void onFlushCompleted() {
                Log.i(APP_TAG, "Accelerometer flush completed");
            }

            @Override
            public void onError(HealthTracker.TrackerError trackerError) {
                Log.e(APP_TAG, "Accelerometer Tracker Error: " + trackerError);
                setHandlerRunning(false);
            }
        };
        setTrackerEventListener(trackerEventListener);
    }


    private void clearExistingData() {
        // Firebase 데이터베이스에서 기존 데이터 삭제
        databaseReference.removeValue()
                .addOnSuccessListener(aVoid -> Log.d(APP_TAG, "All existing Accel data cleared"))
                .addOnFailureListener(e -> Log.e(APP_TAG, "Failed to clear existing Accel data", e));
    }

    // DataPoint에서 가속도계 데이터를 추출 (SDK에서 제공하는 상수를 사용해야 함)
    private void updateAccelerometerData(DataPoint dataPoint) {
        if (!shouldUploadData) { Log.d(APP_TAG, "shouldUploadData is not working"); return; } // 데이터 업로드가 활성화되어 있지 않다면 바로 리턴 (업로드 중이 아니면 처리하지 않음)

        long timestamp = dataPoint.getTimestamp();
        // 실제 SDK에서 제공하는 가속도계 상수를 사용합니다. 예: ACCEL_X, ACCEL_Y, ACCEL_Z
        float ax = dataPoint.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X);
        float ay = dataPoint.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y);
        float az = dataPoint.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z);
        AccelData newData = new AccelData(timestamp, ax, ay, az);
        accelDataList.add(newData);

        Log.d(APP_TAG, "Accel ax value: " + ax);
        Log.d(APP_TAG, "Accel ay value: " + ay);
        Log.d(APP_TAG, "Accel az value: " + az);


        // 필요에 따라 Firebase에 업로드
        uploadAccelDataToFirebase(newData);
    }


    // 가속도계 데이터를 Firebase에 업로드하는 메서드 (포맷팅 및 키 지정)
    private void uploadAccelDataToFirebase(AccelData data) {
        String formattedTimestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                .format(new Date(data.timestamp));

        Map<String, Object> singleData = new HashMap<>();
        singleData.put("ax", data.ax);
        singleData.put("ay", data.ay);
        singleData.put("az", data.az);
        singleData.put("timestamp", formattedTimestamp);

        databaseReference.push().setValue(singleData)
                .addOnSuccessListener(aVoid ->
                        Log.d(APP_TAG, "Accelerometer data uploaded successfully"))
                .addOnFailureListener(e ->
                        Log.e(APP_TAG, "Failed to upload accelerometer data", e));
    }

    // 데이터 업로드 활성화: shouldUploadData를 true로 설정하고, flushHandler를 시작
    public void startDataUpload() {
        shouldUploadData = true;
        flushHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS);
        Log.d(APP_TAG, "Accelerometer data upload started");
    }

    // 데이터 업로드 중지: shouldUploadData를 false로 설정하고, flushHandler 예약된 작업 제거
    public void stopDataUpload() {
        shouldUploadData = false;
        flushHandler.removeCallbacks(flushRunnable);
        Log.d(APP_TAG, "Accelerometer data upload stopped");
    }
}
