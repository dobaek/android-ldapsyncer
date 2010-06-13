/*
 * (c) Copyright Bastian Mathes 2009,2010
 *  Released under GPL v2.
 */
package jp.mathes.android.ldapsyncer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedList;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;

import jp.mathes.android.ldapsyncer.Configuration.AndroidField;
import jp.mathes.android.ldapsyncer.exceptions.ConfigurationParsingException;
import jp.mathes.android.ldapsyncer.exceptions.StopException;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.lang.StringUtils;
import org.xml.sax.SAXException;

import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.provider.Contacts.People;

import com.unboundid.ldap.sdk.AddRequest;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.DeleteRequest;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.ModifyRequest;
import com.unboundid.ldap.sdk.RDN;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;

public class LDAPSyncer extends Service {
  static public final boolean DEBUG = false;

  static public class SubDirEntry {
    AndroidField androidField;
    Uri uri;
    String value;

    public SubDirEntry(AndroidField androidField, Uri uri, String value) {
      this.androidField = androidField;
      this.uri = uri;
      this.value = value;
    }
  }

  public class LDAPSyncerBinder extends Binder {
    LDAPSyncer getService() {
      return LDAPSyncer.this;
    }
  }
  private LDAPSyncerBinder binder = new LDAPSyncerBinder();

  public static final String CONFIG_FILE = "configuration.xml";
  public static final String CHECKSUM_DB = "checksum.db";
  public static final String TYPE = "type";
  public static final String KIND = "kind";
  public static final String LABEL = "label";
  public static final String ID = "_id";

  private LDAPSyncerActivity activity;
  private boolean interrupted = false;

  public LDAPSyncer() {
    super();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  };

  public void setActivity(LDAPSyncerActivity activity) {
    this.activity = activity;
  }

  public void stop() {
    this.interrupted = true;
  }

  synchronized public void initDataDirectory(String dataDirectory) {
    File dataDirectoryFile = new File(dataDirectory);
    if (dataDirectoryFile.exists() && !dataDirectoryFile.isDirectory()) {
      activity.logString(String.format("Deleting '%s' as it is not a directory", dataDirectory));
      dataDirectoryFile.delete();
    }
    if (!dataDirectoryFile.exists()) {
      activity.logString(String.format("Creating '%s' as it does not exist", dataDirectory));
      dataDirectoryFile.mkdirs();
    }
    activity.logString(String.format("Copying '%s' from assets to '%s'", CONFIG_FILE, dataDirectory));
    try {
      InputStream in = activity.getInputStreamFromAssets(CONFIG_FILE);
      OutputStream out = new FileOutputStream(dataDirectory + File.separator + CONFIG_FILE);
      byte[] buf = new byte[1024];
      int len;
      while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
      out.close();
      in.close();
    } catch (IOException e) {
      activity.logString(String.format("Error: IOException while copying %s", CONFIG_FILE));
      return;
    }
    cleanCecksumDB(dataDirectory);
  }

  synchronized public void cleanCecksumDB(String dataDirectory) {
    activity.logString(String.format("Creating checksum database in '%s'", dataDirectory));
    File checksumDbFile = new File(dataDirectory + File.separator + CHECKSUM_DB);
    if (checksumDbFile.exists()) checksumDbFile.delete();
    SQLiteDatabase checksumDb = SQLiteDatabase.openOrCreateDatabase(checksumDbFile, null);
    checksumDb.execSQL("create table checksum (name VARCHAR, field VARCHAR, checksum VARCHAR, CONSTRAINT checksum_pk PRIMARY KEY (name,field))");
    checksumDb.close();
  }

