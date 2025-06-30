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

package io.bosonnetwork;

/**
 * Interface for classes that want to listen for changes in the status of
 * the Boson node.
 */
public interface NodeStatusListener {

	/**
	 * Called when the status of the node changes.
	 *
	 * @param newStatus The new status of the node.
	 * @param oldStatus The previous status of the node.
	 */
	default void statusChanged(NodeStatus newStatus, NodeStatus oldStatus) {
	}

	/**
	 * Called when the node is in the process of starting.
	 */
	default void starting() {
	}

	/**
	 * Called when the node has started.
	 */
	default void started() {
	}

	/**
	 * Called when the node is in the process of stopping.
	 */
	default void stopping() {
	}

	/**
	 * Called when the node has stopped.
	 */
	default void stopped() {
	}
}