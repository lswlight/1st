package com.lsw.carmirror;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 휴대폰 화면(MediaProjection) → ImageReader → 차량 화면(Surface) 으로 프레임을 옮긴다.
 *
 * <p>차량 화면에는 이 클래스만 그린다. 그래서 정차/주행 상태에 따라 영상 대신 안내 문구를
 * 자유롭게 그릴 수 있다. 모든 상태 변경은 전용 스레드에서만 일어난다.
 */
public final class MirrorController implements ParkingGuard.Listener {
	private static final String TAG = "MirrorController";
	private static final MirrorController INSTANCE = new MirrorController();

	public static MirrorController get() {
		return INSTANCE;
	}

	public interface StatusListener {
		void onMirrorStatusChanged();
	}

	private final Handler handler;
	private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
	private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Rect frameRect = new Rect();
	private final CopyOnWriteArraySet<StatusListener> statusListeners = new CopyOnWriteArraySet<>();

	private volatile Context appContext;
	private volatile boolean projecting;
	private volatile boolean carConnected;
	private volatile boolean locked = true;
	private volatile int carWidth;
	private volatile int carHeight;

	// 아래 필드는 handler 스레드에서만 접근한다.
	private MediaProjection projection;
	private VirtualDisplay virtualDisplay;
	private ImageReader reader;
	private Surface carSurface;
	private int carDpi;
	private boolean useSoftwareCanvas;
	private Bitmap frame;
	private ByteBuffer padBuffer;
	private boolean hasFrame;

	private MirrorController() {
		HandlerThread thread = new HandlerThread("car-mirror");
		thread.start();
		handler = new Handler(thread.getLooper());
		titlePaint.setColor(Color.WHITE);
		titlePaint.setTextAlign(Paint.Align.CENTER);
		titlePaint.setFakeBoldText(true);
		subPaint.setColor(0xFFB0B0B0);
		subPaint.setTextAlign(Paint.Align.CENTER);
		locked = !ParkingGuard.get().isParked();
		ParkingGuard.get().addListener(this);
	}

	public MirrorController init(Context ctx) {
		if (appContext == null) appContext = ctx.getApplicationContext();
		return this;
	}

	public boolean isProjecting() {
		return projecting;
	}

	public boolean isCarConnected() {
		return carConnected;
	}

	public boolean isLocked() {
		return locked;
	}

	public void addStatusListener(StatusListener l) {
		statusListeners.add(l);
	}

	public void removeStatusListener(StatusListener l) {
		statusListeners.remove(l);
	}

	// ---------------------------------------------------------------- 휴대폰 화면 캡처

	/** 콜백이 이미 등록된 MediaProjection 을 넘긴다 (안드로이드 14 이상 요구사항). */
	public void setProjection(MediaProjection p) {
		handler.post(() -> {
			if (projection != null && projection != p) stopProjectionInternal();
			projection = p;
			projecting = p != null;
			ensureDisplay();
			redraw();
			notifyStatus();
		});
	}

	/** 시스템이나 사용자가 캡처를 끝냈을 때. */
	public void onProjectionStopped(MediaProjection p) {
		handler.post(() -> {
			if (projection != p) return;
			projection = null;
			projecting = false;
			releaseDisplay();
			redraw();
			notifyStatus();
		});
	}

	public void stopProjection() {
		handler.post(() -> {
			stopProjectionInternal();
			redraw();
			notifyStatus();
		});
	}

	private void stopProjectionInternal() {
		MediaProjection p = projection;
		projection = null;
		projecting = false;
		releaseDisplay();
		if (p != null) {
			try {
				p.stop();
			} catch (RuntimeException e) {
				Log.w(TAG, "projection stop 실패", e);
			}
		}
	}

	// ---------------------------------------------------------------- 차량 화면

	public void setCarSurface(Surface surface, int width, int height, int dpi) {
		handler.post(() -> {
			carSurface = surface;
			carWidth = width;
			carHeight = height;
			carDpi = dpi > 0 ? dpi : 160;
			useSoftwareCanvas = false;
			carConnected = surface != null;
			if (surface == null) {
				closeReader();
			} else {
				ensureDisplay();
				redraw();
			}
			notifyStatus();
		});
	}

