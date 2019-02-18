/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
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
package org.neo4j.kernel.impl.storemigration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import org.neo4j.common.ProgressReporter;
import org.neo4j.common.Service;
import org.neo4j.configuration.Config;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Transaction;
import org.neo4j.index.internal.gbptree.RecoveryCleanupWorkCollector;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.fs.OpenMode;
import org.neo4j.io.fs.StoreChannel;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.pagecache.IOLimiter;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.api.labelscan.LabelScanReader;
import org.neo4j.kernel.api.labelscan.LabelScanWriter;
import org.neo4j.kernel.impl.api.scan.FullStoreChangeStream;
import org.neo4j.kernel.impl.core.TokenCreator;
import org.neo4j.kernel.impl.index.labelscan.NativeLabelScanStore;
import org.neo4j.kernel.impl.store.InvalidIdGeneratorException;
import org.neo4j.kernel.impl.store.MetaDataStore;
import org.neo4j.kernel.impl.store.MetaDataStore.Position;
import org.neo4j.kernel.impl.store.format.standard.StandardV3_4;
import org.neo4j.kernel.impl.store.format.standard.StandardV4_0;
import org.neo4j.kernel.lifecycle.Lifespan;
import org.neo4j.kernel.monitoring.Monitors;
import org.neo4j.storageengine.api.NodeLabelUpdate;
import org.neo4j.storageengine.api.StorageEngineFactory;
import org.neo4j.test.TestGraphDatabaseFactory;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.pagecache.PageCacheExtension;
import org.neo4j.test.rule.TestDirectory;

import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.neo4j.collection.PrimitiveLongCollections.countAndClose;
import static org.neo4j.kernel.impl.store.MetaDataStore.versionStringToLong;

@PageCacheExtension
class NativeLabelScanStoreMigratorTest
{
    @Inject
    private TestDirectory testDirectory;
    @Inject
    private FileSystemAbstraction fileSystem;
    @Inject
    private PageCache pageCache;

    private File storeDir;
    private File nativeLabelIndex;
    private DatabaseLayout migrationLayout;
    private File luceneLabelScanStore;
    private final ProgressReporter progressReporter = mock( ProgressReporter.class );
    private NativeLabelScanStoreMigrator indexMigrator;
    private DatabaseLayout databaseLayout;

    @BeforeEach
    void setUp() throws Exception
    {
        databaseLayout = testDirectory.databaseLayout();
        storeDir = databaseLayout.databaseDirectory();
        nativeLabelIndex = databaseLayout.labelScanStore();
        migrationLayout = testDirectory.databaseLayout( "migrationDir" );
        luceneLabelScanStore = testDirectory.databaseDir().toPath().resolve( Paths.get( "schema", "label", "lucene" ) ).toFile();

        StorageEngineFactory storageEngineFactory = StorageEngineFactory.selectStorageEngine( Service.loadAll( StorageEngineFactory.class ) );
        indexMigrator = new NativeLabelScanStoreMigrator( fileSystem, pageCache, Config.defaults(), storageEngineFactory );
        fileSystem.mkdirs( luceneLabelScanStore );
    }

    @Test
    void skipMigrationIfNativeIndexExist() throws Exception
    {
        ByteBuffer sourceBuffer = writeFile( nativeLabelIndex, new byte[]{1, 2, 3} );

        indexMigrator.migrate( databaseLayout, migrationLayout, progressReporter, StandardV3_4.STORE_VERSION, StandardV3_4.STORE_VERSION );
        indexMigrator.moveMigratedFiles( migrationLayout, databaseLayout, StandardV3_4.STORE_VERSION, StandardV3_4.STORE_VERSION );

        ByteBuffer resultBuffer = readFileContent( nativeLabelIndex, 3 );
        assertEquals( sourceBuffer, resultBuffer );
        assertTrue( fileSystem.fileExists( luceneLabelScanStore ) );
    }

