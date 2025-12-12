package com.gempukku.lotro.bots.rl.learning;

import com.gempukku.lotro.bots.rl.learning.semanticaction.SemanticAction;
import com.gempukku.lotro.logic.decisions.AwaitingDecision;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Unit tests for ReplayBuffer, focusing on thread safety for parallel game simulation.
 */
public class ReplayBufferTest {

    /**
     * Test basic functionality - single-threaded operations
     */
    @Test
    public void testBasicAddAndSize() {
        ReplayBuffer buffer = new ReplayBuffer(100);

        List<LearningStep> episode = createTestEpisode(5);
        buffer.addEpisode(episode);

        assertEquals(5, buffer.size());
    }

    /**
     * Test that buffer respects capacity limit (FIFO behavior)
     */
    @Test
    public void testCapacityLimit() {
        ReplayBuffer buffer = new ReplayBuffer(10);

        // Add 15 steps (should only keep last 10)
        buffer.addEpisode(createTestEpisode(15));

        assertEquals(10, buffer.size());
    }

    /**
     * Test concurrent adds from multiple threads
     * Simulates multiple games adding steps simultaneously
     */
    @Test
    public void testConcurrentAdds() throws InterruptedException {
        final ReplayBuffer buffer = new ReplayBuffer(10000);
        final int numThreads = 8;
        final int stepsPerThread = 100;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(numThreads);

        // Launch threads that will all add episodes simultaneously
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    List<LearningStep> episode = createTestEpisode(stepsPerThread);
                    buffer.addEpisode(episode);
                    completionLatch.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Start all threads at once
        startLatch.countDown();

        // Wait for all threads to complete
        boolean completed = completionLatch.await(10, TimeUnit.SECONDS);
        assertTrue("Threads did not complete in time", completed);

        executor.shutdown();

        // Verify all steps were added
        assertEquals(numThreads * stepsPerThread, buffer.size());
    }

