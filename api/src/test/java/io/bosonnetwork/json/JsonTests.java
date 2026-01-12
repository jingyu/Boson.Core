package io.bosonnetwork.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.json.internal.DateFormat;
import io.bosonnetwork.utils.Hex;

public class JsonTests {
	@Test
	void inetAddressV4Test() throws Exception {
		var addr = InetAddress.getByName("192.168.8.8");

		var str = Json.toString(addr);
		System.out.println(str);
		assertEquals("\"192.168.8.8\"", str);

		var addr2 = Json.parse(str, InetAddress.class);
		assertEquals(addr, addr2);

		var bytes = Json.toBytes(addr);
		System.out.println(Hex.encode(bytes));
		assertEquals(5, bytes.length);
		assertArrayEquals(addr.getAddress(), Arrays.copyOfRange(bytes, 1, bytes.length));

		addr2 = Json.parse(bytes, InetAddress.class);
		assertEquals(addr, addr2);
	}

	@Test
	void inetAddressV6Test() throws Exception {
		var addr = InetAddress.getByName("2001:db8:85a2:0:0:8a2e:2370:7339");

		var str = Json.toString(addr);
		System.out.println(str);
		assertEquals("\"2001:db8:85a2:0:0:8a2e:2370:7339\"", str);

		var addr2 = Json.parse(str, InetAddress.class);
		assertEquals(addr, addr2);

		var bytes = Json.toBytes(addr);
		System.out.println(Hex.encode(bytes));
		assertEquals(17, bytes.length);
		assertArrayEquals(addr.getAddress(), Arrays.copyOfRange(bytes, 1, bytes.length));

		addr2 = Json.parse(bytes, InetAddress.class);
		assertEquals(addr, addr2);
	}

	@Test
	void dateTest() {
		var cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		cal.set(2025, Calendar.FEBRUARY, 15, 18, 56, 21);
		cal.set(Calendar.MILLISECOND, 0);
		var date = cal.getTime();

		var str = Json.toString(date);
		System.out.println(str);
		assertEquals("\"2025-02-15T18:56:21Z\"", str);

		var date2 = Json.parse(str, Date.class);
		assertEquals(date, date2);

		var bytes = Json.toBytes(date);
		System.out.println(Hex.encode(bytes));
		assertEquals(Long.BYTES + 1, bytes.length);
		assertEquals(date.getTime(), ByteBuffer.wrap(bytes).getLong(1));

		date2 = Json.parse(bytes, Date.class);
		assertEquals(date, date2);

		// fail-back format
		var dateWithMillis = "\"2025-02-15T18:56:21.892Z\"";
		date2 = Json.parse(dateWithMillis, Date.class);
		assertNotEquals(date, date2);
		cal.setTime(date2);
		var millis = cal.get(Calendar.MILLISECOND);
		cal.set(Calendar.MILLISECOND, 0);
		date2 = cal.getTime();
		assertEquals(date, date2);
		assertEquals(892, millis);
	}

	@Test
	void bytesTest() {
		var bytes = new byte[125];
		new Random().nextBytes(bytes);

		var str = Json.toString(bytes);
		System.out.println(str);
		assertEquals("\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + "\"", str);

		var bytes2 = Json.parse(str, byte[].class);
		assertArrayEquals(bytes, bytes2);

		var bytes3 = Json.toBytes(bytes);
		assertEquals(bytes.length + 2, bytes3.length);
		// cbor format: type, length, bytes
		assertArrayEquals(bytes, Arrays.copyOfRange(bytes3, 2, bytes3.length));

		var bytes4 = Json.parse(bytes3, byte[].class);
		assertArrayEquals(bytes, bytes4);
	}

