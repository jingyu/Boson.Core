package io.bosonnetwork;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.bosonnetwork.crypto.Hash;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.json.Json;
import io.bosonnetwork.utils.Hex;

public class ValueTests {
	@Test
	void testImmutableValue() {
		byte[] data = "Hello Boson".getBytes();
		Value value = Value.builder().data(data).build();

		assertNotNull(value);
		assertNotNull(value.getId());
		assertArrayEquals(Hash.sha256(data), value.getId().getBytes());
		assertFalse(value.isMutable());
		assertFalse(value.isEncrypted());
		assertNull(value.getPublicKey());
		assertNull(value.getRecipient());
		assertNull(value.getNonce());
		assertEquals(0, value.getSequenceNumber());
		assertNull(value.getSignature());
		assertArrayEquals(data, value.getData());
		assertTrue(value.isValid());

		assertThrows(UnsupportedOperationException.class, () -> value.update(data));

		value.getData()[0] = (byte) (value.getData()[0] + 1);
		assertFalse(value.isValid());

		Value value2 = value.withoutPrivateKey();
		assertSame(value, value2);
	}

	@Test
	void testSignedValue() {
		byte[] data = "Mutable data".getBytes();
		Value value = Value.builder().data(data).buildSigned();

		assertNotNull(value);
		assertTrue(value.hasPrivateKey());
		assertTrue(value.isMutable());
		assertFalse(value.isEncrypted());
		assertNotNull(value.getPublicKey());
		assertEquals(value.getPublicKey(), value.getId());
		assertNull(value.getRecipient());
		assertNotNull(value.getNonce());
		assertEquals(0, value.getSequenceNumber());
		assertNotNull(value.getSignature());
		assertArrayEquals(data, value.getData());
		assertTrue(value.isValid());

		byte[] data1 = "Updated mutable data".getBytes();
		Value value1 = value.update(data1);

		assertNotNull(value1);
		assertTrue(value1.hasPrivateKey());
		assertEquals(value.getId(), value1.getId());
		assertTrue(value1.isMutable());
		assertFalse(value1.isEncrypted());
		assertEquals(value.getPublicKey(), value1.getPublicKey());
		assertNull(value1.getRecipient());
		assertNotNull(value1.getNonce());
		assertFalse(Arrays.equals(value.getNonce(), value1.getNonce()));
		assertEquals(1, value1.getSequenceNumber());
		assertNotNull(value1.getSignature());
		assertArrayEquals(data1, value1.getData());
		assertTrue(value1.isValid());

		byte[] data2 = "Updated mutable data 2".getBytes();
		Value value2 = value1.update(data2);

		assertNotNull(value2);
		assertTrue(value2.hasPrivateKey());
		assertEquals(value.getId(), value2.getId());
		assertTrue(value2.isMutable());
		assertFalse(value2.isEncrypted());
		assertEquals(value.getPublicKey(), value2.getPublicKey());
		assertNull(value2.getRecipient());
		assertNotNull(value.getNonce());
		assertFalse(Arrays.equals(value1.getNonce(), value2.getNonce()));
		assertEquals(2, value2.getSequenceNumber());
		assertNotNull(value2.getSignature());
		assertArrayEquals(data2, value2.getData());
		assertTrue(value2.isValid());

		Value value3 = value2.update(data2);
		assertSame(value2, value3);

		Value value4 = value3.withoutPrivateKey();
		assertFalse(value4.hasPrivateKey());
		assertEquals(value3, value4);
		assertThrows(UnsupportedOperationException.class, () -> value4.update("should be failed".getBytes()));
		assertThrows(UnsupportedOperationException.class, () -> value4.decryptData(Signature.KeyPair.random().privateKey()));

		value.getData()[0] = (byte) (value.getData()[0] + 1);
		assertFalse(value.isValid());
	}

