# 슬림 비디오 (SlimVideo) — 동영상 용량 줄이기

화질은 그대로 두고 용량만 줄이는 안드로이드 동영상 압축기. 휴대폰의 하드웨어 인코더
(H.265 / H.264)를 그대로 쓰기 때문에 **서버 없이, 앱 안에서만** 빠르게 변환한다.

| 항목 | 값 |
| --- | --- |
| 패키지 | `com.youngsu.slimvideo` |
| 버전 | 1.0.1 (versionCode 2) |
| minSdk / targetSdk | 26 (Android 8.0) / 35 (Android 15) |
| 언어 | English(기본) · 한국어 · 日本語 · 中文 · Español |
| 광고 | AdMob 배너(최하단) + 압축 완료 후 전면 1회 — Google 공식 테스트 ID |

## 압축 방식 4가지

| 모드 | 해상도 | 설명 |
| --- | --- | --- |
| **화질 유지** (추천) | 원본 그대로 | H.265 고효율. 1080p H.264 원본이면 보통 40~65% 줄어든다 |
| **공유 균형** | 짧은 변 1080p 이하 | 카톡·SNS 전송용. 60fps 원본은 30fps 로 정리 |
| **초경량** | 짧은 변 480p | 최대 절감 |
| **목표 용량** | 자동 | 10 / 25 / 50 / 100MB 중 선택. 길이로 비트레이트를 역산하고, 목표가 너무 작으면 720p → 480p 로 자동 조정 |

출력 코덱은 H.265(최소 용량) / H.264(호환성) 중에서 고른다. 기기에 H.265 인코더가 없으면
자동으로 H.264 로 저장하고 화면에 그 사실을 알려 준다.

## 엔진 구조 (외부 FFmpeg 없음)

```
MediaExtractor ──▶ MediaCodec(디코더) ──▶ SurfaceTexture(OES)
                                              │ GLES20 (회전 없이 그대로)
                                              ▼
                        MediaCodec(인코더, 입력 Surface) ──▶ MediaMuxer(MP4)
오디오: AAC ──▶ 샘플 그대로 복사(passthrough)
        Opus/Vorbis/AMR ──▶ PCM 디코딩 ──▶ AAC 128k 재인코딩
        트랙 없음 ──▶ 영상만 인코딩
```

- `app/src/main/java/com/youngsu/slimvideo/compress/CompressMath.java` — 순수 계산(비트레이트·해상도·회전·오디오 분기). Android 의존성 없음.
- `MediaProbe.kt` — 컨테이너/코덱/회전/길이/오디오 트랙 조사 (MediaExtractor + MediaMetadataRetriever 보완).
- `GlBlitter.kt` — EGL14 + GLES20 + SurfaceTexture 로 디코더 프레임을 인코더 Surface 로 전달.
- `TranscodeEngine.kt` — 디코드 → 렌더 → 인코드 → 먹싱 파이프라인, 프레임레이트 상한, 진행률, 취소.
- `AudioAacConverter.kt` — AAC 가 아닌 오디오를 AAC 로 바꿔 임시 m4a 로 만든 뒤 최종 파일에 합친다.
- `OutputStore.kt` — Movies/SlimVideo 저장 (Android 10+ MediaStore, 8~9 외부 저장소).
- `CompressManager.kt` — 앱 전역 상태(StateFlow). 화면을 옮기거나 회전해도 작업이 이어진다.
- `CompressService.kt` — 포그라운드 서비스(mediaProcessing) + 진행률 알림 + WakeLock.

### 회전·무음 처리

- 회전은 **픽셀을 돌리지 않고** `MediaMuxer.setOrientationHint()` 로 메타데이터만 유지한다.
  아이폰 세로 영상(저장 1920×1080, 회전 90)은 그대로 1920×1080 으로 인코딩되고 재생할 때 세로로 보인다.
- 오디오 트랙이 없는 영상은 오디오 트랙을 만들지 않고 영상만 인코딩한다.
- 오디오 재인코딩이 실패하면 무음으로 저장하고 결과 화면에 안내를 띄운다.

## 지원 파일

파일 선택은 `ACTION_OPEN_DOCUMENT` + `video/*` 및 MOV · MKV · WEBM · 3GP · M4V · AVI MIME 을 함께 넘긴다.
다른 앱에서 "동영상 열기/공유" 로 들어와도 바로 압축 화면이 열린다.

디코딩 가능 여부는 기기에 달려 있다(H.264, H.265, VP8, VP9, AV1 등). 기기가 지원하지 않는
코덱은 "이 기기에서 열 수 없는 동영상 형식입니다" 로 안내한다.

## 화면

- **압축 탭**: 영상 선택 → 썸네일·길이·해상도·용량·코덱 표시 → 4가지 모드 → 코덱 선택 →
  (목표 용량 모드면) 용량 칩 → 예상 결과 → 압축 시작 → 진행률/취소 → 전/후 비교 카드.
