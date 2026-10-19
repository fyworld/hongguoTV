// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.hongguotv.core.AppUpdate
import com.hongguotv.core.AppUpdateRepository
import com.hongguotv.core.UpdateFiles
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Foreground-only updater: no background service, and no networking or prompts during playback. */
class AppUpdater(private val activity: Activity, private val idle: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val repository = AppUpdateRepository()
    private val preferences = activity.getSharedPreferences("app_updates", Activity.MODE_PRIVATE)
    private val apk = File(activity.cacheDir, "updates/update.apk")
    private var task: Future<*>? = null
    private var generation = 0
    private var active = false
    private var busy = false
    private var available: AppUpdate? = null
    private var ready = false
    private var permissionRequested = false
    private var dialog: AlertDialog? = null
    val showing get() = dialog?.isShowing == true
    private var statusView: TextView? = null
    private var anchor: View? = null
    /** Optional extra action shown at the bottom of the update dialog (e.g. feedback / WeChat QR). */
    var extraAction: Pair<String, () -> Unit>? = null
    private var status = "当前版本 ${BuildConfig.VERSION_NAME}"
    private var retryAfter = 0L
    /** VersionName of the update already prompted this session (versionCode now
     *  increases per release, but the name is still the unique per-build key). */
    private var promptedName: String? = null
    private val auto get() = preferences.getBoolean("automatic", true)

    init {
        // Replace-installs keep SharedPreferences: a fresh build must not inherit the
        // previous version's 6h check throttle or "later" deferral, otherwise the
        // update prompt never fires after upgrading (seen on 1.0.42-test: the old
        // build had just checked, so the new one stayed silent for hours).
        if (preferences.getString("installed_version", null) != BuildConfig.VERSION_NAME) {
            preferences.edit().putString("installed_version", BuildConfig.VERSION_NAME)
                .remove("last_attempt").remove("deferred_code").remove("deferred_name").remove("deferred_until").apply()
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            if (idle() && dialog == null && !busy && auto) {
                val now = System.currentTimeMillis()
                val last = preferences.getLong("last_attempt", 0)
                val update = available
                when {
                    ready && update != null && promptedName != update.versionName && !deferred(update) -> { promptedName = update.versionName; show(activity.currentFocus) }
                    update != null && !ready && now >= retryAfter -> download(false)
                    now < last || now - last >= 6 * 60 * 60 * 1000L -> check(false)
                }
            }
            main.postDelayed(this, 15000)
        }
    }
    fun resume() {
        active = true; main.removeCallbacks(poll); main.postDelayed(poll, 8000)
        if (permissionRequested) {
            permissionRequested = false
            if (Build.VERSION.SDK_INT >= 26 && activity.packageManager.canRequestPackageInstalls()) install()
            else { status = "安装权限未开启，可在这里再次安装"; show(anchor) }
        }
    }
    fun pause() {
        active = false; main.removeCallbacks(poll); interrupt()
        dialog?.dismiss()
    }
    fun interrupt() {
        generation++; task?.cancel(true); task = null; repository.cancel(); busy = false
    }
    fun destroy() {
        pause(); worker.shutdownNow()
        // TLS socket close may send close_notify; it must not run on Android's main thread.
        Thread({ repository.close() }, "hongguotv-update-cleanup").start()
    }
    private fun updateStatus(text: String) { status = text; statusView?.text = text }
    /** "Later" defers by versionName (unique per build), not by versionCode. */
    private fun deferred(update: AppUpdate) = (preferences.getString("deferred_name", null) == update.versionName
        && preferences.getLong("deferred_until", 0) > System.currentTimeMillis())
    private fun renderActions() {
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            text = when { busy -> "处理中…"; ready -> "安装新版"; available != null -> "下载新版"; else -> "检查更新" }
            // Keep D-pad focus stable while the request runs; action methods reject duplicates.
            isEnabled = true
        }
    }
    fun show(from: View?) {
        if (!active) return
        anchor = from
        dialog?.dismiss()
        val dp = activity.resources.displayMetrics.density
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding((24*dp).toInt(), (12*dp).toInt(), (24*dp).toInt(), 0) }
        statusView = TextView(activity).apply { text = status; textSize = 17f; setTextColor(TvStyle.text) }
        body.addView(statusView)
        body.addView(CheckBox(activity).apply {
            text = "自动检查并下载更新"; isChecked = auto
            setOnCheckedChangeListener { _, enabled ->
                preferences.edit().putBoolean("automatic", enabled).apply()
                if (!enabled && busy) { interrupt(); updateStatus("自动更新已关闭，可手动检查"); renderActions() }
            }
        })
        body.addView(TextView(activity).apply {
            text = "只在应用前台浏览时检查，播放中不打扰。下载后需确认系统安装，收藏与观看记录保留。"; textSize = 13f; setTextColor(TvStyle.muted)
            setPadding(0, (8*dp).toInt(), 0, (8*dp).toInt())
        })
        // Feedback entry rides the dialog's own button row: neutral=feedback, negative=稍后, positive=检查更新.
        val feedback = extraAction
        val next = AlertDialog.Builder(activity).setTitle("版本与更新 · ${BuildConfig.VERSION_NAME}").setView(ScrollView(activity).apply { addView(body) })
            .setNegativeButton("稍后", null).setPositiveButton("检查更新", null)
            .apply { feedback?.let { setNeutralButton(it.first, null) } }
            .create()
        dialog = next
        next.setOnDismissListener {
            if (dialog === next) { dialog = null; statusView = null }
            if (from?.isAttachedToWindow == true) from.requestFocus()
        }
        next.show()
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { when { ready -> install(); available != null -> download(true); else -> check(true) } }
        next.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            available?.let { preferences.edit().putString("deferred_name", it.versionName).putLong("deferred_until", System.currentTimeMillis()+24*60*60*1000L).apply() }
            next.dismiss()
        }
        // Neutral (feedback) keeps the dialog open so the QR popup stacks on top of it.
        feedback?.let { next.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { _ -> it.second() } }
        renderActions()
        next.getButton(AlertDialog.BUTTON_POSITIVE).post { if (dialog === next) next.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus() }
    }
    private fun <T> work(action: () -> T, done: (T) -> Unit) {
        busy = true; renderActions(); val ticket = ++generation
        task = worker.submit {
            try {
                val result = action()
                main.post { if (active && generation == ticket) { busy = false; done(result); renderActions() } }
            } catch (e: Exception) {
                main.post { if (active && generation == ticket) {
                    busy = false; ready = false; retryAfter = System.currentTimeMillis()+30*60*1000L
                    updateStatus(if (e is IOException) e.message ?: "更新失败，请重试" else "更新校验失败，请重试"); renderActions()
                } }
            }
        }
    }
    private fun check(manual: Boolean) {
        if (busy || !active || !idle()) return
        preferences.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        updateStatus("正在检查新版…")
        work({ repository.latest() }) { update ->
            available = update.takeIf { it.newerThan(BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT) }; ready = false
            if (available == null) {
                updateStatus(if (update.minSdk > Build.VERSION.SDK_INT) "新版需要 Android API ${update.minSdk}，当前电视继续使用本版" else "已是最新版本 ${BuildConfig.VERSION_NAME}")
                worker.execute { apk.delete() }
            } else {
                updateStatus("发现 ${update.versionName} · ${"%.1f".format(update.size/1048576.0)} MB\n${update.notes}")
                if (manual || auto) download(manual)
            }
        }
    }
    private fun download(manual: Boolean) {
        val update = available ?: return
        if (busy || !active || !idle()) return
        updateStatus("正在下载 ${update.versionName}…")
        val ticket = generation + 1
        work({
            repository.download(update, apk) { percent -> main.post { if (generation == ticket && active) updateStatus("正在下载 ${update.versionName} · $percent%") } }
            validateApk(update)
        }) {
            ready = true; updateStatus("${update.versionName} 已下载并校验\n${update.notes}\n\n确认后由系统覆盖安装，保留本机记录。")
            if (idle() && dialog == null && (manual || !deferred(update))) { promptedName = update.versionName; show(activity.currentFocus) }
        }
    }
    /** The download is already guarded byte-for-byte by the SHA-256 check in
     *  UpdateFiles.receive/verify. Package identity, version and signer continuity are
     *  enforced by the platform installer itself when it replaces the app - an app-side
     *  re-check via getPackageArchiveInfo was redundant and misfired on Android 7/8
     *  (v2-only APKs read as "no signatures" there). Keep only the digest check. */
    private fun validateApk(update: AppUpdate) {
        if (!UpdateFiles.verify(apk, update)) throw IOException("安装包校验未通过，请重新下载")
    }
    private fun install() {
        val update = available ?: return
        if (busy || !active || !ready) return
        work({ validateApk(update) }) {
        try {
            // canRequestPackageInstalls() exists only from API 26; on Android 7 it throws
            // NoSuchMethodError (an Error, NOT caught by this Exception handler) and crashed
            // the app. Pre-8.0 unknown-source installs are a global setting, so just fire
            // the installer intent directly.
            if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
                    val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    if (settings.resolveActivity(activity.packageManager) != null) {
                        permissionRequested = true
                        activity.startActivity(settings)
                    } else {
                        // TV firmwares (Dangbei etc.) strip the per-app unknown-sources
                        // settings page; they gate sideloading globally instead (pre-8.0
                        // style). Fire the installer directly - on such ROMs it works
                        // without the per-app grant (seen on Dangbei Android 10).
                        fireInstaller()
                    }
                } else {
                    fireInstaller()
                }
            } catch (_: Exception) {
                permissionRequested = false
                // No installer entry point at all: export the APK to the public Downloads
                // collection so the user can install it with the TV's file manager.
                updateStatus("电视未提供安装入口，安装包已放入下载目录\n请用系统文件管理器选择 hongguotv-${update.versionName}.apk 安装")
                worker.execute { runCatching { exportToDownloads(update) } }
            }
        }
    }
    private fun fireInstaller() {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("更新安装包", uri) })
    }
    /** Last resort for ROMs without any installer activity: copy the verified APK into the
     *  public Downloads collection (MediaStore, API 29+: no permission needed) so the user
     *  can install it from the TV's own file manager. */
    private fun exportToDownloads(update: AppUpdate) {
        if (Build.VERSION.SDK_INT < 29) return  // legacy public dir needs WRITE storage permission
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "hongguotv-${update.versionName}.apk")
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
        }
        val resolver = activity.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { out -> apk.inputStream().use { it.copyTo(out) } }
    }
}
