package com.example.fadealarm

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Calendar

/* ---------- Планувальник ---------- */
object Scheduler {
    const val LEAD_MS = 30 * 60 * 1000L   // за скільки до часу починати (30 хв)

    private fun servicePi(ctx: Context, target: Long) = PendingIntent.getForegroundService(
        ctx, 1,
        Intent(ctx, AlarmService::class.java).putExtra("target", target),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(ctx: Context, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        val target = cal.timeInMillis
        val start = maxOf(target - LEAD_MS, System.currentTimeMillis() + 1000)
        val show = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        ctx.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(start, show), servicePi(ctx, target))
        return start
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(servicePi(ctx, 0))
        ctx.stopService(Intent(ctx, AlarmService::class.java))
    }
}

/* ---------- Сервіс: грає і плавно додає гучність ---------- */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var target = 0L

    companion object {
        const val MIN_VOL = 0.01   // стартова гучність, можна зменшити
    }

    private val tick = object : Runnable {
        override fun run() {
            val f = ((System.currentTimeMillis() - (target - Scheduler.LEAD_MS)).toDouble()
                    / Scheduler.LEAD_MS).coerceIn(0.0, 1.0)
            val v = (MIN_VOL * Math.pow(1.0 / MIN_VOL, f)).toFloat()
            player?.setVolume(v, v)
            if (f < 1.0) handler.postDelayed(this, 100)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }

        target = intent?.getLongExtra("target", 0L) ?: 0L
        getSharedPreferences("alarm", MODE_PRIVATE).edit().putBoolean("on", false).apply()

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("alarm", "Будильник", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 3, Intent(this, AlarmService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, "alarm")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Будильник")
            .setContentText("Гучність поступово зростає…")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(0, "СТОП", stopPi)
            .build()
        ServiceCompat.startForeground(
            this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )

        val am = getSystemService(AudioManager::class.java)
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)

        startPlayer()
        handler.removeCallbacks(tick)
        tick.run()
        return START_NOT_STICKY
    }

    private fun startPlayer() {
        player?.release()
        val saved = getSharedPreferences("alarm", MODE_PRIVATE).getString("uri", null)
        val fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        player = try {
            build(if (saved != null) Uri.parse(saved) else fallback)
        } catch (e: Exception) {
            build(fallback)
        }
    }

    private fun build(uri: Uri) = MediaPlayer().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        setDataSource(this@AlarmService, uri)
        isLooping = true
        setWakeMode(this@AlarmService, PowerManager.PARTIAL_WAKE_LOCK)
        prepare()
        setVolume(MIN_VOL.toFloat(), MIN_VOL.toFloat())
        start()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        player?.release(); player = null
        super.onDestroy()
    }
}

/* ---------- Відновлення після перезавантаження ---------- */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val p = ctx.getSharedPreferences("alarm", Context.MODE_PRIVATE)
        if (p.getBoolean("on", false)) Scheduler.schedule(ctx, p.getInt("h", 7), p.getInt("m", 0))
    }
}

/* ---------- Екран ---------- */
class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var picker: TimePicker
    private lateinit var status: TextView

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString("uri", uri.toString()).apply()
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("alarm", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val dp = resources.displayMetrics.density
        val pad = (24 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        picker = TimePicker(this).apply {
            setIs24HourView(true)
            hour = prefs.getInt("h", 7); minute = prefs.getInt("m", 0)
        }
        status = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER; setPadding(0, pad, 0, pad) }

        fun btn(label: String, action: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { action() }
        }
        root.addView(picker)
        root.addView(btn("Вибрати мелодію") { pickAudio.launch(arrayOf("audio/*")) })
        root.addView(btn("Увімкнути будильник") {
            prefs.edit().putInt("h", picker.hour).putInt("m", picker.minute).putBoolean("on", true).apply()
            Scheduler.schedule(this, picker.hour, picker.minute)
            refresh()
        })
        root.addView(btn("Скасувати / вимкнути") {
            Scheduler.cancel(this)
            prefs.edit().putBoolean("on", false).apply()
            refresh()
        })
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val uri = prefs.getString("uri", null)
        val melody = if (uri == null) "стандартна" else try {
            contentResolver.query(Uri.parse(uri), null, null, null, null)?.use {
                if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
            } ?: "обрана"
        } catch (e: Exception) { "обрана" }
        val on = prefs.getBoolean("on", false)
        val t = "%02d:%02d".format(prefs.getInt("h", 7), prefs.getInt("m", 0))
        status.text = (if (on) "Будильник на $t (почне грати за 30 хв)" else "Будильник вимкнений") +
                "\nМелодія: $melody"
    }
}