	@Override
	public void onParkingStateChanged(ParkingGuard.State state) {
		boolean newLocked = state != ParkingGuard.State.PARKED;
		handler.post(() -> {
			locked = newLocked;
			redraw();
			notifyStatus();
		});
	}

	// ---------------------------------------------------------------- 내부 처리

	private void ensureDisplay() {
		if (projection == null || carSurface == null || carWidth <= 0 || carHeight <= 0) return;
		if (reader == null || reader.getWidth() != carWidth || reader.getHeight() != carHeight) {
			closeReader();
			reader = ImageReader.newInstance(carWidth, carHeight, PixelFormat.RGBA_8888, 2);
			reader.setOnImageAvailableListener(this::onImageAvailable, handler);
		}
		try {
			if (virtualDisplay == null) {
				// 안드로이드 14 이상은 프로젝션 하나당 VirtualDisplay 를 한 번만 만들 수 있다.
				// 차량 화면 크기가 바뀌면 resize/setSurface 로 재사용한다.
				virtualDisplay = projection.createVirtualDisplay("CarMirror", carWidth, carHeight,
						carDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(),
						null, handler);
			} else {
				virtualDisplay.resize(carWidth, carHeight, carDpi);
				virtualDisplay.setSurface(reader.getSurface());
			}
		} catch (RuntimeException e) {
			Log.e(TAG, "VirtualDisplay 생성 실패", e);
		}
	}

	private void closeReader() {
		if (virtualDisplay != null) virtualDisplay.setSurface(null);
		if (reader != null) {
			reader.close();
			reader = null;
		}
		hasFrame = false;
	}

	private void releaseDisplay() {
		closeReader();
		if (virtualDisplay != null) {
			virtualDisplay.release();
			virtualDisplay = null;
		}
	}

	private void onImageAvailable(ImageReader r) {
		Image image = null;
		try {
			image = r.acquireLatestImage();
			if (image == null) return;
			// 잠금 중에도 최신 프레임은 받아 둔다. 정차하면 화면 변화가 없어도 바로 보여줄 수 있다.
			copyToBitmap(image);
		} catch (RuntimeException e) {
			Log.w(TAG, "프레임 처리 실패", e);
			return;
		} finally {
			if (image != null) image.close();
		}
		if (!locked) drawFrame();
	}

	private void copyToBitmap(Image image) {
		Image.Plane plane = image.getPlanes()[0];
		int pixelStride = plane.getPixelStride();
		int rowStride = plane.getRowStride();
		int width = rowStride / pixelStride;
		int height = image.getHeight();
		if (frame == null || frame.getWidth() != width || frame.getHeight() != height) {
			frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
		}
		ByteBuffer buf = plane.getBuffer();
		buf.rewind();
		int need = frame.getByteCount();
		if (buf.remaining() < need) {
			// 마지막 줄에 패딩이 없는 기기가 있어 버퍼가 비트맵보다 조금 작을 수 있다.
			if (padBuffer == null || padBuffer.capacity() < need) padBuffer = ByteBuffer.allocateDirect(need);
			padBuffer.clear();
			padBuffer.put(buf);
			padBuffer.rewind();
			frame.copyPixelsFromBuffer(padBuffer);
		} else {
			frame.copyPixelsFromBuffer(buf);
		}
		frameRect.set(0, 0, image.getWidth(), height);
		hasFrame = true;
	}

	private void redraw() {
		if (carSurface == null) return;
		if (projection == null) {
			drawMessage("휴대폰에서 CarMirror 앱을 열고", "[미러링 시작]을 누르세요");
		} else if (locked) {
			if (ParkingGuard.get().getState() == ParkingGuard.State.UNKNOWN) {
				drawMessage("차량 속도 확인 중…", "정차가 확인되면 화면이 표시됩니다");
			} else {
				drawMessage("주행 중에는 화면이 표시되지 않습니다", "정차하면 다시 표시됩니다 (소리는 계속 재생)");
			}
		} else if (hasFrame) {
			drawFrame();
		} else {
			drawMessage("휴대폰 화면을 기다리는 중…", "");
		}
	}

