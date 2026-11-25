package com.youngsu.slimvideo.ads

/**
 * 광고 설정 한 곳.
 *
 * 지금은 Google 공식 테스트 ID 로 동작한다. 실제 서비스로 바꿀 때는
 * 이 파일의 PROD_* 상수와 AndroidManifest.xml 의 APPLICATION_ID 만 교체하면 된다.
 *
 * - 배너는 화면 맨 아래(하단 탭 바보다 더 아래)에 붙는다.
 * - 전면 광고는 압축이 끝난 뒤 결과 화면이 뜰 때 한 번만 보여 준다.
 */
object AdConfig {

    /** Google 공식 테스트 앱 ID — AndroidManifest.xml 의 APPLICATION_ID 와 같아야 한다. */
    const val TEST_APP_ID = "ca-app-pub-3940256099942544~3347511713"

    /** Google 공식 테스트 배너 단위 ID */
    const val TEST_BANNER_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"

    /** Google 공식 테스트 전면 광고 단위 ID */
    const val TEST_INTERSTITIAL_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"

    /** 실제 배너 단위 ID — AdMob 에서 단위를 만든 뒤 채운다. */
    const val PROD_BANNER_UNIT_ID = ""

    /** 실제 전면 광고 단위 ID */
    const val PROD_INTERSTITIAL_UNIT_ID = ""

    /** 출시 직전 false 로 바꾸면 위의 PROD_* 를 쓴다. */
    const val USE_TEST_ADS: Boolean = true

    val bannerUnitId: String
        get() = if (USE_TEST_ADS || PROD_BANNER_UNIT_ID.isBlank()) TEST_BANNER_UNIT_ID else PROD_BANNER_UNIT_ID

    val interstitialUnitId: String
        get() = if (USE_TEST_ADS || PROD_INTERSTITIAL_UNIT_ID.isBlank()) TEST_INTERSTITIAL_UNIT_ID else PROD_INTERSTITIAL_UNIT_ID

    fun isTestMode(): Boolean =
        USE_TEST_ADS || PROD_BANNER_UNIT_ID.isBlank() || PROD_INTERSTITIAL_UNIT_ID.isBlank()
}
