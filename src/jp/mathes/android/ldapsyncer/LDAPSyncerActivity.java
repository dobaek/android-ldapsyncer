/*
 * (c) Copyright Bastian Mathes 2009,2010
 *  Released under GPL v2.
 */
package jp.mathes.android.ldapsyncer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import jp.mathes.android.ldapsyncer.LDAPSyncer.LDAPSyncerBinder;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.DialogInterface.OnClickListener;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

public class LDAPSyncerActivity extends Activity {

  private LDAPSyncerBinder syncerBinder;
  private ServiceConnection syncerConnection = new ServiceConnection() {
    @Override
    public void onServiceDisconnected(ComponentName name) {
      syncerBinder = null;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
      syncerBinder = (LDAPSyncerBinder) service;
      syncerBinder.getService().setActivity(LDAPSyncerActivity.this);
    }
  };

  public class InitListener implements OnClickListener {
    private String dataDirectory;

    public InitListener(String dataDirectory) {
      this.dataDirectory = dataDirectory;
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
      syncerBinder.getService().initDataDirectory(dataDirectory);
    }
  }

  public class CleanListener implements OnClickListener {
    private String dataDirectory;

    public CleanListener(String dataDirectory) {
      this.dataDirectory = dataDirectory;
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
      syncerBinder.getService().cleanCecksumDB(dataDirectory);
    }
  }

  public class StartSyncListener implements OnClickListener {
    private String dataDirectory;