  synchronized public void sync(String dataDirectory) {
    this.interrupted = false;
    activity.logString("Starting sync...");
    SQLiteDatabase checksumDb = null;
    LDAPConnection conn = null;
    try {
      Cursor c;
      Configuration configuration = Configuration.readConfiguration(dataDirectory);
      if (DEBUG) System.out.println(String.format("Read configuration with '%d' mappings", configuration.mapping.keySet().size()));
      if (!configuration.validate()) {
        activity.logString("Error: Configuration invalid, check connection and id settings and/or make sure alwaysWins and allChangesFrom are one side only.");
        return;
      }
      checksumDb = SQLiteDatabase.openDatabase(dataDirectory + File.separator + CHECKSUM_DB, null, SQLiteDatabase.OPEN_READWRITE);
      conn = new LDAPConnection();
      conn.connect(configuration.server, configuration.port);
      conn.bind(configuration.binddn, configuration.password);
      SearchResult result = conn.search(configuration.basedn, SearchScope.SUB, "(objectclass=person)");
      List<String> seen = new LinkedList<String>();
      for (SearchResultEntry sre : result.getSearchEntries()) {
        if (this.interrupted) throw new StopException();
        String id = sre.getAttributeValue(configuration.IdOnLDAP);
        if (DEBUG) System.out.println(String.format("Looking at '%s' in LDAP", id));
        seen.add(id);
        c = activity.getContentResolver().query(People.CONTENT_URI, null, configuration.IdOnAndroid + "='" + id + "'", null, null);
        c.moveToFirst();
        if (c.getCount() > 0) {
          if (c.getCount() > 1) activity.logString(String.format("Warning: More than one records for id '%s' in Android, using first one, please clean up.", id));
          if (!mergeEntries(sre, c, checksumDb, configuration, conn)) activity.logString(String.format("There is a conflict for id '%s', please resolve manually", id));
        } else {
          if (configuration.deleteOnLDAP && ((existsInChecksumDb(checksumDb, id) && equalsChecksumDb(sre, checksumDb, configuration) && !configuration.allChangesFromLDAP) ||
              (configuration.allChangesFromAndroid))) {
            activity.logString(String.format("Deleting '%s' in LDAP", id));
            deleteEntryInLDAP(sre, configuration, conn);
            cleanFromChecksumDB(sre.getAttributeValue(configuration.IdOnLDAP), checksumDb);
          } else if (configuration.createOnAndroid && ((!existsInChecksumDb(checksumDb, id) && !configuration.allChangesFromAndroid) || configuration.allChangesFromLDAP)) {
            activity.logString(String.format("Adding '%s' to Android", id));
            addEntryInAndroid(sre, checksumDb, configuration);
          } else {
            activity.logString(String.format("There is a (delete/change) conflict for name '%s', please resolve manually", id));
          }
        }
      }
      c = activity.getContentResolver().query(People.CONTENT_URI, null, null, null, null);
      c.moveToFirst();
      while (!c.isAfterLast()) {
        if (this.interrupted) throw new StopException();
        if (DEBUG) System.out.println(String.format("Looking at '%s' in Android", c.getString(c.getColumnIndex(configuration.IdOnAndroid))));
        String id = c.getString(c.getColumnIndex(configuration.IdOnAndroid));
        if (!seen.contains(id)) {
          if (DEBUG) System.out.println(String.format("'%s' in Android is not seen", id));
          if (configuration.deleteOnAndroid && ((existsInChecksumDb(checksumDb, id) && equalsChecksumDb(c, checksumDb, configuration) && !configuration.allChangesFromAndroid) ||
              (configuration.allChangesFromLDAP))) {
            activity.logString(String.format("Deleting '%s' in Android", id));
            deleteEntryInAndroid(c, configuration);
            cleanFromChecksumDB(c.getString(c.getColumnIndex(configuration.IdOnAndroid)), checksumDb);
          } else if (configuration.createOnLDAP && ((!existsInChecksumDb(checksumDb, id) && !configuration.allChangesFromLDAP) || configuration.allChangesFromAndroid)) {
            activity.logString(String.format("Adding '%s' to LDAP", id));
            addEntryInLDAP(c, checksumDb, configuration, conn);
          } else {
            activity.logString(String.format("There is a (delete/change) conflict for name '%s', please resolve manually", id));
          }
        }
        c.moveToNext();
      }
    } catch (ConfigurationException e) {
      activity.logString("Error: Unexpected ConfigurationException");
      e.printStackTrace();
    } catch (FileNotFoundException e) {
      activity.logString("Error: Configuration file not found, is data directory initialized ?");
      e.printStackTrace();
    } catch (ParserConfigurationException e) {
      activity.logString("Error: Unexpected ParserConfigurationException");
      e.printStackTrace();
    } catch (SAXException e) {
      activity.logString("Error: Unexpected SAXException");
      e.printStackTrace();
    } catch (IOException e) {
      activity.logString("Error: Unexpected IOException");
      e.printStackTrace();
    } catch (ConfigurationParsingException e) {
      activity.logString(String.format("Error: %s", e.getMessage()));
    } catch (LDAPException e) {
      activity.logString("Error: Unexpected LDAPException (e.g. connection failed)");
      e.printStackTrace();
    } catch (IllegalArgumentException e) {
      activity.logString("Error: Unexpected IllegalArgumentException");
      e.printStackTrace();
    } catch (StopException e) {
      activity.logString("Aborting sync...");
    }
    finally {
      if (checksumDb != null) {
        if (DEBUG) System.out.println("Closing checksum DB");
        checksumDb.close();
      }
      if (conn != null) {
        if (DEBUG) System.out.println("Closing LDAP connection");
        conn.close();
      }
    }
    activity.logString("...finished sync.");
  }

