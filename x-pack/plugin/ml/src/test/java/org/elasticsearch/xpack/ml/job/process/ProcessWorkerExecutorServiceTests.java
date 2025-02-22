/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.ml.job.process;

import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.After;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;

public class ProcessWorkerExecutorServiceTests extends ESTestCase {

    private static final String TEST_PROCESS = "test";
    private static final int QUEUE_SIZE = 100;

    private ThreadPool threadPool = new TestThreadPool("AutodetectWorkerExecutorServiceTests");

    @After
    public void stopThreadPool() {
        terminate(threadPool);
    }

    public void testAutodetectWorkerExecutorService_SubmitAfterShutdown() {
        ProcessWorkerExecutorService executor = createExecutorService();

        threadPool.generic().execute(() -> executor.start());
        executor.shutdown();
        expectThrows(EsRejectedExecutionException.class, () -> executor.execute(() -> {}));
    }

    public void testAutodetectWorkerExecutorService_TasksNotExecutedCallHandlerOnShutdown() throws Exception {
        ProcessWorkerExecutorService executor = createExecutorService();

        CountDownLatch latch = new CountDownLatch(1);

        Future<?> executorFinished = threadPool.generic().submit(() -> executor.start());

        // run a task that will block while the others are queued up
        executor.execute(() -> {
            try {
                latch.await();
            } catch (InterruptedException e) {
            }
        });

        AtomicBoolean runnableShouldNotBeCalled = new AtomicBoolean(false);
        executor.execute(() -> runnableShouldNotBeCalled.set(true));

        AtomicInteger onFailureCallCount = new AtomicInteger();
        AtomicInteger doRunCallCount = new AtomicInteger();
        for (int i=0; i<2; i++) {
            executor.execute(new AbstractRunnable() {
                @Override
                public void onFailure(Exception e) {
                    onFailureCallCount.incrementAndGet();
                }

                @Override
                protected void doRun() {
                    doRunCallCount.incrementAndGet();
                }
            });
        }

        // now shutdown
        executor.shutdown();
        latch.countDown();
        executorFinished.get();

        assertFalse(runnableShouldNotBeCalled.get());
        // the AbstractRunnables should have had their callbacks called
        assertEquals(2, onFailureCallCount.get());
        assertEquals(0, doRunCallCount.get());
    }

    public void testAutodetectWorkerExecutorServiceDoesNotSwallowErrors() {
        ProcessWorkerExecutorService executor = createExecutorService();
        if (randomBoolean()) {
            executor.submit(() -> {
                throw new Error("future error");
            });
        } else {
            executor.execute(() -> {
                throw new Error("future error");
            });
        }
        Error e = expectThrows(Error.class, () -> executor.start());
        assertThat(e.getMessage(), containsString("future error"));
    }

    private ProcessWorkerExecutorService createExecutorService() {
        return new ProcessWorkerExecutorService(threadPool.getThreadContext(), TEST_PROCESS, QUEUE_SIZE);
    }
}
