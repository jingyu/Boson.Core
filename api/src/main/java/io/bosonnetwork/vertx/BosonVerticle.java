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
public abstract class BosonVerticle implements Deployable {
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
	protected final Context vertxContext() {
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
	protected final JsonObject vertxConfig() {
		return vertxContext.config();
	}

	/**
	 * Prepares this verticle for deployment.
	 * <p>
	 * This method is invoked internally by {@link #deploy(Context)} and can be overridden
	 * if additional setup is needed before deployment.
	 * </p>
	 *
	 * @param vertx   the Vert.x instance
	 * @param context the Vert.x context
	 */
	protected void prepare(Vertx vertx, Context context) {
		this.vertx = vertx;
		this.vertxContext = context;
	}

	/**
	 * Called during deployment to perform asynchronous initialization logic.
	 * <p>
	 * This method is invoked by {@link #deploy(Context)}.
	 * </p>
	 *
	 * @return a future that completes when setup is finished
	 */
	protected abstract Future<Void> deploy();

	/**
	 * Called during undeployment to perform asynchronous cleanup logic.
	 * <p>
	 * This method is invoked by {@link #undeploy(Context)}.
	 * </p>
	 *
	 * @return a future that completes when teardown is finished
	 */
	protected abstract Future<Void> undeploy();

	/**
	 * Start the deployable.
	 * <p>
	 * Vert.x calls this method when deploying this deployable. You do not call it yourself.
	 *
	 * @param context the Vert.x context assigned to this deployable
	 * @return a future signaling the start-up completion
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
	 * Stop the deployable.
	 * <p>
	 * Vert.x calls this method when undeploying this deployable. You do not call it yourself.
	 *
	 * @param context the Vert.x context assigned to this deployable
	 * @return a future signaling the clean-up completion
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
	protected void runOnContext(Handler<Void> action) {
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
	protected <T> Future<T> executeBlocking(Callable<T> blockingCodeHandler) {
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
	protected <T> Future<T> executeBlocking(Callable<T> blockingCodeHandler, boolean ordered) {
		return vertxContext.executeBlocking(blockingCodeHandler, ordered);
	}
}

// BosonVerticle for Vert.x 4.5.x
// public abstract class BosonVerticle implements Verticle {
//	/**
//	 * Reference to the Vert.x instance that deployed this verticle
//	 */
//	protected Vertx vertx;
//
//	/**
//	 * Reference to the context of the verticle
//	 */
//	protected Context vertxContext;
//
//	/**
//	 * Returns the Vert.x instance that deployed this Verticle.
//	 *
//	 * @return the Vert.x instance
//	 */
//	public final Vertx getVertx() {
//		return vertx;
//	}
//
//	/**
//	 * Returns the Vert.x context associated with this Verticle.
//	 *
//	 * @return the Vert.x context
//	 */
//	protected final Context vertxContext() {
//		return vertxContext;
//	}
//
//	/**
//	 * Returns the deployment ID of this Verticle deployment.
//	 *
//	 * @return the deployment ID
//	 */
//	public final String deploymentID() {
//		return vertxContext.deploymentID();
//	}
//
//	/**
//	 * Returns the configuration object of this Verticle deployment.
//	 * <p>
//	 * This configuration can be specified when the Verticle is deployed.
//	 * </p>
//	 *
//	 * @return the configuration as a {@link JsonObject}
//	 */
//	protected final JsonObject vertxConfig() {
//		return vertxContext.config();
//	}
//
//	/**
//	 * Prepares this verticle for deployment.
//	 * <p>
//	 * This method is invoked internally by {@link #init(Vertx, Context)} and can be overridden
//	 * if additional setup is needed before deployment.
//	 * </p>
//	 *
//	 * @param vertx   the Vert.x instance
//	 * @param context the Vert.x context
//	 */
//	protected void prepare(Vertx vertx, Context context) {
//		this.vertx = vertx;
//		this.vertxContext = context;
//	}
//
//	/**
//	 * Called during startup to perform asynchronous initialization logic.
//	 * <p>
//	 * This method is invoked by {@link #start(Promise)}.
//	 * </p>
//	 *
//	 * @return a future that completes when setup is finished
//	 */
//	protected abstract Future<Void> deploy();
//
//	/**
//	 * Called during shutdown to perform asynchronous cleanup logic.
//	 * <p>
//	 * This method is invoked by {@link #stop(Promise)}.
//	 * </p>
//	 *
//	 * @return a future that completes when teardown is finished
//	 */
//	protected abstract Future<Void> undeploy();
//
//	/**
//	 * Initialise the verticle with the Vert.x instance and the context.
//	 * <p>
//	 * This method is called by Vert.x when the instance is deployed. You do not call it yourself.
//	 *
//	 * @param vertx  the Vert.x instance
//	 * @param context the context
//	 */
//	public final void init(Vertx vertx, Context context) {
//		prepare(vertx, context);
//	}
//
//	/**
//	 * Start the verticle instance.
//	 * <p>
//	 * Vert.x calls this method when deploying the instance. You do not call it yourself.
//	 * <p>
//	 * A promise is passed into the method, and when deployment is complete the verticle should either call
//	 * {@link io.vertx.core.Promise#complete} or {@link io.vertx.core.Promise#fail} the future.
//	 *
//	 * @param startPromise  the future
//	 */
//	public final void start(Promise<Void> startPromise) throws Exception {
//		deploy().onComplete(startPromise);
//	}
//
//	/**
//	 * Stop the verticle instance.
//	 * <p>
//	 * Vert.x calls this method when un-deploying the instance. You do not call it yourself.
//	 * <p>
//	 * A promise is passed into the method, and when un-deployment is complete the verticle should either call
//	 * {@link io.vertx.core.Promise#complete} or {@link io.vertx.core.Promise#fail} the future.
//	 *
//	 * @param stopPromise  the future
//	 */
//	public final void stop(Promise<Void> stopPromise) throws Exception {
//		undeploy().onComplete(stopPromise);
//	}
//
//	/**
//	 * Executes the given handler on this verticle's context.
//	 *
//	 * @param action the handler to run
//	 */
//	protected void runOnContext(Handler<Void> action) {
//		vertxContext.runOnContext(action);
//	}
//
//	/**
//	 * Executes blocking code asynchronously, returning a {@link Future} that completes
//	 * when the blocking operation is done.
//	 *
//	 * @param blockingCodeHandler the blocking code to execute
//	 * @param <T> the result type
//	 * @return a future representing the blocking operation result
//	 */
//	protected <T> Future<T> executeBlocking(Callable<T> blockingCodeHandler) {
//		return vertxContext.executeBlocking(blockingCodeHandler);
//	}
//
//	/**
//	 * Executes blocking code asynchronously, optionally ordering execution relative
//	 * to other blocking operations in the same context.
//	 *
//	 * @param blockingCodeHandler the blocking code to execute
//	 * @param ordered             whether execution should be ordered
//	 * @param <T> the result type
//	 * @return a future representing the blocking operation result
//	 */
//	protected <T> Future<T> executeBlocking(Callable<T> blockingCodeHandler, boolean ordered) {
//		return vertxContext.executeBlocking(blockingCodeHandler, ordered);
//	}
//}