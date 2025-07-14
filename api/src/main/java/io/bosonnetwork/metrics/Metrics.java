package io.bosonnetwork.metrics;

/**
 * The metrics interface is implemented by metrics providers that wants to
 *  provide monitoring of Boson network services.
 */
public interface Metrics {
	/**
	 * Used to close out the metrics, for example when a DHT node has been closed.
	 * <p/>
	 * No specific thread and context can be expected when this method is called.
	 */
	default void close() {
	}
}