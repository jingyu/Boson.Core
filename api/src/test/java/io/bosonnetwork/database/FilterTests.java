package io.bosonnetwork.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.json.Json;

public class FilterTests {
	@Test
	void testNone() {
		Filter filter = Filter.NONE;
		assertEquals(" 1 = 1", filter.toSqlTemplate());
		assertTrue(filter.getParams().isEmpty());
	}

	@Test
	void testEqual() {
		Filter filter = Filter.eq("foo", "hello");
		assertEquals(" foo = #{foo_eq}", filter.toSqlTemplate());
		assertEquals("hello", filter.getParams().get("foo_eq"));
	}

	@Test
	void testNotEqual() {
		Filter filter = Filter.ne("foo", "world");
		assertEquals(" foo <> #{foo_ne}", filter.toSqlTemplate());
		assertEquals("world", filter.getParams().get("foo_ne"));
	}

	@Test
	void testLessThan() {
		Filter filter = Filter.lt("foo", 10);
		assertEquals(" foo < #{foo_lt}", filter.toSqlTemplate());
		assertEquals(10, filter.getParams().get("foo_lt"));
	}

	@Test
	void testLessThanOrEqual() {
		Filter filter = Filter.lte("foo", 15);
		assertEquals(" foo <= #{foo_lte}", filter.toSqlTemplate());
		assertEquals(15, filter.getParams().get("foo_lte"));
	}

	@Test
	void testGreaterThan() {
		Filter filter = Filter.gt("foo", 20);
		assertEquals(" foo > #{foo_gt}", filter.toSqlTemplate());
		assertEquals(20, filter.getParams().get("foo_gt"));
	}

	@Test
	void testGreaterThanOrEqual() {
		Filter filter = Filter.gte("foo", 25);
		assertEquals(" foo >= #{foo_gte}", filter.toSqlTemplate());
		assertEquals(25, filter.getParams().get("foo_gte"));
	}

	@Test
	void testLike() {
		Filter filter = Filter.like("foo", "ABC%");
		assertEquals(" foo LIKE #{foo_like}", filter.toSqlTemplate());
		assertEquals("ABC%", filter.getParams().get("foo_like"));
	}

	@Test
	void testRangeOnSameColumn() {
		// A range query on a single column must not collide on the default param name.
		Filter filter = Filter.and(Filter.gte("ts", 100), Filter.lte("ts", 200));
		assertEquals(" ( ts >= #{ts_gte} AND ts <= #{ts_lte})", filter.toSqlTemplate());

		Map<String, Object> params = filter.getParams();
		assertEquals(2, params.size());
		assertEquals(100, params.get("ts_gte"));
		assertEquals(200, params.get("ts_lte"));

		// Same column AND same operator still collides - surfaced with a clear message.
		Filter colliding = Filter.and(Filter.eq("x", 1), Filter.eq("x", 2));
		assertThrows(IllegalStateException.class, colliding::getParams);
	}

	@Test
	void testIsNull() {
		Filter filter = Filter.isNull("foo");
		assertEquals(" foo IS NULL", filter.toSqlTemplate());
	}

	@Test
	void testIsNotNull() {
		Filter filter = Filter.isNotNull("foo");
		assertEquals(" foo IS NOT NULL", filter.toSqlTemplate());
	}

	@Test
	void testIn() {
		Filter filter = Filter.in("foo", Map.of());
		assertEquals(" 1 = 0", filter.toSqlTemplate());

		Map<String, Object> params = new LinkedHashMap<>();
		params.put("type1", 1);
		params.put("type2", 2);
		params.put("type3", 3);

		filter = Filter.in("foo", params);
		assertEquals(" foo IN (#{type1}, #{type2}, #{type3})", filter.toSqlTemplate());
		System.out.println(Json.toPrettyString(filter.getParams()));
		assertEquals(params, filter.getParams());
	}

	@Test
	void testAnd() {
		Filter filter = Filter.and();
		assertEquals(" 1 = 1", filter.toSqlTemplate());

		filter = Filter.and(Filter.eq("foo", 10));
		assertEquals(" foo = #{foo_eq}", filter.toSqlTemplate());

		Map<String, Object> inParams = new LinkedHashMap<>();
		inParams.put("qux1", "QUX1");
		inParams.put("qux2", "QUX2");
		inParams.put("qux3", "QUX3");

		filter = Filter.and(
				Filter.eq("foo", 10),
				Filter.lte("bar", 20),
				Filter.isNull("baz"),
				Filter.in("qux", inParams));

		assertEquals(" ( foo = #{foo_eq} AND bar <= #{bar_lte} AND baz IS NULL AND qux IN (#{qux1}, #{qux2}, #{qux3}))", filter.toSqlTemplate());

		Map<String, Object> params = filter.getParams();
		System.out.println(Json.toPrettyString(params));
		assertEquals(5, params.size());
		assertEquals(10, params.get("foo_eq"));
		assertEquals(20, params.get("bar_lte"));
		assertEquals("QUX1", params.get("qux1"));
		assertEquals("QUX2", params.get("qux2"));
		assertEquals("QUX3", params.get("qux3"));
	}

	@Test
	void testOr() {
		Filter filter = Filter.and();
		assertEquals(" 1 = 1", filter.toSqlTemplate());

		filter = Filter.and(Filter.eq("foo", "foobar"));
		assertEquals(" foo = #{foo_eq}", filter.toSqlTemplate());

		Map<String, Object> inParams = new LinkedHashMap<>();
		inParams.put("qux1", "QUX1");
		inParams.put("qux2", "QUX2");
		inParams.put("qux3", "QUX3");

		filter = Filter.or(
				Filter.eq("foo", 10),
				Filter.lte("bar", 20),
				Filter.isNull("baz"),
				Filter.in("qux", inParams));

		assertEquals(" ( foo = #{foo_eq} OR bar <= #{bar_lte} OR baz IS NULL OR qux IN (#{qux1}, #{qux2}, #{qux3}))", filter.toSqlTemplate());

		Map<String, Object> params = filter.getParams();
		System.out.println(Json.toPrettyString(params));
		assertEquals(5, params.size());
		assertEquals(10, params.get("foo_eq"));
		assertEquals(20, params.get("bar_lte"));
		assertEquals("QUX1", params.get("qux1"));
		assertEquals("QUX2", params.get("qux2"));
		assertEquals("QUX3", params.get("qux3"));
	}

	@Test
	void testRaw() {
		Filter filter = Filter.raw("foo = bar");
		assertEquals("foo = bar", filter.toSqlTemplate());
	}
}