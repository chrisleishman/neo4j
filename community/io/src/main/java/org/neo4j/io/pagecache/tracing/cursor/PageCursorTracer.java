/*
 * Copyright (c) 2002-2017 "Neo Technology,"
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
package org.neo4j.io.pagecache.tracing.cursor;

import org.neo4j.io.pagecache.PageSwapper;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.io.pagecache.tracing.PinEvent;

/**
 * Event tracer for page cursors.
 *
 * Performs event tracing related to particular page cursors and expose simple counters around them.
 * Since events of each particular page cursor are part of whole page cache events, each particular page cursor
 * tracer will eventually report them to global page cache counters/tracers.
 *
 * @see PageCursorTracer
 */
public interface PageCursorTracer extends PageCursorCounters
{

    PageCursorTracer NULL = new PageCursorTracer()
    {
        @Override
        public long faults()
        {
            return 0;
        }

        @Override
        public long pins()
        {
            return 0;
        }

        @Override
        public long unpins()
        {
            return 0;
        }

        @Override
        public long bytesRead()
        {
            return 0;
        }

        @Override
        public long evictions()
        {
            return 0;
        }

        @Override
        public long evictionExceptions()
        {
            return 0;
        }

        @Override
        public long bytesWritten()
        {
            return 0;
        }

        @Override
        public long flushes()
        {
            return 0;
        }

        @Override
        public PinEvent beginPin( boolean writeLock, long filePageId, PageSwapper swapper )
        {
            return PinEvent.NULL;
        }

        @Override
        public void init( PageCacheTracer tracer )
        {

        }

        @Override
        public void reportEvents()
        {

        }
    };

    PinEvent beginPin( boolean writeLock, long filePageId, PageSwapper swapper );

    void init( PageCacheTracer tracer );

    void reportEvents();
}
