/* 
 Copyright (C) GridGain Systems. All Rights Reserved.
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.util.ipc.shmem;

import org.gridgain.grid.*;
import org.jetbrains.annotations.*;

/**
 * Thrown when IPC operation (such as {@link GridIpcSharedMemorySpace#wait(long)})
 * has timed out.
 */
public class GridIpcSharedMemoryOperationTimedoutException extends GridException {
    /** */
    private static final long serialVersionUID = 0L;


    /**
     * Creates new exception with given error message.
     *
     * @param msg Error message.
     */
    public GridIpcSharedMemoryOperationTimedoutException(String msg) {
        super(msg);
    }

    /**
     * Creates new exception with given throwable as a cause and
     * source of error message.
     *
     * @param cause Non-null throwable cause.
     */
    public GridIpcSharedMemoryOperationTimedoutException(Throwable cause) {
        super(cause);
    }

    /**
     * Creates new exception with given error message and optional nested exception.
     *
     * @param msg Error message.
     * @param cause Optional nested exception (can be {@code null}).
     */
    public GridIpcSharedMemoryOperationTimedoutException(String msg, @Nullable Throwable cause) {
        super(msg, cause);
    }
}