- **보관함 탭**: Movies/SlimVideo 에 저장된 결과 목록, 재생·공유·삭제.
- **설정 탭**: 앱 언어(기기 언어 따르기 / 한국어 / English / 日本語 / 中文 / Español), 기본 압축 방식,
  기본 코덱, 광고·개인정보·엔진 안내, 버전.
- 화면 최하단 순서: **콘텐츠 → 하단 탭 바 → 배너 광고**. 하단 3버튼 내비게이션 바는 자동으로 숨기고
  인셋만큼 여백을 준다.

## 검증

```bash
# 오프라인 빌드 (이 환경 전용)
JAVA_HOME=/workspace/buildenv/jdk-17.0.21.1+2 \
ANDROID_HOME=/workspace/buildenv/android-sdk \
GRADLE_USER_HOME=/workspace/buildenv/gradle-home HOME=/workspace \
./gradlew lintDebug assembleDebug --offline

# 순수 로직 JVM 검증 (기기 없이)
rm -rf /tmp/slimcheck && mkdir -p /tmp/slimcheck
/workspace/buildenv/jdk-17.0.21.1+2/bin/javac -d /tmp/slimcheck \
  app/src/main/java/com/youngsu/slimvideo/compress/*.java \
  tools/logic-check/CompressMathCheck.java
/workspace/buildenv/jdk-17.0.21.1+2/bin/java -cp /tmp/slimcheck CompressMathCheck
```

- `lintDebug` 통과 (error 0).
- `assembleDebug` 통과 → `app/build/outputs/apk/debug/app-debug.apk`.
- `CompressMathCheck` 96개 항목 통과: 회전 정규화/가로·세로 교환, 1080p→720p/480p 비율,
  짝수 정렬, 코덱별 비트레이트, 목표 용량 역산과 720p/480p 자동 하향, 원본 비트레이트 상한,
  무음·AAC·Opus 분기, 원본 비트레이트 미상 파일의 크기 기반 추정, 표시 형식 등.


## v1.0.1 — 빈 화면 버그 수정 + 첫 실행 언어 선택

### 빈 화면(검은 화면) 원인
`MainActivity` 가 `adContainer.viewTreeObserver.addOnGlobalLayoutListener { applyInsets() }` 로
**매 레이아웃 패스마다** `applyInsets()` 를 불렀고, 이어서 부른 `Screen.setSystemInsets()` 가
`보관함`·`설정` 화면에서 `col.removeAllViews() + addView(...)` 로 내용을 통째로 다시 만들었다.
전역 레이아웃 콜백은 **레이아웃이 끝난 직후·그리기 직전**에 불리므로, 그때 새로 붙은 뷰는 아직
측정되지 않은 0×0 상태이고 그대로 그려져 아무것도 보이지 않는다. 다음 패스에서 또 지웠다 붙이므로
영원히 빈 화면이 된다(압축 탭은 `lastRenderedKey` 가드로 재생성을 피해 정상 표시).

### 수정
- 전역 레이아웃 리스너 제거. 배너 광고는 `adContainer.addOnLayoutChangeListener` 로 **높이가 바뀔 때만**
  `post { applyInsets() }`.
- `applyInsets()` 는 상태바 높이가 실제로 바뀔 때만 화면에 알린다.
- `HomeScreen`·`LibraryScreen`·`SettingsScreen` 의 `setSystemInsets()` 는 **패딩만** 갱신한다(재생성 없음).
  덕분에 상단 여백도 상태바 높이에 맞게 제대로 들어간다.

### 첫 실행 언어 선택
- 처음 실행하면(=`AppPrefs.languageChosen == false`) `LanguagePicker` 화면이 뜬다.
  `기기 언어 따르기 / 한국어 / English / 日本語 / 中文 / Español` 6개가 한 화면에 모두 보이고,
  누르면 즉시 그 언어로 적용 + 플래그 저장(이후 실행에서는 안 뜨고 `설정 → 앱 언어` 에서 변경).
- 설정의 언어 대화상자는 `setTitle + setSingleChoiceItems` 만 사용(설명 문구 없이) — 목록이 가려지지 않는다.

## 아직 확인하지 못한 것

이 작업 환경에는 **기기·에뮬레이터가 없다**(`adb devices` 빈 목록). 그래서 실제 인코딩
결과물(MOV→MP4, 세로 영상 회전, 5.1 오디오 → AAC)은 설치 후에 확인해야 한다. 코드 경로와
빌드·lint·순수 로직까지는 검증되어 있다.

## 출시 전에 바꿀 것

1. `ads/AdConfig.kt` 의 `PROD_BANNER_UNIT_ID` / `PROD_INTERSTITIAL_UNIT_ID` 채우고 `USE_TEST_ADS = false`.
2. `AndroidManifest.xml` 의 `com.google.android.gms.ads.APPLICATION_ID` 를 실제 AdMob 앱 ID 로.
3. keystore 를 만들고 `app/build.gradle.kts` 의 release signingConfig 연결.
4. 앱 이름·아이콘·스크린샷은 Play 콘솔 등록 정보에서 지정(아이콘은 `res/mipmap-*` 에 이미 포함).
