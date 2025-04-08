/*
 * Copyright 2022 Samsung Electronics Co., Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.samsung.health.multisensortracking;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.samsung.android.service.health.tracking.HealthTracker;

public class BaseListener {

    // Handler: 메인 스레드 또는 백그라운드에서 작업을 예약하고 실행할 때 사용
    private Handler handler;

    // Samsung HealthTracker 객체: 센서 데이터를 수집하고 이벤트를 관리하는 역할
    private HealthTracker healthTracker;

    // 핸들러의 동작 여부를 나타내는 플래그
    private boolean isHandlerRunning = false;

    // HealthTracker 이벤트 리스너: 이벤트 처리를 위해 설정
    private HealthTracker.TrackerEventListener trackerEventListener = null;

    // HealthTracker를 반환하는 getter (추가)
    public HealthTracker getHealthTracker() {
        return healthTracker;
    }

    // HealthTracker 설정 메서드
    public void setHealthTracker(HealthTracker tracker) {
        healthTracker = tracker;
    }

    // 핸들러 설정 메서드
    public void setHandler(Handler handler) {
        this.handler = handler;
    }

    // 핸들러 동작 상태 설정
    public void setHandlerRunning(boolean handlerRunning) {
        isHandlerRunning = handlerRunning;
    }

    // 트래커 이벤트 리스너 설정
    public void setTrackerEventListener(HealthTracker.TrackerEventListener tracker) {
        trackerEventListener = tracker;
    }

    public void startTracker() {
        // 핸들러가 없다면 메인 루퍼(메인 스레드)의 핸들러 생성
        if (handler == null) {
            setHandler(new Handler(Looper.getMainLooper()));
        }
        // 핸들러가 실행 중이 아니라면 이벤트 리스너를 설정하는 작업을 핸들러에 게시
        if (!isHandlerRunning) {
            handler.post(() -> {
                // HealthTracker에 이벤트 리스너를 설정하여 센서 데이터 이벤트를 받을 수 있도록 함
                healthTracker.setEventListener(trackerEventListener);
                // 핸들러 실행 플래그를 true로 변경
                setHandlerRunning(true);
            });
        }
    }

    // 트래커를 중지하는 메서드
    public void stopTracker() {
        // 핸들러가 실행 중이면 이벤트 리스너를 해제하고 플래그를 false로 변경
        if (isHandlerRunning) {
            healthTracker.unsetEventListener();
            setHandlerRunning(false);

            // 핸들러에 예약된 모든 콜백과 메시지를 제거하여 남은 작업이 없도록 함
            handler.removeCallbacksAndMessages(null);
        }
    }

}
