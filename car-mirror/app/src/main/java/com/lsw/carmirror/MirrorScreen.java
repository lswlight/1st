package com.lsw.carmirror;

import android.content.pm.PackageManager;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.hardware.CarHardwareManager;
import androidx.car.app.hardware.common.CarValue;
import androidx.car.app.hardware.common.OnCarDataAvailableListener;
import androidx.car.app.hardware.info.CarInfo;
import androidx.car.app.hardware.info.Speed;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.util.Collections;

/** 차량 화면. 휴대폰 화면을 그리는 Surface 와 뒤로/홈/최근 앱 버튼을 제공한다. */
public class MirrorScreen extends Screen implements SurfaceCallback {
	private static final String TAG = "MirrorScreen";
	private static final String CAR_SPEED_PERMISSION = "com.google.android.gms.permission.CAR_SPEED";

	private final Handler main = new Handler(Looper.getMainLooper());
	private final OnCarDataAvailableListener<Speed> speedListener = this::onSpeed;
	private CarInfo carInfo;
	private boolean speedListening;

	private float scrollX;
	private float scrollY;
	private final Runnable flushScroll = this::flushScroll;

	public MirrorScreen(@NonNull CarContext carContext) {
		super(carContext);
		MirrorController.get().init(carContext);
		carContext.getCarService(AppManager.class).setSurfaceCallback(this);
		getLifecycle().addObserver(new DefaultLifecycleObserver() {
			@Override
			public void onStart(@NonNull LifecycleOwner owner) {
				startSpeed();
			}

			@Override
			public void onStop(@NonNull LifecycleOwner owner) {
				stopSpeed();
			}
		});
	}

	@NonNull
	@Override
	public Template onGetTemplate() {
		ActionStrip strip = new ActionStrip.Builder()
				.addAction(iconAction(R.drawable.ic_back, TouchService::back))
				.addAction(iconAction(R.drawable.ic_home, TouchService::home))
				.addAction(iconAction(R.drawable.ic_recents, TouchService::recents))
				.build();
		// PAN 버튼을 누르면 차량 화면 드래그가 휴대폰 스크롤로 전달된다.
		ActionStrip mapStrip = new ActionStrip.Builder().addAction(Action.PAN).build();
		return new NavigationTemplate.Builder()
				.setActionStrip(strip)
				.setMapActionStrip(mapStrip)
				.build();
	}

	private Action iconAction(int iconRes, Runnable onClick) {
		CarIcon icon = new CarIcon.Builder(
				IconCompat.createWithResource(getCarContext(), iconRes)).build();
		return new Action.Builder().setIcon(icon).setOnClickListener(onClick::run).build();
	}

	// ---------------------------------------------------------------- 차량 속도

	private void startSpeed() {
		CarContext ctx = getCarContext();
		if (ctx.checkSelfPermission(CAR_SPEED_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
			try {
				ctx.requestPermissions(Collections.singletonList(CAR_SPEED_PERMISSION),
						(granted, rejected) -> {
							if (!granted.isEmpty()) registerSpeed();
						});
			} catch (RuntimeException e) {
				Log.w(TAG, "차량 속도 권한 요청 실패", e);
			}
		}
		registerSpeed();
	}

	private void registerSpeed() {
		if (speedListening) return;
		try {
			CarHardwareManager hw = getCarContext().getCarService(CarHardwareManager.class);
			carInfo = hw.getCarInfo();
			carInfo.addSpeedListener(getCarContext().getMainExecutor(), speedListener);
			speedListening = true;
		} catch (RuntimeException e) {
			// 권한이 없거나 차량이 속도를 제공하지 않으면 GPS 만 쓴다.
			Log.w(TAG, "차량 속도 사용 불가, GPS 로 판정", e);
		}
	}

	private void stopSpeed() {
		if (!speedListening || carInfo == null) return;
		try {
			carInfo.removeSpeedListener(speedListener);
		} catch (RuntimeException e) {
			Log.w(TAG, "차량 속도 해제 실패", e);
		}
		speedListening = false;
	}

	private void onSpeed(@NonNull Speed speed) {
		Float v = valueOf(speed.getRawSpeedMetersPerSecond());
		if (v == null) v = valueOf(speed.getDisplaySpeedMetersPerSecond());
		if (v != null) ParkingGuard.get().onCarSpeed(v);
	}

	private static Float valueOf(CarValue<Float> cv) {
		if (cv == null || cv.getStatus() != CarValue.STATUS_SUCCESS) return null;
		return cv.getValue();
	}

	// ---------------------------------------------------------------- 차량 화면 Surface

	@Override
	public void onSurfaceAvailable(@NonNull SurfaceContainer container) {
		Surface s = container.getSurface();
		if (s == null) return;
		MirrorController.get().setCarSurface(s, container.getWidth(), container.getHeight(),
				container.getDpi());
	}

	@Override
	public void onSurfaceDestroyed(@NonNull SurfaceContainer container) {
		MirrorController.get().setCarSurface(null, 0, 0, 0);
	}

	@Override
	public void onVisibleAreaChanged(@NonNull Rect visibleArea) {
	}

	@Override
	public void onStableAreaChanged(@NonNull Rect stableArea) {
	}

	// ---------------------------------------------------------------- 터치 전달 (정차 중에만)

	@Override
	public void onClick(float x, float y) {
		if (MirrorController.get().isLocked()) return;
		PointF p = MirrorController.get().mapToPhone(x, y);
		if (p != null) TouchService.tap(p.x, p.y);
	}

	@Override
	public void onScroll(float distanceX, float distanceY) {
		if (MirrorController.get().isLocked()) return;
		// 작은 스크롤 이벤트를 모아서 한 번의 스와이프로 보낸다.
		scrollX += distanceX;
		scrollY += distanceY;
		main.removeCallbacks(flushScroll);
		main.postDelayed(flushScroll, 120);
	}

	@Override
	public void onFling(float velocityX, float velocityY) {
		if (MirrorController.get().isLocked()) return;
		main.removeCallbacks(flushScroll);
		scrollX = 0;
		scrollY = 0;
		// 손가락 이동 방향 = 속도 방향. 0.15초 동안 이동한 거리만큼 빠르게 스와이프한다.
		swipeFromCenter(velocityX * 0.15f, velocityY * 0.15f, 100);
	}

	private void flushScroll() {
		// distance 는 화면 내용이 움직인 반대 방향이므로 손가락 이동은 부호를 뒤집는다.
		float fingerX = -scrollX;
		float fingerY = -scrollY;
		scrollX = 0;
		scrollY = 0;
		swipeFromCenter(fingerX, fingerY, 250);
	}

	private void swipeFromCenter(float carDx, float carDy, long durationMs) {
		MirrorController mc = MirrorController.get();
		PointF c = mc.phoneCenter();
		float[] size = mc.phoneSize();
		if (c == null || size == null) return;
		float dx = mc.carToPhoneDistance(carDx);
		float dy = mc.carToPhoneDistance(carDy);
		float x2 = clamp(c.x + dx, 1, size[0] - 1);
		float y2 = clamp(c.y + dy, 1, size[1] - 1);
		TouchService.swipe(c.x, c.y, x2, y2, durationMs);
	}

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}
}
