package io.bosonnetwork.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

public class BytesTests {
	@Test
	public void testIntegerConversion() {
		int[] values = {0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, 123456789, -987654321};
		for (int value : values) {
			byte[] bytes = Bytes.fromInteger(value);
			ByteBuffer buffer = ByteBuffer.allocate(4);
			buffer.putInt(value);
			assertArrayEquals(buffer.array(), bytes, "fromInteger failed for value: " + value);

			assertEquals(value, Bytes.toInteger(bytes), "toInteger failed for value: " + value);
		}
	}

	@Test
	public void testLongConversion() {
		long[] values = {0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 1234567890123456789L, -987654321098765432L};
		for (long value : values) {
			byte[] bytes = Bytes.fromLong(value);
			ByteBuffer buffer = ByteBuffer.allocate(8);
			buffer.putLong(value);
			assertArrayEquals(buffer.array(), bytes, "fromLong failed for value: " + value);

			assertEquals(value, Bytes.toLong(bytes), "toLong failed for value: " + value);
		}
	}
	
	@Test
	public void testShortConversion() {
		short[] values = {0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 12345, -12345};
		for (short value : values) {
			byte[] bytes = Bytes.fromShort(value);
			ByteBuffer buffer = ByteBuffer.allocate(2);
			buffer.putShort(value);
			assertArrayEquals(buffer.array(), bytes, "fromShort failed for value: " + value);

			assertEquals(value, Bytes.toShort(bytes), "toShort failed for value: " + value);
		}
	}

	@Test
	public void testIntegerOffset() {
		byte[] bytes = new byte[10];
		int value = 0x12345678;
		ByteBuffer buffer = ByteBuffer.allocate(4);
		buffer.putInt(value);
		System.arraycopy(buffer.array(), 0, bytes, 3, 4);

		assertEquals(value, Bytes.toInteger(bytes, 3), "toInteger with offset failed");
	}

	@Test
	public void testLongOffset() {
		byte[] bytes = new byte[20];
		long value = 0x1234567890ABCDEFL;
		ByteBuffer buffer = ByteBuffer.allocate(8);
		buffer.putLong(value);
		System.arraycopy(buffer.array(), 0, bytes, 5, 8);

		assertEquals(value, Bytes.toLong(bytes, 5), "toLong with offset failed");
	}

	@Test
	public void testShortOffset() {
		byte[] bytes = new byte[10];
		short value = 0x1234;
		ByteBuffer buffer = ByteBuffer.allocate(2);
		buffer.putShort(value);
		System.arraycopy(buffer.array(), 0, bytes, 4, 2);

		assertEquals(value, Bytes.toShort(bytes, 4), "toShort with offset failed");
	}
}