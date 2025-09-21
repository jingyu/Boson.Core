package io.bosonnetwork.kademlia.utils;

import java.util.function.Consumer;

public interface Timer {
	long setPeriodic(long initialDelay, long delay, Consumer<Long> handler);

	long setPeriodic(long delay, Consumer<Long> handler);

	long setTimer(long delay, Consumer<Long> handler);

	boolean cancelTimer(long timerId);
}