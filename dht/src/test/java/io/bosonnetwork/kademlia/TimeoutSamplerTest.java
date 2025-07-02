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

package io.bosonnetwork.kademlia;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.crypto.Random;

public class TimeoutSamplerTest {
	@Test
	public void testStatisticalProperties() {
		TimeoutSampler f = new TimeoutSampler();

		// decay otherwise sensible defaults
		IntStream.range(0, 10).forEach(i -> f.decay());

		f.update(300);
		f.update(7000);

		IntStream.range(0, 300).forEach(i -> f.update(1500));
		IntStream.range(0, 300).forEach(i -> f.update(1600));
		IntStream.range(0, 300).forEach(i -> f.update(1700));
		IntStream.range(0, 300).forEach(i -> f.update(1800));
		IntStream.range(0, 300).forEach(i -> f.update(1900));
		IntStream.range(0, 1000).forEach(i -> f.update(3000));
		f.makeSnapshot();
		TimeoutSampler.Snapshot ss = f.getStats();

		System.out.println(ss);

		int expectedAvg = (1500*300 + 1600*300 + 1700*300 + 1800 * 300 + 1900 * 300 + 1000 * 3000) / (300 * 5 + 1000);

		assertEquals(expectedAvg, ss.mean, TimeoutSampler.BIN_SIZE);
		assertEquals(3000, ss.mode, TimeoutSampler.BIN_SIZE);
		assertEquals(300, ss.getQuantile(0.0001f), TimeoutSampler.BIN_SIZE);
		assertEquals(1500, ss.getQuantile(0.01f), TimeoutSampler.BIN_SIZE);
		assertEquals(1900, ss.getQuantile(0.5f), TimeoutSampler.BIN_SIZE);
		assertEquals(3000, ss.getQuantile(0.9f), TimeoutSampler.BIN_SIZE);
		assertEquals(7000, ss.getQuantile(0.9999f), TimeoutSampler.BIN_SIZE);
	}

	@Test
	public void testCorrectnessUnderDecay() {
		TimeoutSampler f = new TimeoutSampler();

		IntStream.range(0, 2000).forEach(i -> {
			f.update((long) (Random.random().nextGaussian() * 100 + 5000));
			if((i % 10) == 0)
				f.decay();
		});

		f.makeSnapshot();
		TimeoutSampler.Snapshot ss = f.getStats();

		System.out.println(ss);

		assertEquals(5000, ss.mean, TimeoutSampler.BIN_SIZE);
		assertEquals(5000, ss.getQuantile(0.5f), TimeoutSampler.BIN_SIZE);
		assertEquals(5000, ss.mode, 2 * TimeoutSampler.BIN_SIZE);
	}

	@Test
	public void testRandomRTTs() {
		TimeoutSampler f = new TimeoutSampler();

		IntStream.range(0, 1000000).forEach(i -> {
			int rtt = Random.random().nextInt(100, 500);
			f.update(rtt + (rtt % 10 == 0 ? 5000 : 0));
		});

		f.makeSnapshot();
		TimeoutSampler.Snapshot ss = f.getStats();

		System.out.println(ss);
	}
}