/*
 * Copyright (c) 2012 Sonatype, Inc. All rights reserved.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.ci.jenkins;

import hudson.model.Hudson;
import hudson.model.UpdateSite;
import hudson.util.FormValidation;
import hudson.util.IOUtils;
import hudson.util.TextFile;
import hudson.util.VersionNumber;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

public final class SonatypeSite
    extends UpdateSite
{
    private static Method _getDataFile;

    private static Field _dataTimestamp;

    public SonatypeSite( final String id, final String url )
    {
        super( id, url );
    }

    @SuppressWarnings( { "static-access", "rawtypes", "unchecked" } )
    public FormValidation doPostBack( final StaplerRequest req )
        throws IOException
    {
        final String json = IOUtils.toString( req.getInputStream(), "UTF-8" );
        final JSONObject feedPlugins = JSONObject.fromObject( json ).getJSONObject( "plugins" );
        if ( !feedPlugins.isEmpty() )
        {
            boolean modified = false;
            final JSONObject site = JSONObject.fromObject( _getDataFile().read() );
            for ( final Map.Entry entry : (Iterable<Map.Entry>) site.getJSONObject( "plugins" ).entrySet() )
            {
                final JSONObject plugin = feedPlugins.getJSONObject( (String) entry.getKey() );
                if ( plugin != null )
                {
                    modified |= updatePlugin( (JSONObject) entry.getValue(), plugin.getString( "version" ) );
                }
            }

            if ( modified )
            {
                _getDataFile().write( site.toString() );
                touchDataTimestamp();
            }
        }
        return FormValidation.ok();
    }

    @Override
    public void doPostBack( final StaplerRequest req, final StaplerResponse rsp )
        throws IOException
    {
        doPostBack( req );

        rsp.setContentType( "text/plain" );
    }

    @Override
    public List<Plugin> getUpdates()
    {
        return Collections.emptyList(); // avoid duplicates, core implementation searches everything already
    }

    @Override
    public boolean hasUpdates()
    {
        return false; // no need to repeat the update check, core implementation searches everything already
    }

    boolean needsSeedUpdate( String version )
    {
        try
        {
            final String seedVersion = JSONObject.fromObject( _getDataFile().read() ).getString( "seedVersion" );
            return seedVersion == null || new VersionNumber( version ).isNewerThan( new VersionNumber( seedVersion ) );
        }
        catch ( final Exception e )
        {
            return true;
        }
    }

    private static boolean updatePlugin( final JSONObject plugin, final String newVersion )
    {
        final String name = plugin.getString( "name" );
        final String oldSuffix = linkSuffix( name, plugin.getString( "version" ) );
        final String oldURL = plugin.getString( "url" );
        if ( oldURL.endsWith( oldSuffix ) )
        {
            final String newSuffix = linkSuffix( name, newVersion );
            plugin.put( "url", oldURL.substring( 0, oldURL.length() - oldSuffix.length() ) + newSuffix );
            plugin.put( "version", newVersion );
            return true;
        }
        return false;
    }

    private static String linkSuffix( final String name, final String version )
    {
        return "/" + version + "/" + name + ".hpi";
    }

    TextFile _getDataFile()
    {
        if ( _getDataFile != null )
        {
            try
            {
                return (TextFile) _getDataFile.invoke( this );
            }
            catch ( final Exception e )
            {
                // assume data-file is "~/.jenkins/updates/${id}.json"
            }
        }
        return new TextFile( new File( Hudson.getInstance().getRootDir(), "updates/" + getId() + ".json" ) );
    }

    void touchDataTimestamp()
    {
        if ( _dataTimestamp != null )
        {
            try
            {
                _dataTimestamp.setLong( this, System.currentTimeMillis() );
            }
            catch ( final Exception e )
            {
                // assume will fall back to data-file lastModified
            }
        }
    }

    static
    {
        try
        {
            _getDataFile = UpdateSite.class.getDeclaredMethod( "getDataFile" );
            _getDataFile.setAccessible( true );
        }
        catch ( final Exception e )
        {
            _getDataFile = null;
        }

        try
        {
            _dataTimestamp = UpdateSite.class.getDeclaredField( "dataTimestamp" );
            _dataTimestamp.setAccessible( true );
        }
        catch ( final Exception e )
        {
            _dataTimestamp = null;
        }
    }
}