  /*
   * Private Methods
   */

  private String md5(String s) {
    if (s == null || s.length() == 0) return null;
    try {
      MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
      digest.update(s.getBytes());
      byte messageDigest[] = digest.digest();
      StringBuffer hexString = new StringBuffer();
      for (int i = 0; i < messageDigest.length; i++) {
        String h = Integer.toHexString(0xFF & messageDigest[i]);
        while (h.length() < 2) h = "0" + h;
        hexString.append(h);
      }
      return hexString.toString();
    } catch (NoSuchAlgorithmException e) {
      e.printStackTrace();
    }
    return null;
  }

  private String md5(String[] s) {
    return md5(StringUtils.join(s,"\t"));
  }

  private String md5(List<String> s) {
    return md5(StringUtils.join(s,"\t"));
  }

  private List<SubDirEntry> findSubdirEntries(Cursor c, AndroidField androidField) {
    List<SubDirEntry> result = new LinkedList<SubDirEntry>();
    Uri uri = Uri.withAppendedPath(Uri.withAppendedPath(People.CONTENT_URI, c.getString(c.getColumnIndex(People._ID))), androidField.directory);
    Cursor subdirCursor = this.activity.getContentResolver().query(uri, null, null, null, null);
    subdirCursor.moveToFirst();
    while (!subdirCursor.isAfterLast()) {
          if ((androidField.type != null &&
           androidField.type.length() > 0 &&
           ((!androidField.type.equals(Integer.toString(People.TYPE_CUSTOM)) && androidField.type.equals(subdirCursor.getString(subdirCursor.getColumnIndex(TYPE)))) ||
            (androidField.type.equals(Integer.toString(People.TYPE_CUSTOM)) && androidField.typeLabel.equals(subdirCursor.getString(subdirCursor.getColumnIndex(LABEL))))
           )
          ) && (androidField.kind == null || androidField.kind.length() == 0 || androidField.kind.equals(subdirCursor.getString(subdirCursor.getColumnIndex(KIND))))) {
        result.add(new SubDirEntry(
            androidField,
            Uri.withAppendedPath(uri, subdirCursor.getString(subdirCursor.getColumnIndex(ID))),
            subdirCursor.getString(subdirCursor.getColumnIndex(androidField.name))));
      }
      subdirCursor.moveToNext();
    }
    subdirCursor.close();
    return result;
  }

