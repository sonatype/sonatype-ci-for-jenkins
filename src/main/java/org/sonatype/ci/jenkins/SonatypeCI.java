/*
 * Copyright (c) 2012 Sonatype, Inc. All rights reserved.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.ci.jenkins;

import hudson.BulkChange;
import hudson.Plugin;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Hudson;
import hudson.model.UpdateCenter;
import hudson.model.UpdateSite;
import hudson.util.IOUtils;
import hudson.util.PersistedList;
import hudson.util.TextFile;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

public final class SonatypeCI
    extends Plugin
{
    private static final Logger log = Logger.getLogger( SonatypeCI.class.getName() );

    private static Method getDataFile;
    {
        try
        {
            getDataFile = UpdateSite.class.getDeclaredMethod( "getDataFile" );
            getDataFile.setAccessible( true );
        }
        catch ( final Exception e )
        {
            getDataFile = null;
        }
    }

    private static Method dynamicDeploy;
    {
        try
        {
            dynamicDeploy = UpdateSite.Plugin.class.getDeclaredMethod( "deploy", boolean.class );
            dynamicDeploy.setAccessible( true );
        }
        catch ( final Exception e )
        {
            dynamicDeploy = null;
        }
    }

    @Initializer( after = InitMilestone.EXTENSIONS_AUGMENTED, attains = "installed-sonatype-sites" )
    public static void installSonatypeSites()
    {
        installSites( seedSites( "SonatypeSites.json" ) );
    }

    @Initializer( requires = "installed-sonatype-sites", attains = "installed-sonatype-plugins" )
    public static void installSonatypePlugins()
    {
        installPlugins( "insight-ci" );
    }

    @SuppressWarnings( "unchecked" )
    private static List<UpdateSite> seedSites( final String name )
    {
        final List<UpdateSite> sites = new ArrayList<UpdateSite>();
        for ( final JSONObject obj : (List<JSONObject>) (List<?>) getResourceAsJSONArray( name ) )
        {
            final UpdateSite site = new UpdateSite( obj.getString( "id" ), obj.getString( "url" ) );
            if ( site.getData() == null && getDataFile != null )
            {
                try
                {
                    ( (TextFile) getDataFile.invoke( site ) ).write( obj.getString( "seed" ) );
                }
                catch ( final Exception e )
                {
                    log.log( Level.WARNING, "Cannot seed UpdateSite contents for " + site.getId(), e );
                }
            }
            sites.add( site );
        }
        return sites;
    }

    private static void installSites( final List<UpdateSite> sites )
    {
        final UpdateCenter updateCenter = Hudson.getInstance().getUpdateCenter();
        if ( updateCenter.getSites().isEmpty() )
        {
            try
            {
                updateCenter.load();
            }
            catch ( final IOException e )
            {
                // bail-out, we don't want to accidentally replace the entire config
                log.log( Level.WARNING, "Cannot load UpdateCenter configuration", e );
                return;
            }
        }

        final List<UpdateSite> newSites = new ArrayList<UpdateSite>();
        final List<UpdateSite> oldSites = new ArrayList<UpdateSite>();

        for ( final UpdateSite newSite : sites )
        {
            final String id = newSite.getId();
            final UpdateSite oldSite = updateCenter.getById( id );
            if ( oldSite == null )
            {
                newSites.add( newSite );
            }
            else if ( !newSite.getUrl().equals( oldSite.getUrl() ) )
            {
                oldSites.add( oldSite );
                newSites.add( newSite );
            }
        }

        if ( !newSites.isEmpty() )
        {
            final BulkChange bc = new BulkChange( updateCenter );
            try
            {
                final PersistedList<UpdateSite> persistedSites = updateCenter.getSites();

                for ( final UpdateSite oldSite : oldSites )
                {
                    log.info( "Removing site " + oldSite.getId() + " [" + oldSite.getUrl() + "]" );
                    persistedSites.remove( oldSite );
                }
                for ( final UpdateSite newSite : newSites )
                {
                    log.info( "Adding site " + newSite.getId() + " [" + newSite.getUrl() + "]" );
                    persistedSites.add( newSite );
                }

                bc.commit();
            }
            catch ( final Exception e )
            {
                log.log( Level.WARNING, "Cannot add update sites", e );
            }
            finally
            {
                bc.abort();
            }
        }
    }

    private static void installPlugins( final String... ids )
    {
        final UpdateCenter updateCenter = Hudson.getInstance().getUpdateCenter();

        for ( final String id : ids )
        {
            final UpdateSite.Plugin plugin = updateCenter.getPlugin( id );
            if ( plugin != null && plugin.getInstalled() == null )
            {
                try
                {
                    if ( dynamicDeploy != null )
                    {
                        try
                        {
                            dynamicDeploy.invoke( plugin, Boolean.TRUE );
                            continue;
                        }
                        catch ( final Exception e )
                        {
                            // drop-through to old non-dynamic deploy
                        }
                    }
                    plugin.deploy();
                }
                catch ( final Exception e )
                {
                    log.log( Level.WARNING, "Cannot install " + plugin.name, e );
                }
            }
        }
    }

    @SuppressWarnings( "static-access" )
    private static JSONArray getResourceAsJSONArray( final String name )
    {
        final InputStream is = SonatypeCI.class.getResourceAsStream( name );
        try
        {
            return JSONArray.fromObject( IOUtils.toString( is, "UTF-8" ) );
        }
        catch ( final IOException e )
        {
            return new JSONArray();
        }
        finally
        {
            IOUtils.closeQuietly( is );
        }
    }
}
