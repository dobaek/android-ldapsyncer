/*
 * (c) Copyright Bastian Mathes 2009,2010
 *  Released under GPL v2.
 */
package jp.mathes.android.ldapsyncer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import jp.mathes.android.ldapsyncer.exceptions.ConfigurationParsingException;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.interpol.ConstantLookup;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

public class Configuration {

  static public final boolean DEBUG = false;
  static public final ConstantLookup lookup = new ConstantLookup();

  static public class AndroidField {
    String name, type, kind, typeLabel, directory;

    @Override
    public int hashCode() {
      return new HashCodeBuilder(37, 71).append(name).append(type).append(kind).append(typeLabel).append(directory).hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (obj == null) return false;
      if (getClass() != obj.getClass()) return false;
      AndroidField other = (AndroidField) obj;
      return new EqualsBuilder().append(name, other.name).append(type, other.type).append(kind, other.kind).append(typeLabel, other.typeLabel).append(directory, other.directory).isEquals();
    }
  }

  String server, binddn, password, basedn, IdOnAndroid, IdOnLDAP, DNLeafOnLDAP, ldapClass = "person";
  int port;

  boolean deleteOnAndroid = true;
  boolean deleteOnLDAP = true;
  boolean createOnAndroid = true;
  boolean createOnLDAP = true;
  boolean changeOnAndroid = true;
  boolean changeOnLDAP = true;
  boolean AndroidAlwaysWins = false;
  boolean LDAPAlwaysWins = false;
  boolean allChangesFromAndroid = false;
  boolean allChangesFromLDAP = false;

  List<String> DNLeafOnLDAPCopy = new LinkedList<String>();
  BiMap<String, Configuration.AndroidField> mapping = HashBiMap.create();

  public boolean validate() {
    if (DEBUG) {
      System.out.println("this.server: " + this.server);
      System.out.println("this.binddn: " + this.binddn);
      System.out.println("this.password: " + this.password);
      System.out.println("this.basedn: " + this.basedn);
      System.out.println("this.IdOnAndroid: " + this.IdOnAndroid);
      System.out.println("this.IdOnLDAP: " + this.IdOnLDAP);
      System.out.println("this.DNLeafOnLDAP: " + this.DNLeafOnLDAP);
      System.out.println("this.ldapClass: " + this.ldapClass);
      System.out.println("this.AndroidAlwaysWins: " + (this.AndroidAlwaysWins ? "yes" : "no"));
      System.out.println("this.LDAPAlwaysWins: " + (this.LDAPAlwaysWins ? "yes" : "no"));
      System.out.println("this.allChangesFromAndroid: " + (this.allChangesFromAndroid ? "yes" : "no"));
      System.out.println("this.allChangesFromLDAP: " + (this.allChangesFromLDAP ? "yes" : "no"));
      System.out.println("this.deleteOnAndroid: " + (this.deleteOnAndroid ? "yes" : "no"));
      System.out.println("this.deleteOnLDAP: " + (this.deleteOnLDAP ? "yes" : "no"));
      System.out.println("this.createOnAndroid: " + (this.createOnAndroid ? "yes" : "no"));
      System.out.println("this.createOnLDAP: " + (this.createOnLDAP ? "yes" : "no"));
      System.out.println("this.changeOnAndroid: " + (this.changeOnAndroid ? "yes" : "no"));
      System.out.println("this.changeOnLDAP: " + (this.changeOnLDAP ? "yes" : "no"));
    }
    return this.server != null && this.server.length() > 0 && this.binddn != null && this.binddn.length() > 0 && this.password != null && this.password.length() > 0 && this.basedn != null
        && this.basedn.length() > 0 && this.IdOnAndroid != null && this.IdOnAndroid.length() > 0 && this.IdOnLDAP != null && this.IdOnLDAP.length() > 0 && this.DNLeafOnLDAP != null
        && this.DNLeafOnLDAP.length() > 0 && (!(this.AndroidAlwaysWins && this.LDAPAlwaysWins)) && (!(this.allChangesFromAndroid && this.allChangesFromLDAP));
  }

