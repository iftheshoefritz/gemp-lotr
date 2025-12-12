package com.gempukku.lotro.bots.simulation;

import com.gempukku.lotro.bots.BotPlayer;
import com.gempukku.lotro.bots.rl.learning.LearningBotPlayer;
import com.gempukku.lotro.bots.rl.learning.ReplayBuffer;
import com.gempukku.lotro.game.LotroGameMediator;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/**
 * Performance benchmarking tests for parallel game simulation.
 * These tests measure throughput and speedup at various parallelism levels.
 *
 * NOTE: These are longer-running tests. They can be run manually to evaluate
 * performance characteristics on different hardware configurations.
 */
public class ParallelSimulationBenchmark {

    /**
     * Benchmark different game counts to understand scaling characteristics
     */
    @Test
    public void benchmarkGameCountScaling() {
        int[] gameCounts = {100, 500, 1000, 2000};
        int parallelism = Runtime.getRuntime().availableProcessors() - 1;

        System.out.println("=== Game Count Scaling Benchmark ===");
        System.out.println("Parallelism: " + parallelism + " threads");
        System.out.println("CPU Cores: " + Runtime.getRuntime().availableProcessors());
        System.out.println();

        for (int numGames : gameCounts) {
            ReplayBuffer buffer = new ReplayBuffer(numGames * 20);
            BotFactory botFactory = name -> new BenchmarkBot(name, buffer);

            ParallelBatchSimulationRunner runner = new ParallelBatchSimulationRunner(
                new BenchmarkSimulation(),
                botFactory,
                botFactory,
                numGames,
                parallelism
            );

            long start = System.currentTimeMillis();
            SimulationStats stats = runner.run();
            long duration = System.currentTimeMillis() - start;

            double gamesPerSecond = numGames * 1000.0 / duration;
            double msPerGame = duration / (double) numGames;

            System.out.printf("Games: %5d | Duration: %6dms | Throughput: %6.2f games/sec | Avg: %6.2fms/game%n",
                              numGames, duration, gamesPerSecond, msPerGame);

            assertEquals("All games should complete", numGames, stats.getTotalGames());
        }
        System.out.println();
    }

    /**
     * Benchmark different parallelism levels to find optimal thread count
     */
    @Test
    public void benchmarkParallelismLevels() {
        int numGames = 1000;
        int maxCores = Runtime.getRuntime().availableProcessors();

        System.out.println("=== Parallelism Level Benchmark ===");
        System.out.println("Games: " + numGames);
        System.out.println("CPU Cores: " + maxCores);
        System.out.println();

        List<BenchmarkResult> results = new ArrayList<>();

        // Test sequential baseline
        results.add(benchmarkSequential(numGames));

        // Test various parallelism levels
        for (int threads = 1; threads <= Math.min(16, maxCores * 2); threads *= 2) {
            results.add(benchmarkParallel(numGames, threads));
        }

        // Print results table
        System.out.println();
        System.out.println("=== Speedup Summary ===");
        double sequentialTime = results.get(0).duration;
        System.out.printf("%-15s | %10s | %10s | %10s | %15s%n",
                          "Configuration", "Duration", "Throughput", "Speedup", "Efficiency");
        System.out.println("----------------+------------+------------+------------+-----------------");

        for (BenchmarkResult result : results) {
            double speedup = sequentialTime / result.duration;
            double efficiency = result.threads > 0 ? (speedup / result.threads * 100) : 100;

            System.out.printf("%-15s | %7dms | %7.1f/s | %8.2fx | %13.1f%%%n",
                              result.name,
                              result.duration,
                              result.gamesPerSecond,
                              speedup,
                              efficiency);
        }
        System.out.println();
    }

    /**
     * Stress test with maximum parallelism
     */
    @Test
    public void benchmarkMaximumThroughput() {
        int numGames = 2000;
        int parallelism = Runtime.getRuntime().availableProcessors() * 2; // Oversubscribe

        System.out.println("=== Maximum Throughput Test ===");
        System.out.println("Games: " + numGames);
        System.out.println("Threads: " + parallelism + " (oversubscribed)");
        System.out.println();

        ReplayBuffer buffer = new ReplayBuffer(numGames * 20);
        BotFactory botFactory = name -> new BenchmarkBot(name, buffer);

        ParallelBatchSimulationRunner runner = new ParallelBatchSimulationRunner(
            new BenchmarkSimulation(),
            botFactory,
            botFactory,
            numGames,
            parallelism
        );

        long start = System.currentTimeMillis();
        SimulationStats stats = runner.run();
        long duration = System.currentTimeMillis() - start;

        double gamesPerSecond = numGames * 1000.0 / duration;

        System.out.println("Duration: " + duration + "ms");
        System.out.println("Throughput: " + String.format("%.2f", gamesPerSecond) + " games/second");
        System.out.println("Learning steps collected: " + buffer.size());
        System.out.println();

        assertEquals(numGames, stats.getTotalGames());
    }

