/*
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

package io.bosonnetwork.vertx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import io.vertx.core.Handler;
import io.vertx.core.streams.ReadStream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ObservableReadStream}, driven by a stream the test pushes elements into so that
 * flow control is directly observable.
 */
public class ObservableReadStreamTest {
	/** A minimal {@link ReadStream} that records what was done to it and emits on demand. */
	private static class TestReadStream implements ReadStream<String> {
		private @Nullable Handler<String> handler;
		private @Nullable Handler<Void> endHandler;
		private @Nullable Handler<Throwable> exceptionHandler;
		private boolean paused;
		private int resumes;

		@Override
		public TestReadStream exceptionHandler(@Nullable Handler<Throwable> handler) {
			this.exceptionHandler = handler;
			return this;
		}

		@Override
		public TestReadStream handler(@Nullable Handler<String> handler) {
			this.handler = handler;
			return this;
		}

		@Override
		public TestReadStream pause() {
			paused = true;
			return this;
		}

		@Override
		public TestReadStream resume() {
			return fetch(Long.MAX_VALUE);
		}

		@Override
		public TestReadStream fetch(long amount) {
			paused = false;
			resumes++;
			return this;
		}

		@Override
		public TestReadStream endHandler(@Nullable Handler<Void> endHandler) {
			this.endHandler = endHandler;
			return this;
		}

		/** Emits an element the way a real stream would: not while paused, not without a handler. */
		void emit(String element) {
			if (!paused && handler != null)
				handler.handle(element);
		}

		void end() {
			if (endHandler != null)
				endHandler.handle(null);
		}
	}

	/** A wrapper whose observer throws on the element given, to reach the terminated state. */
	private static ObservableReadStream<String> observing(TestReadStream source, String poison,
			List<String> received, List<Throwable> failures) {
		ObservableReadStream<String> stream = new ObservableReadStream<>(source, element -> {
			if (element.equals(poison))
				throw new IllegalStateException("rejected: " + element);
		});

		stream.exceptionHandler(failures::add);
		stream.endHandler(v -> received.add("<end>"));
		stream.handler(received::add);
		source.resume();
		return stream;
	}

	@Test
	@DisplayName("Elements are observed and forwarded until the observer throws")
	void terminatesOnObserverFailure() {
		TestReadStream source = new TestReadStream();
		List<String> received = new ArrayList<>();
		List<Throwable> failures = new ArrayList<>();
		observing(source, "bad", received, failures);

		source.emit("one");
		source.emit("bad");
		source.emit("two");

		assertEquals(List.of("one"), received, "nothing is forwarded after the observer throws");
		assertEquals(1, failures.size(), "the failure is reported once");
		assertTrue(source.paused, "a terminated stream is left paused");
	}

	@Test
	@DisplayName("A terminated stream refuses to restart")
	void terminatedStreamStaysPaused() {
		TestReadStream source = new TestReadStream();
		List<String> received = new ArrayList<>();
		ObservableReadStream<String> stream = observing(source, "bad", received, new ArrayList<>());

		source.emit("bad");
		stream.resume();
		stream.fetch(16);

		assertTrue(source.paused, "resume and fetch must not restart a terminated stream");
	}

	@Test
	@DisplayName("Closing a terminated stream hands it back, flowing and unhandled")
	void closeDetachesTerminatedStream() {
		TestReadStream source = new TestReadStream();
		List<String> received = new ArrayList<>();
		ObservableReadStream<String> stream = observing(source, "bad", received, new ArrayList<>());

		source.emit("one");
		source.emit("bad");
		stream.close();

		assertFalse(source.paused, "close resumes the stream");
		assertNull(source.handler, "close removes the wrapper's handler");
		assertNull(source.endHandler, "close removes the wrapper's end handler");
		assertNull(source.exceptionHandler, "close removes the wrapper's exception handler");

		source.emit("two");
		source.end();
		assertEquals(List.of("one"), received, "a closed wrapper delivers nothing further");
	}

	@Test
	@DisplayName("Closing an ended stream does not resume it")
	void closeAfterEndDoesNotResume() {
		TestReadStream source = new TestReadStream();
		List<String> received = new ArrayList<>();
		ObservableReadStream<String> stream = observing(source, "bad", received, new ArrayList<>());

		source.emit("one");
		source.end();
		int resumesBeforeClose = source.resumes;
		stream.close();

		assertEquals(List.of("one", "<end>"), received, "the end reaches the consumer");
		assertEquals(resumesBeforeClose, source.resumes, "an ended stream is not resumed");
	}

	@Test
	@DisplayName("Closing twice does nothing the second time")
	void closeIsIdempotent() {
		TestReadStream source = new TestReadStream();
		ObservableReadStream<String> stream =
				observing(source, "bad", new ArrayList<>(), new ArrayList<>());

		stream.close();
		int resumesAfterFirstClose = source.resumes;
		stream.close();

		assertEquals(resumesAfterFirstClose, source.resumes, "the second close is a no-op");
	}

	@Test
	@DisplayName("A closed wrapper cannot be reattached")
	void closedStreamRefusesNewHandlers() {
		TestReadStream source = new TestReadStream();
		List<String> received = new ArrayList<>();
		ObservableReadStream<String> stream =
				observing(source, "bad", received, new ArrayList<>());

		stream.close();
		assertSame(stream, stream.handler(received::add));
		source.emit("one");

		assertNull(source.handler, "a closed wrapper does not install handlers");
		assertEquals(List.of(), received, "and delivers nothing");
	}

	@Test
	@DisplayName("Closing a wrapper closes the wrappers beneath it")
	void closePropagatesThroughNestedWrappers() {
		TestReadStream source = new TestReadStream();
		List<String> received = new ArrayList<>();

		// What the upload path builds: one component measures a stream another has already wrapped.
		// Here the outer wrapper is the one that rejects, so it is the one holding the source paused.
		ObservableReadStream<String> outer = new ObservableReadStream<>(source, element -> {
			if (element.equals("bad"))
				throw new IllegalStateException("rejected: " + element);
		});
		ObservableReadStream<String> inner = new ObservableReadStream<>(outer, element -> { });
		inner.exceptionHandler(t -> { });
		inner.handler(received::add);
		source.resume();

		source.emit("bad");
		assertTrue(source.paused, "the outer wrapper holds the source paused");

		inner.close();

		assertFalse(source.paused, "closing the inner wrapper releases the source");
		assertNull(source.handler, "and leaves no handler behind");
	}

	@Test
	@DisplayName("A pipe's cleanup can unset the handler of a terminated stream")
	void terminatedStreamAcceptsHandlerRemoval() {
		TestReadStream source = new TestReadStream();
		ObservableReadStream<String> stream =
				observing(source, "bad", new ArrayList<>(), new ArrayList<>());

		source.emit("bad");
		stream.handler(null);

		assertNull(source.handler, "unsetting the handler is always allowed");
	}
}
