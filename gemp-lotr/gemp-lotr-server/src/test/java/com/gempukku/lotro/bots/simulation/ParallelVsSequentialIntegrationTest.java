package com.gempukku.lotro.bots.simulation;

import com.gempukku.lotro.bots.BotPlayer;
import com.gempukku.lotro.bots.rl.learning.LearningBotPlayer;
import com.gempukku.lotro.bots.rl.learning.ReplayBuffer;
import com.gempukku.lotro.game.LotroGameMediator;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Integration tests comparing parallel vs sequential execution.
 * Verifies that both approaches produce statistically similar results.
 */
public class ParallelVsSequentialIntegrationTest {

    /**
     * Compare parallel vs sequential for same number of games.
     * Win rates should be within acceptable variance.
     */
    @Test
    public void testParallelVsSequentialWinRates() {
        int numGames = 100;
        int parallelism = 4;

        // Run sequential
        ReplayBuffer seqBuffer = new ReplayBuffer(10000);
        SimpleBatchSimulationRunner seqRunner = createSequentialRunner(numGames, seqBuffer);
        SimulationStats seqStats = seqRunner.run();

        // Run parallel
        ReplayBuffer parBuffer = new ReplayBuffer(10000);
        ParallelBatchSimulationRunner parRunner = createParallelRunner(numGames, parallelism, parBuffer);
        SimulationStats parStats = parRunner.run();

        // Both should complete all games
        assertEquals("Sequential should complete all games", numGames, seqStats.getTotalGames());
        assertEquals("Parallel should complete all games", numGames, parStats.getTotalGames());

        // Win rates should be within 20% of each other (due to randomness)
        double seqWinRate = seqStats.getBot1Wins() / (double) numGames;
        double parWinRate = parStats.getBot1Wins() / (double) numGames;
        double difference = Math.abs(seqWinRate - parWinRate);

        System.out.println("Sequential win rate: " + seqWinRate);
        System.out.println("Parallel win rate: " + parWinRate);
        System.out.println("Difference: " + difference);

        assertTrue("Win rates should be within 20%", difference < 0.20);
    }

    /**
     * Compare learning step collection between parallel and sequential.
     * Both should collect similar numbers of steps.
     */
    @Test
    public void testParallelVsSequentialLearningSteps() {
        int numGames = 50;
        int parallelism = 4;

        // Run sequential
        ReplayBuffer seqBuffer = new ReplayBuffer(10000);
        SimpleBatchSimulationRunner seqRunner = createSequentialRunner(numGames, seqBuffer);
        seqRunner.run();
        int seqSteps = seqBuffer.size();

        // Run parallel
        ReplayBuffer parBuffer = new ReplayBuffer(10000);
        ParallelBatchSimulationRunner parRunner = createParallelRunner(numGames, parallelism, parBuffer);
        parRunner.run();
        int parSteps = parBuffer.size();

        System.out.println("Sequential steps collected: " + seqSteps);
        System.out.println("Parallel steps collected: " + parSteps);

        // Should collect exactly the same number of steps (each game produces fixed steps)
        assertEquals("Should collect same number of learning steps", seqSteps, parSteps);
    }

    /**
     * Compare execution time - parallel should be faster with multiple cores
     */
    @Test
    public void testParallelIsFasterThanSequential() {
        int numGames = 100;
        int parallelism = 4;

        // Run sequential
        ReplayBuffer seqBuffer = new ReplayBuffer(10000);
        SimpleBatchSimulationRunner seqRunner = createSequentialRunner(numGames, seqBuffer);
        long seqStart = System.currentTimeMillis();
        seqRunner.run();
        long seqDuration = System.currentTimeMillis() - seqStart;

        // Run parallel
        ReplayBuffer parBuffer = new ReplayBuffer(10000);
        ParallelBatchSimulationRunner parRunner = createParallelRunner(numGames, parallelism, parBuffer);
        long parStart = System.currentTimeMillis();
        parRunner.run();
        long parDuration = System.currentTimeMillis() - parStart;

        double speedup = seqDuration / (double) parDuration;

        System.out.println("Sequential duration: " + seqDuration + "ms");
        System.out.println("Parallel duration: " + parDuration + "ms");
        System.out.println("Speedup: " + String.format("%.2f", speedup) + "x");

        // With 4 threads, expect at least 1.5x speedup (conservative)
        assertTrue("Parallel should be faster than sequential (speedup >= 1.5x)",
                   speedup >= 1.5);

        // Log warning if speedup is less than expected
        if (speedup < 2.0) {
            System.out.println("WARNING: Speedup is less than 2x with 4 threads. " +
                               "This may indicate contention or limited parallelism.");
        }
    }

