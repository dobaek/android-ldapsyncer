/*
 * (c) Copyright Bastian Mathes 2009,2010
 *  Released under GPL v2.
 */
package jp.mathes.android.ldapsyncer;

import jp.mathes.android.ldapsyncer.R;
import android.os.Bundle;
import android.preference.PreferenceActivity;

public class LDAPSyncerPreferenceActivity extends PreferenceActivity {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    addPreferencesFromResource(R.xml.preferences);
  }
}
