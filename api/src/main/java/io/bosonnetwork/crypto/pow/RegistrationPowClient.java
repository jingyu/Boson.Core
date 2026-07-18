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

package io.bosonnetwork.crypto.pow;

import java.util.Objects;

import io.bosonnetwork.crypto.Signature;

/**
 * The client side of the registration proof-of-work (see director/docs/RegistrationPoW.md).
 *
 * <p>This helper is transport-agnostic: the caller fetches the challenge over whatever transport it
 * uses (for the Director this is {@code GET /api/v1/client/users/challenge}), passes the challenge
 * parameters here to solve the memory-hard puzzle and sign the result, and then submits the returned
 * fields on the registration request. Keeping it in {@code core} lets every client - the Photon
 * Android app, CLI tools, services - reuse one implementation with no extra dependency.
 */
public final class RegistrationPowClient {
	private RegistrationPowClient() {
	}

	/**
	 * The solved, signed fields a client attaches to its registration request.
	 *
	 * @param powNonce the 8-byte proof-of-work nonce (big-endian).
	 * @param solution the Equihash solution indices.
	 * @param signature the identity's signature over the registration signing message.
	 */
	public record Result(byte[] powNonce, int[] solution, byte[] signature) {
	}

	/**
	 * Solves the challenge for an identity and signs the result with its key.
	 *
	 * @param superNodeId  the 32-byte super node id (from {@code GET /client/id}).
	 * @param signerKey    the identity keypair that will own the registration (user or device key).
	 * @param n            the Equihash bit width from the challenge.
	 * @param k            the Equihash rounds from the challenge.
	 * @param effort       the effort target from the challenge.
	 * @param challengeNonce     the challenge nonce.
	 * @param maxPowNonces the search budget (number of nonces to try before failing).
	 * @return the fields to submit with the registration request.
	 * @throws IllegalStateException if no solution is found within the budget.
	 */
	public static Result solve(byte[] superNodeId, Signature.KeyPair signerKey, int n, int k,
			int effort, byte[] challengeNonce, long maxPowNonces) {
		Objects.requireNonNull(superNodeId, "superNodeId");
		Objects.requireNonNull(signerKey, "signerKey");
		Objects.requireNonNull(challengeNonce, "challengeNonce");

		byte[] pubkey = signerKey.publicKey().bytes();
		byte[] seed = ProofOfWork.seed(superNodeId, pubkey, challengeNonce);

		ProofOfWork.Solution solution = ProofOfWork.solve(seed, n, k, effort, maxPowNonces);
		if (solution == null)
			throw new IllegalStateException("No proof-of-work found within the nonce budget");

		byte[] powNonce = ProofOfWork.encodeNonce(solution.powNonce());
		byte[] message = ProofOfWork.signingMessage(superNodeId, pubkey, challengeNonce, powNonce, effort);
		byte[] signature = signerKey.privateKey().sign(message);
		return new Result(powNonce, solution.indices(), signature);
	}

	/**
	 * Signs the registration message for an additional identity that shares the same solution (for
	 * example the initial device key in {@code POST /client/usersAndInitialDevice}).
	 *
	 * @param superNodeId the 32-byte super node id.
	 * @param signerKey   the additional identity keypair.
	 * @param challengeNonce    the challenge nonce.
	 * @param powNonce    the 8-byte proof-of-work nonce from a prior {@link #solve} result.
	 * @param effort      the effort target from the challenge.
	 * @return the signature over the shared registration signing message.
	 */
	public static byte[] sign(byte[] superNodeId, Signature.KeyPair signerKey, byte[] challengeNonce,
			byte[] powNonce, int effort) {
		byte[] message = ProofOfWork.signingMessage(superNodeId, signerKey.publicKey().bytes(),
				challengeNonce, powNonce, effort);
		return signerKey.privateKey().sign(message);
	}
}
