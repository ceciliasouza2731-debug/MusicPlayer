package com.musicplayer

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.musicplayer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mediaPlayer: MediaPlayer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var visualizer: Visualizer? = null

    private val playlist = mutableListOf<Uri>()
    private val songNames = mutableListOf<String>()
    private var currentIndex = 0
    private var isPlaying = false
    private var boostEnabled = false
    private var boostGain = 0

    private val handler = Handler(Looper.getMainLooper())
    private val updateSeekBar = object : Runnable {
        override fun run() {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    binding.seekBar.progress = it.currentPosition
                    val current = formatTime(it.currentPosition)
                    val total = formatTime(it.duration)
                    binding.tvTime.text = "$current / $total"
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    // File picker launcher for multiple files
    private val pickMultipleFiles =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) {
                playlist.clear()
                songNames.clear()
                uris.forEach { uri ->
                    playlist.add(uri)
                    songNames.add(getFileName(uri))
                }
                currentIndex = 0
                Toast.makeText(this, "${playlist.size} músicas adicionadas!", Toast.LENGTH_SHORT).show()
                loadSong(currentIndex)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()
        setupUI()
        handler.post(updateSeekBar)
    }

    private fun setupUI() {
        // Select files button
        binding.btnSelect.setOnClickListener {
            pickMultipleFiles.launch("audio/*")
        }

        // Play/Pause button
        binding.btnPlayPause.setOnClickListener {
            togglePlayPause()
        }

        // Next button
        binding.btnNext.setOnClickListener {
            playNext()
        }

        // Previous button
        binding.btnPrev.setOnClickListener {
            playPrevious()
        }

        // Boost toggle
        binding.switchBoost.setOnCheckedChangeListener { _, isChecked ->
            boostEnabled = isChecked
            applyBoost()
            if (isChecked) {
                binding.seekBarBoost.isEnabled = true
                Toast.makeText(this, "🔊 Boost Ativado!", Toast.LENGTH_SHORT).show()
            } else {
                binding.seekBarBoost.isEnabled = false
                Toast.makeText(this, "Boost Desativado", Toast.LENGTH_SHORT).show()
            }
        }

        // Boost seek bar (0 to 1000 = 0 to 10dB gain)
        binding.seekBarBoost.apply {
            max = 1000
            progress = 0
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    boostGain = progress
                    applyBoost()
                    val db = progress / 100f
                    binding.tvBoostLevel.text = String.format("Boost: +%.1f dB", db)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        // Song seek bar
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.seekTo(progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Volume SeekBar (system volume)
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.seekBarVolume.apply {
            max = maxVol
            progress = currentVol
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
    }

    private fun loadSong(index: Int) {
        if (playlist.isEmpty()) return

        releaseMediaPlayer()

        val uri = playlist[index]
        mediaPlayer = MediaPlayer().apply {
            setDataSource(applicationContext, uri)
            prepare()
            setOnCompletionListener { playNext() }
        }

        binding.seekBar.max = mediaPlayer!!.duration
        binding.tvSongName.text = songNames[index]
        binding.tvSongIndex.text = "${index + 1} / ${playlist.size}"

        setupEffects()
        startPlayback()
    }

    private fun setupEffects() {
        mediaPlayer?.let { mp ->
            // LoudnessEnhancer for sound boost
            try {
                loudnessEnhancer = LoudnessEnhancer(mp.audioSessionId).apply {
                    enabled = boostEnabled
                    if (boostEnabled) setTargetGain(boostGain)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Visualizer for waveform/FFT animation
            try {
                visualizer?.release()
                visualizer = Visualizer(mp.audioSessionId).apply {
                    captureSize = Visualizer.getCaptureSizeRange()[1]
                    setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                            waveform?.let { binding.waveformView.updateWaveform(it) }
                        }
                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                            fft?.let { binding.waveformView.updateFft(it) }
                        }
                    }, Visualizer.getMaxCaptureRate() / 2, true, true)
                    enabled = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun applyBoost() {
        loudnessEnhancer?.let {
            it.enabled = boostEnabled
            if (boostEnabled) {
                it.setTargetGain(boostGain)
            }
        }
    }

    private fun startPlayback() {
        mediaPlayer?.start()
        isPlaying = true
        binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
        binding.waveformView.startAnimation()
    }

    private fun togglePlayPause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                isPlaying = false
                binding.btnPlayPause.setImageResource(R.drawable.ic_play)
                binding.waveformView.pauseAnimation()
            } else {
                it.start()
                isPlaying = true
                binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
                binding.waveformView.startAnimation()
            }
        } ?: run {
            if (playlist.isNotEmpty()) loadSong(currentIndex)
        }
    }

    private fun playNext() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlist.size
        loadSong(currentIndex)
    }

    private fun playPrevious() {
        if (playlist.isEmpty()) return
        currentIndex = if (currentIndex - 1 < 0) playlist.size - 1 else currentIndex - 1
        loadSong(currentIndex)
    }

    private fun releaseMediaPlayer() {
        visualizer?.release()
        visualizer = null
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
    }

    private fun getFileName(uri: Uri): String {
        var name = "Música desconhecida"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex).removeSuffix(".mp3").removeSuffix(".m4a")
                    .removeSuffix(".flac").removeSuffix(".ogg").removeSuffix(".wav")
            }
        }
        return name
    }

    private fun formatTime(ms: Int): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / 1000) / 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateSeekBar)
        releaseMediaPlayer()
    }
}