  private String singleStringQuery(SQLiteDatabase checksumDb, String sql, String... args) {
    String result;
    Cursor queryResult = checksumDb.rawQuery(sql, args);
    if (queryResult.getCount() == 0) {
      queryResult.close();
      return null;
    }
    queryResult.moveToFirst();
    result = queryResult.getString(0);
    queryResult.close();
    return result;
  }

  private Integer singleIntQuery(SQLiteDatabase checksumDb, String sql, String... args) {
    int result;
    Cursor queryResult = checksumDb.rawQuery(sql, args);
    if (queryResult.getCount() == 0) {
      queryResult.close();
      return null;
    }
    queryResult.moveToFirst();
    result = queryResult.getInt(0);
    queryResult.close();
    return result;
  }

  private boolean existsInChecksumDb(SQLiteDatabase checksumDb, String cn) {
    Integer result = singleIntQuery(checksumDb, "select count(*) from checksum where name=?", cn);
    return result != null && result > 0;
  }

  private void cleanFromChecksumDB(String name, SQLiteDatabase checksumDb) {
    checksumDb.execSQL(String.format("delete from checksum where name='%s'", name));
  }

  private String getFieldHash(AndroidField androidField, Cursor c) {
    if (androidField.directory == null || androidField.directory.length() == 0) {
      return md5(c.getString(c.getColumnIndex(androidField.name)));
    } else {
      List<String> values = new LinkedList<String>();
      for (SubDirEntry subdirEntry : findSubdirEntries(c, androidField)) values.add(subdirEntry.value);
      return md5(values);
    }
  }

  private String getFieldHash(String fieldName, SearchResultEntry sre) {
    return md5(sre.getAttributeValues(fieldName));
  }

  private boolean equalsChecksumDb(Cursor c, SQLiteDatabase checksumDb, Configuration configuration) {
    String id = c.getString(c.getColumnIndex(configuration.IdOnAndroid));
    for (String fieldName : configuration.mapping.keySet()) {
      String androidHash = getFieldHash(configuration.mapping.get(fieldName), c);
      String dbHash = singleStringQuery(checksumDb, "select checksum from checksum where name=? and field=?", id, fieldName);
      if ((androidHash == null && dbHash == null) || (androidHash !=null && androidHash.equals(dbHash))) continue;
      return false;
    }
    return true;
  }

  private boolean equalsChecksumDb(SearchResultEntry sre, SQLiteDatabase checksumDb, Configuration configuration) {
    String id = sre.getAttributeValue(configuration.IdOnLDAP);
    for (String fieldName : configuration.mapping.keySet()) {
      String ldapHash = getFieldHash(fieldName, sre);
      String dbHash = singleStringQuery(checksumDb, "select checksum from checksum where name=? and field=?", new String[] { id, fieldName });
      if ((ldapHash == null && dbHash == null) || (ldapHash !=null && ldapHash.equals(dbHash))) continue;
      return false;
    }
    return true;
  }

