/*
 * Copyright (c) 2012 Sonatype, Inc. All rights reserved.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.ci.jenkins;

import hudson.Plugin;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Hudson;
import hudson.model.UpdateCenter;
import hudson.model.UpdateSite;
import hudson.security.ACL;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.acegisecurity.Authentication;
import org.acegisecurity.context.SecurityContextHolder;

public class PluginImpl
    extends Plugin
{
    private static final Logger log = Logger.getLogger( PluginImpl.class.getName() );

    @Initializer( after = InitMilestone.EXTENSIONS_AUGMENTED, attains = "sonatype-update-sites" )
    public static void addUpdateSites()
        throws Exception
    {
        log.info( "Adding Sonatype update site(s)" );

        UpdateCenter updateCenter = Hudson.getInstance().getUpdateCenter();
        if ( updateCenter.getSites().isEmpty() )
        {
            updateCenter.load();
        }

        addUpdateSite( updateCenter, "insight-ci", "http://links.sonatype.com/products/insight/ci/update-site" );
    }

    @Initializer( requires = "sonatype-update-sites" )
    public static void installPlugins()
    {
        log.info( "Installing Sonatype plugin(s)" );

        UpdateCenter updateCenter = Hudson.getInstance().getUpdateCenter();

        installPlugin( updateCenter, "insight-ci" );
    }

    private static void addUpdateSite( UpdateCenter updateCenter, String name, String url )
    {
        if ( updateCenter.getById( name ) == null )
        {
            @SuppressWarnings( "static-access" )
            Authentication authentication = Hudson.getAuthentication();
            try
            {
                SecurityContextHolder.getContext().setAuthentication( ACL.SYSTEM );

                log.info( "Adding " + name + "..." );
                updateCenter.getSites().add( new UpdateSite( name, url ) );
                log.info( "Added " + name );
            }
            catch ( Exception e )
            {
                log.log( Level.WARNING, "Cannot add " + name, e );
            }
            finally
            {
                SecurityContextHolder.getContext().setAuthentication( authentication );
            }
        }
    }

    private static void installPlugin( UpdateCenter updateCenter, String name )
    {
        UpdateSite.Plugin plugin = updateCenter.getPlugin( name );
        if ( plugin != null && plugin.getInstalled() == null )
        {
            @SuppressWarnings( "static-access" )
            Authentication authentication = Hudson.getAuthentication();
            try
            {
                SecurityContextHolder.getContext().setAuthentication( ACL.SYSTEM );

                log.info( "Installing " + name + "..." );
                plugin.deploy().get();
                log.info( "Installed " + name );
            }
            catch ( Exception e )
            {
                log.log( Level.WARNING, "Cannot install " + name, e );
            }
            finally
            {
                SecurityContextHolder.getContext().setAuthentication( authentication );
            }
        }
    }
}