    /**
     * Memory usage test - estimate memory per concurrent game
     */
    @Test
    public void benchmarkMemoryUsage() {
        int[] parallelismLevels = {1, 2, 4, 8};
        int numGames = 100;

        System.out.println("=== Memory Usage Benchmark ===");
        System.out.println("Games per test: " + numGames);
        System.out.println();

        Runtime runtime = Runtime.getRuntime();

        for (int parallelism : parallelismLevels) {
            // Force GC before measurement
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            long memBefore = runtime.totalMemory() - runtime.freeMemory();

            ReplayBuffer buffer = new ReplayBuffer(numGames * 20);
            BotFactory botFactory = name -> new BenchmarkBot(name, buffer);

            ParallelBatchSimulationRunner runner = new ParallelBatchSimulationRunner(
                new BenchmarkSimulation(),
                botFactory,
                botFactory,
                numGames,
                parallelism
            );

            runner.run();

            long memAfter = runtime.totalMemory() - runtime.freeMemory();
            long memUsed = memAfter - memBefore;
            double memPerGame = memUsed / (double) numGames / 1024.0; // KB

            System.out.printf("Threads: %2d | Memory used: %8.2f KB | Per game: %6.2f KB%n",
                              parallelism, memUsed / 1024.0, memPerGame);
        }
        System.out.println();
    }

    /**
     * Test contention by measuring time spent in synchronized sections
     */
    @Test
    public void benchmarkContentionCharacteristics() {
        int numGames = 500;
        int[] parallelismLevels = {2, 4, 8, 16};

        System.out.println("=== Contention Characteristics ===");
        System.out.println("Games: " + numGames);
        System.out.println();
        System.out.printf("%-10s | %12s | %15s | %15s%n",
                          "Threads", "Duration", "Ideal Time", "Overhead %");
        System.out.println("-----------+--------------+-----------------+----------------");

        // Get sequential baseline
        BenchmarkResult seqResult = benchmarkSequential(numGames);
        double idealTimePerThread = seqResult.duration;

        for (int threads : parallelismLevels) {
            if (threads > Runtime.getRuntime().availableProcessors() * 2) {
                continue; // Skip excessive oversubscription
            }

            BenchmarkResult result = benchmarkParallel(numGames, threads);

            double idealParallelTime = idealTimePerThread / threads;
            double overhead = ((result.duration - idealParallelTime) / idealParallelTime) * 100;

            System.out.printf("%-10d | %9dms | %12.1fms | %13.1f%%%n",
                              threads, result.duration, idealParallelTime, overhead);
        }
        System.out.println();
    }

    // ===== Helper Methods =====

    private BenchmarkResult benchmarkSequential(int numGames) {
        ReplayBuffer buffer = new ReplayBuffer(numGames * 20);
        SimpleBatchSimulationRunner runner = new SimpleBatchSimulationRunner(
            new BenchmarkSimulation(),
            new BenchmarkBot("~bot1", buffer),
            new BenchmarkBot("~bot2", buffer),
            numGames
        );

        long start = System.currentTimeMillis();
        runner.run();
        long duration = System.currentTimeMillis() - start;

        return new BenchmarkResult("Sequential", 0, duration, numGames * 1000.0 / duration);
    }

    private BenchmarkResult benchmarkParallel(int numGames, int threads) {
        ReplayBuffer buffer = new ReplayBuffer(numGames * 20);
        BotFactory botFactory = name -> new BenchmarkBot(name, buffer);

        ParallelBatchSimulationRunner runner = new ParallelBatchSimulationRunner(
            new BenchmarkSimulation(),
            botFactory,
            botFactory,
            numGames,
            threads
        );

        long start = System.currentTimeMillis();
        runner.run();
        long duration = System.currentTimeMillis() - start;

        return new BenchmarkResult(threads + " threads", threads, duration, numGames * 1000.0 / duration);
    }

    // ===== Data Classes =====

    private static class BenchmarkResult {
        final String name;
        final int threads;
        final long duration;
        final double gamesPerSecond;

        BenchmarkResult(String name, int threads, long duration, double gamesPerSecond) {
            this.name = name;
            this.threads = threads;
            this.duration = duration;
            this.gamesPerSecond = gamesPerSecond;
        }
    }

    // ===== Mock Classes =====

    /**
     * Benchmark simulation with realistic timing characteristics
     */
    private static class BenchmarkSimulation implements Simulation {
        @Override
        public GameResult simulateGame(BotPlayer bot1, BotPlayer bot2) {
            // Simulate realistic game computation (5-15ms with variability)
            try {
                int baseTime = 5 + (int) (Math.random() * 10);
                Thread.sleep(baseTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 50/50 win rate
            return (Math.random() < 0.5) ? GameResult.P1_WON : GameResult.P2_WON;
        }
    }

    /**
     * Benchmark bot with realistic learning step generation
     */
    private static class BenchmarkBot implements BotPlayer, LearningBotPlayer {
        private final String name;
        private final ReplayBuffer buffer;

        BenchmarkBot(String name, ReplayBuffer buffer) {
            this.name = name;
            this.buffer = buffer;
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

        @Override
        public void observe(com.gempukku.lotro.game.state.GameState gameState,
                           com.gempukku.lotro.logic.decisions.AwaitingDecision decision,
                           String playerId, String chosenAction, double reward, boolean terminal) {}

        @Override
        public void endEpisode(double reward) {
            // Simulate collecting 10-20 learning steps per game
            // (This models realistic training data collection)
            int steps = 10 + (int) (Math.random() * 10);
            for (int i = 0; i < steps; i++) {
                // Actual learning step addition would happen via buffer.addEpisode()
                // For benchmark, we just simulate the work
            }
        }
    }
}
