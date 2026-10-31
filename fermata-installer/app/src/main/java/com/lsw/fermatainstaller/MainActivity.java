package com.lsw.fermatainstaller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/** Fermata 를 '플레이스토어 출처'로 설치해 안드로이드 오토 앱 목록에 보이게 하는 도우미. */
public class MainActivity extends Activity {
	private static final int REQ_SHIZUKU = 1;
	private static final int REQ_PICK_APK = 2;
	private static final String AA_PACKAGE = "com.google.android.projection.gearhead";

	private final ExecutorService worker = Executors.newSingleThreadExecutor();
	private TextView status;
	private TextView log;
	private File apk;
	private String apkPackage;
	private String apkLabel;
	private boolean busy;

	private final Shizuku.OnBinderReceivedListener binderReceived = () -> runOnUiThread(this::refresh);
	private final Shizuku.OnBinderDeadListener binderDead = () -> runOnUiThread(this::refresh);
	private final Shizuku.OnRequestPermissionResultListener permissionResult =
			(code, result) -> runOnUiThread(() -> {
				log(result == PackageManager.PERMISSION_GRANTED
						? "Shizuku 권한 허용됨" : "Shizuku 권한이 거부되었습니다");
				refresh();
			});

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		int pad = dp(18);
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(pad, pad, pad, pad);

		TextView title = text("Fermata 설치 도우미", 24);
		title.setTypeface(Typeface.DEFAULT_BOLD);
		root.addView(title);
		root.addView(text("Fermata 를 '플레이스토어 출처'로 설치해서 안드로이드 오토 앱 목록에 "
				+ "보이게 합니다. 위에서부터 순서대로 누르세요.", 14));

		status = text("", 15);
		status.setPadding(0, dp(12), 0, dp(4));
		root.addView(status);

		root.addView(section("1. Shizuku 준비"));
		root.addView(button("Shizuku 열기 (없으면 설치 화면)", v -> openShizuku()));
		root.addView(text("Shizuku 앱에서 '무선 디버깅을 통해 시작' → 페어링 → 시작까지 해 두세요.", 13));
		root.addView(button("Shizuku 권한 요청", v -> requestShizuku()));

		root.addView(section("2. Fermata 받기"));
		root.addView(button("공식 최신판 자동 다운로드", v -> downloadLatest()));
		root.addView(button("또는 APK 파일 직접 선택", v -> pickApk()));

		root.addView(section("3. 설치"));
		root.addView(button("기존 Fermata 삭제", v -> uninstallExisting()));
		root.addView(button("플레이스토어 출처로 설치", v -> install()));

		root.addView(section("4. 안드로이드 오토 설정"));
		root.addView(text("안드로이드 오토 설정 맨 아래 '버전'을 10번 누르기 → 오른쪽 위 ⋮ → "
				+ "개발자 설정 → '알 수 없는 출처' 체크. 그 다음 차에 연결해 앱 목록을 확인하세요.", 13));
		root.addView(button("안드로이드 오토 앱 정보 열기", v -> openAppInfo(AA_PACKAGE)));

		log = text("", 13);
		log.setPadding(0, dp(16), 0, 0);
		log.setTextIsSelectable(true);
		root.addView(log);

		ScrollView scroll = new ScrollView(this);
		scroll.addView(root);
		setContentView(scroll);

