package com.youngsu.slimvideo.ads

import android.app.Activity
import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * AdMob 배너·전면 광고.
 *
 * - 배너: 광고가 없거나 실패하면 자리를 차지하지 않고 조용히 숨는다.
 * - 전면: 압축이 끝나 결과가 나온 직후에만, 준비된 광고가 있으면 보여 준다. (연속 노출은 제한)
 */
object AdManager {

    private const val TAG = "AdManager"

    /** 전면 광고 최소 간격 */
    private const val INTERSTITIAL_COOLDOWN_MS = 90_000L

    @Volatile private var initialized = false
    private var interstitial: InterstitialAd? = null
    private var loading = false
    private var lastShownAt = 0L

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        runCatching { MobileAds.initialize(context.applicationContext) { } }
            .onFailure { Log.w(TAG, "MobileAds 초기화 실패", it) }
    }

    // ------------------------------------------------------------------ 배너

    fun attachBanner(activity: Activity, container: ViewGroup) {
        runCatching { attachBannerInternal(activity, container) }
            .onFailure {
                container.removeAllViews()
                container.visibility = ViewGroup.GONE
            }
    }

    private fun attachBannerInternal(activity: Activity, container: ViewGroup) {
        container.removeAllViews()
        container.visibility = ViewGroup.GONE
        if (activity.isFinishing || activity.isDestroyed) return

        val widthDp = bannerWidthDp(activity, container)
        val adSize = runCatching {
            AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp)
        }.getOrNull() ?: AdSize.BANNER

        val adView = AdView(activity).apply {
            adUnitId = AdConfig.bannerUnitId
            setAdSize(adSize)
            adListener = object : AdListener() {
                override fun onAdLoaded() {
                    container.visibility = ViewGroup.VISIBLE
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    container.visibility = ViewGroup.GONE
                }
            }
        }
        container.addView(
            adView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        adView.loadAd(AdRequest.Builder().build())
    }

    private fun bannerWidthDp(activity: Activity, container: ViewGroup): Int {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.getMetrics(metrics)
        val widthPx = if (container.width > 0) container.width else metrics.widthPixels
        val density = activity.resources.displayMetrics.density
        return (widthPx / density).toInt().coerceAtLeast(320)
    }

    // -------------------------------------------------------------- 전면 광고

    fun preloadInterstitial(context: Context) {
        if (!initialized) initialize(context)
        if (interstitial != null || loading) return
        loading = true
        val request = AdRequest.Builder().build()
        InterstitialAd.load(
            context.applicationContext,
            AdConfig.interstitialUnitId,
            request,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    loading = false
                    interstitial = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loading = false
                    interstitial = null
                    Log.d(TAG, "전면 광고 준비 실패: ${error.message}")
                }
            }
        )
    }

    /** 준비된 전면 광고가 있고 쿨다운이 지났으면 보여 주고, 어느 경우든 [onDone] 을 부른다. */
    fun showInterstitialIfReady(activity: Activity, onDone: () -> Unit) {
        val ad = interstitial
        val now = System.currentTimeMillis()
        if (ad == null) {
            preloadInterstitial(activity)
            onDone()
            return
        }
        if (now - lastShownAt < INTERSTITIAL_COOLDOWN_MS) {
            onDone()
            return
        }
        var finished = false
        fun finishOnce() {
            if (finished) return
            finished = true
            onDone()
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitial = null
                preloadInterstitial(activity)
                finishOnce()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                interstitial = null
                preloadInterstitial(activity)
                finishOnce()
            }
        }
        interstitial = null
        lastShownAt = now
        if (runCatching { ad.show(activity) }.isFailure) {
            finishOnce()
        }
    }
}
