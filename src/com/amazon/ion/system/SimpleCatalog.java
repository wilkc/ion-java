/*
 * Copyright (c) 2007-2008 Amazon.com, Inc.  All rights reserved.
 */

package com.amazon.ion.system;

import com.amazon.ion.IonCatalog;
import com.amazon.ion.IonMutableCatalog;
import com.amazon.ion.SymbolTable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * A basic implementation of {@link IonCatalog} as a hash table.  There is no
 * automatic removal of entries.
 */
public class SimpleCatalog
    implements IonMutableCatalog, Iterable<SymbolTable>
{
    /*  CAVEATS AND LIMITATIONS
     *
     *  - When getTable can't find an exact match, it does a linear scan of
     *    all tables with the same name to find the greatest version.
     *  - Synchonization could probably be tighter using read/write locks
     *    instead of simple monitors.
     */
    private Map<String,TreeMap<Integer,SymbolTable>> myTablesByName =
        new HashMap<String,TreeMap<Integer,SymbolTable>>();


    public SymbolTable getTable(String name)
    {
        if (name == null)
        {
            throw new IllegalArgumentException("name is null");
        }
        if (name.length() == 0)
        {
            throw new IllegalArgumentException("name is empty");
        }

        TreeMap<Integer,SymbolTable> versions;
        synchronized (myTablesByName)
        {
            versions = myTablesByName.get(name);
        }

        if (versions == null) return null;

        synchronized (versions)
        {
            Integer highestVersion = versions.lastKey();
            return versions.get(highestVersion);
        }
    }

    public SymbolTable getTable(String name, int version)
    {
        if (name == null)
        {
            throw new IllegalArgumentException("name is null");
        }
        if (name.length() == 0)
        {
            throw new IllegalArgumentException("name is empty");
        }
        if (version < 1)
        {
            throw new IllegalArgumentException("version is < 1");
        }

        TreeMap<Integer,SymbolTable> versions;
        synchronized (myTablesByName)
        {
            versions = myTablesByName.get(name);
        }

        if (versions == null) return null;

        synchronized (versions)
        {
            SymbolTable st = versions.get(version);
            if (st == null)
            {
                // if we don't have the one you want, we'll give you the
                // "best" one we have, even if it's newer than what you
                // asked for (see CAVEAT above)
                assert !versions.isEmpty();

                // In Java 5 this works:
                // OK it doesn't seem to work
                //Integer last_version = versions.lastKey();
                //st = versions.get(last_version);
                int best = version;
                Integer ibest = null;
                for (Integer v : versions.keySet())
                {
                    if (best > version) {
                        if (v.intValue() < best) {
                            best = v.intValue();
                            ibest = v;
                        }
                    }
                    else if (best < version) {
                        if (v.intValue() > best) {
                            best = v.intValue();
                            ibest = v;
                        }
                    }
                    else {
                        best = v.intValue();
                        ibest = v;
                    }
                }
                assert(ibest != null);
                st = versions.get(ibest);

                // TODO in Java 6 this is probably faster:
                //Map.Entry<Integer, UnifiedSymbolTable> entry;
                //entry = versions.lastEntry();
                //assert entry != null;
                //st = entry.getValue();

                assert st != null;
            }

            return st;
        }
    }

    public void putTable(SymbolTable table)
    {
        if (table.isLocalTable() || table.isSystemTable())
        {
            throw new IllegalArgumentException("table cannot be local or system table");
        }

        String name = table.getName();
        int version = table.getVersion();
        assert version >= 0;

        synchronized (myTablesByName)
        {
            TreeMap<Integer,SymbolTable> versions =
                myTablesByName.get(name);
            if (versions == null)
            {
                versions = new TreeMap<Integer,SymbolTable>();
                myTablesByName.put(name, versions);
            }
            synchronized (versions)
            {
                versions.put(version, table);
            }
        }
    }


    /**
     * Removes a symbol table from this catalog.
     *
     * @return the removed table, or <code>null</code> if this catalog has
     * no matching table.
     */
    public SymbolTable removeTable(String name, int version)
    {
        SymbolTable removed = null;

        synchronized (myTablesByName)
        {
            TreeMap<Integer,SymbolTable> versions =
                myTablesByName.get(name);
            if (versions != null)
            {
                synchronized (versions)
                {
                    removed = versions.remove(version);

                    // Remove empty intermediate table
                    if (versions.isEmpty())
                    {
                        myTablesByName.remove(name);
                    }
                }
            }
        }

        return removed;
    }


    /**
     * Constructs an iterator that enumerates all of the shared symbol tables
     * in this catalog, at the time of method invocation. The result represents
     * a snapshot of the state of this catalog.
     *
     * @return a non-null, but potentially empty, iterator.
     */
    public Iterator<SymbolTable> iterator()
    {
        ArrayList<SymbolTable> tables;

        synchronized (myTablesByName)
        {
            tables = new ArrayList<SymbolTable>(myTablesByName.size());

            // I don't think we can shorten the synchronization block
            // because HashMap.values() result is a live view (not a copy) and
            // thus needs to be synched too.
            Collection<TreeMap<Integer, SymbolTable>> symtabNames =
                myTablesByName.values();
            for (TreeMap<Integer,SymbolTable> versions : symtabNames)
            {
                synchronized (versions)
                {
                    tables.addAll(versions.values());
                }
            }
        }

        return tables.iterator();
    }

}
