package io.bosonnetwork.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class SecretStreamTests {
	@ParameterizedTest
	@ValueSource(strings = { "", "Test additional data"})
	void testEncryptionDecryption(String additionalData) throws Exception {
		byte[] additional = additionalData.isEmpty() ? null : additionalData.getBytes();

		byte[] key = Random.randomBytes(32);

		byte[] data = new byte[1024 * 1024 * 8 + 123];
		Random.random().nextBytes(data);

		int chunkSize = 4096;
		byte[] encrypted;

		try (SecretStream.EncryptionStream stream = SecretStream.encryptionStream(key);
			 ByteArrayInputStream in = new ByteArrayInputStream(data);
		     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			byte[] header = stream.header();
			assertEquals(SecretStream.HEADER_BYTES, header.length);
			out.write(header);

			int len;
			byte[] chunk = new byte[chunkSize];

			while ((len = in.read(chunk)) != -1) {
				byte[] plain = len == chunkSize ? chunk : Arrays.copyOf(chunk, len);
				byte[] cipher = stream.push(plain, additional, len < chunkSize);
				assertEquals(len + SecretStream.ABYTES, cipher.length);
				out.write(cipher);
			}

			encrypted = out.toByteArray();
		}

		byte[] decrypted;
		try (ByteArrayInputStream in = new ByteArrayInputStream(encrypted);
		     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			byte[] header = new byte[SecretStream.HEADER_BYTES];
			in.read(header);
			try (SecretStream.DecryptionStream stream = SecretStream.decryptionStream(header, key)) {
				int len;
				byte[] chunk = new byte[chunkSize + SecretStream.ABYTES];
				while ((len = in.read(chunk)) != -1) {
					byte[] cipher = len == chunkSize ? chunk : Arrays.copyOf(chunk, len);
					byte[] plain = stream.pull(cipher, additional);
					assertEquals(len - SecretStream.ABYTES, plain.length);
					out.write(plain);
				}

				decrypted = out.toByteArray();
			}
		}

		assertEquals(data.length, decrypted.length);
		assertArrayEquals(data, decrypted);
	}

	@Test
	void testInvalidKeyAndHeaderLength() {
		byte[] invalidKey = new byte[16];
		byte[] validKey = Random.randomBytes(32);
		byte[] invalidHeader = new byte[10];

		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
				SecretStream.encryptionStream(invalidKey));

		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
				SecretStream.decryptionStream(invalidHeader, validKey));
	}

	@Test
	void testStreamClosedAndCompletedState() throws Exception {
		byte[] key = Random.randomBytes(32);
		byte[] msg = "Hello World".getBytes();

		SecretStream.EncryptionStream encStream = SecretStream.encryptionStream(key);
		byte[] cipher = encStream.pushLast(msg);
		org.junit.jupiter.api.Assertions.assertTrue(encStream.isComplete());

		org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
				encStream.push(msg));

		encStream.close();

		SecretStream.DecryptionStream decStream = SecretStream.decryptionStream(encStream.header(), key);
		byte[] plain = decStream.pull(cipher);
		assertArrayEquals(msg, plain);
		org.junit.jupiter.api.Assertions.assertTrue(decStream.isComplete());

		org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
				decStream.pull(cipher));

		decStream.close();
	}
}