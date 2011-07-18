package org.apache.cassandra.io.sstable;
/*
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 */


import static org.apache.cassandra.Util.addMutation;
import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutionException;

import org.apache.cassandra.Util;
import org.junit.Test;

import org.apache.cassandra.CleanupHelper;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.columniterator.IdentityQueryFilter;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.db.filter.IFilter;
import org.apache.cassandra.db.filter.QueryPath;
import org.apache.cassandra.db.marshal.LongType;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.streaming.OperationType;
import org.apache.cassandra.thrift.IndexClause;
import org.apache.cassandra.thrift.IndexExpression;
import org.apache.cassandra.thrift.IndexOperator;
import org.apache.cassandra.utils.ByteBufferUtil;

public class SSTableWriterTest extends CleanupHelper {

    @Test
    public void testRecoverAndOpen() throws IOException, ExecutionException, InterruptedException
    {
        // add data via the usual write path
        RowMutation rm = new RowMutation("Keyspace1", ByteBufferUtil.bytes("k1"));
        rm.add(new QueryPath("Indexed1", null, ByteBufferUtil.bytes("birthdate")), ByteBufferUtil.bytes(1L), 0);
        rm.apply();
        
        // and add an sstable outside the right path (as if via streaming)
        Map<String, ColumnFamily> entries = new HashMap<String, ColumnFamily>();
        ColumnFamily cf;
        // "k2"
        cf = ColumnFamily.create("Keyspace1", "Indexed1");        
        cf.addColumn(new Column(ByteBufferUtil.bytes("birthdate"), ByteBufferUtil.bytes(1L), 0));
        cf.addColumn(new Column(ByteBufferUtil.bytes("anydate"), ByteBufferUtil.bytes(1L), 1234L));
        entries.put("k2", cf);        
        
        // "k3"
        cf = ColumnFamily.create("Keyspace1", "Indexed1");        
        cf.addColumn(new Column(ByteBufferUtil.bytes("anydate"), ByteBufferUtil.bytes(1L), 0));
        entries.put("k3", cf);        
        
        SSTableReader orig = SSTableUtils.prepare().cf("Indexed1").write(entries);        

        // whack the index to trigger the recover
        FileUtils.deleteWithConfirm(orig.descriptor.filenameFor(Component.PRIMARY_INDEX));
        FileUtils.deleteWithConfirm(orig.descriptor.filenameFor(Component.FILTER));

        SSTableReader sstr = CompactionManager.instance.submitSSTableBuild(orig.descriptor, OperationType.AES).get();
        assert sstr != null;

        // ensure max timestamp is captured during rebuild
        assert sstr.getMaxTimestamp() == 1234L;

        ColumnFamilyStore cfs = Table.open("Keyspace1").getColumnFamilyStore("Indexed1");
        cfs.addSSTable(sstr);
        cfs.buildSecondaryIndexes(cfs.getSSTables(), cfs.getIndexedColumns());
        
        IndexExpression expr = new IndexExpression(ByteBufferUtil.bytes("birthdate"), IndexOperator.EQ, ByteBufferUtil.bytes(1L));
        IndexClause clause = new IndexClause(Arrays.asList(expr), ByteBufferUtil.EMPTY_BYTE_BUFFER, 100);
        IFilter filter = new IdentityQueryFilter();
        IPartitioner p = StorageService.getPartitioner();
        Range range = new Range(p.getMinimumToken(), p.getMinimumToken());
        List<Row> rows = cfs.scan(clause, range, filter);
        
        assertEquals("IndexExpression should return two rows on recoverAndOpen", 2, rows.size());
        assertTrue("First result should be 'k1'",ByteBufferUtil.bytes("k1").equals(rows.get(0).key.key));
    }

    @Test
    public void testRecoverAndOpenSuperColumn() throws IOException, ExecutionException, InterruptedException
    {
        // add data via the usual write path
        RowMutation rm = new RowMutation("Keyspace1", ByteBufferUtil.bytes("k1"));
        ByteBuffer superColumnName = ByteBufferUtil.bytes("TestSuperColumn1");
        rm.add(new QueryPath("Super1", superColumnName, ByteBufferUtil.bytes("birthdate")), ByteBufferUtil.bytes(1L), 0);
        rm.apply();

        // and add an sstable outside the right path (as if via streaming)
        Map<String, ColumnFamily> entries = new HashMap<String, ColumnFamily>();
        ColumnFamily cf = ColumnFamily.create("Keyspace1", "Super1");
        SuperColumn superColumn = new SuperColumn(superColumnName, LongType.instance);
        superColumn.addColumn(new Column(ByteBufferUtil.bytes("city"), ByteBufferUtil.bytes(1L), 4321L));
        cf.addColumn(superColumn);
        entries.put("k2", cf);

        cf = ColumnFamily.create("Keyspace1", "Super1");
        superColumn = new SuperColumn(ByteBufferUtil.bytes("TestSuperColumn2"), LongType.instance);
        superColumn.addColumn(new Column(ByteBufferUtil.bytes("country"), ByteBufferUtil.bytes(1L), 1234L));
        superColumn.addColumn(new Column(ByteBufferUtil.bytes("address"), ByteBufferUtil.bytes(1L), 0L));
        cf.addColumn(superColumn);
        entries.put("k3", cf);

        SSTableReader orig = SSTableUtils.prepare().cf("Super1").write(entries);

        // whack the index to trigger the recover
        FileUtils.deleteWithConfirm(orig.descriptor.filenameFor(Component.PRIMARY_INDEX));
        FileUtils.deleteWithConfirm(orig.descriptor.filenameFor(Component.FILTER));

        SSTableReader sstr = CompactionManager.instance.submitSSTableBuild(orig.descriptor, OperationType.AES).get();

        // ensure max timestamp is captured during rebuild
        assert sstr.getMaxTimestamp() == 4321L;
    }
    
    @Test
    public void testSuperColumnMaxTimestamp() throws IOException, ExecutionException, InterruptedException
    {
        ColumnFamilyStore store = Table.open("Keyspace1").getColumnFamilyStore("Super1");
        RowMutation rm;
        DecoratedKey dk = Util.dk("key1");

        // add data
        rm = new RowMutation("Keyspace1", dk.key);
        addMutation(rm, "Super1", "SC1", 1, "val1", 0);
        rm.apply();
        store.forceBlockingFlush();
        
        validateMinTimeStamp(store.getSSTables(), 0);

        // remove
        rm = new RowMutation("Keyspace1", dk.key);
        rm.delete(new QueryPath("Super1", ByteBufferUtil.bytes("SC1")), 1);
        rm.apply();
        store.forceBlockingFlush();
        
        validateMinTimeStamp(store.getSSTables(), 0);

        CompactionManager.instance.performMaximal(store);
        assertEquals(1, store.getSSTables().size());
        validateMinTimeStamp(store.getSSTables(), 1);
    }

    private void validateMinTimeStamp(Collection<SSTableReader> ssTables, int timestamp)
    {
        for (SSTableReader ssTable : ssTables)
            assertTrue(ssTable.getMaxTimestamp() >= timestamp);
    }
}
