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
package org.neo4j.kernel.impl.newapi;

import org.neo4j.internal.kernel.api.Token;
import org.neo4j.internal.kernel.api.exceptions.KernelException;
import org.neo4j.storageengine.api.StorageEngine;
import org.neo4j.storageengine.api.StoreReadLayer;

class KernelToken implements Token
{
    private final StoreReadLayer read;

    KernelToken( StorageEngine engine )
    {
        read = engine.storeReadLayer();
    }

    @Override
    public int labelGetOrCreateForName( String labelName ) throws KernelException
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    @Override
    public int propertyKeyGetOrCreateForName( String propertyKeyName ) throws KernelException
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    @Override
    public int relationshipTypeGetOrCreateForName( String relationshipTypeName ) throws KernelException
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    @Override
    public void labelCreateForName( String labelName, int id ) throws KernelException
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    @Override
    public void propertyKeyCreateForName( String propertyKeyName, int id ) throws KernelException
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    @Override
    public void relationshipTypeCreateForName( String relationshipTypeName, int id ) throws KernelException
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    @Override
    public int nodeLabel( String name )
    {
        return read.labelGetForName( name );
    }

    @Override
    public int relationshipType( String name )
    {
        return read.relationshipTypeGetForName( name );
    }

    @Override
    public int propertyKey( String name )
    {
        return read.propertyKeyGetForName( name );
    }
}
