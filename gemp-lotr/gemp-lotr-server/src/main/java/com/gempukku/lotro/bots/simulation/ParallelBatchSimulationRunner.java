package com.gempukku.lotro.bots.simulation;

import com.gempukku.lotro.bots.BotPlayer;
import com.gempukku.lotro.bots.rl.learning.LearningBotPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Parallel implementation of batch game simulation.
 * Runs multiple games concurrently to leverage multi-core CPUs.
 *
 * Thread Safety:
 * - Creates fresh bot instances per game via BotFactory
 * - Uses thread-safe statistics accumulators (AtomicInteger, synchronized list)
 * - ReplayBuffer (shared across bots) must be thread-safe
 */
public class ParallelBatchSimulationRunner implements SimulationRunner {
    private final Simulation simulation;
    private final BotFactory bot1Factory;
    private final BotFactory bot2Factory;
    private final int numGames;
    private final int parallelism;

    /**
     * Creates a parallel simulation runner.
     *
     * @param simulation Game simulation instance
     * @param bot1Factory Factory for creating bot1 instances
     * @param bot2Factory Factory for creating bot2 instances
     * @param numGames Total number of games to simulate
     * @param parallelism Number of games to run concurrently
     */
    public ParallelBatchSimulationRunner(Simulation simulation, BotFactory bot1Factory, BotFactory bot2Factory,
                                          int numGames, int parallelism) {
        this.simulation = simulation;
        this.bot1Factory = bot1Factory;
        this.bot2Factory = bot2Factory;
        this.numGames = numGames;
        this.parallelism = parallelism;
    }

    @Override
    public SimulationStats run() {
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        AtomicInteger bot1Wins = new AtomicInteger(0);
        List<Long> gameTimesMs = Collections.synchronizedList(new ArrayList<>());

        List<Future<?>> futures = new ArrayList<>();

        System.out.println("Starting parallel simulation: " + numGames + " games with " + parallelism + " threads");
        long overallStartTime = System.currentTimeMillis();

        // Submit all games to the executor
        for (int i = 0; i < numGames; i++) {
            final int gameNum = i;
            Future<?> future = executor.submit(() -> {
                // Create thread-local bot instances
                BotPlayer threadBot1 = bot1Factory.create("~b1_g" + gameNum);
                BotPlayer threadBot2 = bot2Factory.create("~b2_g" + gameNum);

                // Run game
                long startTime = System.currentTimeMillis();
                GameResult result = simulation.simulateGame(threadBot1, threadBot2);
                long gameTime = System.currentTimeMillis() - startTime;

                // Update statistics (thread-safe)
                gameTimesMs.add(gameTime);
                if (result == GameResult.P1_WON) {
                    bot1Wins.incrementAndGet();
                }

                // Assign rewards (endEpisode already handles ReplayBuffer safely)
                double bot1Reward = result == GameResult.P1_WON ? 10.0 : 0.0;
                double bot2Reward = 10.0 - bot1Reward;
                if (threadBot1 instanceof LearningBotPlayer) {
                    ((LearningBotPlayer) threadBot1).endEpisode(bot1Reward);
                }
                if (threadBot2 instanceof LearningBotPlayer) {
                    ((LearningBotPlayer) threadBot2).endEpisode(bot2Reward);
                }

                // Progress reporting every 100 games
                if ((gameNum + 1) % 100 == 0) {
                    System.out.println("Progress: " + (gameNum + 1) + "/" + numGames + " games completed");
                }
            });
            futures.add(future);
        }

        // Wait for all games to complete
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException("Game simulation failed", e);
            }
        }

        executor.shutdown();

        long overallTime = System.currentTimeMillis() - overallStartTime;
        System.out.println("Parallel simulation completed in " + (overallTime / 1000.0) + " seconds");

        return new SimulationStats(
            bot1Wins.get(),
            numGames - bot1Wins.get(),
            numGames,
            gameTimesMs
        );
    }
}
