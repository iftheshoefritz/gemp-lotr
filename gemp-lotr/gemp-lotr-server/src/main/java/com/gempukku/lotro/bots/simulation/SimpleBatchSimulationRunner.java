package com.gempukku.lotro.bots.simulation;

import com.gempukku.lotro.bots.BotPlayer;
import com.gempukku.lotro.bots.rl.learning.LearningBotPlayer;

import java.util.ArrayList;
import java.util.List;

public class SimpleBatchSimulationRunner implements SimulationRunner{
    private final Simulation simulation;
    private final BotPlayer bot1;
    private final BotPlayer bot2;
    private final int numGames;

    public SimpleBatchSimulationRunner(Simulation simulation, BotPlayer bot1, BotPlayer bot2, int numGames) {
        this.simulation = simulation;
        this.bot1 = bot1;
        this.bot2 = bot2;
        this.numGames = numGames;
    }

    @Override
    public SimulationStats run() {
        int bot1Wins = 0;
        List<Long> gameTimesMs = new ArrayList<>();

        for (int i = 0; i < numGames; i++) {
            long gameStartTime = System.currentTimeMillis();
            GameResult result = simulation.simulateGame(bot1, bot2);
            long gameTime = System.currentTimeMillis() - gameStartTime;
            gameTimesMs.add(gameTime);

            if (result == GameResult.P1_WON) {
                bot1Wins++;
            }
            double bot1Reward = result == GameResult.P1_WON ? 10.0 : 0.0;
            double bot2Reward = 10.0 - bot1Reward;
            if (bot1 instanceof LearningBotPlayer) {
                ((LearningBotPlayer) bot1).endEpisode(bot1Reward);
            }
            if (bot2 instanceof LearningBotPlayer) {
                ((LearningBotPlayer) bot2).endEpisode(bot2Reward);
            }
        }

        return new SimulationStats(bot1Wins, numGames - bot1Wins, numGames, gameTimesMs);
    }
}
