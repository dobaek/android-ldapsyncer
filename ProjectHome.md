**Note**: I don't have an Android 1.x phone anymore and use funambol now to sync my phone and computer. So this project is kind of deprecated.

Synchronizes android contacts (using the "old" 1.5 API) with an LDAP Server (tested with OpenLDAP). It is driven by a configuration file that contains the field mapping and defines which operations (add, delete, change) are executed on which side. It does not rely on timestamps but keeps checksums in an internal database, it works on field level, e.g. you can change two different fields on both sides and synchronization will transfer these fields in both directions.

Some warnings: this is in a very early stage and comes with absolute no warranty !! Please make a backup of all involved address data and be sure to be able to restore it.

Please note that the synchronization is not very fast currently. After the installation you have to initialize the data directory and then adapt configuration.xml, at least with your host, bind and base DN and password. The currently configured mapping is tailored to contacts created by evolution.

If you tried it and there are issues (and you think it is worth working further on it), please file a bug on this page.