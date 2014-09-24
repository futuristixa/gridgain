/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.cache.distributed.near;

import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.kernal.processors.cache.*;
import org.gridgain.grid.spi.discovery.tcp.*;
import org.gridgain.grid.spi.discovery.tcp.ipfinder.vm.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.testframework.junits.common.*;

import static org.gridgain.grid.cache.GridCacheMode.*;
import static org.gridgain.grid.cache.GridCacheAtomicityMode.*;
import static org.gridgain.grid.cache.GridCacheDistributionMode.*;
import static org.gridgain.grid.cache.GridCachePreloadMode.*;
import static org.gridgain.grid.cache.GridCacheWriteSynchronizationMode.*;

import java.util.*;

/**
 * Test clear operation in NEAR_PARTITIONED transactional cache.
 */
@SuppressWarnings("unchecked")
public class GridCacheNearPartitionedClearSelfTest extends GridCommonAbstractTest {
    /** Grid count. */
    private static final int GRID_CNT = 3;

    /** Backup count. */
    private static final int BACKUP_CNT = 1;

    /** Cache name. */
    private static final String CACHE_NAME = "cache";

    /** Shared IP finder. */
    private static final GridTcpDiscoveryVmIpFinder IP_FINDER = new GridTcpDiscoveryVmIpFinder(true);

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        startGrids(GRID_CNT);
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        G.stopAll(true);
    }

    /** {@inheritDoc} */
    @Override protected GridConfiguration getConfiguration(String gridName) throws Exception {
        GridConfiguration cfg = super.getConfiguration(gridName);

        cfg.setLocalHost("127.0.0.1");

        GridTcpDiscoverySpi discoSpi = new GridTcpDiscoverySpi();

        discoSpi.setIpFinder(IP_FINDER);

        cfg.setDiscoverySpi(discoSpi);

        GridCacheConfiguration ccfg = new GridCacheConfiguration();

        ccfg.setName(CACHE_NAME);
        ccfg.setCacheMode(PARTITIONED);
        ccfg.setAtomicityMode(TRANSACTIONAL);
        ccfg.setDistributionMode(NEAR_PARTITIONED);
        ccfg.setPreloadMode(SYNC);
        ccfg.setWriteSynchronizationMode(FULL_SYNC);
        ccfg.setBackups(BACKUP_CNT);
        ccfg.setStore(new GridCacheGenericTestStore<>());

        cfg.setCacheConfiguration(ccfg);

        return cfg;
    }

    /**
     * Test clear.
     *
     * @throws Exception If failed.
     */
    public void testClear() throws Exception {
        GridCache cache = cacheForIndex(0);

        int key = primaryKey(cache);

        cache.putx(key, 1);
        cache.clear(key);

        for (int i = 0; i < GRID_CNT; i++) {
            GridCache cache0 = cacheForIndex(i);

            cache0.removeAll();

            assert cache0.isEmpty();
        }

        cache.putx(key, 1);
        cache.clear(key);

        assertEquals(0, cache.size());
    }

    /**
     * Gets primary key for the given cache.
     *
     * @param cache Cache.
     * @return Primary key.
     * @throws Exception If failed.
     */
    private int primaryKey(GridCache cache) throws Exception {
        GridNode locNode = cache.gridProjection().grid().localNode();

        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            if (cache.affinity().isPrimary(locNode, i))
                return i;
        }

        throw new Exception("Cannot determine affinity key.");
    }

    /**
     * Gets cache for the node with the given index.
     *
     * @param idx Index.
     * @return Cache.
     */
    private GridCache cacheForIndex(int idx) {
        return grid(idx).cache(CACHE_NAME);
    }
}
