package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ComponentMapTest {

	@Test
	void dropsNonStringValues() {
		ComponentMap map = ComponentMap.parse(
				"{\"a.B\": \"a.qb.Proxy0Activity\", \"weird\": 42, \"list\": [\"x\"]}");
		assertThat(map.size()).isEqualTo(1);
		assertThat(map.proxyFor("weird")).isNull();
	}

	@Test
	void emptyMapIsUsable() {
		assertThat(ComponentMap.parse("{}").proxyFor("a.B")).isNull();
		assertThat(ComponentMap.EMPTY.proxyFor("a.B")).isNull();
		assertThat(ComponentMap.EMPTY.size()).isEqualTo(0);
	}

	@Test
	void malformedJsonThrows() {
		assertThrows(IllegalArgumentException.class, () -> ComponentMap.parse("nope"));
	}

	@Test
	void mapsUserClassToProxy() {
		ComponentMap map = ComponentMap.parse(
				"{\"com.example.MainActivity\": \"com.example.qb.Proxy0Activity\","
						+ " \"com.example.DetailActivity\": \"com.example.qb.Proxy1Activity\"}");
		assertThat(map.size()).isEqualTo(2);
		assertThat(map.proxyFor("com.example.MainActivity"))
				.isEqualTo("com.example.qb.Proxy0Activity");
		assertThat(map.proxyFor("com.example.DetailActivity"))
				.isEqualTo("com.example.qb.Proxy1Activity");
	}

	@Test
	void unknownClassReturnsNull() {
		ComponentMap map = ComponentMap.parse("{\"a.B\": \"a.qb.Proxy0Activity\"}");
		assertThat(map.proxyFor("a.Unknown")).isNull();
		assertThat(map.proxyFor(null)).isNull();
	}
}
