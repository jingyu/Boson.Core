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

/**
 * Public APIs for building and hosting <em>layer-2 services</em> on top of a Boson super node.
 * <p>
 * A layer-2 service (web gateway, ion store, photon messaging, custom services, …) is published
 * by an operator's super node and consumed by end users (clients) or by other super nodes
 * (federation peers). This package defines the contracts on both sides of that boundary.
 *
 * <h2>Authoring a service: {@link io.bosonnetwork.service.BosonService} +
 * {@link io.bosonnetwork.service.BosonServiceFactory}</h2>
 * A service implements {@link io.bosonnetwork.service.BosonService} (identity, public endpoint,
 * lifecycle: {@code init} → {@code start} → {@code stop}) and ships a
 * {@link io.bosonnetwork.service.BosonServiceFactory} discovered via Java
 * {@link java.util.ServiceLoader} (a {@code META-INF/services/} entry, or a {@code provides}
 * declaration on the module path). The hosting super node loads factories by
 * {@link io.bosonnetwork.service.BosonServiceFactory#getType() type} and instantiates the
 * configured ones.
 *
 * <h2>Runtime: the three contexts</h2>
 * On {@code init}, the host hands the service a
 * {@link io.bosonnetwork.service.ServiceContext}, a one-stop runtime handle:
 * <ul>
 *   <li>{@link io.bosonnetwork.service.ServiceContext#getNode() node},
 *       {@link io.bosonnetwork.service.ServiceContext#getDataDir() data dir},
 *       {@link io.bosonnetwork.service.ServiceContext#getConfiguration() configuration}, and
 *       an {@link io.bosonnetwork.service.ServiceContext#unwrap(Class) unwrap} hook for
 *       infrastructure components (e.g. Vert.x);</li>
 *   <li>a {@link io.bosonnetwork.service.ClientContext} for looking up end-user identities
 *       (users + their devices) — see below;</li>
 *   <li>a {@link io.bosonnetwork.service.FederationContext} for talking to other super nodes
 *       and the services they host.</li>
 * </ul>
 *
 * <h2>Client vs. federation</h2>
 * The two "sides" each carry their own auth surface:
 * <ul>
 *   <li><strong>Client side</strong> ({@link io.bosonnetwork.service.ClientContext}) — read-only
 *       lookups of {@link io.bosonnetwork.service.ClientUser} / {@link io.bosonnetwork.service.ClientDevice},
 *       plus a {@link io.bosonnetwork.service.ClientAuthenticator} for verifying who they are and
 *       a {@link io.bosonnetwork.service.ClientAuthorizer} for deciding what they can do.</li>
 *   <li><strong>Federation side</strong> ({@link io.bosonnetwork.service.FederationContext}) —
 *       lookup/probe of {@link io.bosonnetwork.service.SuperNodeInfo} /
 *       {@link io.bosonnetwork.service.ServiceInfo}, plus a
 *       {@link io.bosonnetwork.service.FederationAuthenticator} for verifying peer node/service
 *       identity.</li>
 * </ul>
 * Both authenticator interfaces share a "nonce/signature contract" (see their interface Javadoc):
 * non-null nonce+signature ⇒ verify; both null ⇒ pre-authenticated mode; exactly one null ⇒ caller bug.
 *
 * <h2>Identity and access scopes</h2>
 * Principal entitlements are exposed through {@link io.bosonnetwork.service.Role} (server-derived
 * tiers: client / admin / federation) and {@link io.bosonnetwork.service.AccessScope}
 * (the over-the-wire {@code api:*} scope strings). See
 * {@link io.bosonnetwork.web.CwtAuth} for how the web layer maps a resolved principal into
 * Vert.x {@code Authorizations}.
 *
 * <h2>Test/demo helpers</h2>
 * The {@code impl/} subpackage ships ready-made contexts for testing and bring-up:
 * {@link io.bosonnetwork.service.ClientContext#allowAll(io.bosonnetwork.Identity) allowAll},
 * {@link io.bosonnetwork.service.ClientContext#staticContext(io.bosonnetwork.Identity) staticContext},
 * {@link io.bosonnetwork.service.FederationContext#disabled() disabled},
 * {@link io.bosonnetwork.service.FederationContext#allowAll(io.bosonnetwork.Identity) allowAll},
 * and {@link io.bosonnetwork.service.FederationContext#staticContext(io.bosonnetwork.Identity) staticContext}.
 */
package io.bosonnetwork.service;