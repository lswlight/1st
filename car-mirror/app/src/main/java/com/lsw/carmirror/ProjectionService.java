package com.lsw.carmirror;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/** 휴대폰 화면 캡처를 유지하는 포그라운드 서비스. */
public class ProjectionService extends Service {
	private static final String TAG = "ProjectionService";
	static final String EXTRA_RESULT_CODE = "resultCode";
	static final String EXTRA_RESULT_DATA = "resultData";
	static final String EXTRA_INDEPENDENT = "independent";
	static final String ACTION_STOP = "com.lsw.carmirror.STOP";
	private static final String CHANNEL_ID = "mirror";
	private static final int NOTIFICATION_ID = 1;

	private boolean gpsStarted;
	private MediaProjection projection;

	public static void stop(Context ctx) {
		ctx.startService(new Intent(ctx, ProjectionService.class).setAction(ACTION_STOP));
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	@SuppressWarnings("deprecation")
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent == null || ACTION_STOP.equals(intent.getAction())) {
			shutdown();
			return START_NOT_STICKY;
		}

		// getMediaProjection 보다 먼저 포그라운드로 올라가야 한다 (안드로이드 10 이상).
		startForegroundWithTypes();

		int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
		Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
		MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
		MediaProjection p = null;
		try {
			if (data != null) p = mpm.getMediaProjection(code, data);
		} catch (RuntimeException e) {
			Log.e(TAG, "MediaProjection 획득 실패", e);
		}
		if (p == null) {
			shutdown();
			return START_NOT_STICKY;
		}

		if (projection != null) MirrorController.get().stopProjection();
		projection = p;
		final MediaProjection current = p;
		// 안드로이드 14 이상: createVirtualDisplay 전에 콜백을 등록해야 한다.
		p.registerCallback(new MediaProjection.Callback() {
			@Override
			public void onStop() {
				MirrorController.get().onProjectionStopped(current);
				if (projection == current) shutdown();
			}
		}, new Handler(Looper.getMainLooper()));

		MirrorController.get().init(this)
				.setProjection(p, intent.getBooleanExtra(EXTRA_INDEPENDENT, false));
		if (!gpsStarted) {
			ParkingGuard.get().startGps(this);
			gpsStarted = true;
		}
		return START_NOT_STICKY;
	}

	@Override
	public void onDestroy() {
		if (projection != null) {
			projection = null;
			MirrorController.get().stopProjection();
		}
		if (gpsStarted) {
			ParkingGuard.get().stopGps();
			gpsStarted = false;
		}
		super.onDestroy();
	}

	private void shutdown() {
		if (projection != null) {
			projection = null;
			MirrorController.get().stopProjection();
		}
		stopForeground(STOP_FOREGROUND_REMOVE);
		stopSelf();
	}

	private void startForegroundWithTypes() {
		NotificationManager nm = getSystemService(NotificationManager.class);
		nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "미러링",
				NotificationManager.IMPORTANCE_LOW));

		PendingIntent open = PendingIntent.getActivity(this, 0,
				new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
		PendingIntent stop = PendingIntent.getService(this, 1,
				new Intent(this, ProjectionService.class).setAction(ACTION_STOP),
				PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

		Notification n = new Notification.Builder(this, CHANNEL_ID)
				.setSmallIcon(android.R.drawable.ic_menu_slideshow)
				.setContentTitle("CarMirror 실행 중")
				.setContentText("주행 중에는 차량 화면이 자동으로 가려집니다")
				.setContentIntent(open)
				.addAction(new Notification.Action.Builder(null, "중지", stop).build())
				.setOngoing(true)
				.build();

		int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
		if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
				== PackageManager.PERMISSION_GRANTED) {
			types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
		}
		startForeground(NOTIFICATION_ID, n, types);
	}
}
