# 슬림 비디오 (SlimVideo) — 동영상 용량 줄이기

화질은 그대로 두고 용량만 줄이는 안드로이드 동영상 압축기. 휴대폰의 하드웨어 인코더
(H.265 / H.264)를 그대로 쓰기 때문에 **서버 없이, 앱 안에서만** 빠르게 변환한다.

| 항목 | 값 |
| --- | --- |
| 패키지 | `com.youngsu.slimvideo` |
| 버전 | 1.0.2 (versionCode 3) |
| minSdk / targetSdk | 26 (Android 8.0) / 36 (Android 16) |
| 언어 | English(기본) · 한국어 · 日本語 · 中文 · Español |
| 광고 | AdMob 배너(최하단) + 압축 완료 후 전면 1회 — debug 는 Google 테스트 ID, release 는 실제 ID |

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
- `CompressMathCheck` 129개 항목 통과: 회전 정규화/가로·세로 교환, 1080p→720p/480p 비율,
  짝수 정렬, 코덱별 비트레이트와 화질 하한/상한, 코덱별 배율(H.264↔H.265), 목표 용량 역산과
  720p/480p 자동 하향, 원본 비트레이트 상한, 무음·AAC·Opus 분기, 초경량 오디오 재인코딩,
  원본 비트레이트 미상 파일의 크기 기반 추정, **실제 기기에서 실패한 두 영상(84MB/6.8MB)의 절감률**, 표시 형식 등.


## v1.0.2 — 실제 기기 변환 실패 원인 수정 + 압축률 현실화

### 1. 변환 실패의 원인 (확정)
v1.0.1 의 `TranscodeEngine` 은 인코더를 `start()` 한 **뒤에** `encoder.createInputSurface()` 를 불렀다.
AOSP `MediaCodec.createInputSurface()` 문서는 “**configure() 뒤, start() 전에만** 부를 수 있다”고
명시하고 `IllegalStateException if not in the Configured state` 를 던진다. 그래서 어떤 영상이든
인코더를 여는 단계에서 바로 예외가 났고, 화면에는 `문제가 생겼습니다` 만 떴다(사용자가 시험한 두 영상 모두).
- 순서를 `configure() → createInputSurface() → start()` 로 바로잡았다.

### 2. 함께 막은 실패 지점
- **인코더 폴백 4단계**: 요청 코덱(`createEncoderByType`) → `MediaCodecList` 의 하드웨어 코덱 이름 →
  16 배수 해상도 → H.264. 1830×1024 처럼 16 배수가 아닌 폭도 여기서 흡수한다.
- **디코더 폴백**: `createDecoderByType` 실패 시 `MediaCodecList` 의 디코더 이름으로 다시 시도.
- **프레임 표시 시각**: `SurfaceTexture.timestamp` 대신 디코더 PTS 를 직접 넣고 0 부터 시작하게 맞췄다
  (기기에 따라 timestamp 가 0/시스템 시각이라 프레임이 버려지는 경우가 있다).
- **프레임 유실 감시**: 첫 프레임을 3번(약 6초) 연속 받지 못하면 8분을 기다리지 않고
  `[디코더] 프레임이 인코더로 넘어오지 않았습니다` 로 바로 알린다.
- **오디오**: 재인코딩 → 원본 AAC 복사 → 무음 순서의 폴백, PTS 단조 증가 보정,
  `writeSampleData` 실패는 그 샘플만 버리고 영상은 계속.
- **전용 스레드**: 압축은 `Executors.newSingleThreadExecutor()` 한 스레드에서만 돈다
  (EGL/GL 컨텍스트는 만든 스레드에서만 유효한데 `Dispatchers.Default` 는 스레드가 바뀔 수 있다).
- **Android 15 포그라운드 서비스**: `onTimeout(startId, fgsType)` 구현(mediaProcessing 6시간 제한).

