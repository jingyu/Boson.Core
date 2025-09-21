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

/**
 * A class to compute the Exponential Weighted Moving Average (EWMA) of a time series.
 * The EWMA provides a smoothed average that gives more weight to recent values, controlled by
 * a weighting factor (alpha). The formula for updating the average is:
 * <pre>
 * average = alpha * newValue + (1 - alpha) * previousAverage
 * </pre>
 * This class is useful for smoothing time series data, such as stock prices, sensor readings,
 * or performance metrics.
 *
 * <p>Example usage:
 * <pre>
 * ExponentialWeightedMovingAverage ewma = new ExponentialWeightedMovingAverage(0.2);
 * ewma.update(10.0);
 * ewma.update(12.0);
 * System.out.println(ewma.getAverage()); // Prints the current EWMA
 * ewma.reset(); // Resets to uninitialized state
 * </pre>
 *
 * <p>Note: This implementation is not thread-safe by default. For thread-safe usage,
 * synchronize access to methods that modify the average.
 */
public class ExponentialWeightedMovingAverage {
	private static final double DEFAULT_WEIGHT = 0.3;
	private static final double DEFAULT_AVERAGE = Double.NaN;

	// Weighting factor, immutable after construction
	private final double alpha;
	// Current EWMA value
	private double average;

	/**
	 * Constructs an EWMA with the specified weight and initial average.
	 *
	 * @param alpha  The weighting factor, typically in (0, 1].
	 * @param average The initial average value, or Double.NaN for uninitialized.
	 * @throws IllegalArgumentException if weight is not in (0, 1].
	 */
	public ExponentialWeightedMovingAverage(double alpha, double average) {
		if (alpha <= 0.0 || alpha > 1.0)
			throw new IllegalArgumentException("Alpha(weight) must be in (0, 1], got: " + alpha);

		this.alpha = alpha;
		this.average = average;
	}

	/**
	 * Constructs an EWMA with the specified weight and an uninitialized average.
	 *
	 * @param alpha The weighting factor (alpha), typically in [0, 1].
	 * @throws IllegalArgumentException if weight is not in [0, 1].
	 */
	public ExponentialWeightedMovingAverage(double alpha) {
		this(alpha, DEFAULT_AVERAGE);
	}

	/**
	 * Constructs an EWMA with default weight (0.3) and an uninitialized average.
	 */
	public ExponentialWeightedMovingAverage() {
		this(DEFAULT_WEIGHT, DEFAULT_AVERAGE);
	}

	/**
	 * Checks if the EWMA is initialized (has a valid average value).
	 *
	 * @return True if the EWMA is initialized, false otherwise.
	 */
	public boolean isInitialized() {
		return !Double.isNaN(average);
	}

	/**
	 * Resets the EWMA to a specific average value.
	 *
	 * @param average The new average value to set, or Double.NaN for uninitialized.
	 */
	public void reset(double average) {
		this.average = average;
	}

	/**
	 * Reset the EWMA to uninitialized state.
	 */
	public void reset() {
		reset(DEFAULT_AVERAGE);
	}

	/**
	 * Updates the EWMA with a new data point using the formula:
	 * average = weight * value + (1 - weight) * average.
	 * If the average is uninitialized (NaN), it is set to the new value.
	 *
	 * @param value The new data point to incorporate.
	 * @throws IllegalArgumentException if value is NaN or infinite (optional, depending on use case).
	 */
	public void update(double value) {
		// EWMA update rule: average = alpha * value + (1 - alpha) * average
		average = Double.isNaN(average) ? value : alpha * value + (1.0 - alpha) * average;
	}

	/**
	 * Returns the current EWMA value.
	 *
	 * @return The current average, or Double.NaN if uninitialized.
	 */
	public double getAverage() {
		return average;
	}

	/**
	 * Returns the current EWMA value, or a default value if uninitialized.
	 *
	 * @param defaultValue The value to return if the average is uninitialized (NaN).
	 * @return The current average, or defaultValue if uninitialized.
	 */
	public double getAverage(double defaultValue) {
		return Double.isNaN(average) ? defaultValue : average;
	}
}