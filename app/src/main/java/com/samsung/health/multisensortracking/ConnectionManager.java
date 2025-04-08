package com.samsung.health.multisensortracking;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.samsung.android.service.health.tracking.ConnectionListener;
import com.samsung.android.service.health.tracking.HealthTracker;
import com.samsung.android.service.health.tracking.HealthTrackerException;
import com.samsung.android.service.health.tracking.HealthTrackingService;
import com.samsung.android.service.health.tracking.data.HealthTrackerType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ConnectionManager {
    private final static String TAG = "ConnectionManager";
    private final ConnectionObserver connectionObserver; // 연결 상태를 전달할 옵저버 (연결 성공, 실패 등 상태를 외부에 알림)
    private HealthTrackingService healthTrackingService = null; // HealthTrackingService 객체 (Samsung의 Health Tracking Service와의 연결 관리)

    // ConnectionListener: 연결 상태를 처리하기 위한 리스너
    private final ConnectionListener connectionListener = new ConnectionListener() {
        @Override
        public void onConnectionSuccess() {
            Log.i(TAG, "Connected to HealthTrackingService");
            connectionObserver.onConnectionResult(R.string.ConnectedToHs); // 연결 성공 시 옵저버에 결과 전달 (예: "ConnectedToHs" 문자열 리소스)

            // 서비스가 지원하는 HealthTrackerType 목록을 가져옴
            List<HealthTrackerType> supportedTrackers = healthTrackingService.getTrackingCapability().getSupportHealthTrackerTypes();
            // 지원하는 각 트래커 타입 이름을 로그에 출력
            for (HealthTrackerType tracker : supportedTrackers) {
                if (tracker != null) {
                    Log.i(TAG, "Supported Tracker: " + tracker.name());
                } else {
                    Log.w(TAG, "지원 트래커 리스트 내에 null 값이 존재합니다.");
                }
            }


            // PPG_CONTINUOUS 트래커(심박수 측정)가 지원되는지 확인
            if (!isPpgAvailable(healthTrackingService)) {
                Log.i(TAG, "Device does not support PPG Continuous tracking");
                // 지원되지 않을 경우 옵저버에 실패 메시지 전달
                connectionObserver.onConnectionResult(R.string.NoHrSupport); // 지원 불가 알림
            }
        }

        @Override
        public void onConnectionEnded() {
            Log.i(TAG, "Disconnected from HealthTrackingService"); // 연결 종료 알림
        }

        @Override
        public void onConnectionFailed(HealthTrackerException e) {
            Log.e(TAG, "Connection Failed: " + e.getErrorCode());
            connectionObserver.onError(e); // 연결 실패 시 예외 전달
            if (e.hasResolution()) {

            }
        }
    };

    // ConnectionManager 생성자
    ConnectionManager(ConnectionObserver observer) {
        connectionObserver = observer; // 연결 상태 옵저버 설정
    }

    // Health Tracking Service 연결
    public void connect(Context context) {
        // ConnectionListener와 context를 사용해 HealthTrackingService 인스턴스 생성
        healthTrackingService = new HealthTrackingService(connectionListener, context);
        healthTrackingService.connectService(); // 서비스 연결 시도
    }

    // Health Tracking Service 연결 해제
    public void disconnect() {
        if (healthTrackingService != null) {
            healthTrackingService.disconnectService();
            Log.i(TAG, "Disconnected from HealthTrackingService");
        }
    }

    // PPG Continuous 트래커 초기화
    public void initPpg(PpgListener ppgListener) {
        try {
            // PPG_CONTINUOUS 트래커 초기화를 위해 PpgType을 지정
//            Set<PpgType> ppgTypes = new HashSet<>();
//            ppgTypes.add(PpgType.GREEN); // 원하는 경우 PpgType.IR, PpgType.RED 추가 가능

            // HealthTrackingService로부터 PPG_CONTINUOUS 타입의 트래커를 가져옴
            final HealthTracker ppgTracker = healthTrackingService.getHealthTracker(HealthTrackerType.PPG_GREEN);
            if (ppgTracker != null) {
                ppgListener.setHealthTracker(ppgTracker); // PpgListener에 트래커 설정
                setHandlerForBaseListener(ppgListener); // 메인 스레드 핸들러 설정
                Log.i(TAG, "PPG Continuous Tracker initialized successfully.");
            } else {
                Log.e(TAG, "Failed to initialize PPG Continuous Tracker.");
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid HealthTrackerType: " + e.getMessage());
        }
    }

    // BaseListener에 메인 스레드 핸들러 설정
    private void setHandlerForBaseListener(BaseListener baseListener) {
        baseListener.setHandler(new Handler(Looper.getMainLooper())); // 메인 스레드 핸들러 지정
    }

    // PPG_CONTINUOUS 트래커 지원 여부 확인
    private boolean isPpgAvailable(@NonNull HealthTrackingService healthTrackingService) {
        // 지원하는 트래커 타입 목록 가져오기
        final List<HealthTrackerType> availableTrackers = healthTrackingService.getTrackingCapability().getSupportHealthTrackerTypes();

        // HealthTrackingService가 지원하는 트래커 타입 중 PPG_CONTINUOUS가 있는지 확인하는 메서드
        return availableTrackers.contains(HealthTrackerType.PPG_GREEN);
    }
}