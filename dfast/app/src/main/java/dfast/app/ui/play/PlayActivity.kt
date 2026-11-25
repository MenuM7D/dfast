package dfast.app.ui.play

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import dfast.app.R
import dfast.app.database.DBManager
import dfast.app.database.enums.DownloadType
import dfast.app.util.MediaRef
import dfast.app.util.OrbitColors
import dfast.app.util.OrbitTabs
import dfast.app.util.ThemeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Play tab: everything downloaded, as Videos and Songs. Tap one to open the player screen. */
class PlayActivity : AppCompatActivity() {
    private var songs = false
    private var all: List<MediaRef> = emptyList()
    private lateinit var pills: LinearLayout
    private lateinit var body: LinearLayout
    private fun dp(n: Int) = PlayUi.dp(this, n)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.updateTheme(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_orbit) }
        root.addView(TextView(this).apply {
            text = "Play"; textSize = 24f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(OrbitColors.ink(this@PlayActivity))
            setPadding(dp(18), dp(14), dp(18), dp(4))
        })
        root.addView(OrbitTabs.build(this, 1))
        pills = LinearLayout(this).apply { setPadding(dp(14), dp(2), dp(14), dp(6)) }
        root.addView(pills)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); insets
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            all = withContext(Dispatchers.IO) {
                DBManager.getInstance(this@PlayActivity).historyDao.getAllHistoryList()
                    .filter { h -> h.downloadPath.any { p -> p.isNotBlank() && (p.startsWith("content://") || File(p).exists()) } }
                    .sortedByDescending { it.time }
                    .map { MediaRef(it.url, it.title, it.author, it.thumb, it.duration, it.type == DownloadType.audio) }
            }
            render()
        }
    }

    private fun render() {
        pills.removeAllViews(); body.removeAllViews()
        pills.addView(PlayUi.pill(this, "Videos", !songs) { songs = false; render() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        pills.addView(PlayUi.pill(this, "Songs", songs) { songs = true; render() })
        val list = all.filter { it.audio == songs }
        if (list.isEmpty()) { body.addView(PlayUi.empty(this, "Nothing here yet. Download something and it shows up here.")); return }
        val play = LinearLayout(this).apply { setPadding(dp(14), dp(2), dp(14), dp(6)) }
        play.addView(PlayUi.pill(this, "Play all", true) { startActivity(PlayerActivity.intent(this, list.first(), list)) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        play.addView(PlayUi.pill(this, "Shuffle", false) { val s = list.shuffled(); startActivity(PlayerActivity.intent(this, s.first(), s)) }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(play)
        list.forEach { r -> body.addView(PlayUi.row(this, r) { startActivity(PlayerActivity.intent(this, r, list)) }) }
    }
}
