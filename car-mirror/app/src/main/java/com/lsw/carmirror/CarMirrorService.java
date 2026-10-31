package com.lsw.carmirror;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

/** 안드로이드 오토가 연결하는 진입점. */
public class CarMirrorService extends CarAppService {

	@NonNull
	@Override
	public HostValidator createHostValidator() {
		// 개인 사이드로드 전용이라 호스트 검증을 하지 않는다.
		return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
	}

	@NonNull
	@Override
	public Session onCreateSession() {
		return new Session() {
			@NonNull
			@Override
			public Screen onCreateScreen(@NonNull Intent intent) {
				return new MirrorScreen(getCarContext());
			}
		};
	}
}
