package org.smssecure.smssecure.preferences;

import android.content.Context;
import android.os.Bundle;
import android.support.v4.preference.PreferenceFragment;

import org.smssecure.smssecure.ApplicationPreferencesActivity;
import org.smssecure.smssecure.R;
import org.smssecure.smssecure.util.SMSSecurePreferences;

public class WebPreferenceFragment extends PreferenceFragment {

  @Override
  public void onCreate(Bundle paramBundle) {
    super.onCreate(paramBundle);
    addPreferencesFromResource(R.xml.preferences_web);
  }

  @Override
  public void onResume() {
    super.onResume();
    ((ApplicationPreferencesActivity)getActivity()).getSupportActionBar().setTitle(R.string.preferences__web);
  }

  public static CharSequence getSummary(Context context) {
    final int on   = R.string.ApplicationPreferencesActivity_On;
    final int off  = R.string.ApplicationPreferencesActivity_Off;

    return context.getString(SMSSecurePreferences.isWebInterfaceEnabled(context) ? on : off);
  }

}
