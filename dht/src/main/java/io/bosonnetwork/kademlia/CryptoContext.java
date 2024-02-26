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

package io.bosonnetwork.kademlia;

import java.util.Arrays;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.CryptoBox;
import io.bosonnetwork.crypto.CryptoBox.KeyPair;
import io.bosonnetwork.crypto.CryptoBox.Nonce;
import io.bosonnetwork.crypto.CryptoBox.PublicKey;
import io.bosonnetwork.crypto.CryptoException;
import io.bosonnetwork.kademlia.exceptions.CryptoError;

/**
 * @hidden
 */
public class CryptoContext implements AutoCloseable {
	private CryptoBox box;
	private Nonce nextNonce;
	private Nonce lastPeerNonce;

	public CryptoContext(Id id, KeyPair keyPair) throws CryptoError {
		try {
			PublicKey pk = id.toEncryptionKey();
			box = CryptoBox.fromKeys(pk, keyPair.privateKey());
			nextNonce = Nonce.random();
		} catch (CryptoException e) {
			throw new CryptoError(e.getMessage(), e);
		}
	}

	private synchronized Nonce getAndIncrementNonce() {
		Nonce nonce = nextNonce;
		nextNonce = nonce.increment();
		return nonce;
	}

	public byte[] encrypt(byte[] plain) throws CryptoError {
		try {
			// TODO: how to avoid the memory copy?!
			Nonce nonce = getAndIncrementNonce();
			byte[] cipher = box.encrypt(plain, nonce);

			byte[] buf = new byte[Nonce.BYTES + cipher.length];
			System.arraycopy(nonce.bytes(), 0, buf, 0, Nonce.BYTES);
			System.arraycopy(cipher, 0, buf, Nonce.BYTES, cipher.length);
			return buf;
		} catch (CryptoException e) {
			throw new CryptoError(e.getMessage(), e);
		}
	}

	public byte[] decrypt(byte[] cipher) throws CryptoError {
		try {
			if (cipher.length <= Nonce.BYTES + CryptoBox.MAC_BYTES)
				throw new CryptoError("Invalid cipher size");

			// TODO: how to avoid the memory copy?!
			byte[] n = Arrays.copyOfRange(cipher, 0, Nonce.BYTES);
			Nonce nonce = Nonce.fromBytes(n);
			if (lastPeerNonce != null && nonce.equals(lastPeerNonce))
				throw new CryptoError("Duplicated nonce");

			lastPeerNonce = nonce;

			byte[] m = Arrays.copyOfRange(cipher, Nonce.BYTES, cipher.length);

			return box.decrypt(m, nonce);
		} catch (CryptoException e) {
			throw new CryptoError(e.getMessage(), e);
		}
	}

	@Override
	public void close() {
		box.close();
	}
}
