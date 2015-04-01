/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.cache;

import org.gridgain.grid.cache.*;

/**
 * TTL test with offheap.
 */
public class GridCacheTtlOffheapAtomicSelfTest extends GridCacheTtlOffheapAbstractSelfTest {

    /** {@inheritDoc} */
    @Override protected GridCacheAtomicityMode atomicityMode() {
        return GridCacheAtomicityMode.ATOMIC;
    }
}