	@Test
	void testEncryptedValue() throws Exception {
		byte[] data = "Secret message".getBytes();
		Signature.KeyPair recipientKp = Signature.KeyPair.random();
		Id recipient = Id.of(recipientKp.publicKey().bytes());

		Value value = Value.builder().recipient(recipient).data(data).buildEncrypted();

		assertNotNull(value);
		assertTrue(value.isMutable());
		assertTrue(value.isEncrypted());
		assertNotNull(value.getPublicKey());
		assertEquals(value.getPublicKey(), value.getId());
		assertEquals(recipient, value.getRecipient());
		assertNotNull(value.getNonce());
		assertEquals(0, value.getSequenceNumber());
		assertNotNull(value.getSignature());
		assertFalse(Arrays.equals(data, value.getData())); // Data should be encrypted
		assertTrue(value.isValid());

		byte[] decrypted = value.decryptData(recipientKp.privateKey());
		assertArrayEquals(data, decrypted);

		byte[] data1 = "Updated secret message".getBytes();
		Value value1 = value.update(data1);

		assertNotNull(value1);
		assertTrue(value1.hasPrivateKey());
		assertEquals(value.getId(), value1.getId());
		assertTrue(value1.isMutable());
		assertTrue(value1.isEncrypted());
		assertEquals(value.getPublicKey(), value1.getPublicKey());
		assertEquals(recipient, value1.getRecipient());
		assertNotNull(value1.getNonce());
		assertFalse(Arrays.equals(value.getNonce(), value1.getNonce()));
		assertEquals(1, value1.getSequenceNumber());
		assertNotNull(value1.getSignature());
		assertFalse(Arrays.equals(data1, value1.getData())); // Data should be encrypted
		assertTrue(value1.isValid());

		decrypted = value1.decryptData(recipientKp.privateKey());
		assertArrayEquals(data1, decrypted);

		byte[] data2 = "Updated secret message 2".getBytes();
		Value value2 = value1.update(data2);

		assertNotNull(value2);
		assertTrue(value2.hasPrivateKey());
		assertEquals(value.getId(), value2.getId());
		assertTrue(value2.isMutable());
		assertTrue(value2.isEncrypted());
		assertEquals(value.getPublicKey(), value2.getPublicKey());
		assertEquals(recipient, value1.getRecipient());
		assertNotNull(value.getNonce());
		assertFalse(Arrays.equals(value1.getNonce(), value2.getNonce()));
		assertEquals(2, value2.getSequenceNumber());
		assertNotNull(value2.getSignature());
		assertFalse(Arrays.equals(data2, value2.getData())); // Data should be encrypted
		assertTrue(value2.isValid());

		decrypted = value2.decryptData(recipientKp.privateKey());
		assertArrayEquals(data2, decrypted);

		Value value3 = value2.update(data2);
		assertNotSame(value2, value3);
		assertNotEquals(value2, value3);
		assertFalse(Arrays.equals(value2.getNonce(), value3.getNonce()));
		assertEquals(3, value3.getSequenceNumber());

		decrypted = value3.decryptData(recipientKp.privateKey());
		assertArrayEquals(data2, decrypted);

		Value value4 = value3.withoutPrivateKey();
		assertFalse(value4.hasPrivateKey());
		assertEquals(value3, value4);
		assertThrows(UnsupportedOperationException.class, () -> value4.update("should be failed".getBytes()));
		assertThrows(IllegalArgumentException.class, () -> value4.decryptData(Signature.KeyPair.random().privateKey()));

		value4.getData()[0] = (byte) (value4.getData()[0] + 1);
		assertFalse(value4.isValid());
		assertThrows(IllegalStateException.class, () -> value4.decryptData(recipientKp.privateKey()));
	}

	@Test
	void testInvalidValue() {
		assertFalse(Value.of(Id.random(), "data".getBytes()).isValid());

		byte[] data = "data".getBytes();
		Id pk = Id.random();
		byte[] nonce = Random.randomBytes(Value.NONCE_BYTES);
		byte[] sig = Random.randomBytes(Signature.BYTES);

		// Invalid sequence number
		assertThrows(IllegalArgumentException.class, () -> Value.of(pk, null, nonce, -1, sig, data));

		// Invalid nonce length
		assertThrows(IllegalArgumentException.class, () -> Value.of(pk, null, new byte[10], 0, sig, data));

		// Invalid signature length
		assertThrows(IllegalArgumentException.class, () -> Value.of(pk, null, nonce, 0, new byte[10], data));
	}

	@Test
	void testEqualsAndHashCode() {
		byte[] data = "data".getBytes();
		Value v1 = Value.builder().data(data).build();
		Value v2 = Value.builder().data(data).build();
		Value v3 = Value.builder().data(data).buildSigned();
		Value v4 = Value.builder().data(data).buildSigned();

		assertEquals(v1, v2);
		assertEquals(v1.hashCode(), v2.hashCode());
		assertNotEquals(v3, v4);
		assertNotEquals(v1, v3);
	}

	@ParameterizedTest
	@ValueSource(strings = {"immutable", "signed", "encrypted"})
	void testJson(String mode) throws Exception {
		Value v = switch (mode) {
			case "immutable" -> Value.builder()
					.data("Hello from bosonnetwork!\n".repeat(10).getBytes())
					.build();
			case "signed" -> Value.builder()
					.data("Hello from bosonnetwork!\n".repeat(10).getBytes())
					.buildSigned();
			case "encrypted" -> Value.builder()
					.recipient(Id.of(Signature.KeyPair.random().publicKey().bytes()))
					.data("Hello from bosonnetwork!\n".repeat(10).getBytes())
					.buildEncrypted();
			default -> throw new AssertionError("Unknown mode: " + mode);
		};

		String json = Json.toString(v);
		System.out.println(json);
		System.out.println(Json.toPrettyString(v));

		Value v2 = Json.parse(json, Value.class);
		assertEquals(v, v2);

		String json2 = Json.toString(v2);
		assertEquals(json, json2);
	}

	@ParameterizedTest
	@ValueSource(strings = {"immutable", "signed", "encrypted"})
	void testCbor(String mode) throws Exception {
		Value v = switch (mode) {
			case "immutable" -> Value.builder()
					.data("Hello from bosonnetwork!\n".repeat(10).getBytes())
					.build();
			case "signed" -> Value.builder()
					.data("Hello from bosonnetwork!\n".repeat(10).getBytes())
					.buildSigned();
			case "encrypted" -> Value.builder()
					.recipient(Id.of(Signature.KeyPair.random().publicKey().bytes()))
					.data("Hello from bosonnetwork!\n".repeat(10).getBytes())
					.buildEncrypted();
			default -> throw new AssertionError("Unknown mode: " + mode);
		};

		byte[] cbor = Json.toBytes(v);
		System.out.println(Hex.encode(cbor));
		System.out.println(Json.toPrettyString(Json.parse(cbor)));

		Value v2 = Json.parse(cbor, Value.class);
		assertEquals(v, v2);

		byte[] cbor2 = Json.toBytes(v2);
		assertArrayEquals(cbor, cbor2);
	}
}