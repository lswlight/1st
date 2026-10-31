package com.lsw.carmirror;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 휴대폰 화면: 권한 준비, 미러링 시작/중지, 상태 확인. */
public class MainActivity extends Activity
		implements ParkingGuard.Listener, MirrorController.StatusListener {
	private static final int REQ_PERMISSIONS = 1;
	private static final int REQ_CAPTURE = 2;

	private TextView status;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		MirrorController.get().init(this);

		int pad = dp(20);
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(pad, pad, pad, pad);

		TextView title = text("CarMirror", 26);
		title.setTypeface(Typeface.DEFAULT_BOLD);
		root.addView(title);
		root.addView(text("휴대폰 화면을 안드로이드 오토 차량 화면에 띄웁니다.\n"
				+ "정차가 확인될 때만 화면이 보이고, 주행 중에는 자동으로 가려집니다.", 15));

		status = text("", 16);
		status.setPadding(0, dp(16), 0, dp(16));
		root.addView(status);

		root.addView(button("① 미러링 시작", v -> startMirroring()));
		root.addView(button("미러링 중지", v -> ProjectionService.stop(this)));
		root.addView(button("② 터치 제어 켜기 (접근성 설정 열기)", v -> openAccessibility()));
		root.addView(button("접근성이 회색으로 막혀 있으면: 앱 정보 열기", v -> openAppInfo()));

		root.addView(text("\n사용 순서\n"
				+ "1. 차량에 연결하고 안드로이드 오토에서 CarMirror 를 엽니다.\n"
				+ "2. 휴대폰에서 [미러링 시작] → '전체 화면'을 선택합니다.\n"
				+ "3. 유튜브·넷플릭스 등을 열고 휴대폰을 가로로 돌리면 차량 화면을 꽉 채웁니다.\n"
				+ "4. 차량 화면 터치를 쓰려면 [터치 제어 켜기]에서 'CarMirror 터치 제어'를 켭니다.\n\n"
				+ "참고\n"
				+ "• 휴대폰 화면이 꺼지거나 잠기면 미러링이 멈춥니다. 충전 중 화면 켜짐 유지를 권장합니다.\n"
				+ "• 넷플릭스 등 일부 앱은 화면 캡처를 막아 검은 화면으로 보일 수 있습니다.\n"
				+ "• 정차 판정에는 위치 권한(GPS 속도)이 필요합니다.", 14));

		ScrollView scroll = new ScrollView(this);
		scroll.addView(root);
		setContentView(scroll);
	}

	@Override
	protected void onResume() {
		super.onResume();
		ParkingGuard.get().addListener(this);
		MirrorController.get().addStatusListener(this);
		refresh();
	}

	@Override
	protected void onPause() {
		ParkingGuard.get().removeListener(this);
		MirrorController.get().removeStatusListener(this);
		super.onPause();
	}

	@Override
	public void onParkingStateChanged(ParkingGuard.State state) {
		runOnUiThread(this::refresh);
	}

	@Override
	public void onMirrorStatusChanged() {
		runOnUiThread(this::refresh);
	}

	private void refresh() {
		MirrorController mc = MirrorController.get();
		String parking;
		switch (ParkingGuard.get().getState()) {
			case PARKED:
				parking = "정차 (화면 표시)";
				break;
			case MOVING:
				parking = "주행 중 (화면 가림)";
				break;
			default:
				parking = "확인 중 (화면 가림)";
		}
		status.setText("미러링: " + (mc.isProjecting() ? "실행 중" : "중지됨")
				+ "\n차량 화면: " + (mc.isCarConnected() ? "연결됨" : "연결 안 됨")
				+ "\n정차 판정: " + parking
				+ "\n위치 권한: " + (hasLocation() ? "허용" : "필요")
				+ "\n터치 제어: " + (TouchService.isEnabled() ? "켜짐" : "꺼짐"));
	}

	// ---------------------------------------------------------------- 미러링 시작

	private void startMirroring() {
		List<String> need = new ArrayList<>();
		if (!hasLocation()) {
			need.add(Manifest.permission.ACCESS_FINE_LOCATION);
			need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
		}
		if (Build.VERSION.SDK_INT >= 33
				&& checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
				!= PackageManager.PERMISSION_GRANTED) {
			need.add(Manifest.permission.POST_NOTIFICATIONS);
		}
		if (need.isEmpty()) {
			requestCapture();
		} else {
			requestPermissions(need.toArray(new String[0]), REQ_PERMISSIONS);
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
		super.onRequestPermissionsResult(requestCode, permissions, results);
		if (requestCode != REQ_PERMISSIONS) return;
		if (!hasLocation()) {
			Toast.makeText(this, "위치 권한이 없으면 정차를 확인할 수 없어 화면이 계속 가려집니다",
					Toast.LENGTH_LONG).show();
		}
		requestCapture();
	}

	private void requestCapture() {
		MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
		startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode != REQ_CAPTURE) return;
		if (resultCode != RESULT_OK || data == null) {
			Toast.makeText(this, "화면 캡처가 취소되었습니다", Toast.LENGTH_SHORT).show();
			return;
		}
		Intent svc = new Intent(this, ProjectionService.class)
				.putExtra(ProjectionService.EXTRA_RESULT_CODE, resultCode)
				.putExtra(ProjectionService.EXTRA_RESULT_DATA, data);
		startForegroundService(svc);
	}

	// ---------------------------------------------------------------- 설정 화면 이동

	private void openAccessibility() {
		startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
	}

	private void openAppInfo() {
		startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
				Uri.fromParts("package", getPackageName(), null)));
	}

	// ---------------------------------------------------------------- 보조

	private boolean hasLocation() {
		return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
				== PackageManager.PERMISSION_GRANTED;
	}

	private TextView text(String s, int sp) {
		TextView t = new TextView(this);
		t.setText(s);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setLineSpacing(0, 1.2f);
		return t;
	}

	private Button button(String label, View.OnClickListener l) {
		Button b = new Button(this);
		b.setText(label);
		b.setAllCaps(false);
		b.setOnClickListener(l);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(8);
		b.setLayoutParams(lp);
		return b;
	}

	private int dp(int v) {
		return Math.round(v * getResources().getDisplayMetrics().density);
	}
}
