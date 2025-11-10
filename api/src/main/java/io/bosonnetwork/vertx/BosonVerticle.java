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

import java.util.List;
import java.util.concurrent.Callable;

import io.vertx.core.Context;
import io.vertx.core.Deployable;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;

/**
 * An abstract base class for Verticles in the Boson project that unifies the Vert.x 4.x
 * and 5.x {@code Verticle} API changes.
 *
 * <p>
 * Vert.x 5 deprecated {@code Verticle} and introduced {@code Deployable}, while Vert.x 4
 * requires Verticles to implement {@code Verticle}. This base class hides that difference,
 * so user code can remain compatible across versions.
 * </p>
 *
 * <h2>Usage</h2>
 * <p>
 * Extend this class and implement {@link #deploy()} and {@link #undeploy()} to define
 * startup and shutdown logic respectively. Do <b>not</b> override {@code start(Promise)}
 * or {@code stop(Promise)} — those are managed internally and delegate to your methods.
 * </p>
 *
 * <h2>Method naming</h2>
 * <p>
 * The Vert.x lifecycle methods {@code init}, {@code start}, and {@code stop} are reserved
 * for Vert.x itself and mapped internally to your implementation via {@link #deploy()} and
 * {@link #undeploy()}. This ensures forward compatibility with Vert.x 5’s {@code VerticleBase}.
 * </p>
 */
public abstract class BosonVerticle implements /* Verticle, */ Deployable {
	/**
	 * Reference to the Vert.x instance that deployed this verticle
	 */
	protected Vertx vertx;

	/**
	 * Reference to the context of the verticle
	 */
	protected Context vertxContext;

	/**
	 * Returns the Vert.x instance that deployed this Verticle.
	 *
	 * @return the Vert.x instance
	 */
	public final Vertx getVertx() {
		return vertx;
	}

	/**
	 * Returns the Vert.x context associated with this Verticle.
	 *
	 * @return the Vert.x context
	 */
	public final Context vertxContext() {
		return vertxContext;
	}

	/**
	 * Returns the deployment ID of this Verticle deployment.
	 *
	 * @return the deployment ID
	 */
	public final String deploymentID() {
		return vertxContext.deploymentID();
	}

	/**
	 * Returns the configuration object of this Verticle deployment.
	 * <p>
	 * This configuration can be specified when the Verticle is deployed.
	 * </p>
	 *
	 * @return the configuration as a {@link JsonObject}
	 */
	public final JsonObject vertxConfig() {
		return vertxContext.config();
	}

	/**
	 * Returns the process arguments for the current Vert.x instance.
	 *
	 * @return a list of process arguments
	 */
	public final List<String> processArgs() {
		return vertxContext.processArgs();
	}

	/**
	 * Initializes the Verticle.
	 * <p>
	 * This method is called by Vert.x when the Verticle instance is deployed.
	 * User code should not call this directly.
	 * </p>
	 *
	 * @param vertx   the Vert.x instance
	 * @param context the context associated with this Verticle
	 */
	public final void init(Vertx vertx, Context context) {
		prepare(vertx, context);
	}

	/**
	 * Prepares this verticle for execution.
	 * <p>
	 * This method is invoked internally by {@link #init(Vertx, Context)} and can be overridden
	 * if additional setup is needed before deployment.
	 * </p>
	 *
	 * @param vertx   the Vert.x instance
	 * @param context the Vert.x context
	 */
	public void prepare(Vertx vertx, Context context) {
		this.vertx = vertx;
		this.vertxContext = context;
	}

	/**
	 * Called when the Verticle is started.
	 * <p>
	 * This implementation delegates to {@link #deploy()}, which should return a {@link Future}
	 * that completes when startup is done.
	 * </p>
	 *
	 * @param startPromise a promise that should be completed when startup is done
	 * @throws Exception if startup fails
	 */
	public final void start(Promise<Void> startPromise) throws Exception {
		deploy().onComplete(startPromise);
	}

	/**
	 * Called when the Verticle is stopped.
	 * <p>
	 * This implementation delegates to {@link #undeploy()}, which should return a {@link Future}
	 * that completes when shutdown is done.
	 * </p>
	 *
	 * @param stopPromise a promise that should be completed when shutdown is done
	 * @throws Exception if shutdown fails
	 */
	public final void stop(Promise<Void> stopPromise) throws Exception {
		undeploy().onComplete(stopPromise);
	}

	/**
	 * Called during startup to perform asynchronous initialization logic.
	 * <p>
	 * This method is invoked by {@link #start(Promise)}.
	 * </p>
	 *
	 * @return a future that completes when setup is finished
	 */
	public abstract Future<Void> deploy();

	/**
	 * Called during shutdown to perform asynchronous cleanup logic.
	 * <p>
	 * This method is invoked by {@link #stop(Promise)}.
	 * </p>
	 *
	 * @return a future that completes when teardown is finished
	 */
	public abstract Future<Void> undeploy();

	/**
	 * Internal helper method to simulate deployment under Vert.x 5.x’s {@code Deployable} interface.
	 * <p>
	 * This should not be called directly by user code. It is used by Vert.x internals or
	 * integration layers that work with Vert.x 5.x’s deployment model.
	 * </p>
	 *
	 * @param context the Vert.x context
	 * @return a future that completes when deployment is finished
	 * @throws Exception if deployment fails
	 */
	public final Future<?> deploy(Context context) throws Exception {
		prepare(context.owner(), context);
		ContextInternal internal = (ContextInternal) context;
		Promise<Void> promise = internal.promise();
		try {
			deploy().onComplete(promise);
		} catch (Throwable t) {
			if (!promise.tryFail(t))
				internal.reportException(t);
		}
		return promise.future();
	}

	/**
	 * Internal helper method to simulate undeployment under Vert.x 5.x’s {@code Deployable} interface.
	 * <p>
	 * This should not be called directly by user code. It is used by Vert.x internals or
	 * integration layers that work with Vert.x 5.x’s deployment model.
	 * </p>
	 *
	 * @param context the Vert.x context
	 * @return a future that completes when undeployment is finished
	 * @throws Exception if undeployment fails
	 */
	public final Future<?> undeploy(Context context) throws Exception {
		ContextInternal internal = (ContextInternal) context;
		Promise<Void> promise = internal.promise();
		try {
			undeploy().onComplete(promise);
		} catch (Throwable t) {
			if (!promise.tryFail(t))
				internal.reportException(t);
		}
		return promise.future();
	}

	/**
	 * Executes the given handler on this verticle's context.
	 *
	 * @param action the handler to run
	 */
	public void runOnContext(Handler<Void> action) {
		vertxContext.runOnContext(action);
	}


	/**
	 * Executes blocking code asynchronously, returning a {@link Future} that completes
	 * when the blocking operation is done.
	 *
	 * @param blockingCodeHandler the blocking code to execute
	 * @param <T> the result type
	 * @return a future representing the blocking operation result
	 */
	public <T> Future<T> executeBlocking(Callable<T> blockingCodeHandler) {
		return vertxContext.executeBlocking(blockingCodeHandler);
	}

	/**
	 * Executes blocking code asynchronously, optionally ordering execution relative
	 * to other blocking operations in the same context.
	 *
	 * @param blockingCodeHandler the blocking code to execute
	 * @param ordered             whether execution should be ordered
	 * @param <T> the result type
	 * @return a future representing the blocking operation result
	 */
	public <T> Future<T> executeBlocking(Callable<T> blockingCodeHandler, boolean ordered) {
		return vertxContext.executeBlocking(blockingCodeHandler, ordered);
	}
}