  public static Configuration readConfiguration(String dataDirectory) throws ConfigurationException, ParserConfigurationException, FileNotFoundException, SAXException, IOException,
      ConfigurationParsingException {
    Configuration configuration = new Configuration();
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = factory.newDocumentBuilder();
    Document dom = builder.parse(new FileInputStream(new File(dataDirectory + File.separator + LDAPSyncer.CONFIG_FILE)));
    NodeList childNodes = dom.getDocumentElement().getChildNodes();
    for (int i = 0; i < childNodes.getLength(); i++) {
      if (childNodes.item(i).getNodeName().equals("ldap")) {
        NodeList LDAPchildNodes = childNodes.item(i).getChildNodes();
        for (int j = 0; j < LDAPchildNodes.getLength(); j++) {
          configuration.server = getTrimmedStringIfEqual("server", LDAPchildNodes, j, configuration.server);
          configuration.binddn = getTrimmedStringIfEqual("binddn", LDAPchildNodes, j, configuration.binddn);
          configuration.password = getTrimmedStringIfEqual("password", LDAPchildNodes, j, configuration.password);
          configuration.basedn = getTrimmedStringIfEqual("basedn", LDAPchildNodes, j, configuration.basedn);
          configuration.port = getIntIfEqual("port", LDAPchildNodes, j, configuration.port);
        }
      } else if (childNodes.item(i).getNodeName().equals("settings")) {
        NodeList settingsChildNodes = childNodes.item(i).getChildNodes();
        for (int j = 0; j < settingsChildNodes.getLength(); j++) {
          if (settingsChildNodes.item(j).getNodeName().equals("identifier")) {
            NodeList identifierChildNotes = settingsChildNodes.item(j).getChildNodes();
            for (int k = 0; k < identifierChildNotes.getLength(); k++) {
              configuration.IdOnAndroid = getLookupedTrimmedStringIfEqual("OnAndroid", identifierChildNotes, k, configuration.IdOnAndroid);
              configuration.IdOnLDAP = getTrimmedStringIfEqual("OnLdap", identifierChildNotes, k, configuration.IdOnLDAP);
              configuration.DNLeafOnLDAP = getTrimmedStringIfEqual("DNLeafOnLdap", identifierChildNotes, k, configuration.DNLeafOnLDAP);
              configuration.ldapClass = getTrimmedStringIfEqual("ldapClass", identifierChildNotes, k, configuration.ldapClass);
              String dnLeafCopy = getTrimmedStringIfEqual("DNLeafOnLdapCopy", identifierChildNotes, k, null);
              if (dnLeafCopy != null && dnLeafCopy.length() > 0) configuration.DNLeafOnLDAPCopy.add(dnLeafCopy);
            }
          } else if (settingsChildNodes.item(j).getNodeName().equals("delete")) {
            NodeList deleteChildNodes = settingsChildNodes.item(j).getChildNodes();
            for (int k = 0; k < deleteChildNodes.getLength(); k++) {
              configuration.deleteOnAndroid = getBoolIfEqual("OnAndroid", deleteChildNodes, k, configuration.deleteOnAndroid);
              configuration.deleteOnLDAP = getBoolIfEqual("OnLdap", deleteChildNodes, k, configuration.deleteOnLDAP);
            }
          } else if (settingsChildNodes.item(j).getNodeName().equals("create")) {
            NodeList createChildNodes = settingsChildNodes.item(j).getChildNodes();
            for (int k = 0; k < createChildNodes.getLength(); k++) {
              configuration.createOnAndroid = getBoolIfEqual("OnAndroid", createChildNodes, k, configuration.createOnAndroid);
              configuration.createOnLDAP = getBoolIfEqual("OnLdap", createChildNodes, k, configuration.createOnLDAP);
            }
          } else if (settingsChildNodes.item(j).getNodeName().equals("change")) {
            NodeList createChildNodes = settingsChildNodes.item(j).getChildNodes();
            for (int k = 0; k < createChildNodes.getLength(); k++) {
              configuration.changeOnAndroid = getBoolIfEqual("OnAndroid", createChildNodes, k, configuration.changeOnAndroid);
              configuration.changeOnLDAP = getBoolIfEqual("OnLdap", createChildNodes, k, configuration.changeOnLDAP);
            }
          } else if (settingsChildNodes.item(j).getNodeName().equals("alwaysWins")) {
            if (settingsChildNodes.item(j).getChildNodes().item(0).getNodeValue().equals("true")) {
              if (settingsChildNodes.item(j).getAttributes().getNamedItem("source") != null &&
                  settingsChildNodes.item(j).getAttributes().getNamedItem("source").getNodeValue().equals("ldap")) {
                configuration.LDAPAlwaysWins = true;
              } else if (settingsChildNodes.item(j).getAttributes().getNamedItem("source") != null &&
                  settingsChildNodes.item(j).getAttributes().getNamedItem("source").getNodeValue().equals("android")) {
                configuration.AndroidAlwaysWins = true;
              } else {
                throw new ConfigurationParsingException("Warning: AlwaysWins element preset, but no or not supported source attribute");
              }
            }
          } else if (settingsChildNodes.item(j).getNodeName().equals("allChangesFrom")) {
            if (settingsChildNodes.item(j).getChildNodes().item(0).getNodeValue().equals("true")) {
              if (settingsChildNodes.item(j).getAttributes().getNamedItem("source") != null &&
                  settingsChildNodes.item(j).getAttributes().getNamedItem("source").getNodeValue().equals("ldap")) {
                configuration.allChangesFromLDAP = true;
              } else if (settingsChildNodes.item(j).getAttributes().getNamedItem("source") != null &&
                  settingsChildNodes.item(j).getAttributes().getNamedItem("source").getNodeValue().equals("android")) {
                configuration.allChangesFromAndroid = true;
              } else {
                throw new ConfigurationParsingException("Warning: AllChangesFrom element preset, but no or not supported source attribute");
              }
            }
          }
        }
      } else if (childNodes.item(i).getNodeName().equals("mapping")) {
        NodeList mappingChildNodes = childNodes.item(i).getChildNodes();
        for (int j = 0; j < mappingChildNodes.getLength(); j++) {
          if (mappingChildNodes.item(j).getNodeName().equals("attribute")) {
            AndroidField androidField = new AndroidField();
            String ldapField = null;
            NodeList attributeChildNodes = mappingChildNodes.item(j).getChildNodes();
            for (int k = 0; k < attributeChildNodes.getLength(); k++) {
              ldapField = getTrimmedStringIfEqual("ldap", attributeChildNodes, k, ldapField);
              if (attributeChildNodes.item(k).getNodeName().equals("android")) {
                NodeList androidChildNodes = attributeChildNodes.item(k).getChildNodes();
                for (int l = 0; l < androidChildNodes.getLength(); l++) {
                  androidField.directory = getLookupedTrimmedStringIfEqual("directory", androidChildNodes, l, androidField.directory);
                  androidField.name = getLookupedTrimmedStringIfEqual("name", androidChildNodes, l, androidField.name);
                  androidField.type = getLookupedTrimmedStringIfEqual("type", androidChildNodes, l, androidField.type);
                  androidField.kind = getLookupedTrimmedStringIfEqual("kind", androidChildNodes, l, androidField.kind);
                  androidField.typeLabel = getTrimmedStringIfEqual("typeLabel", androidChildNodes, l, androidField.typeLabel);
                }
              }
            }
            if (ldapField != null) configuration.mapping.put(ldapField, androidField);
          }
        }
      }
    }
    return configuration;
  }

