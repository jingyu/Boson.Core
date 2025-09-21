/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
 * Copyright (c) 2023 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.bosonnetwork.kademlia.utils;

import static io.bosonnetwork.utils.Functional.tap;

import java.util.Arrays;
import java.util.Formatter;

/**
 * Estimates adaptive RPC timeouts by analyzing round-trip times (RTTs)
 * using a decaying histogram and quantile-based sampling. The configuration
 * is provided via constructor parameters for bin size, timeout bounds,
 * and minimum baseline.
 */
public class TimeoutSampler {
	// Minimum allowed timeout in milliseconds. Used as a floor in timeout estimation.
	private final int timeoutMin;
	// Maximum allowed timeout in milliseconds. Used as a cap in timeout estimation.
	private final int timeoutMax;
	// Minimum baseline increment added to the lower quantile to prevent timeouts from being too aggressive.
	private final int timeoutBaselineMin;

	// Minimum value for the histogram bins (typically 0 ms)
	// Logically derived from timeoutMin, kept separately for clarity.
	protected final int minBin;
	// Maximum value for the histogram bins, defined by a constant for max RPC timeout
	// Logically derived from timeoutMax, kept separately for clarity.
	protected final int maxBin;
	// Size of each histogram bin in milliseconds (each bin represents a 50ms range)
	protected final int binSize;

	// Histogram bins storing frequency counts of RTTs
	private final float[] bins;
	// Count of total updates to the histogram (volatile for thread safety if needed)
	private /*volatile*/ long updateCount;
	// Upper bound for timeout (90th percentile of RTTs)
	private int timeoutCeiling;
	// Lower bound for timeout (10th percentile of RTTs)
	private int timeoutBaseline;

	// Current snapshot of the histogram for statistical analysis
	// Initialize snapshot with a bias toward MAX_BIN for conservative initial timeouts
	private Snapshot snapshot;

	/**
	 * Class to hold a snapshot of the histogram and compute statistics
	 */
	protected class Snapshot {
		// Array of normalized bin values representing the distribution of RTTs
		final float[] values;
		// Mean RTT calculated from the histogram
		float mean = 0;
		float mode = 0;

		/**
		 * Initializes snapshot with a copy of the bins.
		 *
		 * @param bins copy of the bins
		 */
		Snapshot(float[] bins) {
			values = bins;
			normalize();
			calcStats();
		}

		/**
		 * Normalizes the histogram so that the sum of bin values equals 1
		 */
		private void normalize() {
			float cumulativePopulation = 0;
			for (float value : values)
				cumulativePopulation += value;

			if (cumulativePopulation > 0) {
				for (int i = 0; i < values.length; i++)
					values[i] /= cumulativePopulation;
			}
		}

		/**
		 * Calculates the mean (average RTT) and mode (most frequent RTT) of the histogram.
		 * Mean and mode are in milliseconds, based on bin midpoints.
		 */
		private void calcStats() {
			// Tracks highest bin population for mode calculation
			float modePop = 0;

			// Iterate through bins to compute mean and mode
			for (int bin = 0; bin < values.length; bin++) {
				// Midpoint of the bin
				float midpoint = (bin + 0.5f) * binSize;
				// Mean: Weighted average of bin midpoints
				mean += values[bin] * midpoint;
				// Mode: Find bin with the highest population
				if (values[bin] > modePop) {
					mode = midpoint;
					modePop = values[bin];
				}
			}
		}

		// Alternate calcStats implementation
		// mode use the average of the mode bins if there are multiple mode bins
		/*
		private void calcStats2() {
			float modePop = 0;
			int modeCount = 0;
			float modeSum = 0;

			for (int bin = 0; bin < values.length; bin++) {
				float midpoint = (bin + 0.5f) * BIN_SIZE;
				mean += values[bin] * midpoint;
				if (values[bin] > modePop) {
					modePop = values[bin];
					modeCount = 1;
					modeSum = midpoint;
				} else if (values[bin] == modePop) {
					modeCount = 1;
					modeSum += midpoint;
				}
			}

			mode = modeCount > 0 ? modeSum / modeCount : 0;
		}
		*/

		/**
		 * Returns the RTT value at a given quantile (e.g., 0.5 for median)
		 *
		 * @param quant Quantile to compute
		 * @return Quantile value
		 */
		public float getQuantile(float quant) {
			// Subtract bin values until quantile is reached
			for (int i = 0; i < values.length; i++) {
				quant -= values[i];
				if (quant <= 0) // Return midpoint of the bin where quantile is reached
					return (i + 0.5f) * binSize;
			}

			// If quantile not found, return maximum bin value
			return maxBin;
		}

		/**
		 * Generates a string representation of the histogram and its statistics
		 *
		 * @return the histogram statistics
		 */
		@Override
		public String toString() {
			StringBuilder repr = new StringBuilder();

			// Append key statistics: mean, median, mode, 10th and 90th percentiles
			repr.append("Statistics: mean=").append(mean)
					.append(", median=").append(getQuantile(0.5f))
					.append(", mode=").append(mode)
					.append(", 10tile=").append(getQuantile(0.1f))
					.append(", 90tile=").append(getQuantile(0.9f))
					.append('\n');

			// Format non-negligible bins (values >= 0.001) for visualization
			Formatter l1 = new Formatter(); // Bin start times
			Formatter l2 = new Formatter(); // Normalized bin values (per mille)
			for (int i = 0; i < values.length; i++) {
				if (values[i] >= 0.001) {
					l1.format(" %5d | ", i * binSize);
					l2.format("%5d‰ | ", Math.round(values[i] * 1000));
				}
			}

			repr.append(l1).append('\n')
					.append(l2).append('\n');

			return repr.toString();
		}
	}

