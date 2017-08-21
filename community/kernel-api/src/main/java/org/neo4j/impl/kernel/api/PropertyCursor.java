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
package org.neo4j.impl.kernel.api;

import java.util.regex.Pattern;

import org.neo4j.values.Value;
import org.neo4j.values.ValueGroup;
import org.neo4j.values.ValueWriter;

/**
 * Cursor for scanning the properties of a node or edge.
 */
public interface PropertyCursor extends Cursor
{
    /**
     * Find the first property with a key greater than or equal to the provided key.
     * <p>
     * Note that the default implementation of this method (and most likely any sane use of this method - regardless of
     * implementation) assumes that properties are ordered by the key.
     *
     * @param propertyKey
     *         the property key to search for.
     * @return {@code true} if a matching property was found, {@code false} if all properties within reach of this
     * cursor were exhausted without finding a matching property.
     */
    default boolean seek( int propertyKey )
    {
        while ( next() )
        {
            if ( propertyKey < propertyKey() )
            {
                return true;
            }
        }
        return false;
    }

    int propertyKey();

    ValueGroup propertyType();

    Value propertyValue();

    <E extends Exception> void writeTo( ValueWriter<E> target );

    // typed accessor methods

    boolean booleanValue();

    String stringValue();

    long longValue();

    double doubleValue();

    // Predicates methods that don't require de-serializing the value

    boolean valueEqualTo( long value );

    boolean valueEqualTo( double value );

    boolean valueEqualTo( String value );

    boolean valueMatches( Pattern regex );

    boolean valueGreaterThan( long number );

    boolean valueGreaterThan( double number );

    boolean valueLessThan( long number );

    boolean valueLessThan( double number );

    boolean valueGreaterThanOrEqualTo( long number );

    boolean valueGreaterThanOrEqualTo( double number );

    boolean valueLessThanOrEqualTo( long number );

    boolean valueLessThanOrEqualTo( double number );
}