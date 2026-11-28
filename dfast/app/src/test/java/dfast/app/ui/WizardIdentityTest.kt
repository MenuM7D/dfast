package dfast.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural guards for the guided new-source flow and the DFast identity foundation. */
class WizardIdentityTest {
    private val res = File("src/main/res").let { if (it.exists()) it else File("app/src/main/res") }
    private val java = File(res, "../java")
    private fun r(p: String) = File(res, p).readText()

    @Test fun wizardKeepsEveryIdTheSheetCodeUses() {
        val wizard = r("layout/observe_source_wizard.xml")
        val code = File(java, "dfast/app/ui/downloadcard/ObserveSourcesBottomSheetDialog.kt").readText()
        val ids = Regex("R\\.id\\.(\\w+)").findAll(code).map { it.groupValues[1] }.toSet()
        val missing = ids.filter { it != "snackbar_text" && !wizard.contains("@+id/$it") }
        assertTrue("wizard lacks ids used by code: $missing", missing.isEmpty())
    }

    @Test fun wizardWrapsTextAndUsesStartEnd() {
        val w = r("layout/observe_source_wizard.xml")
        assertFalse(w.contains("singleLine=\"true\""))
        assertFalse(Regex("maxLines=\"1\"").containsMatchIn(w))
        assertFalse(Regex("layout_margin(Left|Right)|paddingLeft|paddingRight|gravity=\"(left|right)\"").containsMatchIn(w))
        assertTrue(w.contains("@drawable/ic_dfast_wordmark"))
        assertFalse(w.any { it.code in 0x2600..0x27BF || it.isSurrogate() })
    }

    @Test fun classicSheetIsGoneAndWizardIsTheOnlyLayout() {
        val k = File(java, "dfast/app/ui/downloadcard/ObserveSourcesBottomSheetDialog.kt").readText()
        assertTrue(k.contains("R.layout.observe_source_wizard"))
        assertFalse(k.contains("classic_source_sheet"))
        assertFalse(File(res, "layout/observe_sources_bottom_sheet.xml").exists())
        assertFalse(r("xml/processing_preferences.xml").contains("classic_source_sheet"))
    }

    @Test fun noTealOrStockAccentsRemainInTheIdentity() {
        val colors = r("values/colors.xml") + r("values-night/colors.xml")
        for (c in listOf("#00796F", "#9FFCF0", "#12958B", "#C7F5EC", "#00C2B8", "#12B5A6")) assertFalse("teal $c", colors.contains(c, ignoreCase = true))
        val arrays = r("values/arrays.xml")
        val acc = arrays.substring(arrays.indexOf("<array name=\"accents_values\">"), arrays.indexOf("</array>", arrays.indexOf("<array name=\"accents_values\">")))
        for (k in listOf("blue", "red", "green", "purple", "yellow", "orange", "monochrome")) assertFalse("accent $k still listed", acc.contains(">$k<"))
        val theme = File(java, "dfast/app/util/ThemeUtil.kt").readText()
        assertTrue("retired accents must fall back to the DFast theme", theme.contains("else -> activity.setTheme(R.style.Theme_DFast)"))
    }

    @Test fun textFieldsUseTheDFastStyle() {
        val bad = ArrayList<String>()
        File(res, ".").listFiles { f -> f.isDirectory && f.name.startsWith("layout") }!!.forEach { d ->
            d.listFiles()!!.forEach { f -> if (f.readText().contains("TextInputLayout.FilledBox")) bad += f.name }
        }
        assertTrue("stock filled boxes left in $bad", bad.isEmpty())
        assertTrue(r("values/dfast_styles.xml").contains("Widget.DFast.TextInputLayout"))
    }

