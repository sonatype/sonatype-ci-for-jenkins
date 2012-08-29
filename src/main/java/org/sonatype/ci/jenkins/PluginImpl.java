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
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PluginImpl
    extends Plugin
{
    private static final Logger log = Logger.getLogger( PluginImpl.class.getName() );

    @Initializer( after = InitMilestone.EXTENSIONS_AUGMENTED, attains = "sonatype-update-sites" )
    public static void addUpdateSites()
    {
        addUpdateSites( Hudson.getInstance().getUpdateCenter(), loadUpdateSites() );
    }

    @Initializer( requires = "sonatype-update-sites" )
    public static void installPlugins()
    {
        installPlugin( Hudson.getInstance().getUpdateCenter(), "insight-ci" );
    }

    @SuppressWarnings( "static-access" )
    private static List<UpdateSite> loadUpdateSites()
    {
        final Properties properties = new Properties();
        final InputStream is = PluginImpl.class.getResourceAsStream( "UpdateSites.txt" );
        try
        {
            properties.load( is );
        }
        catch ( final IOException e )
        {
            log.log( Level.WARNING, "Cannot load update sites", e );
        }
        finally
        {
            IOUtils.closeQuietly( is );
        }

        final List<UpdateSite> updateSites = new ArrayList<UpdateSite>( properties.size() );
        for ( final Entry<?, ?> line : properties.entrySet() )
        {
            updateSites.add( new UpdateSite( (String) line.getKey(), (String) line.getValue() ) );
        }
        return updateSites;
    }

    private static void addUpdateSites( final UpdateCenter updateCenter, final List<UpdateSite> updateSites )
    {
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

        for ( final UpdateSite newSite : updateSites )
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
                final PersistedList<UpdateSite> sites = updateCenter.getSites();

                for ( final UpdateSite oldSite : oldSites )
                {
                    log.info( "Removing " + oldSite.getId() + " [" + oldSite.getUrl() + "]" );
                    sites.remove( oldSite );
                }
                for ( final UpdateSite newSite : newSites )
                {
                    log.info( "Adding " + newSite.getId() + " [" + newSite.getUrl() + "]" );
                    sites.add( newSite );
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

    private static void installPlugin( final UpdateCenter updateCenter, final String name )
    {
        final UpdateSite.Plugin plugin = updateCenter.getPlugin( name );
        if ( plugin != null && plugin.getInstalled() == null )
        {
            try
            {
                log.info( "Installing " + name + "..." );
                plugin.deploy().get();
                log.info( "Installed " + name );
            }
            catch ( final Exception e )
            {
                log.log( Level.WARNING, "Cannot install " + name, e );
            }
        }
    }
}
