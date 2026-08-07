package com.gifboard

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat

class AdvancedSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.advanced_preferences, rootKey)
    }

    override fun onResume() {
        super.onResume()
        // Set activity title when this fragment is shown
        requireActivity().title = "Advanced Settings"
    }
}
