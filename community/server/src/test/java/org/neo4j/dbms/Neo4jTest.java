/*
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.dbms;

import org.junit.Before;
import org.junit.Test;
import org.neo4j.function.Consumer;
import org.neo4j.graphdb.mockfs.EphemeralFileSystemAbstraction;
import org.neo4j.kernel.info.JvmChecker;
import org.neo4j.kernel.info.JvmMetadataRepository;
import org.neo4j.logging.Log;
import org.neo4j.server.web.ServerInternalSettings;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.neo4j.kernel.impl.util.Charsets.UTF_8;

public class Neo4jTest
{
    private StringWriter stdout;
    private StringWriter stderr;
    private EphemeralFileSystemAbstraction fs;
    private Consumer<Runnable> addShutdownHook;
    private List<Runnable> shutdownHooks = new ArrayList<>();
    private Neo4j neo4j;

    @Before
    public void setUp()
    {
        JvmChecker jvmChecker = new JvmChecker( new JvmMetadataRepository() );
        this.stdout = new StringWriter();
        this.stderr = new StringWriter();
        this.fs = new EphemeralFileSystemAbstraction();
        this.addShutdownHook = new Consumer<Runnable>()
        {
            @Override
            public void accept( Runnable runnable )
            {
                shutdownHooks.add( runnable );
            }
        };
        this.neo4j = new Neo4j( jvmChecker, new PrintWriter( stdout ), new PrintWriter( stderr ), fs, addShutdownHook );
    }

    @Test
    public void shouldOutputUsageOnInvalidArguments() throws IOException
    {
        // When
        int result = this.neo4j.start( new String[]{"-invalid"} );

        // Then
        assertThat( stdout.toString(), is( "" ) );
        String output = stderr.toString();
        assertThat( output, containsString( "Unrecognized option:" ) );
        assertThat( output, containsString( "usage: neo4j" ) );
        assertThat( result, is( 1 ) );
    }

    @Test
    public void shouldOutputHelpIfRequested() throws IOException
    {
        // When
        int result = this.neo4j.start( new String[]{"-h"} );

        // Then
        String output = stdout.toString();
        assertThat( output, containsString( "usage: neo4j" ) );
        assertThat( stderr.toString(), is( "" ) );
        assertThat( result, is( 0 ) );
    }

    @Test
    public void shouldOutputUsageWhenMissingConfigFileArgument() throws IOException
    {
        // When
        int result = this.neo4j.start( new String[]{} );

        // Then
        assertThat( stdout.toString(), is( "" ) );
        String output = stderr.toString();
        assertThat( output, containsString( "No configuration file specified" ) );
        assertThat( output, containsString( "usage: neo4j" ) );
        assertThat( result, is( 1 ) );
    }

    @Test
    public void shouldReturnErrorWhenMissingConfigFile() throws IOException
    {
        // When
        int result = this.neo4j.start( new String[]{"/invalid/config/file"} );

        // Then
        assertThat( stdout.toString(), is( "" ) );
        String output = stderr.toString();
        assertThat( output, containsString( "Reading configuration failed: /invalid/config/file (No such file or directory)" ) );
        assertThat( result, is( 1 ) );
    }

    @Test
    public void shouldReturnErrorWhenInvalidConfigFile() throws IOException
    {
        // Given
        writeFile( new File( "/etc/config.properties" ), "invalid \\ug\r\n" );

        // When
        int result = this.neo4j.start( new String[]{"/etc/config.properties"} );

        // Then
        assertThat( stdout.toString(), is( "" ) );
        String output = stderr.toString();
        assertThat( output, containsString( "Reading configuration failed: Malformed \\uxxxx encoding." ) );
        assertThat( result, is( 1 ) );
    }

    @Test
    public void shouldReturnErrorWhenLogFileCannotBeOpened() throws IOException
    {
        // Given
        writeFile( new File( "/etc/config.properties" ), ServerInternalSettings.dbms_log_name.name() + "=/output\r\n" );
        fs.mkdir( new File( "/output" ) );

        // When
        int result = this.neo4j.start( new String[]{"/etc/config.properties"} );

        // Then
        assertThat( stdout.toString(), is( "" ) );
        String output = stderr.toString();
        assertThat( output, containsString( "Failed to open output log file: /output (Is a directory)" ) );
        assertThat( result, is( 1 ) );
    }

    @Test
    public void shouldReportJVMErrors() throws IOException
    {
        // Given
        writeFile( new File( "/etc/config.properties" ), "" );
        JvmChecker stubChecker = new JvmChecker( new JvmMetadataRepository() )
        {
            @Override
            public void checkJvmCompatibilityAndIssueWarning( Log log )
            {
                log.warn( "A jvm related error" );
            }
        };
        Neo4j neo4j = new Neo4j( stubChecker, new PrintWriter( stdout ), new PrintWriter( stderr ), fs, addShutdownHook );

        // When
        int result = neo4j.start( new String[]{"-f", "/etc/config.properties"} );

        // Then
        assertThat( stdout.toString(), containsString( "WARN  A jvm related error" ) );
        assertThat( stderr.toString(), is( "" ) );
        assertThat( result, is( 0 ) );
    }

    private void writeFile( File file, String content ) throws IOException
    {
        fs.mkdir( file.getParentFile() );
        OutputStream outputStream = fs.openAsOutputStream( file, false );
        outputStream.write( content.getBytes( UTF_8 ) );
        outputStream.close();
    }
}
