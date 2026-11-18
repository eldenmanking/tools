package goals.app.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import goals.app.GateActivity
import goals.app.MainActivity
import goals.app.Permissions
import goals.app.R
import goals.app.data.StatusStore
import goals.core.AppEvent
import goals.core.ForegroundTracker
import goals.core.Notice
import goals.core.gateState
import goals.core.notice
import goals.core.shouldGate
import java.time.Instant
import java.time.ZoneId

/**
 * The always-on watcher: a foreground service in its own process (":watch") that never loads
 * Compose or any UI class, so it costs about as much as an empty Android process (see the
 * README's Memory section). While the screen is on it polls the usage events every
 * [POLL_MS], and when a gated app comes to the front it starts [GateActivity] over it. It
 * reads the status the UI process last saved; it doesn't fetch.
 */
class WatchService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tracker = ForegroundTracker()
    private lateinit var usage: UsageStatsManager
    private lateinit var store: StatusStore
    private lateinit var alwaysAllowed: Set<String>
    private var queriedTo = 0L
    private var shown: Notice? = null
    private var gatedPkg: String? = null
    private var gatedAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, POLL_MS)
        }
    }

    // Poll only while the screen is on: nothing can be opened while it's off.
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.removeCallbacks(tick)
            if (intent.action == Intent.ACTION_SCREEN_ON) handler.post(tick)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        usage = getSystemService(UsageStatsManager::class.java)
        store = StatusStore(this)
        alwaysAllowed = alwaysAllowed()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Gym Gate status", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        val first = noticeNow()
        shown = first
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, build(first), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, build(first))
        }
        registerReceiver(
            screen,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            RECEIVER_NOT_EXPORTED,
        )
        // Recent history tells the tracker what is in front right now.
        queriedTo = System.currentTimeMillis() - LOOKBACK_MS
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        unregisterReceiver(screen)
        super.onDestroy()
    }

    private fun check() {
        val now = System.currentTimeMillis()
        val pkg = tracker.update(readEvents(queriedTo, now))
        queriedTo = now
        val cached = store.load()
        val state = gateState(cached, Instant.now(), ZoneId.systemDefault())

        val n = notice(state, cached)
        if (n != shown) { // e-ink: redraw only on change
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(n))
            shown = n
        }

        if (pkg == null || !shouldGate(pkg, state, cached, alwaysAllowed)) {
            gatedPkg = null
            return
        }
        // Once per app coming to the front, and again if it is still there after a while
        // (the gate was somehow dismissed without leaving the app).
        if (pkg != gatedPkg || now - gatedAt > REGATE_MS) {
            gatedPkg = pkg
            gatedAt = now
            startActivity(
                Intent(this, GateActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }

    private fun readEvents(from: Long, to: Long): List<AppEvent> {
        val out = ArrayList<AppEvent>()
        val events = usage.queryEvents(from, to) ?: return out
        val e = UsageEvents.Event()
        while (events.getNextEvent(e)) {
            val kind = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> AppEvent.Kind.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> AppEvent.Kind.PAUSED
                else -> continue
            }
            out.add(AppEvent(e.timeStamp, e.packageName, e.className ?: "", kind))
        }
        return out
    }

    private fun noticeNow(): Notice {
        val cached = store.load()
        return notice(gateState(cached, Instant.now(), ZoneId.systemDefault()), cached)
    }

    private fun build(n: Notice): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_gate)
            .setContentTitle(n.title)
            .setContentText(n.text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
    }

    /**
     * This app, the launcher(s), Settings and the package installer. Not the permission
     * controller: a gated app could keep raising its own permission dialog to stay in front,
     * and the gate's permissions are all granted on Settings screens anyway.
     */
    private fun alwaysAllowed(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers = packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
        return setOf(packageName) + launchers + SYSTEM_ALLOWED
    }

    companion object {
        private const val CHANNEL = "status"
        private const val NOTIFICATION_ID = 1
        private const val POLL_MS = 1_000L
        private const val LOOKBACK_MS = 10 * 60_000L
        private const val REGATE_MS = 5_000L
        private val SYSTEM_ALLOWED = setOf(
            "com.android.settings",
            "com.android.systemui",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.phone", // emergency calls
        )

        /** Start the watcher if the permissions it needs are granted. Safe to call repeatedly. */
        fun start(context: Context) {
            if (Permissions.canWatch(context)) {
                context.startForegroundService(Intent(context, WatchService::class.java))
            }
        }
    }
}
