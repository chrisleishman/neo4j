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
package org.neo4j.dbms.internal.logging;

import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.logging.DuplicatingLogProvider;
import org.neo4j.logging.FormattedLogProvider;
import org.neo4j.logging.LogProvider;
import org.neo4j.logging.RotatingFileOutputStreamSupplier;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.concurrent.Executor;

import static org.neo4j.io.file.Files.createOrOpenAsOuputStream;

/**
 * Logging service for the DBMS. Supplies the user {@link LogProvider} and has methods that
 * must be called when server startup has completed, and at the conclusion of shutdown.
 */
public class LogService
{
    private static final Runnable NOOP = new Runnable()
    {
        @Override
        public void run()
        {
        }
    };

    private final LogProvider userLogProvider;
    private final Runnable onStartupCompleted;
    private final Runnable onShutdown;

    public LogService( final PrintWriter stdout, boolean foreground, FileSystemAbstraction fileSystem, File logFile, long rotationThreshold, int rotationDelay, int maxArchives, Executor rotationExecutor ) throws IOException
    {
        final LogProvider stdoutLogProvider = FormattedLogProvider.withoutRenderingContext().toPrintWriter( stdout );

        if ( foreground || logFile == null )
        {

            this.userLogProvider = stdoutLogProvider;
            this.onStartupCompleted = NOOP;
            this.onShutdown = NOOP;
            return;
        }

        final FormattedLogProvider fileLogProvider;

        if ( rotationThreshold == 0 )
        {
            final OutputStream outputStream = createOrOpenAsOuputStream( fileSystem, logFile, true );
            fileLogProvider = FormattedLogProvider.withoutRenderingContext().toOutputStream( outputStream );
            this.onShutdown = new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        outputStream.close();
                    } catch ( IOException e )
                    {
                        // ignore
                    }
                }
            };
        } else
        {
            final RotatingFileOutputStreamSupplier rotatingSupplier = new RotatingFileOutputStreamSupplier( fileSystem, logFile,
                    rotationThreshold, rotationDelay, maxArchives, rotationExecutor );
            fileLogProvider = FormattedLogProvider.withoutRenderingContext().toOutputStream( rotatingSupplier );
            this.onShutdown = new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        rotatingSupplier.close();
                    } catch ( IOException e )
                    {
                        // ignore
                    }
                }
            };
        }

        final DuplicatingLogProvider logProvider = new DuplicatingLogProvider( stdoutLogProvider, fileLogProvider );
        this.userLogProvider = logProvider;
        this.onStartupCompleted = new Runnable()
        {
            @Override
            public void run()
            {
                logProvider.remove( stdoutLogProvider );
                stdout.close();
            }
        };
    }

    public LogProvider getUserLogProvider()
    {
        return this.userLogProvider;
    }

    public void startupCompleted()
    {
        this.onStartupCompleted.run();
    }

    public void shutdown()
    {
        this.onShutdown.run();
    }
}
