package dfast.app.ui.more.settings.downloading

import android.os.Bundle
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import dfast.app.R
import dfast.app.ui.more.settings.BaseSettingsFragment
import dfast.app.ui.more.settings.SettingsRegistry
import dfast.app.util.UiUtil

class DownloadSettingsFragment : BaseSettingsFragment() {
    override val title: Int = R.string.downloads

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val preferenceXMLRes = R.xml.downloading_preferences
        setPreferencesFromResource(preferenceXMLRes, rootKey)
        SettingsRegistry.bindFragment(this, preferenceXMLRes)

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireActivity())
        val editor = prefs.edit()

        findPreference<Preference>("reset_preferences")?.setOnPreferenceClickListener {
            UiUtil.showGenericConfirmDialog(requireContext(), getString(R.string.reset), getString(R.string.reset_preferences_in_screen)) {
                resetPreferences(editor, preferenceXMLRes)
                requireActivity().recreate()
                findNavController().currentDestination?.id?.apply {
                    findNavController().popBackStack(this,true)
                    findNavController().navigate(this)
                }
            }
            true
        }
    }

}