    @Test
    void failMigrationWhenNodeIdFileIsBroken()
    {
        assertThrows( InvalidIdGeneratorException.class, () ->
        {
            prepareEmpty34Database();
            File nodeIdFile = databaseLayout.idNodeStore();
            writeFile( nodeIdFile, new byte[]{1, 2, 3} );

            indexMigrator.migrate( databaseLayout, migrationLayout, progressReporter, StandardV3_4.STORE_VERSION, StandardV3_4.STORE_VERSION );
        } );
    }

    @Test
    void clearMigrationDirFromAnyLabelScanStoreBeforeMigrating() throws Exception
    {
        // given
        prepareEmpty34Database();
        initializeNativeLabelScanStoreWithContent( migrationLayout );
        File toBeDeleted = migrationLayout.labelScanStore();
        assertTrue( fileSystem.fileExists( toBeDeleted ) );

        // when
        indexMigrator.migrate( databaseLayout, migrationLayout, progressReporter, StandardV3_4.STORE_VERSION, StandardV3_4.STORE_VERSION );

        // then
        assertNoContentInNativeLabelScanStore( migrationLayout );
    }

    @Test
    void luceneLabelIndexRemovedAfterSuccessfulMigration() throws IOException
    {
        prepareEmpty34Database();

        indexMigrator.migrate( databaseLayout, migrationLayout, progressReporter, StandardV3_4.STORE_VERSION, StandardV4_0.STORE_VERSION );
        indexMigrator.moveMigratedFiles( migrationLayout, databaseLayout, StandardV3_4.STORE_VERSION, StandardV4_0.STORE_VERSION );

        assertFalse( fileSystem.fileExists( luceneLabelScanStore ) );
    }

    @Test
    void moveCreatedNativeLabelIndexBackToStoreDirectory() throws IOException
    {
        prepareEmpty34Database();
        indexMigrator.migrate( databaseLayout, migrationLayout, progressReporter, StandardV3_4.STORE_VERSION, StandardV4_0.STORE_VERSION );
        File migrationNativeIndex = migrationLayout.labelScanStore();
        ByteBuffer migratedFileContent = writeFile( migrationNativeIndex, new byte[]{5, 4, 3, 2, 1} );

        indexMigrator.moveMigratedFiles( migrationLayout, databaseLayout, StandardV3_4.STORE_VERSION, StandardV4_0.STORE_VERSION );

        ByteBuffer movedNativeIndex = readFileContent( nativeLabelIndex, 5 );
        assertEquals( migratedFileContent, movedNativeIndex );
    }

    @Test
    void populateNativeLabelScanIndexDuringMigration() throws IOException
    {
        prepare34DatabaseWithNodes();
        indexMigrator.migrate( databaseLayout, migrationLayout, progressReporter, StandardV4_0.STORE_VERSION, StandardV4_0.STORE_VERSION );
        indexMigrator.moveMigratedFiles( migrationLayout, databaseLayout, StandardV3_4.STORE_VERSION, StandardV4_0.STORE_VERSION );

        try ( Lifespan lifespan = new Lifespan() )
        {
            NativeLabelScanStore labelScanStore = getNativeLabelScanStore( databaseLayout, true );
            lifespan.add( labelScanStore );
            for ( int labelId = 0; labelId < 10; labelId++ )
            {
                LabelScanReader labelScanReader = labelScanStore.newReader();
                int nodeCount = countAndClose( labelScanReader.nodesWithLabel( labelId ) );
                assertEquals( 1, nodeCount,
                        format( "Expected to see only one node for label %d but was %d.", labelId, nodeCount ) );
            }
        }
    }

    @Test
    void reportProgressOnNativeIndexPopulation() throws IOException
    {
        prepare34DatabaseWithNodes();
        indexMigrator.migrate( databaseLayout, migrationLayout, progressReporter, StandardV4_0.STORE_VERSION, StandardV4_0.STORE_VERSION );
        indexMigrator.moveMigratedFiles( migrationLayout, databaseLayout, StandardV3_4.STORE_VERSION, StandardV4_0.STORE_VERSION );

        verify( progressReporter ).start( 10 );
        verify( progressReporter, times( 10 ) ).progress( 1 );
    }

