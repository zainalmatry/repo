package org.smssecure.smssecure.preferences;

import android.content.Context;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.support.v4.preference.PreferenceFragment;
import android.util.Log;

import org.smssecure.smssecure.ApplicationPreferencesActivity;
import org.smssecure.smssecure.R;
import org.smssecure.smssecure.util.Base64;
import org.smssecure.smssecure.util.SMSSecurePreferences;

import java.security.SecureRandom;

public class WebPreferenceFragment extends PreferenceFragment {
  private static final String TAG = WebPreferenceFragment.class.getSimpleName();

  @Override
  public void onCreate(Bundle paramBundle) {
    super.onCreate(paramBundle);
    addPreferencesFromResource(R.xml.preferences_web);

    findPreference(SMSSecurePreferences.WEB_INTERFACE_PASSPHRASE)
        .setSummary(SMSSecurePreferences.getWebInterfacePassphrase(getActivity()));

    findPreference(SMSSecurePreferences.WEB_INTERFACE_ENABLED)
        .setOnPreferenceClickListener(new InitializePassphrase());
    findPreference(SMSSecurePreferences.WEB_INTERFACE_PASSPHRASE)
        .setOnPreferenceChangeListener(new SetWebInterfacePassphrase());
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

  private class InitializePassphrase implements Preference.OnPreferenceClickListener {
    @Override
    public boolean onPreferenceClick(Preference preference) {
      Context context = getActivity();
      if (SMSSecurePreferences.isWebInterfacePassphraseEnabled(context)) {
        return true;
      }

      try {
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        byte[] salt         = new byte[16];
        random.nextBytes(salt);
        String passphrase = Base64.encodeBytes(salt);

        SMSSecurePreferences.setWebInterfacePassphrase(context, passphrase);
        findPreference(SMSSecurePreferences.WEB_INTERFACE_PASSPHRASE).setSummary(passphrase);
      } catch (Exception e) {
        Log.w(TAG, e);
        throw new AssertionError(e);
      }

      return true;
    }
  }

  private class SetWebInterfacePassphrase implements Preference.OnPreferenceChangeListener {

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
      if (newValue.toString().equals("")) {
        return false;
      } else {
        preference.setSummary(newValue.toString());
        return true;
      }
    }
  }

}
