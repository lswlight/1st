package com.lsw.carmirror;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 독립 화면 모드 자체 테스트. 차량 화면에 이 화면이 보이면 가상 화면 자체는 동작하는 것이다. */
public class TestDisplayActivity extends Activity {
	private final Handler handler = new Handler(Looper.getMainLooper());
	private TextView text;
	private final Runnable tick = new Runnable() {
		@Override
		public void run() {
			String time = new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date());
			int displayId = getDisplay() != null ? getDisplay().getDisplayId() : -1;
			text.setText("독립 화면 테스트 성공\n" + time + "\n(화면 ID " + displayId + ")");
			handler.postDelayed(this, 1000);
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		text = new TextView(this);
		text.setGravity(Gravity.CENTER);
		text.setTextColor(Color.WHITE);
		text.setBackgroundColor(0xFF1E3A5F);
		text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
		text.setOnClickListener(v -> finish());
		setContentView(text);
	}

	@Override
	protected void onResume() {
		super.onResume();
		handler.post(tick);
	}

	@Override
	protected void onPause() {
		handler.removeCallbacks(tick);
		super.onPause();
	}
}
