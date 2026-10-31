package com.lsw.carmirror;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 독립 화면 모드(실험): 차량용 가상 화면에 앱을 띄운다. */
final class DisplayLauncher {
	private static final String TAG = "DisplayLauncher";

	static final class AppEntry {
		final String label;
		final String packageName;

		AppEntry(String label, String packageName) {
			this.label = label;
			this.packageName = packageName;
		}
	}

	private DisplayLauncher() {
	}

	/** 실행 가능한 앱 목록 (이름순). */
	static List<AppEntry> listApps(Context ctx) {
		PackageManager pm = ctx.getPackageManager();
		Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
		List<AppEntry> apps = new ArrayList<>();
		for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
			String pkg = ri.activityInfo.packageName;
			if (pkg.equals(ctx.getPackageName())) continue;
			apps.add(new AppEntry(String.valueOf(ri.loadLabel(pm)), pkg));
		}
		Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
		return apps;
	}

	/** 자체 테스트 화면을 차량 화면에 띄운다. 결과 메시지를 돌려준다. */
	static String launchTest(Context ctx) {
		Intent i = new Intent(ctx, TestDisplayActivity.class);
		return launch(ctx, i, "테스트 화면");
	}

	/** 다른 앱을 차량 화면에 띄운다. 결과 메시지를 돌려준다. */
	static String launchApp(Context ctx, AppEntry app) {
		Intent i = ctx.getPackageManager().getLaunchIntentForPackage(app.packageName);
		if (i == null) return app.label + ": 실행할 수 없는 앱입니다";
		return launch(ctx, i, app.label);
	}

	private static String launch(Context ctx, Intent intent, String label) {
		MirrorController mc = MirrorController.get();
		if (!mc.isIndependent() || !mc.isProjecting()) return "먼저 [독립 화면 시작]을 누르세요";
		int id = mc.getDisplayId();
		if (id < 0) return "차량 화면이 아직 연결되지 않았습니다. 안드로이드 오토에서 CarMirror 를 여세요";
		if (mc.isLocked()) return "정차가 확인된 뒤에 실행할 수 있습니다";
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
		ActivityOptions options = ActivityOptions.makeBasic();
		options.setLaunchDisplayId(id);
		try {
			ctx.startActivity(intent, options.toBundle());
			Log.i(TAG, label + " 실행 요청: display " + id);
			return label + ": 실행 요청 완료 (화면 ID " + id + ")\n"
					+ "차량에 안 보이고 휴대폰에 열리면 안드로이드가 막은 것입니다";
		} catch (SecurityException e) {
			Log.w(TAG, label + " 실행 거부", e);
			return label + ": 안드로이드가 실행을 막았습니다\n" + e.getMessage();
		} catch (RuntimeException e) {
			Log.w(TAG, label + " 실행 실패", e);
			return label + ": 실행 실패\n" + e;
		}
	}
}