	private void drawFrame() {
		if (!hasFrame) return;
		Canvas c = lockCanvas();
		if (c == null) return;
		try {
			c.drawColor(Color.BLACK);
			c.drawBitmap(frame, frameRect, new Rect(0, 0, carWidth, carHeight), bitmapPaint);
		} finally {
			unlockCanvas(c);
		}
	}

	private void drawMessage(String title, String sub) {
		Canvas c = lockCanvas();
		if (c == null) return;
		try {
			c.drawColor(Color.BLACK);
			float base = Math.min(carWidth, carHeight);
			titlePaint.setTextSize(base / 12f);
			subPaint.setTextSize(base / 18f);
			float cx = carWidth / 2f;
			float cy = carHeight / 2f;
			c.drawText(title, cx, cy, titlePaint);
			if (!sub.isEmpty()) c.drawText(sub, cx, cy + base / 9f, subPaint);
		} finally {
			unlockCanvas(c);
		}
	}

	/** 하드웨어 캔버스를 우선 쓰고, 실패하면 이 Surface 에서는 계속 소프트웨어 캔버스를 쓴다. */
	private Canvas lockCanvas() {
		Surface s = carSurface;
		if (s == null || !s.isValid()) return null;
		if (!useSoftwareCanvas) {
			try {
				return s.lockHardwareCanvas();
			} catch (RuntimeException e) {
				Log.w(TAG, "하드웨어 캔버스 실패, 소프트웨어로 전환", e);
				useSoftwareCanvas = true;
			}
		}
		try {
			return s.lockCanvas(null);
		} catch (RuntimeException e) {
			Log.w(TAG, "캔버스 잠금 실패", e);
			return null;
		}
	}

	private void unlockCanvas(Canvas c) {
		try {
			carSurface.unlockCanvasAndPost(c);
		} catch (RuntimeException e) {
			Log.w(TAG, "캔버스 해제 실패", e);
		}
	}

	private void notifyStatus() {
		for (StatusListener l : statusListeners) l.onMirrorStatusChanged();
	}

	// ---------------------------------------------------------------- 터치 좌표 변환

	/**
	 * 휴대폰 화면이 차량 화면 가운데에 비율을 유지한 채 맞춰진다고 보고 변환 값을 구한다.
	 *
	 * @return {scale, offsetX, offsetY, phoneWidth, phoneHeight} 또는 계산할 수 없으면 null
	 */
	private float[] fit() {
		Context ctx = appContext;
		int cw = carWidth;
		int ch = carHeight;
		if (ctx == null || cw <= 0 || ch <= 0) return null;
		DisplayManager dm = ctx.getSystemService(DisplayManager.class);
		Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
		if (d == null) return null;
		DisplayMetrics m = new DisplayMetrics();
		d.getRealMetrics(m);
		float pw = m.widthPixels;
		float ph = m.heightPixels;
		if (pw <= 0 || ph <= 0) return null;
		float scale = Math.min(cw / pw, ch / ph);
		return new float[]{scale, (cw - pw * scale) / 2f, (ch - ph * scale) / 2f, pw, ph};
	}

	/** 차량 화면 좌표 → 휴대폰 화면 좌표. 휴대폰 화면 바깥(검은 여백)이면 null. */
	public PointF mapToPhone(float x, float y) {
		float[] f = fit();
		if (f == null) return null;
		float px = (x - f[1]) / f[0];
		float py = (y - f[2]) / f[0];
		if (px < 0 || py < 0 || px >= f[3] || py >= f[4]) return null;
		return new PointF(px, py);
	}

	/** 차량 화면 기준 이동 거리 → 휴대폰 화면 기준 이동 거리. */
	public float carToPhoneDistance(float d) {
		float[] f = fit();
		return f == null ? d : d / f[0];
	}

	/** 휴대폰 화면 가운데 좌표. */
	public PointF phoneCenter() {
		float[] f = fit();
		return f == null ? null : new PointF(f[3] / 2f, f[4] / 2f);
	}

	/** 휴대폰 화면 크기 {가로, 세로}. */
	public float[] phoneSize() {
		float[] f = fit();
		return f == null ? null : new float[]{f[3], f[4]};
	}
}
