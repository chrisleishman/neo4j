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

import org.neo4j.graphdb.config.Setting;

import java.io.File;

import static org.neo4j.helpers.Settings.BYTES;
import static org.neo4j.helpers.Settings.INTEGER;
import static org.neo4j.helpers.Settings.PATH;
import static org.neo4j.helpers.Settings.max;
import static org.neo4j.helpers.Settings.min;
import static org.neo4j.helpers.Settings.setting;

public interface Settings
{
    Setting<File> dbms_log_name = setting( "dbms.log.name", PATH, "log/neo4j.log" );
    Setting<Long> dbms_log_rotation_threshold = setting( "dbms.log.rotation_threshold", BYTES, "20m", min( 0L ), max( Long.MAX_VALUE ) );
    Setting<Integer> dbms_log_rotation_delay = setting( "dbms.log.rotation_threshold", INTEGER, "300", min( 0 ), max( Integer.MAX_VALUE ) );
    Setting<Integer> dbms_log_max_archives = setting( "dbms.log.max_archives", INTEGER, "7", min( 1 ) );
}