  private static String getTrimmedStringIfEqual(String comperator, NodeList nodes, int counter, String oldValue) {
    if (nodes.item(counter).getNodeName().equals(comperator)) {
      return nodes.item(counter).getChildNodes().item(0).getNodeValue().trim();
    } else {
      return oldValue;
    }
  }

  private static String getLookupedTrimmedStringIfEqual(String comperator, NodeList nodes, int counter, String oldValue) {
    if (nodes.item(counter).getNodeName().equals(comperator)) {
      return lookup.lookup(nodes.item(counter).getChildNodes().item(0).getNodeValue().trim());
    } else {
      return oldValue;
    }
  }

  private static int getIntIfEqual(String comperator, NodeList nodes, int counter, int oldValue) {
    if (nodes.item(counter).getNodeName().equals(comperator)) {
      return Integer.valueOf(nodes.item(counter).getChildNodes().item(0).getNodeValue().trim());
    } else {
      return oldValue;
    }
  }

  private static boolean getBoolIfEqual(String comperator, NodeList nodes, int counter, boolean oldValue) {
    if (nodes.item(counter).getNodeName().equals(comperator)) {
      return nodes.item(counter).getChildNodes().item(0).getNodeValue().trim().equals("true");
    } else {
      return oldValue;
    }
  }
}
