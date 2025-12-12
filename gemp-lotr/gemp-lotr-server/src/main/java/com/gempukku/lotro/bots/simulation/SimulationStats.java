package com.gempukku.lotro.bots.simulation;

import java.util.Arrays;
import java.util.List;

public class SimulationStats {
    private final int bot1Wins;
    private final int bot2Wins;
    private final int totalGames;
    private final List<Long> gameTimesMs;

    public SimulationStats(int bot1Wins, int bot2Wins, int totalGames) {
        this(bot1Wins, bot2Wins, totalGames, null);
    }

    public SimulationStats(int bot1Wins, int bot2Wins, int totalGames, List<Long> gameTimesMs) {
        this.bot1Wins = bot1Wins;
        this.bot2Wins = bot2Wins;
        this.totalGames = totalGames;
        this.gameTimesMs = gameTimesMs;
    }

    public int getBot1Wins() {
        return bot1Wins;
    }

    public int getBot2Wins() {
        return bot2Wins;
    }

    public int getTotalGames() {
        return totalGames;
    }

    public List<Long> getGameTimesMs() {
        return gameTimesMs;
    }

    public double getBot1WinRate() {
        return bot1Wins * 100.0 / totalGames;
    }

    public double getBot2WinRate() {
        return bot2Wins * 100.0 / totalGames;
    }

    private long getMin() {
        return gameTimesMs.stream().min(Long::compareTo).orElse(0L);
    }

    private long getMax() {
        return gameTimesMs.stream().max(Long::compareTo).orElse(0L);
    }

    private double getAverage() {
        return gameTimesMs.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }

    private long getPercentile(double percentile) {
        if (gameTimesMs.isEmpty()) {
            return 0;
        }
        long[] sorted = gameTimesMs.stream().mapToLong(Long::longValue).sorted().toArray();
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Bot1: %.1f%%, Bot2: %.1f%%", getBot1WinRate(), getBot2WinRate()));

        if (gameTimesMs != null && !gameTimesMs.isEmpty()) {
            double avgMs = getAverage();
            double gamesPerSec = avgMs > 0 ? 1000.0 / avgMs : 0;

            sb.append("\n  Game performance:");
            sb.append(String.format("\n    Average: %.0fms/game (%.1f games/sec)", avgMs, gamesPerSec));
            sb.append(String.format("\n    Min: %dms | p50: %dms | p90: %dms | Max: %dms",
                    getMin(), getPercentile(50), getPercentile(90), getMax()));
        }

        return sb.toString();
    }
}