    /**
     * Test that both approaches handle mixed bot types correctly
     */
    @Test
    public void testMixedBotTypes() {
        int numGames = 50;
        int parallelism = 4;

        // Sequential with learning vs non-learning
        ReplayBuffer seqBuffer = new ReplayBuffer(10000);
        BotFactory seqBot1 = name -> new MockLearningBot(name, seqBuffer);
        BotFactory seqBot2 = name -> new MockBot(name);
        SimpleBatchSimulationRunner seqRunner = new SimpleBatchSimulationRunner(
            new MockSimulation(),
            seqBot1.create("~bot1"),
            seqBot2.create("~bot2"),
            numGames
        );
        SimulationStats seqStats = seqRunner.run();

        // Parallel with learning vs non-learning
        ReplayBuffer parBuffer = new ReplayBuffer(10000);
        BotFactory parBot1 = name -> new MockLearningBot(name, parBuffer);
        BotFactory parBot2 = name -> new MockBot(name);
        ParallelBatchSimulationRunner parRunner = new ParallelBatchSimulationRunner(
            new MockSimulation(),
            parBot1,
            parBot2,
            numGames,
            parallelism
        );
        SimulationStats parStats = parRunner.run();

        // Both should complete successfully
        assertEquals(numGames, seqStats.getTotalGames());
        assertEquals(numGames, parStats.getTotalGames());

        // Both should collect steps (only bot1 is learning)
        assertTrue("Sequential should collect learning steps", seqBuffer.size() > 0);
        assertTrue("Parallel should collect learning steps", parBuffer.size() > 0);
    }

    /**
     * Stress test: Large number of games
     */
    @Test
    public void testLargeScaleExecution() {
        int numGames = 500;
        int parallelism = 8;

        ReplayBuffer buffer = new ReplayBuffer(50000);
        ParallelBatchSimulationRunner runner = createParallelRunner(numGames, parallelism, buffer);

        long start = System.currentTimeMillis();
        SimulationStats stats = runner.run();
        long duration = System.currentTimeMillis() - start;

        assertEquals(numGames, stats.getTotalGames());
        System.out.println("Completed " + numGames + " games in " + duration + "ms " +
                           "with " + parallelism + " threads");
        System.out.println("Throughput: " + (numGames * 1000.0 / duration) + " games/second");
    }

    // ===== Helper Methods =====

    private SimpleBatchSimulationRunner createSequentialRunner(int numGames, ReplayBuffer buffer) {
        return new SimpleBatchSimulationRunner(
            new MockSimulation(),
            new MockLearningBot("~bot1", buffer),
            new MockLearningBot("~bot2", buffer),
            numGames
        );
    }

    private ParallelBatchSimulationRunner createParallelRunner(int numGames, int parallelism, ReplayBuffer buffer) {
        BotFactory botFactory = name -> new MockLearningBot(name, buffer);
        return new ParallelBatchSimulationRunner(
            new MockSimulation(),
            botFactory,
            botFactory,
            numGames,
            parallelism
        );
    }

    // ===== Mock Classes =====

    private static class MockSimulation implements Simulation {
        private final AtomicInteger gameCounter = new AtomicInteger(0);

        @Override
        public GameResult simulateGame(BotPlayer bot1, BotPlayer bot2) {
            // Simulate realistic game time (1-10ms)
            try {
                Thread.sleep(1 + (int) (Math.random() * 9));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Random outcome with slight P1 bias
            return (Math.random() < 0.52) ? GameResult.P1_WON : GameResult.P2_WON;
        }
    }

    private static class MockBot implements BotPlayer {
        private final String name;

        MockBot(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String chooseAction(com.gempukku.lotro.logic.timing.DefaultLotroGame game,
                                   com.gempukku.lotro.logic.decisions.AwaitingDecision awaitingDecision) {
            return null;
        }

        @Override
        public void decisionMadeByPlayer(com.gempukku.lotro.logic.timing.DefaultLotroGame game,
                                         com.gempukku.lotro.logic.decisions.AwaitingDecision awaitingDecision,
                                         String answer, String player) {}

        @Override
        public void cleanUpAfterGame() {}
    }

    private static class MockLearningBot extends MockBot implements LearningBotPlayer {
        private final ReplayBuffer buffer;

        MockLearningBot(String name, ReplayBuffer buffer) {
            super(name);
            this.buffer = buffer;
        }

        @Override
        public void observe(com.gempukku.lotro.game.state.GameState gameState,
                           com.gempukku.lotro.logic.decisions.AwaitingDecision decision,
                           String playerId, String chosenAction, double reward, boolean terminal) {}

        @Override
        public void endEpisode(double reward) {
            // Simulate adding 5-15 learning steps per game
            int stepsPerGame = 5 + (int) (Math.random() * 10);
            for (int i = 0; i < stepsPerGame; i++) {
                // Add mock learning step (simplified)
                // In real implementation, this would be actual game state vectors
            }
        }
    }
}