  private boolean mergeEntries(SearchResultEntry sre, Cursor c, SQLiteDatabase checksumDb, Configuration configuration, LDAPConnection conn) throws LDAPException {
    boolean success = true;
    boolean changed = false;
    String id = c.getString(c.getColumnIndex(configuration.IdOnAndroid));
    for (String fieldName : configuration.mapping.keySet()) {
      if (DEBUG) System.out.println(String.format("Starting merge for field '%s'", fieldName));
      String LdapHash = getFieldHash(fieldName, sre);
      if (DEBUG) System.out.println(String.format("Got hash values from LDAP: '%s'", LdapHash));
      String androidHash = getFieldHash(configuration.mapping.get(fieldName), c);
      if (DEBUG) System.out.println(String.format("Got hash values from Android: '%s'", androidHash));
      String hashValue = singleStringQuery(checksumDb, "select checksum from checksum where name=? and field=?", id, fieldName);
      if (hashValue == null && LdapHash == null && androidHash == null) continue;
      if (hashValue == null) {
        if (DEBUG) System.out.println(String.format("No hash value for name '%s' and field '%s'", id, fieldName));
        if (LdapHash != null && androidHash != null && androidHash.equals(LdapHash)) {
          checksumDb.execSQL(String.format("insert into checksum values ('%s','%s','%s')", id, fieldName, LdapHash));
        } else if (configuration.changeOnLDAP && ((androidHash != null && LdapHash == null && !configuration.allChangesFromLDAP) || configuration.allChangesFromAndroid)) {
          copyAndroid2LDAP(fieldName, c, sre, configuration, conn);
          changed = true;
          checksumDb.execSQL(String.format("insert into checksum values ('%s','%s','%s')", id, fieldName, androidHash));
        } else if (configuration.changeOnAndroid && ((androidHash == null && LdapHash != null && !configuration.allChangesFromAndroid) || configuration.allChangesFromLDAP)) {
          copyLDAP2Android(fieldName, sre, c, configuration);
          changed = true;
          checksumDb.execSQL(String.format("insert into checksum values ('%s','%s','%s')", id, fieldName, LdapHash));
        } else if (configuration.changeOnLDAP && configuration.AndroidAlwaysWins && !configuration.allChangesFromLDAP) {
          copyAndroid2LDAP(fieldName, c, sre, configuration, conn);
          changed = true;
          checksumDb.execSQL(String.format("insert into checksum values ('%s','%s','%s')", id, fieldName, androidHash));
        } else if (configuration.changeOnAndroid && configuration.LDAPAlwaysWins && !configuration.allChangesFromAndroid) {
          copyLDAP2Android(fieldName, sre, c, configuration);
          changed = true;
          checksumDb.execSQL(String.format("insert into checksum values ('%s','%s','%s')", id, fieldName, LdapHash));
        } else {
          if (DEBUG) System.out.println("No way found to merge, raising conflict");
          return false;
        }
      } else {
        if (DEBUG) System.out.println(String.format("Hash in checksumDB for name '%s' and field '%s' is '%s'", id, fieldName, hashValue));
        if (hashValue.equals(androidHash) && hashValue.equals(LdapHash)) continue;
        if (configuration.changeOnAndroid && ((hashValue.equals(androidHash) && !configuration.allChangesFromAndroid) || configuration.allChangesFromLDAP)) {
          if (DEBUG) System.out.println("LDAP has changes, transfering to Android");
          copyLDAP2Android(fieldName, sre, c, configuration);
          changed = true;
          checksumDb.execSQL(String.format("update checksum set checksum='%s' where name='%s' and field='%s'", LdapHash, id, fieldName));
        } else if (configuration.changeOnLDAP && ((hashValue.equals(LdapHash) && !configuration.allChangesFromLDAP) || configuration.allChangesFromAndroid)) {
          if (DEBUG) System.out.println("Android has changes, transfering to LDAP");
          copyAndroid2LDAP(fieldName, c, sre, configuration, conn);
          changed = true;
          checksumDb.execSQL(String.format("update checksum set checksum='%s' where name='%s' and field='%s'", androidHash, id, fieldName));
        } else if (configuration.changeOnAndroid && configuration.LDAPAlwaysWins && !configuration.allChangesFromAndroid) {
          copyLDAP2Android(fieldName, sre, c, configuration);
          changed = true;
          checksumDb.execSQL(String.format("update checksum set checksum='%s' where name='%s' and field='%s'", LdapHash, id, fieldName));
        } else if (configuration.changeOnLDAP && configuration.AndroidAlwaysWins && !configuration.allChangesFromLDAP) {
          copyAndroid2LDAP(fieldName, c, sre, configuration, conn);
          changed = true;
          checksumDb.execSQL(String.format("update checksum set checksum='%s' where name='%s' and field='%s'", androidHash, id, fieldName));
        } else {
          if (DEBUG) System.out.println("No way found to merge, raising conflict");
          return false;
        }
      }
    }
    if (changed) this.activity.logString(String.format("Changed '%s'", id));
    return success;
  }

