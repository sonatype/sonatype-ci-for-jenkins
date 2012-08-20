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
import hudson.util.PersistedList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PluginImpl
    extends Plugin
{
    private static final Logger log = Logger.getLogger( PluginImpl.class.getName() );

    @Initializer( after = InitMilestone.EXTENSIONS_AUGMENTED, attains = "sonatype-update-sites" )
    public static void addUpdateSites()
        throws IOException
    {
        log.info( "Adding Sonatype update site(s)" );

        final UpdateCenter updateCenter = Hudson.getInstance().getUpdateCenter();

        final UpdateSite[] updateSites =
            { new UpdateSite( "insight-ci", "http://links.sonatype.com/products/insight/ci/update-site" ) };

        addUpdateSites( updateCenter, updateSites );
    }

    @Initializer( requires = "sonatype-update-sites" )
    public static void installPlugins()
    {
        log.info( "Installing Sonatype plugin(s)" );

        final UpdateCenter updateCenter = Hudson.getInstance().getUpdateCenter();

        installPlugin( updateCenter, "insight-ci" );
    }

    private static void addUpdateSites( final UpdateCenter updateCenter, final UpdateSite... updateSites )
        throws IOException
    {
        final List<UpdateSite> newSites = new ArrayList<UpdateSite>();
        final List<UpdateSite> oldSites = new ArrayList<UpdateSite>();

        for ( final UpdateSite newSite : updateSites )
        {
            final String id = newSite.getId();
            final UpdateSite oldSite = updateCenter.getById( id );
            String action = null;

            if ( oldSite == null )
            {
                action = "Adding ";
                newSites.add( newSite );
            }
            else if ( !newSite.getUrl().equals( oldSite.getUrl() ) )
            {
                action = "Updating ";
                oldSites.add( oldSite );
                newSites.add( newSite );
            }

            if ( action != null )
            {
                log.info( action + id + " [" + newSite.getUrl() + "]" );
            }
        }

        if ( !newSites.isEmpty() )
        {
            final BulkChange bc = new BulkChange( updateCenter );
            try
            {
                final PersistedList<UpdateSite> sites = updateCenter.getSites();
                if ( sites.isEmpty() )
                {
                    updateCenter.load();
                }

                for ( final UpdateSite oldSite : oldSites )
                {
                    sites.remove( oldSite );
                }
                sites.addAll( newSites );

                bc.commit();
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
