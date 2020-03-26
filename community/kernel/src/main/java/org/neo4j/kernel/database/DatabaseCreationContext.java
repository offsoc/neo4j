/*
 * Copyright (c) 2002-2020 "Neo4j,"
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
package org.neo4j.kernel.database;

import java.util.function.Function;
import java.util.function.LongFunction;

import org.neo4j.common.DependencyResolver;
import org.neo4j.configuration.Config;
import org.neo4j.dbms.database.DatabaseConfig;
import org.neo4j.function.Factory;
import org.neo4j.internal.id.IdController;
import org.neo4j.internal.id.IdGeneratorFactory;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.fs.watcher.DatabaseLayoutWatcher;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.pagecache.IOLimiter;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.tracing.cursor.context.VersionContextSupplier;
import org.neo4j.kernel.api.procedure.GlobalProcedures;
import org.neo4j.kernel.availability.DatabaseAvailabilityGuard;
import org.neo4j.kernel.extension.ExtensionFactory;
import org.neo4j.kernel.impl.api.CommitProcessFactory;
import org.neo4j.kernel.impl.api.LeaseService;
import org.neo4j.kernel.impl.constraints.ConstraintSemantics;
import org.neo4j.kernel.impl.factory.AccessCapabilityFactory;
import org.neo4j.kernel.impl.factory.DatabaseInfo;
import org.neo4j.kernel.impl.locking.Locks;
import org.neo4j.kernel.impl.locking.StatementLocksFactory;
import org.neo4j.kernel.impl.query.QueryEngineProvider;
import org.neo4j.kernel.impl.transaction.log.checkpoint.StoreCopyCheckPointMutex;
import org.neo4j.kernel.impl.transaction.stats.DatabaseTransactionStats;
import org.neo4j.kernel.impl.util.collection.CollectionsFactorySupplier;
import org.neo4j.kernel.internal.event.GlobalTransactionEventListeners;
import org.neo4j.kernel.internal.locker.FileLockerService;
import org.neo4j.kernel.monitoring.tracing.Tracers;
import org.neo4j.logging.internal.DatabaseLogService;
import org.neo4j.memory.MemoryPools;
import org.neo4j.monitoring.DatabaseEventListeners;
import org.neo4j.monitoring.DatabaseHealth;
import org.neo4j.monitoring.Monitors;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.storageengine.api.StorageEngineFactory;
import org.neo4j.time.SystemNanoClock;
import org.neo4j.token.TokenHolders;

public interface DatabaseCreationContext
{
    NamedDatabaseId getNamedDatabaseId();

    DatabaseLayout getDatabaseLayout();

    Config getGlobalConfig();

    DatabaseConfig getDatabaseConfig();

    IdGeneratorFactory getIdGeneratorFactory();

    DatabaseLogService getDatabaseLogService();

    JobScheduler getScheduler();

    DependencyResolver getGlobalDependencies();

    TokenHolders getTokenHolders();

    Locks getLocks();

    StatementLocksFactory getStatementLocksFactory();

    GlobalTransactionEventListeners getTransactionEventListeners();

    FileSystemAbstraction getFs();

    DatabaseTransactionStats getTransactionStats();

    Factory<DatabaseHealth> getDatabaseHealthFactory();

    CommitProcessFactory getCommitProcessFactory();

    PageCache getPageCache();

    ConstraintSemantics getConstraintSemantics();

    Monitors getMonitors();

    Tracers getTracers();

    GlobalProcedures getGlobalProcedures();

    IOLimiter getIoLimiter();

    LongFunction<DatabaseAvailabilityGuard> getDatabaseAvailabilityGuardFactory();

    SystemNanoClock getClock();

    StoreCopyCheckPointMutex getStoreCopyCheckPointMutex();

    IdController getIdController();

    DatabaseInfo getDatabaseInfo();

    VersionContextSupplier getVersionContextSupplier();

    CollectionsFactorySupplier getCollectionsFactorySupplier();

    Iterable<ExtensionFactory<?>> getExtensionFactories();

    Function<DatabaseLayout,DatabaseLayoutWatcher> getWatcherServiceFactory();

    QueryEngineProvider getEngineProvider();

    DatabaseEventListeners getDatabaseEventListeners();

    StorageEngineFactory getStorageEngineFactory();

    FileLockerService getFileLockerService();

    AccessCapabilityFactory getAccessCapabilityFactory();

    LeaseService getLeaseService();

    DatabaseStartupController getStartupController();

    MemoryPools getMemoryPools();
}
