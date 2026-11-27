package dfast.app.ui.more.settings

import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import dfast.app.R
import dfast.app.ui.more.settings.folder.FolderSettingsModule
import dfast.app.ui.more.settings.processing.ProcessingSettingsModule
import dfast.app.util.FileUtil
import dfast.app.util.ThemeUtil
import dfast.app.util.UiUtil

/**
 * One settings page of the redesign. The rows are the same Preference objects the old screens
 * used: they are inflated from the original XML files, pulled out by key and put into question
 * groups. Each row is bound by the module of the XML it came from, so its behaviour is unchanged.
 */
class RegroupedSettingsFragment : BaseSettingsFragment() {
    private var page: SettingsPages.Page = SettingsPages.pages.first()
    override val title: Int get() = page.titleRes

    private val usedXmls = linkedSetOf<Int>()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        page = SettingsPages.byId(arguments?.getString("page") ?: "downloading") ?: SettingsPages.pages.first()
        val ctx = requireContext()
        val screen = preferenceManager.createPreferenceScreen(ctx)
        preferenceScreen = screen

        val pool = HashMap<String, Pair<Preference, Int>>()
        var resetRow: Preference? = null
        SettingsPages.sourceXmls.forEach { xml ->
            val inflated = preferenceManager.inflateFromResource(ctx, xml, null)
            getPreferences(inflated, mutableListOf()).forEach { p ->
                val key = p.key ?: return@forEach
                if (key == "reset_preferences") {
                    if (resetRow == null) resetRow = p
                } else if (key !in pool) {
                    pool[key] = p to xml
                }
            }
        }

        val bound = mutableListOf<Pair<Preference, Int>>()
        page.groups.forEach { g ->
            val cat = PreferenceCategory(ctx)
            cat.title = ctx.getString(g.titleRes)
            screen.addPreference(cat)
            g.keys.forEach { key ->
                val entry = pool[key] ?: return@forEach
                val p = entry.first
                p.parent?.removePreference(p)
                cat.addPreference(p)
                usedXmls.add(entry.second)
                bound.add(entry)
            }
        }
        resetRow?.let { r ->
            r.parent?.removePreference(r)
            val last = PreferenceCategory(ctx)
            screen.addPreference(last)
            last.addPreference(r)
        }

        bound.forEach { (p, xml) -> SettingsRegistry.getModuleForXml(xml)?.bindLogic(p, this) }

        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        // the compatible-video switch locks the codec rows; they can now be on a different page
        if (page.id == "downloading" || page.id == "formats") {
            ProcessingSettingsModule.updateCompatibleVideoConfig(this, prefs)
        }

        findPreference<Preference>("reset_preferences")?.setOnPreferenceClickListener {
            UiUtil.showGenericConfirmDialog(ctx, getString(R.string.reset), getString(R.string.reset_preferences_in_screen)) {
                val editor = prefs.edit()
                page.keys.forEach { editor.remove(it) }
                editor.apply()
                usedXmls.forEach { PreferenceManager.setDefaultValues(requireActivity().applicationContext, it, true) }
                if (page.id == "appearance") ThemeUtil.updateThemes()
                requireActivity().recreate()
                findNavController().currentDestination?.id?.apply {
                    findNavController().popBackStack(this, true)
                    findNavController().navigate(this)
                }
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        val ctx = requireContext()
        if (page.id == "downloading") {
            val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                findPreference<Preference>("ignore_battery")?.isVisible = false
            }
        }
        if (page.id == "files") {
            val editor = PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            val allFilesPref = findPreference<Preference>("access_all_files")
            val cachePref = findPreference<Preference>("cache_downloads")
            val cachePath = findPreference<Preference>("cache_path")
            if (FileUtil.hasAllFilesAccess()) {
                allFilesPref?.isVisible = false
                cachePref?.isEnabled = true
                cachePath?.isEnabled = true
            } else {
                editor.putBoolean("cache_downloads", true).apply()
                allFilesPref?.isVisible = true
                cachePref?.isEnabled = false
                cachePath?.isEnabled = false
            }
            findPreference<Preference>("temporary_files")?.let { FolderSettingsModule.bindLogic(it, this) }
            refreshUI()
        }
    }
}
