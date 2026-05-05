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

package io.bosonnetwork.service;

import io.bosonnetwork.Id;

/**
 * Interface representing a client device in the Boson network.
 * <p>
 * A client device is associated with a user and can have specific attributes like a name, application information,
 * and usage statistics such as creation time, update time, and last seen details.
 */
public interface ClientDevice {
	/**
	 * Gets the unique identifier of the device.
	 *
	 * @return the device {@link Id}
	 */
	Id getId();

	/**
	 * Gets the unique identifier of the user who owns this device.
	 *
	 * @return the user {@link Id}
	 */
	Id getUserId();

	/**
	 * Gets the name of the device.
	 *
	 * @return the device name
	 */
	String getName();

	/**
	 * Gets the name of the application associated with this device.
	 *
	 * @return the application name
	 */
	String getApp();

	/**
	 * Gets the timestamp when the device was created.
	 *
	 * @return the creation timestamp in milliseconds
	 */
	long getCreatedAt();

	/**
	 * Gets the timestamp when the device information was last updated.
	 *
	 * @return the last update timestamp in milliseconds
	 */
	long getUpdatedAt();

	/**
	 * Gets the timestamp when the device was last seen active.
	 *
	 * @return the last-seen timestamp in milliseconds
	 */
	long getLastSeen();

	/**
	 * Gets the last known network address (e.g., IP address) of the device.
	 *
	 * @return the last known address as a String
	 */
	String getLastAddress();
}