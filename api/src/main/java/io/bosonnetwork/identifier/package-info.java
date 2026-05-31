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

/**
 * Decentralized identity for Boson: a W3C-aligned DID / Verifiable Credential layer anchored to
 * Boson {@link io.bosonnetwork.Id} identities and resolved over the DHT.
 *
 * <h2>Identity documents and claims</h2>
 * <ul>
 *   <li>{@link io.bosonnetwork.identifier.DIDDocument} — the DID document for an identity, with
 *       {@link io.bosonnetwork.identifier.VerificationMethod}s and embedded credentials;</li>
 *   <li>{@link io.bosonnetwork.identifier.Card} — a Boson profile/business-card object;</li>
 *   <li>{@link io.bosonnetwork.identifier.Credential} and
 *       {@link io.bosonnetwork.identifier.VerifiableCredential} — issued claims about a subject;</li>
 *   <li>{@link io.bosonnetwork.identifier.VerifiablePresentation} and
 *       {@link io.bosonnetwork.identifier.Vouch} — holder-presented bundles of credentials;</li>
 *   <li>{@link io.bosonnetwork.identifier.Proof} — the Ed25519 signature/proof attached to the
 *       above objects.</li>
 * </ul>
 * Each object has a fluent {@code *Builder} (for example
 * {@link io.bosonnetwork.identifier.CardBuilder}, {@link io.bosonnetwork.identifier.CredentialBuilder},
 * {@link io.bosonnetwork.identifier.VerifiableCredentialBuilder}) that signs on build.
 *
 * <h2>Addressing and resolution</h2>
 * {@link io.bosonnetwork.identifier.DIDURL} parses and builds {@code did:boson:} URLs;
 * {@link io.bosonnetwork.identifier.DIDConstants} and {@link io.bosonnetwork.identifier.W3CDIDFormat}
 * hold the shared constants. {@link io.bosonnetwork.identifier.Resolver} /
 * {@link io.bosonnetwork.identifier.Registry} define DID resolution and publication, with a
 * DHT-backed implementation ({@link io.bosonnetwork.identifier.DHTResolver},
 * {@link io.bosonnetwork.identifier.DHTRegistry}) and a
 * {@link io.bosonnetwork.identifier.ResolutionCache} (e.g.
 * {@link io.bosonnetwork.identifier.FileSystemResolutionCache}) fronted by
 * {@link io.bosonnetwork.identifier.CachedResolver}.
 *
 * <h2>References</h2>
 * <ul>
 *   <li><a href="https://www.w3.org/TR/did-core/">W3C DID Core</a></li>
 *   <li><a href="https://www.w3.org/TR/vc-data-model/">W3C Verifiable Credentials Data Model</a></li>
 * </ul>
 */
package io.bosonnetwork.identifier;
