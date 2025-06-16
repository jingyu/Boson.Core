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

package io.bosonnetwork.identifier;

import java.util.Date;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;
import io.bosonnetwork.Node;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.CryptoBox;
import io.bosonnetwork.utils.vertx.VertxFuture;

class DHTRegistry implements Registry {
	private Vertx vertx;
	private Node node;
	private ResolverCache persistentCache;
	private Resolver resolver;

	private static DHTRegistry instance;
	private static final Logger log = LoggerFactory.getLogger(DHTRegistry.class);

	private DHTRegistry() {
	}

	protected static Registry getInstance() {
		if (instance == null)
			instance = new DHTRegistry();

		return instance;
	}

	@Override
	public CompletableFuture<Void> initialize(Object... args) {
		if (vertx != null && node != null)
			return VertxFuture.failedFuture(new IllegalStateException("Already initialized"));

		if (args.length < 2 || args.length > 3)
			return VertxFuture.failedFuture(new IllegalArgumentException("Invalid arguments length"));

		if (args[0] instanceof Vertx v)
			this.vertx = v;
		else
			return VertxFuture.failedFuture(new IllegalArgumentException("Invalid arguments: args[0] is not a Vertx instance"));

		if (args[1] instanceof Node n)
			this.node = n;
		else
			return VertxFuture.failedFuture(new IllegalArgumentException("Invalid arguments: args[1] is not a Node instance"));

		if (args.length == 3) {
			if (args[2] instanceof ResolverCache c)
				this.persistentCache = c;
			else
				return VertxFuture.failedFuture(new IllegalArgumentException("Invalid arguments: args[2] is not a ResolverCache instance"));
		} else {
			this.persistentCache = null;
		}

		this.resolver = new DHTResolver();

		return VertxFuture.succeededFuture();
	}

	@Override
	public CompletableFuture<Void> register(Card card, CryptoBox.Nonce nonce, int version, byte[] signature) {
		Value value = Value.of(card.getId(), nonce.bytes(), version, signature, card.toBytes());
		if (!value.isValid())
			return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid signature"));

		log.debug("Registering card {} ...", card.getId());
		return node.storeValue(value, true);
	}

	@Override
	public Resolver getResolver() {
		return resolver;
	}

	class DHTResolver extends AbstractResolver {
		public DHTResolver() {
			super(vertx, persistentCache);
		}

		@Override
		protected CompletableFuture<ResolutionResult<Card>> resolveId(Id id) {
			Objects.requireNonNull(id, "id");

			log.debug("Resolving {} ...", id);
			return node.findValue(id).thenApply(value -> {
				if (value == null)
					return ResolutionResult.notfound();

				if (!Objects.equals(id, value.getPublicKey()))
					return ResolutionResult.invalid();

				Card card;
				try {
					card = Card.parse(value.getData());
				} catch (Exception e) {
					return ResolutionResult.invalid();
				}

				if (!card.isGenuine())
					return ResolutionResult.invalid();

				int version = value.getSequenceNumber();

				return new ResolutionResult<>(card, new ResolutionResultMetadata(
						card.getSignedAt(), card.getSignedAt(), new Date(), false, version));
			});
		}

		@Override
		protected Logger log() {
			return log;
		}
	}
}