    public StartSyncListener(String dataDirectory) {
      this.dataDirectory = dataDirectory;
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
      new Thread() {
        @Override
        public void run() {
          LDAPSyncerActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
              ((Button) findViewById(R.id.button_startSync)).setVisibility(View.GONE);
              ((Button) findViewById(R.id.button_stopSync)).setVisibility(View.VISIBLE);
            }
          });
          syncerBinder.getService().sync(dataDirectory);
          LDAPSyncerActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
              ((Button) findViewById(R.id.button_startSync)).setVisibility(View.VISIBLE);
              ((Button) findViewById(R.id.button_stopSync)).setVisibility(View.GONE);
            }
          });
        }
      }.start();
    }
  }

  public class StopSyncListener implements OnClickListener {

    public StopSyncListener() {
      super();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
      syncerBinder.getService().stop();
    }
  }

  @Override
  public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_BACK) {
      return moveTaskToBack(true);
    } else {
      return super.onKeyDown(keyCode, event);
    }
  };

  @Override
  protected void onDestroy() {
    super.onDestroy();
  };

  @Override
  public void onCreate(Bundle savedInstanceState) {
    bindService(new Intent(this, LDAPSyncer.class), this.syncerConnection, Context.BIND_AUTO_CREATE);
    setContentView(R.layout.main);
    ((Button) this.findViewById(R.id.button_startSync)).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        String dataDirectory = PreferenceManager.getDefaultSharedPreferences(LDAPSyncerActivity.this).getString("data_directory", null);
        AlertDialog.Builder builder = new AlertDialog.Builder(LDAPSyncerActivity.this);
        if (dataDirectory != null && dataDirectory.length() > 0) {
          builder.setTitle("Confirm");
          builder.setMessage(String.format("Start sync with data directory '%s' ?", dataDirectory));
          builder.setPositiveButton("Ok", new StartSyncListener(dataDirectory));
          builder.setNegativeButton("Cancel", null);
          AlertDialog ad = builder.create();
          ad.show();
        } else {
          builder.setTitle("Error");
          builder.setMessage("Data directory not set, go to the preferences");
          builder.setNeutralButton("Ok", null);
          AlertDialog ad = builder.create();
          ad.show();
        }
      }
    });
    ((Button) this.findViewById(R.id.button_stopSync)).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        AlertDialog.Builder builder = new AlertDialog.Builder(LDAPSyncerActivity.this);
        builder.setTitle("Confirm");
        builder.setMessage(String.format("Stop current sync ?"));
        builder.setPositiveButton("Ok", new StopSyncListener());
        builder.setNegativeButton("Cancel", null);
        AlertDialog ad = builder.create();
        ad.show();
      }
    });
    super.onCreate(savedInstanceState);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    MenuInflater inflater = getMenuInflater();
    inflater.inflate(R.menu.menu, menu);
    return super.onCreateOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == R.id.menu_preferences) {
      Intent preferencesActivity = new Intent(getBaseContext(), LDAPSyncerPreferenceActivity.class);
      startActivity(preferencesActivity);
    } else if (item.getItemId() == R.id.menu_quit) {
      this.finish();
    } else if (item.getItemId() == R.id.menu_help) {
      AlertDialog.Builder builder = new AlertDialog.Builder(LDAPSyncerActivity.this);
      builder.setTitle("Help");
      builder.setMessage("Please set a data dictionary in the preferences. Afterwards initialize the data directory (menu). Then you have to adapt the mapping and settings in the file configuration.xml in the data dictionary.\nThen you can start a sync.\nYou can configure in configure.xml if records are delete or created on either side and if conflicts are always resolved in favour of one side.");
      builder.setNeutralButton("Ok", null);
      AlertDialog ad = builder.create();
      ad.show();
    } else if (item.getItemId() == R.id.menu_initDir) {
      String dataDirectory = PreferenceManager.getDefaultSharedPreferences(LDAPSyncerActivity.this).getString("data_directory", null);
      AlertDialog.Builder builder = new AlertDialog.Builder(LDAPSyncerActivity.this);
      if (dataDirectory != null && dataDirectory.length() > 0) {
        builder.setTitle("Confirm");
        builder.setMessage(String.format("Really init '%s' (potentially creating the directory or deleting its content) ? YOU HAVE TO ADAPT THE CONFIGURATION", dataDirectory));
        builder.setPositiveButton("Ok", new InitListener(dataDirectory));
        builder.setNegativeButton("Cancel", null);
        AlertDialog ad = builder.create();
        ad.show();
      } else {
        builder.setTitle("Error");
        builder.setMessage("Data directory not set, go to the preferences");
        builder.setNeutralButton("Ok", null);
        AlertDialog ad = builder.create();
        ad.show();
      }
    } else if (item.getItemId() == R.id.menu_cleanChecksum) {
      String dataDirectory = PreferenceManager.getDefaultSharedPreferences(LDAPSyncerActivity.this).getString("data_directory", null);
      AlertDialog.Builder builder = new AlertDialog.Builder(LDAPSyncerActivity.this);
      if (dataDirectory != null && dataDirectory.length() > 0 && new File(dataDirectory).exists() && new File(dataDirectory).isDirectory()) {
        builder.setTitle("Confirm");
        builder.setMessage(String.format("Really clean checksum DB in '%s' ?", dataDirectory));
        builder.setPositiveButton("Ok", new CleanListener(dataDirectory));
        builder.setNegativeButton("Cancel", null);
        AlertDialog ad = builder.create();
        ad.show();
      } else {
        builder.setTitle("Error");
        builder.setMessage("Data directory not set or not a directory, go to the preferences");
        builder.setNeutralButton("Ok", null);
        AlertDialog ad = builder.create();
        ad.show();
      }
    }
    return true;
  }

  public void logString(final String entry) {
    this.runOnUiThread(new Runnable() {
      @Override
      public void run() {
        TextView log = (TextView) LDAPSyncerActivity.this.findViewById(R.id.textView_Log);
        log.append("* ");
        log.append(entry);
        log.append("\n");
        ScrollView sv = (ScrollView) LDAPSyncerActivity.this.findViewById(R.id.ScrollView_Log);
        sv.fullScroll(ScrollView.FOCUS_DOWN);
      }
    });
  }

  public InputStream getInputStreamFromAssets(String filename) throws IOException {
    return getAssets().open(filename);
  }
}