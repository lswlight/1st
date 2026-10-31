package com.lsw.carmirror;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;

/** 차량 화면에서 받은 터치를 휴대폰 화면 제스처로 전달하는 접근성 서비스. */
public class TouchService extends AccessibilityService {
	private static volatile TouchService instance;

	public static boolean isEnabled() {
		return instance != null;
	}

	@Override
	protected void onServiceConnected() {
		instance = this;
	}

	@Override
	public boolean onUnbind(Intent intent) {
		instance = null;
		return super.onUnbind(intent);
	}

	@Override
	public void onDestroy() {
		instance = null;
		super.onDestroy();
	}

	@Override
	public void onAccessibilityEvent(AccessibilityEvent event) {
	}

	@Override
	public void onInterrupt() {
	}

	public static void tap(float x, float y) {
		Path p = new Path();
		p.moveTo(Math.max(0, x), Math.max(0, y));
		dispatch(p, 60);
	}

	public static void swipe(float x1, float y1, float x2, float y2, long durationMs) {
		Path p = new Path();
		p.moveTo(Math.max(0, x1), Math.max(0, y1));
		p.lineTo(Math.max(0, x2), Math.max(0, y2));
		dispatch(p, Math.max(1, durationMs));
	}

	public static void back() {
		global(GLOBAL_ACTION_BACK);
	}

	public static void home() {
		global(GLOBAL_ACTION_HOME);
	}

	public static void recents() {
		global(GLOBAL_ACTION_RECENTS);
	}

	private static void global(int action) {
		if (MirrorController.get().isLocked()) return;
		TouchService s = instance;
		if (s != null) s.performGlobalAction(action);
	}

	private static void dispatch(Path path, long durationMs) {
		if (MirrorController.get().isLocked()) return;
		TouchService s = instance;
		if (s == null) return;
		GestureDescription g = new GestureDescription.Builder()
				.addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
				.build();
		s.dispatchGesture(g, null, null);
	}
}
