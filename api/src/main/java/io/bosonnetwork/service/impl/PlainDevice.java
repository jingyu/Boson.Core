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

package io.bosonnetwork.service.impl;

import java.util.Objects;

import io.bosonnetwork.Id;
import io.bosonnetwork.service.ClientDevice;

/**
 * An implementation of the {@link ClientDevice} interface that represents a basic client device
 * with minimal information and default attribute values.
 */
public class PlainDevice implements ClientDevice {
	private final Id id;
	private final Id userId;
	private final String name;
	private final String app;
	private final long ts;

	PlainDevice(Id id, Id userId) {
		this(id, userId, null, null);
	}

	PlainDevice(Id id, Id userId, String name, String app) {
		this.id = Objects.requireNonNull(id);
		this.userId = Objects.requireNonNull(userId);
		this.name = name == null || name.isEmpty() ? id.toAbbrBase58String() : name;
		this.app = app == null || app.isEmpty() ? "unknown" : app;
		this.ts = System.currentTimeMillis();
	}

	@Override
	public Id getId() {
		return id;
	}

	@Override
	public Id getUserId() {
		return userId;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String getApp() {
		return app;
	}

	@Override
	public long getCreatedAt() {
		return ts;
	}

	@Override
	public long getUpdatedAt() {
		return ts;
	}

	@Override
	public long getLastSeen() {
		return ts;
	}

	@Override
	public String getLastAddress() {
		return "n/a";
	}
}