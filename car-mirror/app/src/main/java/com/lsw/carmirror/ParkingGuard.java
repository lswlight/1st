package com.lsw.carmirror;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 정차 여부 판정.
 *
 * <p>차량 속도(안드로이드 오토 제공)가 있으면 그것을, 없으면 휴대폰 GPS 속도를 쓴다.
 * 정차로 확인될 때만 화면을 표시하고, 속도를 알 수 없으면 화면을 숨긴다.
 */
public final class ParkingGuard {
	private static final String TAG = "ParkingGuard";

	public enum State { PARKED, MOVING, UNKNOWN }

	public interface Listener {
		void onParkingStateChanged(State state);
	}

	/** 차량 속도 기준: 이 속도 이상이면 바로 주행 (약 1 km/h). */
	static final float CAR_MOVING_MPS = 0.3f;
	/** GPS 기준: 이 속도 이상이면 주행으로 본다 (약 5.4 km/h). */
	static final float MOVING_MPS = 1.5f;
	/** 이 속도 이하가 일정 시간 이어져야 정차로 본다 (약 1.8 km/h, GPS 오차 감안). */
	static final float STOPPED_MPS = 0.5f;
	static final long STOP_HOLD_MS = 3_000;
	/** 측정값이 이 시간 안에 들어온 것만 유효한 값으로 쓴다. */
	static final long FRESH_MS = 5_000;
	/** 모든 측정이 이 시간 이상 끊기면 '확인 불가'로 바꾸고 화면을 숨긴다. */
	static final long STALE_MS = 30_000;

	private static final ParkingGuard INSTANCE = new ParkingGuard();

	public static ParkingGuard get() {
		return INSTANCE;
	}

	private final Handler main = new Handler(Looper.getMainLooper());
	private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
	private final Runnable ticker = new Runnable() {
		@Override
		public void run() {
			evaluate();
			main.postDelayed(this, 1_000);
		}
	};

	private State state = State.UNKNOWN;
	private float carSpeed = Float.NaN;
	private long carTime;
	private float gpsSpeed = Float.NaN;
	private long gpsTime;
	private long lastDataTime;
	private long stoppedSince = -1;
	private boolean tickerStarted;

	private LocationManager locationManager;
	private int gpsUsers;
	private final LocationListener gpsListener = new LocationListener() {
		@Override
		public void onLocationChanged(Location location) {
			if (location.hasSpeed()) onGpsSpeed(location.getSpeed());
		}

		// API 29 에서는 아래 메서드들이 추상 메서드라 직접 구현해야 한다.
		@Override
		public void onStatusChanged(String provider, int status, Bundle extras) {
		}

		@Override
		public void onProviderEnabled(String provider) {
		}

		@Override
		public void onProviderDisabled(String provider) {
		}
	};

	private ParkingGuard() {
	}

	public synchronized State getState() {
		return state;
	}

	public boolean isParked() {
		return getState() == State.PARKED;
	}

	public void addListener(Listener l) {
		listeners.add(l);
		ensureTicker();
	}

	public void removeListener(Listener l) {
		listeners.remove(l);
	}

	public void onCarSpeed(float metersPerSecond) {
		synchronized (this) {
			carSpeed = Math.abs(metersPerSecond);
			carTime = SystemClock.elapsedRealtime();
			lastDataTime = carTime;
		}
		ensureTicker();
		evaluate();
	}

	public void onGpsSpeed(float metersPerSecond) {
		synchronized (this) {
			gpsSpeed = Math.abs(metersPerSecond);
			gpsTime = SystemClock.elapsedRealtime();
			lastDataTime = gpsTime;
		}
		ensureTicker();
		evaluate();
	}

	/** GPS 속도 수신 시작. 호출 횟수만큼 {@link #stopGps()} 를 불러야 꺼진다. */
	public void startGps(Context ctx) {
		main.post(() -> {
			if (gpsUsers++ > 0) return;
			Context app = ctx.getApplicationContext();
			if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
					!= PackageManager.PERMISSION_GRANTED) {
				Log.w(TAG, "위치 권한이 없어 GPS 속도를 쓸 수 없음");
				return;
			}
			try {
				locationManager = app.getSystemService(LocationManager.class);
				locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f,
						gpsListener, Looper.getMainLooper());
			} catch (RuntimeException e) {
				Log.w(TAG, "GPS 시작 실패", e);
				locationManager = null;
			}
		});
		ensureTicker();
	}

	public void stopGps() {
		main.post(() -> {
			if (gpsUsers == 0 || --gpsUsers > 0) return;
			if (locationManager != null) {
				try {
					locationManager.removeUpdates(gpsListener);
				} catch (RuntimeException e) {
					Log.w(TAG, "GPS 종료 실패", e);
				}
				locationManager = null;
			}
		});
	}

	private void ensureTicker() {
		main.post(() -> {
			if (tickerStarted) return;
			tickerStarted = true;
			main.post(ticker);
		});
	}

	private void evaluate() {
		State before;
		State after;
		synchronized (this) {
			before = state;
			long now = SystemClock.elapsedRealtime();
			boolean carFresh = !Float.isNaN(carSpeed) && now - carTime <= FRESH_MS;
			boolean gpsFresh = !Float.isNaN(gpsSpeed) && now - gpsTime <= FRESH_MS;

			if (!carFresh && !gpsFresh) {
				stoppedSince = -1;
				if (lastDataTime == 0 || now - lastDataTime > STALE_MS) state = State.UNKNOWN;
				// 잠깐 끊긴 경우(터널 등)는 직전 판정을 유지한다.
			} else {
				// 차량 속도는 정확하므로 있으면 그것만 쓰고, 없을 때만 GPS 를 쓴다.
				float speed = carFresh ? carSpeed : gpsSpeed;
				float movingAt = carFresh ? CAR_MOVING_MPS : MOVING_MPS;
				float stoppedAt = carFresh ? CAR_MOVING_MPS : STOPPED_MPS;
				if (speed >= movingAt) {
					stoppedSince = -1;
					state = State.MOVING;
				} else if (speed <= stoppedAt) {
					if (stoppedSince < 0) stoppedSince = now;
					if (state == State.PARKED || now - stoppedSince >= STOP_HOLD_MS) {
						state = State.PARKED;
					}
				} else {
					// 서행 구간: 정차 대기 시간을 초기화하고 직전 판정 유지
					stoppedSince = -1;
				}
			}
			after = state;
		}
		if (before != after) {
			Log.i(TAG, "정차 판정: " + before + " -> " + after);
			main.post(() -> {
				for (Listener l : listeners) l.onParkingStateChanged(after);
			});
		}
	}
}
