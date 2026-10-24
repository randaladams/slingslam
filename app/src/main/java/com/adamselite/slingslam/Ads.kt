package com.adamselite.slingslam

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google AdMob ads with Google's consent form (required in the EU/UK/Switzerland).
 *
 *  - Banner: the strip at the top of the screen (created by MainActivity once the SDK is ready).
 *  - Interstitial: before every 3rd run.
 *  - Rewarded: "watch an ad to continue" after the shield runs out, once per run.
 *
 * Ad unit ids come from BuildConfig: the Play Store bundle gets the real ids, the side-loaded
 * APK gets Google's public test ids (see app/build.gradle.kts).
 */
class Ads(private val activity: Activity) : AdGate {

    private val main = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private var consent: ConsentInformation? = null
    private var onReady: (() -> Unit)? = null

    @Volatile private var rewarded: RewardedAd? = null
    @Volatile private var interstitial: InterstitialAd? = null
    @Volatile private var showing = false
    private var loadingRewarded = false
    private var loadingInterstitial = false
    private var earned = false

    /** Ask for consent where the law requires it, then start the SDK; [ready] runs once it's up. */
    fun start(ready: () -> Unit) {
        onReady = ready
        try {
            val c = UserMessagingPlatform.getConsentInformation(activity)
            consent = c
            val params = ConsentRequestParameters.Builder().build()
            c.requestConsentInfoUpdate(
                activity, params,
                {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ ->
                        if (c.canRequestAds()) initSdk()
                    }
                },
                { _ -> if (c.canRequestAds()) initSdk() },
            )
            if (c.canRequestAds()) initSdk()
        } catch (t: Throwable) {
            // never let ads break the game
        }
    }

    private fun initSdk() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            try {
                MobileAds.initialize(activity) { _ ->
                    main.post {
                        loadRewarded()
                        loadInterstitial()
                        onReady?.invoke()
                    }
                }
            } catch (t: Throwable) {
            }
        }, "ads-init").start()
    }

    // ---- loading ----------------------------------------------------------------------

    private fun loadRewarded() {
        if (!started.get() || rewarded != null || loadingRewarded) return
        loadingRewarded = true
        RewardedAd.load(activity, BuildConfig.ADMOB_REWARDED, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) {
                loadingRewarded = false
                rewarded = ad
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                loadingRewarded = false
                rewarded = null
                main.postDelayed({ loadRewarded() }, 30_000)
            }
        })
    }

    private fun loadInterstitial() {
        if (!started.get() || interstitial != null || loadingInterstitial) return
        loadingInterstitial = true
        InterstitialAd.load(activity, BuildConfig.ADMOB_INTERSTITIAL, AdRequest.Builder().build(), object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) {
                loadingInterstitial = false
                interstitial = ad
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                loadingInterstitial = false
                interstitial = null
                main.postDelayed({ loadInterstitial() }, 30_000)
            }
        })
    }

    // ---- showing ----------------------------------------------------------------------

    override fun rewardedReady() = rewarded != null && !showing

    override fun interstitialReady() = interstitial != null && !showing

    override fun showRewarded(done: (Boolean) -> Unit) {
        main.post {
            val ad = rewarded
            if (ad == null || showing) {
                done(false)
                return@post
            }
            showing = true
            earned = false
            rewarded = null
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    showing = false
                    done(earned)
                    loadRewarded()
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    showing = false
                    done(false)
                    loadRewarded()
                }
            }
            ad.show(activity) { _ -> earned = true }
        }
    }

    override fun showInterstitial(done: () -> Unit) {
        main.post {
            val ad = interstitial
            if (ad == null || showing) {
                done()
                return@post
            }
            showing = true
            interstitial = null
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    showing = false
                    done()
                    loadInterstitial()
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    showing = false
                    done()
                    loadInterstitial()
                }
            }
            ad.show(activity)
        }
    }

    // ---- privacy options (a button the law requires once consent was asked) ----------

    override fun privacyOptionsRequired(): Boolean = try {
        consent?.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    } catch (t: Throwable) {
        false
    }

    override fun showPrivacyOptions() {
        main.post {
            try {
                UserMessagingPlatform.showPrivacyOptionsForm(activity) { _ ->
                    if (consent?.canRequestAds() == true) initSdk()
                }
            } catch (t: Throwable) {
            }
        }
    }
}
