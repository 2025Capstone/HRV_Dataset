package com.samsung.health.multisensortracking;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.samsung.android.service.health.tracking.HealthTrackerException;
import com.samsung.health.multisensortracking.databinding.ActivityMainBinding;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final Logger log = LogManager.getLogger(MainActivity.class);
    private GestureDetector gestureDetector;
    // PERMISSION_REQUEST_CODE 상수를 추가 (원하는 값, 여기서는 0 사용)
    private final static int PERMISSION_REQUEST_CODE = 0;
    private static final String USER_ID = "123456";  // 사용자별 고정 UID
    private DatabaseReference pairingRef;
    private DatabaseReference stopRef;
    private final static String APP_TAG = "MainActivity";
    private final static int MEASUREMENT_DURATION = 3603000; // 측정 길이(1시간)
    private final static Long MEASUREMENT_TICK = 250L; // 측정 간격, ms단위

    private final AtomicBoolean isMeasurementRunning = new AtomicBoolean(false); // 측정 실행 여부 플래그
    Thread uiUpdateThread = null;
    private ConnectionManager connectionManager; // HealthTrackingService와 연결을 관리하는 객체
    private PpgListener ppgListener = null; // 심박수 데이터를 처리할 리스너
//    private AccelerometerListener accelerometerListener = null; // 가속도계 데이터를 처리할 리스너
    private boolean connected = false; // 서비스 연결 여부
    private boolean permissionGranted = false; // 권한 부여 여부

    // UI 요소들
    private TextView txtHeartRate; // 심박수 표시 텍스트뷰
    private TextView txtTimeElapsed; // 경과 시간 표시 텍스트뷰
    private Button butStart; // 시작/중지 버튼
    private CircularProgressIndicator measurementProgress = null; // 측정 진행 표시기
    private DatabaseReference databaseReference; // Firebase 데이터베이스 참조 객체
