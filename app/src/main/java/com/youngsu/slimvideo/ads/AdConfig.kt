package com.youngsu.slimvideo.ads

import com.youngsu.slimvideo.BuildConfig

/**
 * 광고 설정 한 곳.
 *
 * 광고 ID 는 코드 상수가 아니라 `app/build.gradle.kts` 의 `buildConfigField` 가 넣는다.
 *  - debug 빌드  : Google 공식 테스트 ID (실기기에서 눌러도 계정에 문제 없음)
 *  - release 빌드: AdMob 에 등록한 실제 ID (Play 스토어 배포용)
 *
 * 이렇게 하면 release `.aab` 의 dex 에 테스트 ID 문자열이 남지 않는다.
 * 앱 ID 는 `AndroidManifest.xml` 의 `${admobAppId}` 자리표시자가 같은 방식으로 받는다.
 *
 * - 배너는 화면 맨 아래(하단 탭 바보다 더 아래)에 붙는다.
 * - 전면 광고는 압축이 끝난 뒤 결과 화면이 뜰 때 한 번만 보여 준다.
 */
object AdConfig {

    /** 하단 배너 단위 ID (디버그=테스트 / release=슬림 비디오 하단 배너) */
    val bannerUnitId: String
        get() = BuildConfig.ADMOB_BANNER_UNIT_ID

    /** 전면 광고 단위 ID (디버그=테스트 / release=슬림 비디오 전면 광고) */
    val interstitialUnitId: String
        get() = BuildConfig.ADMOB_INTERSTITIAL_UNIT_ID

    /** 테스트 광고 모드 여부 — 설정 화면 안내 문구에 사용 */
    fun isTestMode(): Boolean = BuildConfig.DEBUG
}
