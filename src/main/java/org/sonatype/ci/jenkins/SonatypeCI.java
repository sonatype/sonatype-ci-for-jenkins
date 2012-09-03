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

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

public final class SonatypeCI
    extends Plugin
{
    private static final Logger log = Logger.getLogger( SonatypeCI.class.getName() );

    private static boolean installFeaturedPlugins;

    private static Method _dynamicDeploy;

    @Initializer( after = InitMilestone.EXTENSIONS_AUGMENTED, attains = "installed-sonatype-sites" )
    public static void installSonatypeSites()
    {
        installSites( seedSites( "SonatypeSites.json" ) );
    }

    @Initializer( requires = "installed-sonatype-sites", attains = "installed-sonatype-plugins" )
    public static void installSonatypePlugins()
    {
        if ( installFeaturedPlugins )
        {
            installPlugins( "insight-ci" );
        }
    }

    private static List<UpdateSite> seedSites( final String name )
    {
        final List<UpdateSite> sites = new ArrayList<UpdateSite>();
        for ( final JSONObject obj : getResourceAsListOfJSON( name ) )
        {
            final SonatypeSite site = new SonatypeSite( obj.getString( "id" ), obj.getString( "url" ) );
            if ( site.getData() == null )
            {
                try
                {
                    site._getDataFile().write( obj.getString( "seed" ) );
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
                // first time installation
                installFeaturedPlugins = true;
                newSites.add( newSite );
            }
            else if ( !oldSite.getUrl().equals( newSite.getUrl() ) )
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
                    _dynamicDeploy( plugin );
                }
                catch ( final Exception e )
                {
                    log.log( Level.WARNING, "Cannot install " + plugin.name, e );
                }
            }
        }
    }

    @SuppressWarnings( { "static-access", "rawtypes", "unchecked" } )
    private static List<JSONObject> getResourceAsListOfJSON( final String name )
    {
        final InputStream is = SonatypeCI.class.getResourceAsStream( name );
        try
        {
            return (List) JSONArray.fromObject( IOUtils.toString( is, "UTF-8" ) );
        }
        catch ( final IOException e )
        {
            return Collections.emptyList();
        }
        finally
        {
            IOUtils.closeQuietly( is );
        }
    }

    private static void _dynamicDeploy( final UpdateSite.Plugin plugin )
    {
        if ( _dynamicDeploy != null )
        {
            try
            {
                _dynamicDeploy.invoke( plugin, Boolean.TRUE );
                return;
            }
            catch ( final Exception e )
            {
                log.log( Level.WARNING, "Restart needed to complete install of " + plugin.name );
                // drop-through to old non-dynamic deploy
            }
        }
        plugin.deploy();
    }

    static
    {
        UpdateCenter.XSTREAM.alias( "sonatype", SonatypeSite.class );

        try
        {
            _dynamicDeploy = UpdateSite.Plugin.class.getDeclaredMethod( "deploy", boolean.class );
            _dynamicDeploy.setAccessible( true );
        }
        catch ( final Exception e )
        {
            _dynamicDeploy = null;
        }
    }
}
