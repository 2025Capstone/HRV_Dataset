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
    private static final String APP_TAG = "AccelerometerListener";
    private DatabaseReference databaseReference;
    private List<AccelData> accelDataList = new ArrayList<>();

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

    // 생성자: Firebase의 AccelerometerData 경로를 참조하거나 필요에 따라 초기화
    public AccelerometerListener() {
        databaseReference = FirebaseDatabase.getInstance().getReference("AccelerometerData");
        // (옵션) 기존 데이터를 초기화하고 싶다면 clearExistingData() 같은 메서드를 호출
    }

    // HealthTracker 이벤트 리스너 등록 (BaseListener의 이벤트 리스너 설정 활용)
    public AccelerometerListener setup() {
        HealthTracker.TrackerEventListener trackerEventListener = new HealthTracker.TrackerEventListener() {
            @Override
            public void onDataReceived(@NonNull List<DataPoint> dataPoints) {
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
        return this;
    }

    // DataPoint에서 가속도계 데이터를 추출 (SDK에서 제공하는 상수를 사용해야 함)
    private void updateAccelerometerData(DataPoint dataPoint) {
        long timestamp = dataPoint.getTimestamp();
        // 실제 SDK에서 제공하는 가속도계 상수를 사용합니다. 예: ACCEL_X, ACCEL_Y, ACCEL_Z
        float ax = dataPoint.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X);
        float ay = dataPoint.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y);
        float az = dataPoint.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z);

        AccelData newData = new AccelData(timestamp, ax, ay, az);
        accelDataList.add(newData);

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
                .addOnSuccessListener(aVoid -> Log.d(APP_TAG, "Accelerometer data uploaded successfully"))
                .addOnFailureListener(e -> Log.e(APP_TAG, "Failed to upload accelerometer data", e));
    }
}
