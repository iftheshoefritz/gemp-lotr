package com.gempukku.lotro.bots.simulation;

import com.gempukku.lotro.bots.BotPlayer;
import com.gempukku.lotro.bots.rl.learning.LearningBotPlayer;
import com.gempukku.lotro.bots.rl.learning.ReplayBuffer;
import com.gempukku.lotro.game.LotroGameMediator;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Unit tests for ParallelBatchSimulationRunner to verify thread-safe operation,
 * correct statistics collection, and proper bot factory usage.
 */
public class ParallelBatchSimulationRunnerTest {

    /**
     * Test that parallel runner correctly counts wins across multiple threads
     */
    @Test
    public void testWinCountingIsThreadSafe() {
        int numGames = 100;
        int parallelism = 4;

        // Create a mock simulation that alternates wins
        Simulation mockSimulation = new MockSimulation(true);

        // Simple bot factory
        BotFactory botFactory = name -> new MockBot(name);

        ParallelBatchSimulationRunner runner = new ParallelBatchSimulationRunner(
            mockSimulation, botFactory, botFactory, numGames, parallelism
        );

        SimulationStats stats = runner.run();

        // Verify all games were counted
        assertEquals(numGames, stats.getTotalGames());
        assertEquals(numGames, stats.getBot1Wins() + stats.getBot2Wins());

        // With alternating wins, should be roughly 50/50
        assertTrue("Bot1 wins should be between 40-60",
                   stats.getBot1Wins() >= 40 && stats.getBot1Wins() <= 60);
    }

    /**
     * Test that each game gets its own bot instances (no shared state)
     */
    @Test
    public void testBotInstancesAreUnique() {
        int numGames = 50;
        int parallelism = 4;

        Set<String> bot1Names = Collections.synchronizedSet(new HashSet<>());
        Set<String> bot2Names = Collections.synchronizedSet(new HashSet<>());

        // Track bot instances created
        BotFactory bot1Factory = name -> {
            bot1Names.add(name);
            return new MockBot(name);
        };

        BotFactory bot2Factory = name -> {
            bot2Names.add(name);
            return new MockBot(name);
        };

        Simulation mockSimulation = new MockSimulation(false);

        ParallelBatchSimulationRunner runner = new ParallelBatchSimulationRunner(
            mockSimulation, bot1Factory, bot2Factory, numGames, parallelism
        );

        runner.run();

        // Should have created unique bot instances for each game
        assertEquals("Should create unique bot1 instances", numGames, bot1Names.size());
        assertEquals("Should create unique bot2 instances", numGames, bot2Names.size());
    }

    /**
     * Test that game times are correctly collected across threads
     */
    @Test
    public void testGameTimesCollection() {
        int numGames = 100;
        int parallelism = 4;

        Simulation mockSimulation = new MockSimulation(false);
        BotFactory botFactory = name -> new MockBot(name);

        ParallelBatchSimulationRunner runner = new ParallelBatchSimulationRunner(
            mockSimulation, botFactory, botFactory, numGames, parallelism
        );

        SimulationStats stats = runner.run();

        // Should have timing for each game
        List<Long> gameTimes = stats.getGameTimesMs();
        assertEquals("Should have timing for all games", numGames, gameTimes.size());

        // All times should be non-negative
        for (Long time : gameTimes) {
            assertTrue("Game time should be non-negative", time >= 0);
        }
    }

    /**
     * Test that learning bots receive rewards correctly
     */
    @Test
    public void testLearningBotRewardsAreAssigned() {
        int numGames = 20;
        int parallelism = 2;

        AtomicInteger bot1RewardsReceived = new AtomicInteger(0);
        AtomicInteger bot2RewardsReceived = new AtomicInteger(0);

        BotFactory bot1Factory = name -> new MockLearningBot(name, bot1RewardsReceived);
        BotFactory bot2Factory = name -> new MockLearningBot(name, bot2RewardsReceived);

        Simulation mockSimulation = new MockSimulation(true);

        ParallelBatchSimulationRunner runner = new ParallelBatchSimulationRunner(
            mockSimulation, bot1Factory, bot2Factory, numGames, parallelism
        );

        runner.run();

        // Both bots should have received rewards for all games
        assertEquals("Bot1 should receive rewards for all games",
                     numGames, bot1RewardsReceived.get());
        assertEquals("Bot2 should receive rewards for all games",
                     numGames, bot2RewardsReceived.get());
    }