    /**
     * Test concurrent reads (sampling) while writes are happening
     * Simulates reading for training while games are still being played
     */
    @Test
    public void testConcurrentReadsAndWrites() throws InterruptedException {
        final ReplayBuffer buffer = new ReplayBuffer(5000);
        final int numWriters = 4;
        final int numReaders = 4;
        final int stepsPerWrite = 50;
        final AtomicInteger totalWritten = new AtomicInteger(0);
        final AtomicInteger readErrors = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(numWriters + numReaders);
        CountDownLatch completionLatch = new CountDownLatch(numWriters + numReaders);

        // Pre-populate buffer so readers have something to read
        buffer.addEpisode(createTestEpisode(100));

        // Launch writer threads
        for (int i = 0; i < numWriters; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 10; j++) {
                        List<LearningStep> episode = createTestEpisode(stepsPerWrite);
                        buffer.addEpisode(episode);
                        totalWritten.addAndGet(stepsPerWrite);
                        Thread.sleep(1); // Small delay to allow interleaving
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        // Launch reader threads
        for (int i = 0; i < numReaders; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 20; j++) {
                        List<LearningStep> batch = buffer.sampleBatch(10);
                        if (batch.size() > 10) {
                            readErrors.incrementAndGet();
                        }
                        Thread.sleep(1); // Small delay
                    }
                } catch (Exception e) {
                    readErrors.incrementAndGet();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        // Wait for all operations to complete
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        assertTrue("Operations did not complete in time", completed);

        executor.shutdown();

        // Verify no read errors occurred
        assertEquals("Read errors occurred during concurrent access", 0, readErrors.get());

        // Verify buffer size is reasonable (within capacity)
        assertTrue("Buffer size is within capacity", buffer.size() <= 5000);
        assertTrue("Buffer has content", buffer.size() > 0);
    }

    /**
     * Test listener notification during concurrent adds
     */
    @Test
    public void testConcurrentListenerNotification() throws InterruptedException {
        final ReplayBuffer buffer = new ReplayBuffer(10000);
        final int threshold = 500;
        final AtomicInteger notificationCount = new AtomicInteger(0);
        final int numThreads = 4;
        final int stepsPerThread = 200;

        // Add listener
        buffer.addListener(threshold, (ReplayBuffer b) -> {
            notificationCount.incrementAndGet();
        });

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch completionLatch = new CountDownLatch(numThreads);

        // Launch threads to add episodes
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    List<LearningStep> episode = createTestEpisode(stepsPerThread);
                    buffer.addEpisode(episode);
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        // Wait for completion
        boolean completed = completionLatch.await(10, TimeUnit.SECONDS);
        assertTrue("Threads did not complete in time", completed);

        executor.shutdown();

        // Verify listener was called (at least once after crossing threshold)
        assertTrue("Listener should have been notified", notificationCount.get() > 0);
        assertEquals("All steps were added", numThreads * stepsPerThread, buffer.size());
    }

    /**
     * Test that clear operation works safely
     */
    @Test
    public void testClear() {
        ReplayBuffer buffer = new ReplayBuffer(100);
        buffer.addEpisode(createTestEpisode(50));

        assertEquals(50, buffer.size());

        buffer.clear();

        assertEquals(0, buffer.size());
    }

    /**
     * Test sampling from empty buffer
     */
    @Test
    public void testSampleFromEmptyBuffer() {
        ReplayBuffer buffer = new ReplayBuffer(100);
        List<LearningStep> batch = buffer.sampleBatch(10);

        assertNotNull(batch);
        assertEquals(0, batch.size());
    }

    /**
     * Test adding null or empty episodes
     */
    @Test
    public void testAddNullOrEmptyEpisode() {
        ReplayBuffer buffer = new ReplayBuffer(100);

        buffer.addEpisode(null);
        assertEquals(0, buffer.size());

        buffer.addEpisode(new ArrayList<>());
        assertEquals(0, buffer.size());
    }

    /**
     * Stress test - high concurrency with mixed operations
     */
    @Test
    public void testHighConcurrencyStress() throws InterruptedException {
        final ReplayBuffer buffer = new ReplayBuffer(5000);
        final int numThreads = 16;
        final int operationsPerThread = 50;
        final AtomicInteger errors = new AtomicInteger(0);

        // Pre-populate
        buffer.addEpisode(createTestEpisode(100));

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch completionLatch = new CountDownLatch(numThreads);

        // Launch threads with mixed operations
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        // Mix of operations
                        if (threadId % 3 == 0) {
                            // Writer
                            buffer.addEpisode(createTestEpisode(10));
                        } else if (threadId % 3 == 1) {
                            // Reader
                            buffer.sampleBatch(5);
                        } else {
                            // Size checker
                            int size = buffer.size();
                            if (size < 0) {
                                errors.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        // Wait for completion
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        assertTrue("Operations did not complete in time", completed);

        executor.shutdown();

        // Verify no errors
        assertEquals("No errors should occur during concurrent operations", 0, errors.get());
    }

    // ===== Helper Methods =====

    /**
     * Creates a test episode with the specified number of learning steps
     */
    private List<LearningStep> createTestEpisode(int numSteps) {
        List<LearningStep> episode = new ArrayList<>();
        for (int i = 0; i < numSteps; i++) {
            episode.add(createTestLearningStep());
        }
        return episode;
    }

    /**
     * Creates a single test learning step with dummy data
     */
    private LearningStep createTestLearningStep() {
        double[] state = new double[]{1.0, 2.0, 3.0};
        SemanticAction action = new TestSemanticAction();
        boolean fpPlayer = true;
        AwaitingDecision decision = new TestAwaitingDecision();

        LearningStep step = new LearningStep(state, action, fpPlayer, decision);
        step.reward = 1.0;
        return step;
    }

    /**
     * Dummy SemanticAction for testing
     */
    private static class TestSemanticAction implements SemanticAction {
        @Override
        public com.alibaba.fastjson2.JSONObject toJson() {
            return new com.alibaba.fastjson2.JSONObject();
        }

        @Override
        public String toDecisionString(AwaitingDecision decision, com.gempukku.lotro.game.state.GameState gameState) {
            return "test";
        }
    }

    /**
     * Dummy AwaitingDecision for testing
     */
    private static class TestAwaitingDecision implements AwaitingDecision {
        @Override
        public int getAwaitingDecisionId() {
            return 0;
        }

        @Override
        public com.gempukku.lotro.logic.decisions.AwaitingDecisionType getDecisionType() {
            return com.gempukku.lotro.logic.decisions.AwaitingDecisionType.MULTIPLE_CHOICE;
        }

        @Override
        public java.util.Map<String, String[]> getDecisionParameters() {
            return new java.util.HashMap<>();
        }

        @Override
        public String getText() {
            return "Test decision";
        }

        @Override
        public void decisionMade(String result) {
            // No-op for testing
        }

        @Override
        public com.alibaba.fastjson2.JSONObject toJson() {
            return new com.alibaba.fastjson2.JSONObject();
        }
    }
}
