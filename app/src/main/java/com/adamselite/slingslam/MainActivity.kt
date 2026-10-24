package com.adamselite.slingslam

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

class MainActivity : Activity() {

    private lateinit var game: GameView
    private lateinit var root: FrameLayout
    private lateinit var ads: Ads
    private var banner: AdView? = null
    private var stripTop = 0
    private var stripHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val bannerPx = AdSize.BANNER.height * density
        ads = Ads(this)
        // the reserved strip is a little taller than a standard 50dp banner
        game = GameView(this, { finish() }, ads, bannerPx + 8f * density)
        game.onAdStrip = { top, height ->
            stripTop = top
            stripHeight = height
            placeBanner()
        }
        root = FrameLayout(this)
        root.addView(game, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        ads.start { showBanner() }

        // Android 13+ back handling (Android 16 no longer calls onBackPressed):
        // the first back press pauses a running game; otherwise the app closes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                OnBackInvokedCallback { if (!game.handleBack()) finish() },
            )
        }
    }

    /** Standard 320x50 banner, centred in the strip GameView reserves at the top. */
    private fun showBanner() {
        if (banner != null) return
        try {
            val v = AdView(this)
            v.setAdSize(AdSize.BANNER)
            v.adUnitId = BuildConfig.ADMOB_BANNER
            root.addView(v, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ))
            banner = v
            placeBanner()
            v.loadAd(AdRequest.Builder().build())
        } catch (t: Throwable) {
            // never let ads break the game
        }
    }

    private fun placeBanner() {
        val v = banner ?: return
        val lp = v.layoutParams as FrameLayout.LayoutParams
        val bh = (AdSize.BANNER.height * resources.displayMetrics.density).toInt()
        lp.topMargin = stripTop + ((stripHeight - bh) / 2).coerceAtLeast(0)
        v.layoutParams = lp
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        banner?.pause()
        super.onPause()
        game.onHostPause()
    }

    override fun onResume() {
        super.onResume()
        banner?.resume()
        game.onHostResume()
    }

    override fun onDestroy() {
        banner?.destroy()
        game.release()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // First back press pauses a running game instead of quitting it.
        if (!game.handleBack()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }
    }
}
