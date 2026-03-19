package io.bosonnetwork.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class CollectionParameterTests {
	@Test
	void testWithMultipleValues() {
		CollectionParameter<Integer> parameter = new CollectionParameter<>("type", List.of(1, 2, 3));

		assertEquals("(#{type_0}, #{type_1}, #{type_2})", parameter.getTemplate());
		assertEquals(Map.of(
				"type_0", 1,
				"type_1", 2,
				"type_2", 3
		), parameter.getParams());
	}

	@Test
	void testWithSingleValue() {
		CollectionParameter<String> parameter = new CollectionParameter<>("name", List.of("alpha"));

		assertEquals("(#{name_0})", parameter.getTemplate());
		assertEquals(Map.of("name_0", "alpha"), parameter.getParams());
	}

	@Test
	void testWithEmptyValues() {
		CollectionParameter<Object> parameter = new CollectionParameter<>("empty", List.of());

		assertEquals("()", parameter.getTemplate());
		assertEquals(Map.of(), parameter.getParams());
	}

	@Test
	void testConstructorRejectsNullName() {
		assertThrows(NullPointerException.class, () -> new CollectionParameter<>("name", null));
	}

	@Test
	void testConstructorRejectsNullValues() {
		assertThrows(NullPointerException.class, () -> new CollectionParameter<>(null, List.of(1, 2, 3)));
	}
}