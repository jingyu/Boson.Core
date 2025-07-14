package io.bosonnetwork.metrics;

/**
 * Marker interface for objects that have metrics.
 */
public interface Measured {
	/**
	 * Whether the metrics are enabled for this measured object
	 * The default implementation returns {@code false}
	 *
	 * @return {@code true} if metrics are enabled
	 */
	default boolean isMetricsEnabled() {
		return false;
	}
}