		Shizuku.addBinderReceivedListenerSticky(binderReceived);
		Shizuku.addBinderDeadListener(binderDead);
		Shizuku.addRequestPermissionResultListener(permissionResult);
	}

	@Override
	protected void onResume() {
		super.onResume();
		refresh();
	}

	@Override
	protected void onDestroy() {
		Shizuku.removeBinderReceivedListener(binderReceived);
		Shizuku.removeBinderDeadListener(binderDead);
		Shizuku.removeRequestPermissionResultListener(permissionResult);
		worker.shutdownNow();
		super.onDestroy();
	}

	// ---------------------------------------------------------------- 상태

	private String shizukuState() {
		if (!Installer.isInstalled(this, Installer.SHIZUKU_PACKAGE)) return "설치 안 됨";
		if (!Shizuku.pingBinder()) return "실행 안 됨 (Shizuku 앱에서 시작 필요)";
		if (Shizuku.isPreV11()) return "버전이 너무 오래됨 (업데이트 필요)";
		if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return "권한 필요";
		return "준비됨";
	}

	private boolean shizukuReady() {
		return Shizuku.pingBinder() && !Shizuku.isPreV11()
				&& Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
	}

	private void refresh() {
		StringBuilder sb = new StringBuilder();
		sb.append("Shizuku: ").append(shizukuState());
		sb.append("\n받은 APK: ").append(apk != null ? apkLabel : "없음");
		List<String> installed = Installer.installedFermata(this);
		if (installed.isEmpty()) {
			sb.append("\n설치된 Fermata: 없음");
		} else {
			for (String pkg : installed) {
				sb.append("\n설치된 Fermata: ").append(pkg);
				String src = Installer.describeInstallSource(this, pkg);
				if (src != null) sb.append("\n  ").append(src.replace("\n", "\n  "));
				sb.append(Installer.PLAY_STORE.equals(installerOf(pkg))
						? "\n  → 플레이스토어 출처로 설치됨 ✓" : "\n  → 플레이스토어 출처 아님");
			}
		}
		status.setText(sb);
	}

	private String installerOf(String pkg) {
		try {
			return getPackageManager().getInstallSourceInfo(pkg).getInstallingPackageName();
		} catch (PackageManager.NameNotFoundException e) {
			return null;
		}
	}

	// ---------------------------------------------------------------- 1. Shizuku

	private void openShizuku() {
		Intent launch = getPackageManager().getLaunchIntentForPackage(Installer.SHIZUKU_PACKAGE);
		if (launch != null) {
			startActivity(launch);
			return;
		}
		try {
			startActivity(new Intent(Intent.ACTION_VIEW,
					Uri.parse("market://details?id=" + Installer.SHIZUKU_PACKAGE)));
		} catch (ActivityNotFoundException e) {
			startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
					"https://play.google.com/store/apps/details?id=" + Installer.SHIZUKU_PACKAGE)));
		}
	}

	private void requestShizuku() {
		if (!Shizuku.pingBinder()) {
			log("Shizuku 가 실행 중이 아닙니다. Shizuku 앱에서 먼저 시작하세요.");
			return;
		}
		if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
			log("이미 Shizuku 권한이 있습니다");
			return;
		}
		if (Shizuku.shouldShowRequestPermissionRationale()) {
			log("Shizuku 권한을 '거부'로 고정했습니다. Shizuku 앱 → 앱 관리에서 이 앱을 허용하세요.");
			return;
		}
		Shizuku.requestPermission(REQ_SHIZUKU);
	}

	// ---------------------------------------------------------------- 2. 다운로드

	private void downloadLatest() {
		if (busy) return;
		busy = true;
		log("공식 GitHub(AndreyPavlenko/Fermata)에서 최신판 확인 중…");
		worker.execute(() -> {
			try {
				Installer.Release r = Installer.findLatest();
				post("최신판 " + r.tag + ": " + r.assetName);
				File f = Installer.download(this, r, this::post);
				onApkReady(f, r.assetName + " (" + r.tag + ")");
			} catch (Exception e) {
				post("다운로드 실패: " + e.getMessage()
						+ "\n→ 브라우저로 받은 뒤 [APK 파일 직접 선택]을 쓰세요.");
			} finally {
				busy = false;
			}
		});
	}

	private void pickApk() {
		Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
				.addCategory(Intent.CATEGORY_OPENABLE)
				.setType("*/*");
		startActivityForResult(i, REQ_PICK_APK);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode != REQ_PICK_APK || resultCode != RESULT_OK || data == null) return;
		Uri uri = data.getData();
		if (uri == null) return;
		busy = true;
		worker.execute(() -> {
			try {
				File f = Installer.copyToCache(this, getContentResolver().openInputStream(uri));
				onApkReady(f, String.valueOf(uri.getLastPathSegment()));
			} catch (Exception e) {
				post("파일을 읽지 못했습니다: " + e.getMessage());
			} finally {
				busy = false;
			}
		});
	}

	/** 작업 스레드에서 호출. */
	private void onApkReady(File f, String label) {
		String pkg = Installer.packageNameOf(this, f);
		if (pkg == null) {
			post("올바른 APK 파일이 아닙니다");
			return;
		}
		runOnUiThread(() -> {
			apk = f;
			apkPackage = pkg;
			apkLabel = label + "\n  패키지: " + pkg;
			if (!pkg.startsWith(Installer.FERMATA_PREFIX)) {
				log("주의: Fermata 가 아닌 앱입니다 (" + pkg + "). 그래도 설치할 수 있습니다.");
			}
			log("APK 준비 완료. 3단계로 진행하세요.");
			refresh();
		});
	}

	// ---------------------------------------------------------------- 3. 설치

	private void uninstallExisting() {
		List<String> installed = Installer.installedFermata(this);
		if (installed.isEmpty()) {
			log("설치된 Fermata 가 없습니다. 바로 설치로 진행하세요.");
			return;
		}
		for (String pkg : installed) {
			startActivity(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)));
		}
		log("삭제 확인 창에서 '확인'을 누르세요. Fermata 설정과 재생 기록도 함께 지워집니다.");
	}

	private void install() {
		if (busy) return;
		if (apk == null) {
			log("먼저 2단계에서 APK 를 준비하세요");
			return;
		}
		if (!shizukuReady()) {
			log("Shizuku 가 준비되지 않았습니다: " + shizukuState());
			return;
		}
		if (Installer.isInstalled(this, apkPackage)
				&& !Installer.PLAY_STORE.equals(installerOf(apkPackage))) {
			new AlertDialog.Builder(this)
					.setTitle("기존 앱이 있습니다")
					.setMessage("이미 설치된 " + apkPackage + " 가 플레이스토어 출처가 아닙니다. "
							+ "덮어쓰기로는 출처가 바뀌지 않을 수 있어 먼저 삭제하는 것을 권장합니다.")
					.setPositiveButton("그래도 설치", (d, w) -> doInstall())
					.setNegativeButton("먼저 삭제", (d, w) -> uninstallExisting())
					.show();
			return;
		}
		doInstall();
	}

	private void doInstall() {
		busy = true;
		File f = apk;
		log("설치 중… (Shizuku 로 'pm install -i com.android.vending' 실행)");
		worker.execute(() -> {
			try {
				String out = Installer.installAsPlayStore(f);
				post("설치 완료: " + out + "\n→ 4단계 안드로이드 오토 설정을 확인하고 차에 연결해 보세요.");
			} catch (Exception e) {
				post("설치 실패: " + e);
			} finally {
				busy = false;
				runOnUiThread(this::refresh);
			}
		});
	}

	// ---------------------------------------------------------------- 보조

	private void openAppInfo(String pkg) {
		startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
				Uri.fromParts("package", pkg, null)));
	}

	private void post(String msg) {
		runOnUiThread(() -> log(msg));
	}

	private void log(String msg) {
		CharSequence prev = log.getText();
		log.setText(prev.length() == 0 ? msg : msg + "\n\n" + prev);
	}

	private TextView section(String s) {
		TextView t = text(s, 17);
		t.setTypeface(Typeface.DEFAULT_BOLD);
		t.setPadding(0, dp(16), 0, 0);
		return t;
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
		lp.topMargin = dp(6);
		b.setLayoutParams(lp);
		return b;
	}

	private int dp(int v) {
		return Math.round(v * getResources().getDisplayMetrics().density);
	}
}
