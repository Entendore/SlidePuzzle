package com.example.slidepuzzle

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Tiny synthesized sound effects rendered to WAV files in the cache dir once
 * (then cached), played through a SoundPool — no sound assets needed.
 */
class PuzzleSounds(context: Context) {

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private var loadedCount = 0
    private var slideId = 0
    private var winId = 0

    init {
        soundPool.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) loadedCount++
        }
        val dir = File(context.cacheDir, "sounds").apply { mkdirs() }
        slideId = soundPool.load(cachedWav(File(dir, "slide.wav")) { renderWav(it, 0.08, ::slideSample) }, 1)
        winId = soundPool.load(cachedWav(File(dir, "win.wav")) { renderWav(it, 0.70, ::winSample) }, 1)
    }

    fun playSlide() = play(slideId, 0.5f)
    fun playWin() = play(winId, 0.8f)

    private fun play(id: Int, volume: Float) {
        if (loadedCount > 0) soundPool.play(id, volume, volume, 1, 0, 1f)
    }

    fun release() {
        soundPool.release()
        loadedCount = 0
    }

    private fun cachedWav(file: File, render: (File) -> String): String {
        if (!file.exists() || file.length() <= 44L) render(file)
        return file.absolutePath
    }

    /** Soft "tock": fast-decaying downward chirp. */
    private fun slideSample(t: Double): Double {
        val phase = 2.0 * PI * (620.0 * t - 1_285.0 * t * t)
        return 0.55 * exp(-t * 42.0) * sin(phase)
    }

    /** Three-note arpeggio: C6 → E6 → G6. */
    private fun winSample(t: Double): Double {
        val notes = doubleArrayOf(1_046.5, 1_318.5, 1_568.0)
        val noteLength = 0.22
        val index = min(2, (t / noteLength).toInt())
        val noteT = t - index * noteLength
        return 0.35 * exp(-noteT * 9.0) * sin(2.0 * PI * notes[index] * noteT)
    }

    private fun renderWav(file: File, durationSeconds: Double, sample: (t: Double) -> Double): String {
        val sampleRate = 44_100
        val frames = (durationSeconds * sampleRate).toInt()
        val pcm = ByteArray(frames * 2)
        for (i in 0 until frames) {
            val v = (sample(i / sampleRate.toDouble()).coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt()
            pcm[2 * i] = (v and 0xFF).toByte()
            pcm[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val header = ByteArray(44)
        putInt(header, 0, 0x52_49_46_46)   // "RIFF"
        putInt(header, 4, 36 + pcm.size)
        putInt(header, 8, 0x57_41_56_45)   // "WAVE"
        putInt(header, 12, 0x66_6D_74_20)  // "fmt "
        putInt(header, 16, 16)             // PCM chunk size
        putShort(header, 20, 1)            // audio format: PCM
        putShort(header, 22, 1)            // channels: mono
        putInt(header, 24, sampleRate)
        putInt(header, 28, sampleRate * 2) // byte rate
        putShort(header, 32, 2)            // block align
        putShort(header, 34, 16)           // bits per sample
        putInt(header, 36, 0x64_61_74_61)  // "data"
        putInt(header, 40, pcm.size)
        file.outputStream().use { out ->
            out.write(header)
            out.write(pcm)
        }
        return file.absolutePath
    }

    private fun putInt(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value shr 8) and 0xFF).toByte()
        dst[offset + 2] = ((value shr 16) and 0xFF).toByte()
        dst[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun putShort(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