	@Test
	void objectTest() throws Exception {
		record TestObject(@JsonProperty("id") Id id,
						  @JsonProperty("name") String name,
						  @JsonProperty("date") Date date,
						  @JsonProperty("ip4") InetAddress ip4,
						  @JsonProperty("ip6") InetAddress ip6,
						  @JsonProperty("data") byte[] data) {
			@JsonCreator
			public TestObject(@JsonProperty("id") Id id,
							  @JsonProperty("name") String name,
							  @JsonProperty("date") Date date,
							  @JsonProperty("ip4") InetAddress ip4,
							  @JsonProperty("ip6") InetAddress ip6,
							  @JsonProperty("data") byte[] data) {
				this.id = id;
				this.name = name;
				this.date = date;
				this.ip4 = ip4;
				this.ip6 = ip6;
				this.data = data;
			}
		};

		byte[] data = new byte[61];
		new Random().nextBytes(data);

		// erase millisecond
		var cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		cal.set(Calendar.MILLISECOND, 0);
		Date now = cal.getTime();

		var obj = new TestObject(Id.random(), "FooBar", now,
				InetAddress.getByName("108.178.25.111"),
				InetAddress.getByName("2001:db8:85a2:0:0:8a2e:2370:7339"),
				data);

		var s = Json.toString(obj);
		System.out.println(s);
		System.out.println(Json.toPrettyString(obj));

		var obj2 = Json.parse(s, TestObject.class);
		assertEquals(obj.id, obj2.id);
		assertEquals(obj.name, obj2.name);
		assertEquals(obj.date, obj2.date);
		assertEquals(obj.ip4, obj2.ip4);
		assertEquals(obj.ip6, obj2.ip6);
		assertArrayEquals(obj.data, obj2.data);

		var bytes2 = Json.toBytes(obj);
		System.out.println(Hex.encode(bytes2));
		assertEquals(163, bytes2.length);

		var obj3 = Json.parse(bytes2, TestObject.class);
		assertEquals(obj.id, obj3.id);
		assertEquals(obj.name, obj3.name);
		assertEquals(obj.date, obj3.date);
		assertEquals(obj.ip4, obj3.ip4);
		assertEquals(obj.ip6, obj3.ip6);
		assertArrayEquals(obj.data, obj3.data);
	}

	@Test
	void mapTest() throws Exception {
		var map = Map.of("foo", 1,
				"bar", "baz",
				"date", "2025-05-15T08:21:16Z",
				"id", Id.random().toString(),
				"ip", "108.178.25.111",
				"ip6", "2001:db8:85a2:0:0:8a2e:2370:7339",
				"bool", true,
				"array", Arrays.asList(1, 2, 3),
				"nested", Map.of("foo", 1, "bar", "baz"),
				"nestedArray", Arrays.asList(Map.of("foo", 2, "bar", "baz"), Map.of("foo", 3, "bar", "baz")));

		var s = Json.toString(map);
		System.out.println(s);
		System.out.println(Json.toPrettyString(map));
		var map2 = Json.parse(s);
		assertEquals(map.size(), map2.size());
		assertEquals(map, map2);

		var b = Json.toBytes(map);
		System.out.println(Hex.encode(b));
		var map3 = Json.parse(b);
		assertEquals(map.size(), map3.size());
		assertEquals(map, map3);
	}

	@Test
	void mapWithBinaryTest() throws Exception {
		var now = Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTime();
		var id = Id.random();
		var ip4 = InetAddress.getByName("108.178.25.111");
		var ip6 = InetAddress.getByName("2001:db8:85a2:0:0:8a2e:2370:7339");
		var bytes = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};

		var map = Map.of("bytes", bytes,
				"date", now,
				"id", id,
				"ip4", ip4,
				"ip6", ip6,
				"bool", true,
				"array", Arrays.asList(1, 2, 3));

		var s = Json.toString(map);
		System.out.println(s);
		System.out.println(Json.toPrettyString(map));
		var map2 = Json.parse(s);
		assertEquals(map.size(), map2.size());

		assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), map2.get("bytes"));
		assertEquals(DateFormat.getDefault().format(now), map2.get("date"));
		assertEquals(id.toString(), map2.get("id"));
		assertEquals(ip4.getHostAddress(), map2.get("ip4"));
		assertEquals(ip6.getHostAddress(), map2.get("ip6"));
		assertEquals(true, map2.get("bool"));
		assertEquals(Arrays.asList(1, 2, 3), map2.get("array"));

		var b = Json.toBytes(map);
		System.out.println(Hex.encode(b));
		var map3 = Json.parse(b);
		assertEquals(map.size(), map3.size());

		assertArrayEquals(bytes, (byte[])map3.get("bytes"));
		assertEquals(now.getTime(), map3.get("date"));
		assertArrayEquals(id.bytes(), (byte[])map3.get("id"));
		assertArrayEquals(ip4.getAddress(), (byte[])map3.get("ip4"));
		assertArrayEquals(ip6.getAddress(), (byte[])map3.get("ip6"));
		assertEquals(true, map3.get("bool"));
		assertEquals(Arrays.asList(1, 2, 3), map3.get("array"));
	}
}