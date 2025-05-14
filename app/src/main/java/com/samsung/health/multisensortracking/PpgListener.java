package com.samsung.health.multisensortracking;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.samsung.android.service.health.tracking.HealthTracker;
import com.samsung.android.service.health.tracking.data.DataPoint;
import com.samsung.android.service.health.tracking.data.ValueKey;

import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;

public class PpgListener extends BaseListener {
    public class PpgData {
        public long timestamp;
        public int ppgValue;

        public PpgData(long timestamp, int ppgValue) {
            this.timestamp = timestamp;
            this.ppgValue = ppgValue;
        }
    }

    private static final String APP_TAG = "PPG_Listener";
    private static final String USER_ID = "123456";  // 사용자별 고정 UID
    private DatabaseReference databaseReference; // Firebase Database의 참조 (심박수 데이터를 저장할 경로)
    private DatabaseReference databaseReference_survey;
    private boolean shouldUploadData = false; // 데이터 업로드 활성화 여부 플래그
    private List<PpgData> ppgDataList = new ArrayList<>(); // PPG 센서 데이터(정수값)를 저장할 리스트

    // PPG 업데이트 리스너: 외부(UI 등)로 PPG 업데이트 값을 전달하기 위한 인터페이스
    private PpgUpdateListener updateListener;

    // 업데이트 리스너를 설정하는 메서드
    public void setPpgUpdateListener(PpgUpdateListener listener) {
        this.updateListener = listener;
    }

    // PPG 업데이트 리스너 인터페이스 정의: onPpgUpdate() 메서드를 통해 PPG 값을 전달
    public interface PpgUpdateListener {
        void onPpgUpdate(int ppgValue);  // PPG 값을 전달하는 메서드
    }

    // flush 호출 주기 (예: 1초)
    private static final long FLUSH_INTERVAL_MS = 1000;
    // flush를 호출할 Handler (메인 스레드 사용)
    private final android.os.Handler flushHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    // flush를 주기적으로 호출하는 Runnable
    private final Runnable flushRunnable = new Runnable() {
        @Override
        public void run() {
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

    // 생성자: 객체 생성 시 초기화 작업 수행
    PpgListener() {
        // Firebase Database의 "HeartRateData" 경로를 참조
        databaseReference = FirebaseDatabase.getInstance()
                .getReference(USER_ID)
                .child("PPG_Data");
        databaseReference_survey = FirebaseDatabase.getInstance().getReference("DrowsinessData");

        // 초기에 Firebase 기존에 저장된 데이터를 모두 삭제(초기화)
        clearExistingData();

        // HealthTracker 이벤트 리스너 생성 (TrackerEventListener 구현)
        final HealthTracker.TrackerEventListener trackerEventListener = new HealthTracker.TrackerEventListener() {
            @Override
            public void onDataReceived(@NonNull List<DataPoint> list) {
                // HealthTracker로부터 데이터가 수신되면 호출됨
                // 수신된 각 DataPoint에 대해 updateHeartRate()를 호출하여 처리
                Log.d("Data Received", "onDataReceived is successfully worked.");
                for (DataPoint data : list) {
                    updatePpg(data);
//                    Log.d(APP_TAG, "PPG_Continuous : " + data);
                }
            }

            // HealthTracker가 flush(데이터 전송 완료)를 완료하면 호출됨
            @Override
            public void onFlushCompleted() {
                Log.i(APP_TAG, "onFlushCompleted called");
            }

            @Override
            public void onError(HealthTracker.TrackerError trackerError) {
                // 데이터 수신 중 오류 발생 시 호출됨
                Log.e(APP_TAG, "onError called: " + trackerError);
                // 오류 발생 시 BaseListener에서 핸들러 동작 플래그를 false로 설정
                setHandlerRunning(false);
            }
        };
        // BaseListener의 setTrackerEventListener()를 호출하여 이벤트 리스너 등록
        setTrackerEventListener(trackerEventListener);
    }



    private void clearExistingData() {
        // Firebase 데이터베이스에서 기존 데이터 삭제
        databaseReference.removeValue()
                .addOnSuccessListener(aVoid -> Log.d(APP_TAG, "All existing Heart Rate data cleared"))
                .addOnFailureListener(e -> Log.e(APP_TAG, "Failed to clear existing Heart Rate data", e));
        databaseReference_survey.removeValue()
                .addOnSuccessListener(aVoid -> Log.d(APP_TAG, "All existing Survey data cleared"))
                .addOnFailureListener(e -> Log.e(APP_TAG, "Failed to clear existing Heart Rate data", e));
    }

    // HealthTracker로부터 전달받은 DataPoint 데이터를 처리하는 메서드
    public void updatePpg(DataPoint dataPoint) {
        if (!shouldUploadData) { Log.d(APP_TAG, "shouldUploadData is not working"); return; } // 데이터 업로드가 활성화되어 있지 않다면 바로 리턴 (업로드 중이 아니면 처리하지 않음)

        // DataPoint에서 PPG_GREEN 값을 가져옴 (ValueKey.PpgSet.PPG_GREEN 상수를 사용)
        long currentTimestamp = dataPoint.getTimestamp();
        int ppgValue = dataPoint.getValue(ValueKey.PpgGreenSet.PPG_GREEN);  // PPG_GREEN 값을 가져옴

        PpgData newData = new PpgData(currentTimestamp, ppgValue);
        ppgDataList.add(newData);
        Log.d(APP_TAG, "PPG Green Value: " + ppgValue);  // PPG 값을 로그로 출력

        // 만약 UI 업데이트 리스너가 설정되어 있다면, 해당 리스너에게 PPG 값을 전달
        if (updateListener != null) {
            updateListener.onPpgUpdate(ppgValue);  // PPG 값을 전달
        }

        // 피크 감지 및 RR 간격 계산을 위한 로직
        // 현재 PPG 값을 리스트에 추가
        uploadPpgDataToFirebase(ppgValue, currentTimestamp);
    }

    // 계산된 RR 간격과 타임스탬프를 Firebase에 업로드하는 메서드
    private void uploadPpgDataToFirebase(long ppgData, long timestamp) {
        String formattedTimestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",
                Locale.getDefault()).format(new Date(timestamp));

        Map<String, Object> singleData = new HashMap<>();
        singleData.put("ppgGreen", ppgData);
        singleData.put("timestamp", formattedTimestamp);
        singleData.put("isError", false);  // 정상적인 데이터는 에러가 아님

        // Firebase Database에 데이터 푸시 (push()를 통해 고유 키 생성 후 setValue)
        databaseReference.push().setValue(singleData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(APP_TAG, "Single PPG Green data uploaded successfully");

                    pruneOldData(databaseReference, "timestamp");
                })
                .addOnFailureListener(e -> {
                    Log.e(APP_TAG, "Failed to upload Single RR Interval data", e);
                    // 실패 시 재시도 로직 추가 가능
                });
    }

    // 데이터 업로드 활성화 메서드 (startDataUpload() 호출 시 true로 설정)
    public void startDataUpload() {
        shouldUploadData = true;
        // flush()를 FLUSH_INTERVAL_MS 간격으로 호출
        flushHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS);
    }

    // 데이터 업로드 중지 메서드 (stopDataUpload() 호출 시 false로 설정)
    public void stopDataUpload() {
        shouldUploadData = false;
        flushHandler.removeCallbacks(flushRunnable);
    }
}

