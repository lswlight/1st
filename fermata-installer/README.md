# Fermata 설치 도우미 (개인용)

PC 없이 휴대폰만으로 Fermata Auto 를 **플레이스토어 출처**로 설치해, 안드로이드 오토 앱 목록에 보이게 하는 앱입니다.
Fermata 자체는 수정하지 않고, 공식 GitHub(AndreyPavlenko/Fermata) 최신 릴리스를 그대로 받아 설치합니다.

## 하는 일

1. Shizuku 상태 확인, 권한 요청
2. 공식 GitHub 최신 릴리스에서 휴대폰 CPU에 맞는 Fermata Auto APK 자동 다운로드 (또는 파일 직접 선택)
3. 기존 Fermata 삭제 안내
4. Shizuku(셸 권한)로 `pm install -r -i com.android.vending` 실행 (APK는 표준 입력으로 전달)
5. 설치 출처(설치 프로그램, 설치 시작한 앱) 표시

## 받기

저장소 **Releases** → `Fermata 설치 도우미 (최신 빌드)` → `FermataInstaller.apk`

## 사용 순서

1. 플레이스토어에서 **Shizuku** 설치 → 개발자 옵션 켜기(빌드번호 7번 탭) → Shizuku 에서 '무선 디버깅을 통해 시작' → 페어링 코드 입력 → 시작
2. 이 앱에서 위에서부터 차례로: Shizuku 권한 요청 → 최신판 다운로드 → 기존 Fermata 삭제 → 플레이스토어 출처로 설치
3. 안드로이드 오토 설정 → 버전 10번 탭 → 개발자 설정 → **알 수 없는 출처** 체크
4. 차에 연결해 앱 목록 확인

## 제약

- 안드로이드 11 이상, 무선 디버깅에 Wi-Fi 연결 필요
- 셸이 설치를 시작하므로 '설치 시작한 앱'은 `com.android.shell` 로 기록됩니다. 안드로이드 오토가 이 값까지 검사하면 목록에 안 보일 수 있습니다 (PC adb 설치와 같은 조건).
- Shizuku 는 재부팅하면 다시 시작해야 하지만, 설치된 Fermata 는 그대로 남습니다.