### 3. 오류 원인 표시
`CompressError` 문구 아래에 `오류 상세: [단계] 예외이름: 메시지` 를 작게 함께 보여 준다.
단계 이름은 `[추출기] [인코더] [디코더] [GL] [먹서] [엔진]` 중 하나다.

### 4. 압축률 현실화 (`CompressMath`)
예전에는 1080p 기본 비트레이트 × 0.82 같은 “해상도 기준” 값이라, 이미 작게 압축된 휴대폰 영상은
원본과 비슷한 비트레이트가 나와 **6~11%** 밖에 줄지 않았다. 이제는 **원본 영상 비트레이트 대비 배율**로 정한다.

| 모드 | H.264 원본 → H.265 | H.265 원본 → H.265 | 하한/상한 |
| --- | --- | --- | --- |
| 화질 유지 | 52% | 78% | 해상도 화질 하한·상한, 원본의 90% |
| 공유 균형 | 42% | 63% | 위와 같음 |
| 초경량 | 28% | 42% | 위와 같음(480p, 오디오 96k 재인코딩) |

- 원본 코덱을 모르면 H.265 로 갈 때만 이득을 본다(배율 1.0).
- 화질 하한은 `0.008bpp(H.265)` / `0.011bpp(H.264)` 이며 원본의 60~70% 를 넘지 않는다
  (이미 작은 영상에 원본보다 높은 하한을 들이대지 않는다).
- 예상 용량이 원본의 95% 를 넘으면 비트레이트를 더 낮춘다.
- AAC 를 그대로 옮길 때는 예상 용량에 원본 오디오 비트레이트를 쓴다(예전에는 무조건 128k).

실제 시험 영상 기준 예상 절감률:

| 영상 | 모드 | v1.0.1 | v1.0.2 |
| --- | --- | --- | --- |
| 1830×1024 · 8:05 · 84MB | 공유 균형 · H.265 | 11% | **52%** |
| 1830×1024 · 8:05 · 84MB | 화질 유지 · H.265 | - | **43%** |
| 1830×1024 · 8:05 · 84MB | 초경량 · H.265 | - | **67%** |
| 1280×590 · 3:04 · 6.8MB | 초경량 · H.265 | 6% | **29%** |


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

이 작업 환경에는 **기기·에뮬레이터가 없다**(`adb devices` 빈 목록). v1.0.2 는 변환 실패의 원인을
AOSP `MediaCodec` 문서·소스로 확정해 고치고 빌드·lint·순수 로직까지 검증했지만,
**실제 기기에서 인코딩이 끝까지 도는 것은 설치 후에 확인해야 한다.**
설치 후 실패한다면 이제 화면에 `오류 상세: [단계] …` 가 함께 나오므로 바로 원인을 알 수 있다.
결과물(MOV→MP4, 세로 영상 회전, 5.1 오디오 → AAC)은 설치 후에 확인해야 한다. 코드 경로와
빌드·lint·순수 로직까지는 검증되어 있다.

## 출시 빌드 (완료)

광고 ID 는 코드 상수가 아니라 `app/build.gradle.kts` 의 `buildConfigField` · `manifestPlaceholders` 가 넣는다.

- `debug` 빌드: Google 공식 테스트 ID (`ca-app-pub-3940256099942544~3347511713`, 단위 …/6300978111 · …/1033173712)
- `release` 빌드: 실제 AdMob ID (`ca-app-pub-4832055633016363~6566300855`, 배너 …/8566738692, 전면 …/4299743512)
- release dex 에 테스트 ID 문자열 0건 (2026-11-26 확인)

업로드 키는 저장소 밖 `../keystores/keystore.properties` + `topster-upload.jks` 를 읽어 release 에 자동 연결된다.
키 파일이 없으면 서명 없이 빌드되며, 저장소에는 어떤 키도 포함되지 않는다.

```
./gradlew --offline bundleRelease     # app/build/outputs/bundle/release/app-release.aab (서명됨)
./gradlew --offline assembleRelease   # 실기기 확인용 서명 APK
```