	/**
	 * Constructs a TimeoutSampler with configurable histogram and timeout bounds.
	 *
	 * @param binSize             Size of each histogram bin in milliseconds.
	 * @param timeoutMin          Minimum allowed timeout in milliseconds.
	 * @param timeoutMax          Maximum allowed timeout in milliseconds.
	 * @param timeoutBaselineMin  Minimum baseline offset added to the 10th percentile during timeout estimation.
	 * @throws IllegalArgumentException if parameters are invalid.
	 */
	public TimeoutSampler(int binSize, int timeoutMin, int timeoutMax, int timeoutBaselineMin) {
		if (binSize <= 0 || timeoutMin >= timeoutMax || timeoutBaselineMin < 0)
			throw new IllegalArgumentException("Invalid TimeoutSampler configuration");

		this.timeoutMin = timeoutMin;
		this.timeoutMax = timeoutMax;
		this.timeoutBaselineMin = timeoutBaselineMin;

		this.minBin = timeoutMin;
		this.maxBin = timeoutMax;
		this.binSize = binSize;

		// Number of bins in the histogram, calculated to cover the range [minBin, maxBin]
		int numBins = (int) Math.ceil((maxBin - minBin) * 1.0f / this.binSize);
		bins = new float[numBins];

		snapshot = new Snapshot(tap(bins.clone(), array -> array[array.length - 1] = 1.0f));
		reset();
	}

	/**
	 * Resets the histogram and timeout statistics to their initial state.
	 * Initializes all bins evenly and sets conservative timeout bounds.
	 */
	public void reset() {
		updateCount = 0;
		timeoutBaseline = timeoutCeiling = timeoutMax;
		Arrays.fill(bins, 1.0f / bins.length);
	}

	/**
	 * Returns the size of each histogram bin in milliseconds.
	 *
	 * @return bin size
	 */
	protected int getBinSize() {
		return binSize;
	}

	/**
	 * Returns the total number of updates to the histogram
	 *
	 * @return sample count
	 */
	public long getSampleCount() {
		return updateCount;
	}

	/**
	 * Updates the histogram with a new RTT and periodically recalculates stats.
	 *
	 * @param newRTT the new RTT
	 */
	public void updateAndRecalc(long newRTT) {
		update(newRTT); // Add RTT to histogram
		// Recalculate snapshot and decay every 16 updates
		if ((updateCount++ & 0x0f) == 0) {
			makeSnapshot();
			decay(); // Apply decay to reduce influence of old data
		}
	}

	/**
	 * Updates the histogram with a new RTT value.
	 *
	 * @param newRTT the new RTT
	 */
	public void update(long newRTT) {
		// Calculate which bin the RTT falls into
		int bin = (int) (newRTT - minBin) / binSize;
		// Clamp bin index to valid range [0, NUM_BINS-1]
		bin = Math.max(Math.min(bin, bins.length - 1), 0);

		// Increment the corresponding bin
		bins[bin] += 1.0f;
	}

	/**
	 * Applies exponential decay to all bins to reduce influence of older data.
	 */
	protected void decay() {
		for (int i = 0; i < bins.length; i++)
			bins[i] *= 0.95f;
	}

	// Alternate decay implementation:
	// - Make the decay factor configurable (e.g., via a constructor parameter) to allow tuning based on workload.
	// - Consider time-based decay instead of update-based decay. For example, decay bins based on elapsed time
	//   since the last snapshot (requires tracking timestamps).
	/*
	private long lastDecayTime = System.currentTimeMillis();

	protected void decay2() {
		long now = System.currentTimeMillis();
		long elapsedMillis = Math.max(now - lastDecayTime, 0);

		// Smooth exponential decay over time
		double decayFactor = Math.pow(0.95, elapsedMillis / 1000.0);
		for (int i = 0; i < bins.length; i++) {
			bins[i] *= decayFactor;
		}
		lastDecayTime = now;
	}
	*/

	/**
	 * Creates a new snapshot and recalculates the timeout baseline and ceiling.
	 */
	protected void makeSnapshot() {
		snapshot = new Snapshot(bins.clone());
		timeoutBaseline = (int) snapshot.getQuantile(0.1f);
		timeoutCeiling = (int) snapshot.getQuantile(0.9f);
	}

	/**
	 * Returns the current histogram snapshot
	 *
	 * @return the snapshot
	 */
	protected Snapshot getStats() {
		return snapshot;
	}

	/**
	 * Estimates the current stall timeout using the observed RTT distribution.
	 * Uses the higher of (10th percentile + baseline) or 90th percentile,
	 * and clamps the result to [timeoutMin, timeoutMax].
	 *
	 * @return the estimated timeout value in milliseconds
	 */
	public long getStallTimeout() {
		// Use the higher of: 90th percentile or 10th percentile + 100ms baseline.
		// Ensures timeout doesn't drop too low and miss packets.
		// Whichever is HIGHER (to prevent descent to zero and missing more
		// than 10% of the packets in the worst case).
		long timeout = Math.max(timeoutBaseline + timeoutBaselineMin, timeoutCeiling);

		// Cap the timeout at the maximum allowed value
		timeout = Math.min(timeout, timeoutMax);
		// Ensure timeout is not less than minimum allowed timeout
		return Math.max(timeout, timeoutMin);
	}
}