  private void copyAndroid2LDAP(String fieldName, Cursor c, SearchResultEntry sre, Configuration configuration, LDAPConnection conn) throws LDAPException {
    ModifyRequest mr = new ModifyRequest(sre.getDN(), new Modification(ModificationType.DELETE, fieldName));
    if (sre.hasAttribute(fieldName)) conn.modify(mr);
    AndroidField androidField = configuration.mapping.get(fieldName);
    List<Modification> modifications = new LinkedList<Modification>();
    if (androidField.directory == null || androidField.directory.length() == 0) {
      modifications.add(new Modification(ModificationType.ADD, fieldName, c.getString(c.getColumnIndex(androidField.name))));
    } else {
      for (SubDirEntry subdirEntry : findSubdirEntries(c, androidField)) modifications.add(new Modification(ModificationType.ADD, fieldName, subdirEntry.value));
    }
    mr = new ModifyRequest(sre.getDN(), modifications);
    conn.modify(mr);
  }


  private void copyLDAP2Android(String fieldName, SearchResultEntry sre, Cursor c, Configuration configuration) {
    String[] values = sre.getAttributeValues(fieldName);
    Uri baseUri = Uri.withAppendedPath(People.CONTENT_URI, c.getString(c.getColumnIndex(People._ID)));
    AndroidField androidField = configuration.mapping.get(fieldName);
    // delete old values
    if (androidField.directory != null && androidField.directory.length() > 0) {
      for (SubDirEntry subdirEntry : findSubdirEntries(c, androidField)) activity.getContentResolver().delete(subdirEntry.uri, null, null);
    }
    // update (direct) or insert (subdir) values
    if (values != null) for (String value : values) {
      if (androidField.directory == null || androidField.directory.length() == 0) {
        ContentValues updateValues = new ContentValues();
        updateValues.put(androidField.name, value);
        activity.getContentResolver().update(baseUri, updateValues, null, null);
      } else {
        ContentValues newSubDirValues = new ContentValues();
        newSubDirValues.put(androidField.name, value);
        if (androidField.type != null && androidField.type.length() > 0) newSubDirValues.put(TYPE, androidField.type);
        if (androidField.kind != null && androidField.kind.length() > 0) newSubDirValues.put(KIND, androidField.kind);
        if (androidField.typeLabel != null && androidField.typeLabel.length() > 0) newSubDirValues.put(LABEL, androidField.typeLabel);
        Uri subdirUri = Uri.withAppendedPath(baseUri, androidField.directory);
        this.activity.getContentResolver().insert(subdirUri, newSubDirValues);
      }
    }
  }

  private void addEntryInAndroid(SearchResultEntry sre, SQLiteDatabase checksumDb, Configuration configuration) {
    ContentValues newValues = new ContentValues();
    List<ContentValues> valueList = new LinkedList<ContentValues>();
    for (String fieldName : configuration.mapping.keySet()) {
      String[] values = sre.getAttributeValues(fieldName);
      if (values != null) {
        for (String value : values) {
          if (value == null || value.length() == 0) continue;
          AndroidField androidField = configuration.mapping.get(fieldName);
          if (androidField.directory == null || androidField.directory.length() == 0) {
            newValues.put(androidField.name, value);
          } else {
            ContentValues newSubDirValues = new ContentValues();
            newSubDirValues.put(androidField.name, value);
            if (androidField.type != null && androidField.type.length() > 0) newSubDirValues.put(TYPE, androidField.type);
            if (androidField.kind != null && androidField.kind.length() > 0) newSubDirValues.put(KIND, androidField.kind);
            if (androidField.typeLabel != null && androidField.typeLabel.length() > 0) newSubDirValues.put(LABEL, androidField.typeLabel);
            newSubDirValues.put("_directory", androidField.directory);
            valueList.add(newSubDirValues);
          }
        }
        checksumDb.execSQL(String.format("delete from checksum where name='%s' and field='%s'", sre.getAttributeValue(configuration.IdOnLDAP), fieldName));
        checksumDb.execSQL(String.format("insert into checksum values ('%s','%s','%s')", sre.getAttributeValue(configuration.IdOnLDAP), fieldName, md5(values)));
      }
    }
    Uri newPersonUri = People.createPersonInMyContactsGroup(this.activity.getContentResolver(), newValues);
    if (newPersonUri == null) {
      this.activity.logString(String.format("Error creating android entry for '%s'", sre.getAttributeValue(configuration.IdOnLDAP)));
      cleanFromChecksumDB(sre.getAttributeValue(configuration.IdOnLDAP), checksumDb);
    } else {
      for (ContentValues subdirValues : valueList) {
        String directory = subdirValues.getAsString("_directory");
        subdirValues.remove("_directory");
        Uri subdirUri = Uri.withAppendedPath(newPersonUri, directory);
        this.activity.getContentResolver().insert(subdirUri, subdirValues);
      }
      if (DEBUG) System.out.println(String.format("Added '%s'", sre.getAttributeValue(configuration.IdOnLDAP)));
    }
  }