//    private DatabaseReference databaseReference_drowsinessLevel;
    private DatabaseReference databaseReference_accelerometer;


    // 측정 타이머
    final CountDownTimer countDownTimer = new CountDownTimer(MEASUREMENT_DURATION, MEASUREMENT_TICK) {
        @Override
        public void onTick(long timeLeft) {
            if (isMeasurementRunning.get()) {
                // UI 스레드에서 측정 진행률 업데이트 (progress bar 진행)
                runOnUiThread(() ->
                        measurementProgress.setProgress(measurementProgress.getProgress() + 1, true));

                // 경과 시간 계산
                long elapsedMillis = MEASUREMENT_DURATION - timeLeft;
                long seconds = (elapsedMillis / 1000) % 60;
                long minutes = (elapsedMillis / (1000 * 60)) % 60;
                long hours = (elapsedMillis / (1000 * 60 * 60));

                String elapsedTime = String.format("%02d:%02d:%02d", hours, minutes, seconds);

                // txtTimeElapsed에 경과 시간 설정
                txtTimeElapsed.setText(elapsedTime);
            } else
                cancel();
        }

        @Override
        public void onFinish() {
            if (!isMeasurementRunning.get())
                return;

            // 측정 종료 처리
            ppgListener.stopTracker();
//            accelerometerListener.stopTracker();

            isMeasurementRunning.set(false);
            runOnUiThread(() -> {
                // UI 초기화: 심박수, 경과 시간, 버튼 텍스트, progress bar 최대치 설정
                txtHeartRate.setText(R.string.HeartRateDefaultValue);
                txtTimeElapsed.setText("00:00:00");
                butStart.setText(R.string.StartLabel);
                measurementProgress.setProgress(measurementProgress.getMax(), true);
            });
            // 화면이 꺼지지 않도록 설정했던 플래그 제거
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    };

    // 연결 상태 옵저버: ConnectionManager의 연결 결과를 받아 처리
    private final ConnectionObserver connectionObserver = new ConnectionObserver() {
        @Override
        public void onConnectionResult(int stringResourceId) {
            runOnUiThread(() ->
                    Toast.makeText(getApplicationContext(), getString(stringResourceId), Toast.LENGTH_LONG).show());

            // 연결 성공이 아닌 경우 (예: 에러 메시지) 종료
            if (stringResourceId != R.string.ConnectedToHs) {
                finish(); // 연결 실패 시 종료
            }

            connected = true; // 연결 성공

            ppgListener = new PpgListener();// 리스너 초기화
            connectionManager.initPpg(ppgListener); // ConnectionManager를 통해 PPG 트래커 초기화

//            accelerometerListener = new AccelerometerListener(); // 가속도계 리스너 초기화
//            connectionManager.initAccelerometer(accelerometerListener); // ConnectionManager를 통해 가속도계 트래커 초기화
        }

        @Override
        public void onError(HealthTrackerException e) {
            if (e.hasResolution()) {
                e.resolve(MainActivity.this); // 문제 해결
            } else {
                Toast.makeText(getApplicationContext(), getString(R.string.ConnectionError), Toast.LENGTH_LONG).show();
                Log.e(APP_TAG, "Connection error: " + e.getMessage());
                finish();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Firebase 데이터베이스 참조 초기화 ("HeartRateData" 노드)
        databaseReference = FirebaseDatabase.getInstance()
                .getReference(USER_ID)
                .child("PPG_Data");
//        databaseReference_drowsinessLevel = FirebaseDatabase.getInstance().getReference("DrowsinessData");
//        databaseReference_accelerometer = FirebaseDatabase.getInstance().getReference("AccelerometerData");
        pairingRef = FirebaseDatabase.getInstance()
                .getReference(USER_ID)
                .child("pairing")
                .child("pair_code");
        stopRef = FirebaseDatabase.getInstance()
                .getReference(USER_ID)
                .child("pairing")
                .child("stop");

        // View 바인딩: ActivityMainBinding 사용하여 레이아웃 inflate
        final ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // UI 요소들을 바인딩
        txtHeartRate = binding.txtHeartRate;
        txtTimeElapsed = binding.txtTimeElapsed; // txtTimeElapsed 바인딩
        butStart = binding.butStart;
        measurementProgress = binding.progressBar;

        View rootLayout = findViewById(R.id.root_layout);
        rootLayout.setOnTouchListener((v, event) -> {
            Log.d("Gesture", "rootLayout onTouch: " + event.toString());
            return gestureDetector.onTouchEvent(event);
        });

        // 프로그레스바 크기 조정
        adjustProgressBar(measurementProgress);
        measurementProgress.setMax((int) (MEASUREMENT_DURATION / MEASUREMENT_TICK));

        // BODY_SENSORS 와 ACTIVITY_RECOGNITION 권한 체크
        if (ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_DENIED ||
                ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_DENIED) {

            requestPermissions(
                    new String[]{Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION},
                    PERMISSION_REQUEST_CODE
            );
        } else {
            permissionGranted = true;
            createConnectionManager();
        }

        databaseReference.setValue("Test Data")
                .addOnSuccessListener(aVoid -> Log.d(APP_TAG, "Test data uploaded"))
                .addOnFailureListener(e -> Log.e(APP_TAG, "Test data upload failed", e));

        stopRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snap) {
                Boolean shouldStop = snap.getValue(Boolean.class);
                Log.d("STOP_REF", "onDataChange() 호출됨 — shouldStop=" + shouldStop);
                if (Boolean.TRUE.equals(shouldStop)) {
                    Log.d("STOP_REF", ">>> PPG 리스너 중지 트리거");
                    // PPG 측정/업로드 중지
                    ppgListener.stopTracker();
                    ppgListener.stopDataUpload();

                    isMeasurementRunning.set(false);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                    // 약간의 딜레이 후 UI 초기화 (버튼 텍스트, progress 초기화 등)
                    final Handler progressHandler = new Handler(Looper.getMainLooper());
                    progressHandler.postDelayed(() -> {
                        butStart.setText(R.string.StartLabel);
                        measurementProgress.setProgress(0);
                        butStart.setEnabled(true);
                    }, MEASUREMENT_TICK * 2);

                    // paired 리셋
                    FirebaseDatabase.getInstance()
                            .getReference(USER_ID)
                            .child("pairing")
                            .child("paired")
                            .setValue(false);
                } else {
                    Log.d("STOP_REF", ">>> stop=false 이므로 PPG 계속 동작");
                }
            }
            @Override public void onCancelled(DatabaseError e) {
                Log.e("STOP_REF", "stopRef 읽기 실패", e.toException());
            }
        });


        // 스와이프 제스처 객체 생성
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_THRESHOLD = 100;
            private static final int SWIPE_VELOCITY_THRESHOLD = 100;

            @Override
            public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
                float diffX = e2.getX() - e1.getX();
                float diffY = e2.getY() - e1.getY();

                if (Math.abs(diffX) > Math.abs(diffY)) {
                    if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX < 0) {
                            Log.d("Gesture", "오른쪽-왼쪽 스와이프 감지 - 다이얼로그 호출");
                            showPairingDialog();
                            return true;
                        } else {

                            Log.d("Gesture", "왼쪽-오른쪽 스와이프 감지");
                        }
                    }
                }
                return false;
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        Log.d("Gesture", "onTouchEvent: " + event.toString());
        if (gestureDetector.onTouchEvent(event)) {
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 액티비티 종료 시, 트래커 중지 및 서비스 연결 해제
        if (ppgListener != null)
            ppgListener.stopTracker();
//        if (accelerometerListener != null)
//            accelerometerListener.stopTracker();
        if (connectionManager != null) {
            connectionManager.disconnect();
        }
    }

    // 졸음 여부 설문조사 다이얼로그 창 표시 메서드
    public void showDrowsinessDialog(View view) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_MainTheme);
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_custom, null);
        builder.setView(dialogView);
        builder.setCancelable(true);

        AlertDialog dialog = builder.create();
        dialog.show();

        RadioGroup rgDrowsiness = dialogView.findViewById(R.id.rgDrowsiness);
        Button btnSubmit = dialogView.findViewById(R.id.btnSubmitDrowsiness);
        Button btnCancle = dialogView.findViewById(R.id.btnCancleDrowsiness);

        btnCancle.setOnClickListener(v -> dialog.dismiss());

        btnSubmit.setOnClickListener(v -> {
            int selectedId = rgDrowsiness.getCheckedRadioButtonId();
            if (selectedId == -1) {
                Toast.makeText(this, "졸음 단계를 선택해주세요.", Toast.LENGTH_SHORT).show();
                return;
            }

            RadioButton selectedRadio = dialogView.findViewById(selectedId);
            int drowsinessLevel = Integer.parseInt(selectedRadio.getText().toString());
            long timeStamp = System.currentTimeMillis();
            String formattedTimestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date(timeStamp));


            DatabaseReference drowsinessRef = FirebaseDatabase.getInstance().getReference("DrowsinessData");
            Map<String, Object> data = new HashMap<>();
            data.put("timestamp", formattedTimestamp);
            data.put("drowsinessLevel", drowsinessLevel);

            drowsinessRef.push().setValue(data)
                    .addOnSuccessListener(aVoid -> {
                        Toast.makeText(this, "졸음 설문 결과가 기록되었습니다.", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    })
                    .addOnFailureListener(e -> {
                        Toast.makeText(this, "설문 결과 업로드 실패", Toast.LENGTH_SHORT).show();
                        Log.e("DrowsinessLevel Survey Upload", "Upload Failure", e);
                    });
        });
    }

    private void showPairingDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_MainTheme);
        View view = getLayoutInflater().inflate(R.layout.dialog_pairing, null);
        builder.setView(view).setCancelable(true);
        AlertDialog dlg = builder.create();
        dlg.show();

        EditText edtCode = view.findViewById(R.id.edtPairCode);
        Button btnPair = view.findViewById(R.id.btnPairing);
        Button btnCancle = view.findViewById(R.id.btnCancle);

        btnCancle.setOnClickListener(v -> dlg.dismiss());
        btnPair.setOnClickListener(v -> {
            String input = edtCode.getText().toString().trim().toUpperCase();

            DatabaseReference pairingNode = FirebaseDatabase
                    .getInstance()
                    .getReference(USER_ID)
                    .child("pairing");

            pairingNode.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snap) {
                    String realCode  = snap.child("pair_code").getValue(String.class);
                    Boolean stopFlag = snap.child("stop").getValue(Boolean.class);
                    if (realCode != null && realCode.equals(input) && Boolean.FALSE.equals(stopFlag)) {
                        dlg.dismiss();
                        // PPG 측정 시작
                        ppgListener.startTracker();
                        ppgListener.startDataUpload();
                        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                        // 심박수 업데이트 리스너 설정: 심박수 변화가 발생하면 txtHeartRate 업데이트
                        ppgListener.setPpgUpdateListener(ppg -> runOnUiThread(() -> {
                            String ppgText = ppg + " ms";
                            txtHeartRate.setText(ppgText);
                        }));

                        // CountDownTimer를 별도 스레드에서 실행하여 UI 업데이트 시작
                        uiUpdateThread = new Thread(countDownTimer::start);
                        uiUpdateThread.start();

                        // paired 플래그 쓰기
                        pairingNode.child("paired").setValue(true);
                    } else {
                        Toast.makeText(MainActivity.this, "페어링 실패", Toast.LENGTH_SHORT).show();
                    }
                }
                @Override public void onCancelled(DatabaseError e) { /* 에러 처리 */ }
            });
        });
    }



    // ConnectionManager 객체를 생성하고 HealthTrackingService와 연결 시도
    void createConnectionManager() {
        try {
            connectionManager = new ConnectionManager(connectionObserver);
            connectionManager.connect(getApplicationContext());

        } catch (Throwable t) {
            Log.e(APP_TAG, t.getMessage());
        }
    }

    // 프로그레스바의 크기를 화면 크기에 맞게 조정하는 메서드
    void adjustProgressBar(CircularProgressIndicator progressBar) {
        final DisplayMetrics displayMetrics = this.getResources().getDisplayMetrics();
        final int pxWidth = displayMetrics.widthPixels;
        final int padding = 1;
        progressBar.setPadding(padding, padding, padding, padding);
        final int trackThickness = progressBar.getTrackThickness();

        final int progressBarSize = pxWidth - trackThickness - 2 * padding;
        progressBar.setIndicatorSize(progressBarSize);
    }

    // 측정 시작/중지를 토글하는 버튼 클릭 이벤트 핸들러
    public void performMeasurement(View view) {
        // 권한이 거부되었거나 연결에 실패했을 경우
        if (isPermissionsOrConnectionInvalid()) {
            return;
        }

        if (!isMeasurementRunning.get()) {
            // 측정 시작
            butStart.setText(R.string.StopLabel); // 버튼 텍스트 변경
            measurementProgress.setProgress(0); // progress 초기화
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // 화면이 꺼지지 않도록 설정

            // 심박수 업데이트 리스너 설정: 심박수 변화가 발생하면 txtHeartRate 업데이트
            ppgListener.setPpgUpdateListener(ppg -> runOnUiThread(() -> {
                String ppgText = ppg + " ms";
                txtHeartRate.setText(ppgText);
            }));

            // 트래커 측정을 시작하고, 데이터 업로드도 활성화
            ppgListener.startTracker(); // 측정 시작
            ppgListener.startDataUpload(); // 데이터 업로드 활성화

//            accelerometerListener.startTracker();
//            accelerometerListener.startDataUpload();


            // 측정 시작 시 Firebase 데이터 초기화 (이전 데이터 삭제)
            databaseReference.removeValue()
                    .addOnSuccessListener(aVoid -> Log.d(APP_TAG, "Firebase data cleared successfully"))
                    .addOnFailureListener(e -> Log.e(APP_TAG, "Failed to clear Firebase data", e));
            // 측정 시작 시 Firebase 데이터 초기화 (이전 데이터 삭제)
//            databaseReference_drowsinessLevel.removeValue()
//                    .addOnSuccessListener(aVoid -> Log.d(APP_TAG, "Firebase data cleared successfully"))
//                    .addOnFailureListener(e -> Log.e(APP_TAG, "Failed to clear Firebase data", e));
            // 측정 시작 시 Firebase 데이터 초기화 (이전 데이터 삭제)
//            databaseReference_accelerometer.removeValue()
//                    .addOnSuccessListener(aVoid -> Log.d(APP_TAG, "Firebase data cleared successfully"))
//                    .addOnFailureListener(e -> Log.e(APP_TAG, "Failed to clear Firebase data", e));

            isMeasurementRunning.set(true);

            // CountDownTimer를 별도 스레드에서 실행하여 UI 업데이트 시작
            uiUpdateThread = new Thread(countDownTimer::start);
            uiUpdateThread.start();
        } else {
            // 측정 중이면 중지: 플래그 해제, 화면 유지 플래그 제거, 트래커 및 데이터 업로드 중지
            isMeasurementRunning.set(false);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            ppgListener.stopTracker(); // 측정 종료
            ppgListener.stopDataUpload(); // 데이터 업로드 비활성화

//            accelerometerListener.stopTracker();
//            accelerometerListener.stopDataUpload();

            // 약간의 딜레이 후 UI 초기화 (버튼 텍스트, progress 초기화 등)
            final Handler progressHandler = new Handler(Looper.getMainLooper());
            progressHandler.postDelayed(() -> {
                butStart.setText(R.string.StartLabel);
                measurementProgress.setProgress(0);
                butStart.setEnabled(true);
            }, MEASUREMENT_TICK * 2);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            permissionGranted = true;
            for (int i = 0; i < permissions.length; ++i) {
                if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                    // 사용자가 권한 거부한 경우
                    if (!shouldShowRequestPermissionRationale(permissions[i]))
                        Toast.makeText(getApplicationContext(), getString(R.string.PermissionDeniedPermanently), Toast.LENGTH_LONG).show();
                    else
                        Toast.makeText(getApplicationContext(), getString(R.string.PermissionDeniedRationale), Toast.LENGTH_LONG).show();
                    finish();
                    permissionGranted = false;
                    return;
                }
            }
            createConnectionManager();
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    // 권한 및 연결 상태가 올바른지 확인하는 헬퍼 메서드
    private boolean isPermissionsOrConnectionInvalid() {
        if (!permissionGranted) {
            Toast.makeText(getApplicationContext(), getString(R.string.PermissionDenied), Toast.LENGTH_SHORT).show();
            return true;
        }

        if (!connected) {
            Toast.makeText(getApplicationContext(), getString(R.string.NotConnected), Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }



}