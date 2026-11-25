package com.youngsu.slimvideo.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.youngsu.slimvideo.R
import com.youngsu.slimvideo.ads.AdManager
import com.youngsu.slimvideo.compress.CompressManager
import com.youngsu.slimvideo.compress.CompressRequest
import com.youngsu.slimvideo.compress.CompressService
import com.youngsu.slimvideo.data.AppLanguage
import com.youngsu.slimvideo.data.AppPrefs
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var rootFrame: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var adContainer: FrameLayout

    private val screens = HashMap<Int, Screen>()
    private var currentTab = R.id.nav_home
    private var topInset = 0
    private var appliedTopInset = -1
    private var bottomInset = 0

    /** 첫 실행 언어 선택 화면 (고르기 전까지만 떠 있다) */
    private var languagePicker: View? = null

    /** 압축이 끝나 전면 광고를 아직 보여 주지 않았는지 */
    private var interstitialDue = false
    private var wasRunning = false

    /** 저장소 권한(Android 9 이하)을 허용받은 뒤 실행할 동작 */
    private var pendingAfterStoragePermission: (() -> Unit)? = null

    private val pickVideo = registerForActivityResult(PickVideoContract()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        bottomNav.selectedItemId = R.id.nav_home
        (screens[R.id.nav_home] as? HomeScreen)?.loadVideo(uri)
    }

    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingAfterStoragePermission
        pendingAfterStoragePermission = null
        if (granted) {
            action?.invoke()
        } else {
            toast(getString(R.string.error_storage))
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 거절해도 압축은 그대로 진행한다 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        AdManager.initialize(this)
        AdManager.preloadInterstitial(this)

        // 첫 실행 언어 선택 화면을 콘텐츠 위에 겹쳐 놓을 수 있도록 한 겹 더 둔다
        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.bg))
        }
        setContentView(rootFrame)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
        }
        rootFrame.addView(
            root,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        content = FrameLayout(this)
        root.addView(
            content,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        )

        bottomNav = BottomNavigationView(this).apply {
            inflateMenu(R.menu.nav_menu)
            background = GradientDrawable().apply {
                setColor(getColor(R.color.nav_bg))
                setStroke(dp(1f), getColor(R.color.divider))
            }
            elevation = 0f
            itemIconTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(getColor(R.color.accent), getColor(R.color.text_tertiary))
            )
            itemTextColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(getColor(R.color.accent), getColor(R.color.text_tertiary))
            )
            itemActiveIndicatorColor = ColorStateList.valueOf(getColor(R.color.accent_dim))
            itemIconSize = dp(21f)
            labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
            setOnItemSelectedListener { item ->
                selectTab(item.itemId)
                true
            }
        }
        root.addView(bottomNav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64f)))

        // 배너 광고는 하단 탭 바보다 더 아래 — 화면 맨 끝(최하단)에 둔다
        adContainer = FrameLayout(this)
        root.addView(
            adContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        hideSystemNavBar()

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topInset = bars.top
            bottomInset = bars.bottom
            applyInsets()
            insets
        }

        val startTab = savedTab(savedInstanceState)
        bottomNav.selectedItemId = startTab
        selectTab(startTab)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (languagePicker != null) {
                    // 첫 실행 언어 선택 전에는 뒤로 가기로 빠져나가지 않는다
                    return
                }
                if (CompressManager.isRunning) {
                    toast(getString(R.string.compressing))
                } else if (currentTab != R.id.nav_home) {
                    bottomNav.selectedItemId = R.id.nav_home
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // 배너가 붙어 높이가 바뀌면 인셋을 다시 계산한다.
        // (예전처럼 viewTreeObserver.addOnGlobalLayoutListener 로 매 레이아웃마다 부르면
        //  레이아웃 직후·그리기 직전에 화면을 다시 만들어 빈 화면이 됐다)
        adContainer.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val sizeChanged = (right - left != oldRight - oldLeft) || (bottom - top != oldBottom - oldTop)
            if (sizeChanged) adContainer.post { applyInsets() }
        }
        AdManager.attachBanner(this, adContainer)

        observeCompression()
        handleIncoming(intent)
        showLanguagePickerIfNeeded()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        hideSystemNavBar()
        updateKeepScreenOn()
        screens[currentTab]?.refresh()
        maybeShowInterstitial()
    }

    // ------------------------------------------------------------- insets

    private fun hideSystemNavBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    /**
     * 인셋 반영 — 화면을 다시 만들지 않고 **여백과 탭 바 높이만** 바꾼다.
     * 화면 쪽 setSystemInsets 도 여백만 갱신하므로 레이아웃이 끝나기를 기다리는 일이 없다.
     */
    private fun applyInsets() {
        val adVisible = adContainer.childCount > 0 && adContainer.visibility == View.VISIBLE
        if (adVisible) {
            applyTabBarBottomInset(0)
            adContainer.updatePadding(bottom = bottomInset)
        } else {
            applyTabBarBottomInset(bottomInset)
            adContainer.updatePadding(bottom = 0)
        }
        languagePicker?.updatePadding(top = topInset, bottom = bottomInset)
        if (topInset != appliedTopInset) {
            appliedTopInset = topInset
            screens.values.forEach { it.setSystemInsets(topInset) }
        }
    }

    private fun applyTabBarBottomInset(inset: Int) {
        val params = bottomNav.layoutParams
        val target = dp(64f) + inset
        if (params.height != target) {
            params.height = target
            bottomNav.layoutParams = params
        }
        bottomNav.updatePadding(bottom = inset)
    }

    private fun updateKeepScreenOn() {
        if (CompressManager.isRunning) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // --------------------------------------------------------------- tabs

    private fun selectTab(itemId: Int) {
        screens[currentTab]?.onHidden()
        currentTab = itemId
        val screen = screens.getOrPut(itemId) { createScreen(itemId) }
        content.removeAllViews()
        content.addView(
            screen.view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        screen.setSystemInsets(topInset)
        screen.refresh()
        if (itemId == R.id.nav_library || itemId == R.id.nav_settings) {
            AdManager.preloadInterstitial(this)
        }
    }

    private fun createScreen(itemId: Int): Screen = when (itemId) {
        R.id.nav_library -> LibraryScreen(this)
        R.id.nav_settings -> SettingsScreen(this)
        else -> HomeScreen(this)
    }

    private fun savedTab(savedInstanceState: Bundle?): Int {
        val tab = savedInstanceState?.getInt(STATE_TAB) ?: R.id.nav_home
        return if (tab == R.id.nav_library || tab == R.id.nav_settings) tab else R.id.nav_home
    }

    fun openTab(itemId: Int) {
        runOnUiThread { bottomNav.selectedItemId = itemId }
    }

    fun currentTabId(): Int = currentTab

    // ------------------------------------------- 첫 실행 언어 선택 (온보딩)

    private fun showLanguagePickerIfNeeded() {
        if (AppPrefs.get(this).languageChosen) return
        val picker = LanguagePicker(this) { tag -> onLanguagePicked(tag) }
        languagePicker = picker.view
        rootFrame.addView(
            picker.view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        applyInsets()
    }

    private fun onLanguagePicked(tag: String) {
        // 다시 만들어져도 선택 화면이 또 뜨지 않도록 먼저 저장한다
        AppPrefs.get(this).languageChosen = true
        dismissLanguagePicker()
        if (tag != AppLanguage.currentTag()) {
            AppLanguage.apply(tag)
        }
    }

    private fun dismissLanguagePicker() {
        languagePicker?.let { rootFrame.removeView(it) }
        languagePicker = null
    }

    // ------------------------------------------------------------ actions

    fun pickVideo() {
        runCatching { pickVideo.launch(Unit) }
            .onFailure { toast(getString(R.string.error_read)) }
    }

    /** 압축 시작 — 권한 확인, 알림 서비스, 실제 작업 순서 */
    fun beginCompression(request: CompressRequest) {
        val needsStorage = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsStorage) {
            pendingAfterStoragePermission = { startCompressionNow(request) }
            requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            startCompressionNow(request)
        }
    }

    private fun startCompressionNow(request: CompressRequest) {
        requestNotificationPermissionIfNeeded()
        // 상태를 먼저 Running 으로 만든 뒤 서비스를 띄운다 — 서비스가 Idle 을 보고 곧바로 내려가지 않게
        val started = CompressManager.start(this, request)
        if (started) {
            CompressService.start(this)
        } else {
            toast(getString(R.string.compressing))
        }
        updateKeepScreenOn()
    }

    fun cancelCompression() {
        CompressManager.cancel()
    }

    fun playVideo(uri: Uri) {
        startActivity(PlayerActivity.intent(this, uri))
    }

    fun shareVideo(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        }.onFailure { toast(getString(R.string.share_failed)) }
    }

    fun toast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    fun setScreenTopPadding(view: View) {
        view.updatePadding(top = topInset)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    // ------------------------------------------------------ compression state

    private fun observeCompression() {
        lifecycleScope.launch {
            CompressManager.state.collect { state ->
                val running = state is CompressManager.State.Running
                if (wasRunning && !running) {
                    CompressService.stop(this@MainActivity)
                }
                if (wasRunning && state is CompressManager.State.Success) {
                    interstitialDue = true
                }
                wasRunning = running
                updateKeepScreenOn()
                maybeShowInterstitial()
                if (!running) {
                    screens[currentTab]?.refresh()
                }
            }
        }
    }

    /** 압축이 끝난 뒤 결과 화면이 보일 때 전면 광고를 한 번 띄운다 (앱이 보일 때만) */
    private fun maybeShowInterstitial() {
        if (!interstitialDue) return
        if (CompressManager.state.value !is CompressManager.State.Success) return
        if (currentTab != R.id.nav_home) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (isFinishing || isDestroyed) return
        interstitialDue = false
        // 결과가 먼저 보이도록 한 박자 뒤에
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) {
                AdManager.showInterstitialIfReady(this) { }
            }
        }, 700)
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri == null) return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        bottomNav.selectedItemId = R.id.nav_home
        (screens[R.id.nav_home] as? HomeScreen)?.loadVideo(uri)
    }

    companion object {
        private const val STATE_TAB = "slim_tab"
    }
}