    /**
     * Test with different parallelism levels
     */
    @Test
    public void testVariousParallelismLevels() {
        int numGames = 50;

        for (int parallelism : new int[]{1, 2, 4, 8}) {
            Simulation mockSimulation = new MockSimulation(false);
            BotFactory botFactory = name -> new MockBot(name);

            ParallelBatchSimulationRunner runner = new ParallelBatchSimulationRunner(
                mockSimulation, botFactory, botFactory, numGames, parallelism
            );

            SimulationStats stats = runner.run();

            assertEquals("Should complete all games with parallelism=" + parallelism,
                         numGames, stats.getTotalGames());
        }
    }

    /**
     * Test that runner handles exceptions gracefully
     */
    @Test
    public void testExceptionHandling() {
        int numGames = 10;
        int parallelism = 2;

        // Simulation that throws exception on 5th game
        Simulation failingSimulation = (bot1, bot2) -> {
            throw new RuntimeException("Simulated game failure");
        };

        BotFactory botFactory = name -> new MockBot(name);

        ParallelBatchSimulationRunner runner = new ParallelBatchSimulationRunner(
            failingSimulation, botFactory, botFactory, numGames, parallelism
        );

        try {
            runner.run();
            fail("Should have thrown RuntimeException");
        } catch (RuntimeException e) {
            assertTrue("Should wrap execution exception",
                       e.getMessage().contains("Game simulation failed"));
        }
    }

    /**
     * Stress test with many concurrent games
     */
    @Test
    public void testHighConcurrencyStress() {
        int numGames = 200;
        int parallelism = 8;

        Simulation mockSimulation = new MockSimulation(true);
        BotFactory botFactory = name -> new MockBot(name);

        ParallelBatchSimulationRunner runner = new ParallelBatchSimulationRunner(
            mockSimulation, botFactory, botFactory, numGames, parallelism
        );

        long startTime = System.currentTimeMillis();
        SimulationStats stats = runner.run();
        long duration = System.currentTimeMillis() - startTime;

        assertEquals(numGames, stats.getTotalGames());
        System.out.println("Completed " + numGames + " games in " + duration + "ms " +
                           "(" + (duration / (double) numGames) + "ms per game)");
    }

    // ===== Mock Classes =====

    /**
     * Mock simulation that can be configured to alternate wins or always let P1 win
     */
    private static class MockSimulation implements Simulation {
        private final AtomicInteger gameCounter = new AtomicInteger(0);
        private final boolean alternateWins;

        MockSimulation(boolean alternateWins) {
            this.alternateWins = alternateWins;
        }

        @Override
        public GameResult simulateGame(BotPlayer bot1, BotPlayer bot2) {
            // Simulate some work (1-5ms)
            try {
                Thread.sleep(1 + (int) (Math.random() * 4));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (alternateWins) {
                return (gameCounter.getAndIncrement() % 2 == 0)
                    ? GameResult.P1_WON
                    : GameResult.P2_WON;
            } else {
                return GameResult.P1_WON;
            }
        }
    }

    /**
     * Mock bot for testing
     */
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

    /**
     * Mock learning bot that tracks reward assignments
     */
    private static class MockLearningBot extends MockBot implements LearningBotPlayer {
        private final AtomicInteger rewardCounter;

        MockLearningBot(String name, AtomicInteger rewardCounter) {
            super(name);
            this.rewardCounter = rewardCounter;
        }

        @Override
        public void observe(com.gempukku.lotro.game.state.GameState gameState,
                           com.gempukku.lotro.logic.decisions.AwaitingDecision decision,
                           String playerId, String chosenAction, double reward, boolean terminal) {}

        @Override
        public void endEpisode(double reward) {
            rewardCounter.incrementAndGet();
        }
    }
}
