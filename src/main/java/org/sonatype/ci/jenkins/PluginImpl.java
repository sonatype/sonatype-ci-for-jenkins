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

    @Initializer( after = InitMilestone.EXTENSIONS_AUGMENTED, attains = "sonatype-ci-update-site" )
    public static void addUpdateSite()
        throws Exception
    {
        log.info( "Adding Sonatype CI update site" );

        UpdateCenter updateCenter = Hudson.getInstance().getUpdateCenter();
        if ( updateCenter.getSites().isEmpty() )
        {
            updateCenter.load();
        }
        if ( updateCenter.getById( "sonatype-ci" ) == null )
        {
            updateCenter.getSites().add( new UpdateSite( "sonatype-ci", "file:update-center.json" ) );
        }
    }

    @Initializer( requires = "sonatype-ci-update-site" )
    public static void installPlugins()
    {
        log.info( "Installing Sonatype CI plugins" );

        UpdateCenter updateCenter = Hudson.getInstance().getUpdateCenter();

        installPlugin( updateCenter, "insight-ci" );
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