    private NativeLabelScanStore getNativeLabelScanStore( DatabaseLayout databaseLayout, boolean readOnly )
    {
        return new NativeLabelScanStore( pageCache, databaseLayout, fileSystem, FullStoreChangeStream.EMPTY, readOnly, new Monitors(),
                RecoveryCleanupWorkCollector.ignore() );
    }

    private void initializeNativeLabelScanStoreWithContent( DatabaseLayout databaseLayout ) throws IOException
    {
        try ( Lifespan lifespan = new Lifespan() )
        {
            NativeLabelScanStore nativeLabelScanStore = getNativeLabelScanStore( databaseLayout, false );
            lifespan.add( nativeLabelScanStore );
            try ( LabelScanWriter labelScanWriter = nativeLabelScanStore.newWriter() )
            {
                labelScanWriter.write( NodeLabelUpdate.labelChanges( 1, new long[0], new long[]{1} ) );
            }
            nativeLabelScanStore.force( IOLimiter.UNLIMITED );
        }
    }

    private void assertNoContentInNativeLabelScanStore( DatabaseLayout databaseLayout )
    {
        try ( Lifespan lifespan = new Lifespan() )
        {
            NativeLabelScanStore nativeLabelScanStore = getNativeLabelScanStore( databaseLayout, true );
            lifespan.add( nativeLabelScanStore );
            LabelScanReader labelScanReader = nativeLabelScanStore.newReader();
            int count = countAndClose( labelScanReader.nodesWithLabel( 1 ) );
            assertEquals( 0, count );
        }
    }

    private ByteBuffer writeFile( File file, byte[] content ) throws IOException
    {
        ByteBuffer sourceBuffer = ByteBuffer.wrap( content );
        storeFileContent( file, sourceBuffer );
        sourceBuffer.flip();
        return sourceBuffer;
    }

    private void prepare34DatabaseWithNodes()
    {
        GraphDatabaseService embeddedDatabase = new TestGraphDatabaseFactory().newEmbeddedDatabase( storeDir );
        try
        {
            try ( Transaction transaction = embeddedDatabase.beginTx() )
            {
                for ( int i = 0; i < 10; i++ )
                {
                    embeddedDatabase.createNode( Label.label( "label" + i ) );
                }
                transaction.success();
            }
        }
        finally
        {
            embeddedDatabase.shutdown();
        }
        fileSystem.deleteFile( nativeLabelIndex );
    }

    private void prepareEmpty34Database() throws IOException
    {
        new TestGraphDatabaseFactory().newEmbeddedDatabase( storeDir ).shutdown();
        fileSystem.deleteFile( nativeLabelIndex );
        MetaDataStore.setRecord( pageCache, databaseLayout.metadataStore(),
                Position.STORE_VERSION, versionStringToLong( StandardV3_4.STORE_VERSION ) );
    }

    private ByteBuffer readFileContent( File nativeLabelIndex, int length ) throws IOException
    {
        try ( StoreChannel storeChannel = fileSystem.open( nativeLabelIndex, OpenMode.READ ) )
        {
            ByteBuffer readBuffer = ByteBuffer.allocate( length );
            //noinspection StatementWithEmptyBody
            while ( readBuffer.hasRemaining() && storeChannel.read( readBuffer ) > 0 )
            {
                // read till the end of store channel
            }
            readBuffer.flip();
            return readBuffer;
        }
    }

    private void storeFileContent( File file, ByteBuffer sourceBuffer ) throws IOException
    {
        try ( StoreChannel storeChannel = fileSystem.create( file ) )
        {
            storeChannel.writeAll( sourceBuffer );
        }
    }

    private class SimpleTokenCreator implements TokenCreator
    {
        private final AtomicInteger next = new AtomicInteger();

        @Override
        public int createToken( String name )
        {
            return next.incrementAndGet();
        }
    }
}