  private void addEntryInLDAP(Cursor c, SQLiteDatabase checksumDb, Configuration configuration, LDAPConnection conn) throws LDAPException {
    List<Attribute> attributes = new LinkedList<Attribute>();
    for (String fieldName : configuration.mapping.keySet()) {
      checksumDb.execSQL(String.format("delete from checksum where name='%s' and field='%s'", c.getString(c.getColumnIndex(configuration.IdOnAndroid)), fieldName));
      AndroidField androidField = configuration.mapping.get(fieldName);
      if (androidField.directory == null || androidField.directory.length() == 0) {
        String value = c.getString(c.getColumnIndex(androidField.name));
        if (value != null && value.length() > 0) {
          attributes.add(new Attribute(fieldName, value));
          checksumDb.execSQL(String.format("insert into checksum values ('%s','%s','%s')", c.getString(c.getColumnIndex(configuration.IdOnAndroid)), fieldName, md5(value)));
        }
      } else {
        List<String> values = new LinkedList<String>();
        for (SubDirEntry subdirEntry : findSubdirEntries(c, androidField)) {
          if (subdirEntry.value != null && subdirEntry.value.length() > 0) {
            attributes.add(new Attribute(fieldName, subdirEntry.value));
            values.add(subdirEntry.value);
          }
        }
        if (values.size() > 0) {
          checksumDb.execSQL(String.format("insert into checksum values ('%s','%s','%s')", c.getString(c.getColumnIndex(configuration.IdOnAndroid)), fieldName, md5(values)));
        }
      }
    }
    DN dn = new DN(new RDN(configuration.DNLeafOnLDAP, c.getString(c.getColumnIndex(configuration.IdOnAndroid))), new DN(configuration.basedn));
    if (!configuration.mapping.keySet().contains(configuration.DNLeafOnLDAP)) {
      attributes.add(new Attribute(configuration.DNLeafOnLDAP, c.getString(c.getColumnIndex(configuration.IdOnAndroid))));
    }
    for (String dnLeafCopy : configuration.DNLeafOnLDAPCopy) {
      attributes.add(new Attribute(dnLeafCopy, c.getString(c.getColumnIndex(configuration.IdOnAndroid))));
    }
    attributes.add(new Attribute("objectClass", configuration.ldapClass));
    conn.add(new AddRequest(dn, attributes));
  }

  private void deleteEntryInAndroid(Cursor c, Configuration configuration) {
    Uri baseUri = Uri.withAppendedPath(People.CONTENT_URI, c.getString(c.getColumnIndex(People._ID)));
    activity.getContentResolver().delete(baseUri, null, null);
  }

  private void deleteEntryInLDAP(SearchResultEntry sre, Configuration configuration, LDAPConnection conn) throws LDAPException {
    conn.delete(new DeleteRequest(sre.getDN()));
  }
}