    @Test fun noTextViewEllipsizesOrCapsLinesInLayoutsOrCode() {
        val bad = ArrayList<String>()
        val tagRe = Regex("<(TextView|com\\.google\\.android\\.material\\.textview\\.MaterialTextView|Button|com\\.google\\.android\\.material\\.button\\.MaterialButton)\\s[^<>]*>", RegexOption.DOT_MATCHES_ALL)
        File(res, ".").listFiles { f -> f.isDirectory && f.name.startsWith("layout") }!!.forEach { d ->
            d.listFiles()!!.forEach { f ->
                tagRe.findAll(f.readText()).forEach { m ->
                    val t = m.value
                    if (!t.contains("autoSizeTextType") && Regex("android:(ellipsize|singleLine|lines)=|android:maxLines=\"\\d\"").containsMatchIn(t)) bad += f.name
                }
            }
        }
        java.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, l ->
                if (l.contains("TruncateAt") && !l.contains("snack")) bad += "${f.name}:${i + 1}"
            }
        }
        assertTrue("clipped or ellipsized text left in $bad", bad.isEmpty())
    }

    @Test fun autoplaySwitchAppliesAtOnce() {
        val p = File(java, "dfast/app/ui/play/PlayerActivity.kt").readText()
        val fn = p.substring(p.indexOf("private fun onAutoplayToggled"), p.indexOf("private fun onEnded"))
        assertTrue("saved synchronously", fn.contains(".commit()"))
        assertTrue("hold follows the switch at once", fn.contains("applyHold()"))
        assertTrue("an ended video reacts at once", fn.contains("onEnded()"))
        assertTrue("switch is re-synced when the screen returns", p.contains("autoSwitch?.setOn(autoplayOn())"))
    }

    @Test fun cardFollowsRelatedChain() {
        val svc = File(java, "dfast/app/services/PlaybackService.kt").readText()
        val hook = File(java, "dfast/app/util/PlayNavHook.kt").readText()
        val act = File(java, "dfast/app/ui/play/PlayerActivity.kt").readText()
        assertTrue(svc.contains("PlayNavHook.next(player)") && svc.contains("PlayNavHook.prev(player)"))
        assertTrue("a real background queue keeps the old behaviour", hook.contains("mediaItemCount <= 1"))
        assertTrue(act.contains("PlayNavHook.register(this") && act.contains("PlayNavHook.unregister(this)"))
        assertTrue("session uses the guarded wrapper", svc.contains("MediaSession.Builder(this, chainPlayer(p))") && svc.contains("super.seekToNext()") && svc.contains("super.seekToPreviousMediaItem()"))
        assertTrue(File(java, "dfast/app/util/MiniPlayer.kt").readText().contains("PlayNavHook.next("))
    }

    @Test fun chainSurvivesScreenClose() {
        val chain = File(java, "dfast/app/util/PlayChain.kt").readText()
        val hook = File(java, "dfast/app/util/PlayNavHook.kt").readText()
        val act = File(java, "dfast/app/ui/play/PlayerActivity.kt").readText()
        assertTrue("only the song the screen published, single song", chain.contains("item.mediaId == mediaId") && chain.contains("mediaItemCount <= 1"))
        assertTrue("runs outside the screen", chain.contains("CoroutineScope(SupervisorJob()") && !chain.contains("lifecycleScope"))
        assertTrue(hook.contains("PlayChain.next(p)") && hook.contains("PlayChain.prev(p)") && hook.contains("PlayChain.owns(p)"))
        assertTrue(act.contains("publishChain()") && act.contains("PlayChain.publish("))
        assertTrue(File(java, "dfast/app/services/PlaybackService.kt").readText().contains("PlayChain.clear()"))
    }

    @Test fun autoplayContinuesWithScreenClosed() {
        val chain = File(java, "dfast/app/util/PlayChain.kt").readText()
        val svc = File(java, "dfast/app/services/PlaybackService.kt").readText()
        val act = File(java, "dfast/app/ui/play/PlayerActivity.kt").readText()
        assertTrue(svc.contains("STATE_ENDED") && svc.contains("PlayChain.onSongEnded(p)"))
        assertTrue("never doubles with the screen", chain.contains("PlayNavHook.screenFront"))
        assertTrue("same switch, repeat and idle rules", chain.contains("autoplay_next") && chain.contains("REPEAT_MODE_OFF") && chain.contains("AutoplayPick.allowed("))
        assertTrue(act.contains("PlayNavHook.screenFront = true") && act.contains("PlayNavHook.screenFront = false"))
    }

    @Test fun backgroundLimitOptionIsLocalized() {
        val chain = File(java, "dfast/app/util/PlayChain.kt").readText()
        assertTrue(chain.contains("bg_autoplay_limit") && chain.contains("limitMin <= 0L"))
        assertTrue(r("xml/general_preferences.xml").contains("bg_autoplay_limit"))
        assertTrue(File(java, "dfast/app/ui/more/settings/SettingsPages.kt").readText().contains("\"bg_autoplay_limit\""))
        val dirs = File(res, ".").listFiles { f -> f.isDirectory && f.name.startsWith("values") && File(f, "strings.xml").let { x -> x.exists() && x.readText().contains("name=\"o_ring_intensity\"") } }!!
        assertTrue(dirs.size >= 28)
        dirs.forEach { assertTrue("${it.name} lacks the limit strings", File(it, "strings.xml").readText().let { t -> t.contains("name=\"o_bg_limit\"") && t.contains("name=\"o_bg_limit_8h\"") }) }
    }
}
