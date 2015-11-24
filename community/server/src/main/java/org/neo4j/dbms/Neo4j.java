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

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import org.neo4j.GraphDatabase;
import org.neo4j.dbms.internal.logging.LogService;
import org.neo4j.function.Consumer;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.helpers.NamedThreadFactory;
import org.neo4j.helpers.collection.MapUtil;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.GraphDatabaseAPI;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.info.JvmChecker;
import org.neo4j.kernel.info.JvmMetadataRepository;
import org.neo4j.logging.Log;
import org.neo4j.logging.LogProvider;
import org.neo4j.server.logging.JULBridge;
import org.neo4j.server.logging.JettyLogBridge;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.neo4j.kernel.impl.util.Charsets.UTF_8;

public class Neo4j
{
    private static final int EXIT_SUCCESS = 0;
    private static final int EXIT_GENERAL_FAILURE = 1;

    public static void main( String[] args ) throws IOException
    {
        JvmChecker jvmChecker = new JvmChecker( new JvmMetadataRepository() );
        PrintWriter stdout = new PrintWriter( new OutputStreamWriter( System.out, UTF_8 ) );
        PrintWriter stderr = new PrintWriter( new OutputStreamWriter( System.err, UTF_8 ), true );
        FileSystemAbstraction fileSystem = new DefaultFileSystemAbstraction();
        DatabaseManagementSystem dbms = new DatabaseManagementSystem()
        {
            @Override
            public GraphDatabaseService addDatabase( Path dbPath, Config config )
            {
                return null;
            }

            @Override
            public boolean removeDatabase( GraphDatabaseService gdb )
            {
                return false;
            }
        };
        Consumer<Runnable> addShutdownHook = new Consumer<Runnable>()
        {
            @Override
            public void accept( Runnable runnable )
            {
                Runtime.getRuntime().addShutdownHook( new Thread( runnable ) );
            }
        };
        Neo4j neo4j = new Neo4j( jvmChecker, stdout, stderr, fileSystem, dbms, addShutdownHook );
        System.exit( neo4j.start( args ) );
    }

    private final JvmChecker jvmChecker;
    private final PrintWriter stdout;
    private final PrintWriter stderr;
    private final FileSystemAbstraction fileSystem;
    private final DatabaseManagementSystem dbms;
    private final Consumer<Runnable> addShutdownHook;
    private final ExecutorService executor;
    private final Options options;

    private static final String USAGE = "neo4j [-h] [-f] conffile";

    Neo4j(
            JvmChecker jvmChecker,
            PrintWriter stdout,
            PrintWriter stderr,
            FileSystemAbstraction fileSystem,
            DatabaseManagementSystem dbms,
            Consumer<Runnable> addShutdownHook )
    {
        this.jvmChecker = jvmChecker;
        this.stdout = stdout;
        this.stderr = stderr;
        this.fileSystem = fileSystem;
        this.dbms = dbms;
        this.addShutdownHook = addShutdownHook;
        this.executor = Executors.newCachedThreadPool( NamedThreadFactory.daemon( "Neo4j" ) );
        this.options = new Options();
        // Note: these are the options for the Java process, and not what the user would typically see.
        // The wrapper script that launches this process will have different options.
        options.addOption( "h", "help", false, "Display this help text" );
        options.addOption( "f", "foreground", false, "Output DBMS logs to stdout rather than a log (otherwise stdout is closed after startup)" );
    }

    int start( String[] args )
    {
        CommandLine line;
        try
        {
            line = parseArguments( options, args );
        } catch ( ParseException e )
        {
            stderr.println( e.getMessage() );
            printHelp( stderr, options );
            return EXIT_GENERAL_FAILURE;
        }

        if ( line.hasOption( "h" ) )
        {
            printHelp( stdout, options );
            return EXIT_SUCCESS;
        }

        String[] remainingArgs = line.getArgs();
        if ( remainingArgs.length < 1 || remainingArgs[0] == null || remainingArgs[0].isEmpty() )
        {
            stderr.println( "No configuration file specified" );
            printHelp( stderr, options );
            return EXIT_GENERAL_FAILURE;
        }
        File configFile = new File( remainingArgs[0] );

        // load config
        Config config;
        try
        {
            config = loadConfig( configFile );
        } catch ( Exception e )
        {
            stderr.println( "Reading configuration failed: " + e.getMessage() );
            return EXIT_GENERAL_FAILURE;
        }

        // start logging
        boolean foreground = line.hasOption( "f" );
        final LogService logService;
        try
        {
            logService = openLog( config, foreground );
        } catch ( IOException e )
        {
            stderr.println( "Failed to open output log file: " + e.getMessage() );
            return EXIT_GENERAL_FAILURE;
        }
        final LogProvider userLogProvider = logService.getUserLogProvider();
        redirectLoggingFrameworks( userLogProvider );
        final Log log = userLogProvider.getLog( getClass() );

        // check JVM
        jvmChecker.checkJvmCompatibilityAndIssueWarning( log );

        // register configured database
        registerDatabase( config, userLogProvider );

        // start server

        log.info( "Neo4j startup complete" );
        logService.startupCompleted();

        addShutdownHook.accept( new Runnable()
        {
            @Override
            public void run()
            {
                log.info( "Neo4j shutdown complete" );
                logService.shutdown();
            }
        } );

        return EXIT_SUCCESS;
    }

    private CommandLine parseArguments( Options options, String[] args ) throws ParseException
    {
        CommandLineParser parser = new GnuParser();
        return parser.parse( options, args );
    }

    private static void printHelp( PrintWriter out, Options options )
    {
        new HelpFormatter().printHelp( out, HelpFormatter.DEFAULT_WIDTH, USAGE, null, options, HelpFormatter.DEFAULT_LEFT_PAD, HelpFormatter.DEFAULT_DESC_PAD, null );
        out.flush();
    }

    private Config loadConfig( File configFile ) throws IOException
    {
        return new Config( MapUtil.load( fileSystem.openAsInputStream( configFile ) ) );
    }

    private LogService openLog( Config config, boolean foreground ) throws IOException
    {
        File logFile = config.get( Settings.dbms_log_name );
        long rotationThreshold = config.get( Settings.dbms_log_rotation_threshold );
        int rotationDelay = config.get( Settings.dbms_log_rotation_delay );
        int maxArchives = config.get( Settings.dbms_log_max_archives );
        return new LogService( stdout, foreground, fileSystem, logFile, rotationThreshold, rotationDelay, maxArchives, executor );
    }

    private static void redirectLoggingFrameworks( LogProvider userLogProvider )
    {
        JULBridge.resetJUL();
        Logger.getLogger( "" ).setLevel( Level.WARNING );
        JULBridge.forwardTo( userLogProvider );
        JettyLogBridge.setLogProvider( userLogProvider );
    }

    private void registerDatabase( Config config, LogProvider userLogProvider )
    {
        dbms.addDatabase(  );
    }
}
