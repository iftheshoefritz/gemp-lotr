package com.gempukku.lotro.bots.simulation;

import com.gempukku.lotro.bots.BotPlayer;

/**
 * Factory for creating bot instances for parallel game simulation.
 * Each thread in parallel execution needs its own bot instances to avoid shared state issues.
 */
@FunctionalInterface
public interface BotFactory {
    /**
     * Creates a new bot instance.
     *
     * @param name Bot name (should be unique per thread/game)
     * @return Fresh bot instance
     */
    BotPlayer create(String name);
}
