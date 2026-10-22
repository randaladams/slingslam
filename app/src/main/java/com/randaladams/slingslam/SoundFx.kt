package com.randaladams.slingslam

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

class SoundFx(context: Context) {

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = HashMap<Sfx, Int>()
    var enabled = true

    init {
        ids[Sfx.THROW] = pool.load(context, R.raw.sfx_throw, 1)
        ids[Sfx.LAND] = pool.load(context, R.raw.sfx_land, 1)
        ids[Sfx.HIT] = pool.load(context, R.raw.sfx_hit, 1)
        ids[Sfx.BREAK] = pool.load(context, R.raw.sfx_break, 1)
        ids[Sfx.HURT] = pool.load(context, R.raw.sfx_hurt, 1)
        ids[Sfx.PICKUP] = pool.load(context, R.raw.sfx_pickup, 1)
        ids[Sfx.UPGRADE] = pool.load(context, R.raw.sfx_upgrade, 1)
        ids[Sfx.GAMEOVER] = pool.load(context, R.raw.sfx_gameover, 1)
    }

    fun play(s: Sfx) {
        if (!enabled) return
        val id = ids[s] ?: return
        val vol = MASTER * when (s) {
            Sfx.LAND -> 0.6f
            Sfx.THROW -> 0.7f
            else -> 1f
        }
        pool.play(id, vol, vol, 1, 0, 1f)
    }

    fun release() = pool.release()

    companion object {
        /** Overall loudness (0..1). */
        const val MASTER = 0.5f
    }
}
