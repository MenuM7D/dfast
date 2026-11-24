package dfast.app.ui.more.settings.updating.app

import android.os.Bundle
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import dfast.app.R
import dfast.app.ui.more.settings.BaseSettingsFragment
import dfast.app.ui.more.settings.SettingsRegistry
import dfast.app.util.UiUtil


class AppUpdateSettingsFragment : BaseSettingsFragment() {
    override val title: Int = R.string.app

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val preferenceXMLRes = R.xml.app_update_preferences
        setPreferencesFromResource(preferenceXMLRes, rootKey)
        SettingsRegistry.bindFragment(this, preferenceXMLRes)

        val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        findPreference<Preference>("reset_preferences")?.setOnPreferenceClickListener {
            UiUtil.showGenericConfirmDialog(requireContext(), getString(R.string.reset), getString(R.string.reset_preferences_in_screen)) {
                resetPreferences(preferences.edit(), preferenceXMLRes)
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
