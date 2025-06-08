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

package io.bosonnetwork.identifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.MalformedURLException;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;

public class DIDURLTests {
	@Test
	void idOnlyURLTest() throws MalformedURLException {
		var id = Id.random();

		var url = new DIDURL(id.toDIDString());
		System.out.println(url);

		assertEquals("did", url.getScheme());
		assertEquals("boson", url.getMethod());
		assertEquals(id, url.getId());
		assertNull(url.getPath());
		assertNull(url.getQuery());
		assertNull(url.getFragment());

		var url2 = new DIDURL(url.toString());
		assertEquals(url, url2);

		var url3 = new DIDURL(id);
		assertEquals(url, url3);
	}

	@Test
	void idPathTest() throws MalformedURLException {
		var id = Id.random();
		var path = "/path/to/file";

		var url = new DIDURL(id.toDIDString() + path);
		System.out.println(url);

		assertEquals("did", url.getScheme());
		assertEquals("boson", url.getMethod());
		assertEquals(id, url.getId());
		assertEquals(path, url.getPath());
		assertNull(url.getQuery());
		assertNull(url.getFragment());

		var url2 = new DIDURL(url.toString());
		assertEquals(url, url2);

		var url3 = new DIDURL(id, path, null, null);
		assertEquals(url, url3);
	}

	@Test
	void idQueryTest() throws MalformedURLException {
		var id = Id.random();
		var query = "foo=bar&test=true&seq=1234&flag";

		var url = new DIDURL(id.toDIDString() + "?" + query);
		System.out.println(url);

		assertEquals("did", url.getScheme());
		assertEquals("boson", url.getMethod());
		assertEquals(id, url.getId());
		assertNull(url.getPath());
		assertEquals(query, url.getQuery());
		assertNull(url.getFragment());

		var url2 = new DIDURL(url.toString());
		assertEquals(url, url2);

		var url3 = new DIDURL(id, null, query, null);
		assertEquals(url, url3);
	}

	@Test
	void idFragmentTest() throws MalformedURLException {
		var id = Id.random();
		var fragment = "testFragment";

		var url = new DIDURL(id.toDIDString() + "#" + fragment);
		System.out.println(url);

		assertEquals("did", url.getScheme());
		assertEquals("boson", url.getMethod());
		assertEquals(id, url.getId());
		assertNull(url.getPath());
		assertNull(url.getQuery());
		assertEquals(fragment, url.getFragment());

		var url2 = new DIDURL(url.toString());
		assertEquals(url, url2);

		var url3 = new DIDURL(id, null, null, fragment);
		assertEquals(url, url3);

		var url4 = new DIDURL(id, null, null, fragment);
		assertEquals(url, url4);
	}

	@Test
	void idPathQueryTest() throws MalformedURLException {
		var id = Id.random();
		var path = "/path/to/file";
		var query = "foo=bar&test=true&seq=1234&flag";

		var url = new DIDURL(id.toDIDString() + path + "?" + query);
		System.out.println(url);

		assertEquals("did", url.getScheme());
		assertEquals("boson", url.getMethod());
		assertEquals(id, url.getId());
		assertEquals(path, url.getPath());
		assertEquals(query, url.getQuery());
		assertNull(url.getFragment());

		var url2 = new DIDURL(url.toString());
		assertEquals(url, url2);

		var url3 = new DIDURL(id, path, query, null);
		assertEquals(url, url3);
	}

	@Test
	void idPathFragmentTest() throws MalformedURLException {
		var id = Id.random();
		var path = "/path/to/file";
		var fragment = "testFragment";

		var url = new DIDURL(id.toDIDString() + path + "#" + fragment);
		System.out.println(url);

		assertEquals("did", url.getScheme());
		assertEquals("boson", url.getMethod());
		assertEquals(id, url.getId());
		assertEquals(path, url.getPath());
		assertNull(url.getQuery());
		assertEquals(fragment, url.getFragment());

		var url2 = new DIDURL(url.toString());
		assertEquals(url, url2);

		var url3 = new DIDURL(id, path, null, fragment);
		assertEquals(url, url3);
	}

	@Test
	void idQueryFragmentTest() throws MalformedURLException {
		var id = Id.random();
		var query = "foo=bar&test=true&seq=1234&flag";
		var fragment = "testFragment";

		var url = new DIDURL(id.toDIDString() + "?" + query + "#" + fragment);
		System.out.println(url);

		assertEquals("did", url.getScheme());
		assertEquals("boson", url.getMethod());
		assertEquals(id, url.getId());
		assertNull(url.getPath());
		assertEquals(query, url.getQuery());
		assertEquals(fragment, url.getFragment());

		var url2 = new DIDURL(url.toString());
		assertEquals(url, url2);

		var url3 = new DIDURL(id, null, query, fragment);
		assertEquals(url, url3);
	}

	@Test
	void fullURLTest() throws MalformedURLException {
		var id = Id.random();
		var path = "/path/to/file";
		var query = "foo=bar&test=true&seq=1234&flag";
		var fragment = "testFragment";

		var url = new DIDURL(id.toDIDString() + path + "?" + query + "#" + fragment);
		System.out.println(url);

		assertEquals("did", url.getScheme());
		assertEquals("boson", url.getMethod());
		assertEquals(id, url.getId());
		assertEquals(path, url.getPath());
		assertEquals(query, url.getQuery());
		assertEquals(fragment, url.getFragment());

		var url2 = new DIDURL(url.toString());
		assertEquals(url, url2);

		var url3 = new DIDURL(id, path, query, fragment);
		assertEquals(url, url3);
	}

	@Test
	void relativePathTest() throws MalformedURLException {
		var url = new DIDURL("/path/to/file");
		System.out.println(url);

		assertNull(url.getScheme());
		assertNull(url.getMethod());
		assertNull(url.getId());
		assertEquals("/path/to/file", url.getPath());
		assertNull(url.getQuery());
		assertNull(url.getFragment());

		var url2 = new DIDURL(url.toString());
		assertEquals(url, url2);

		var id = Id.random();

		url = new DIDURL(id, "/path/to/file");
		System.out.println(url);

		assertEquals("did", url.getScheme());
		assertEquals("boson",url.getMethod());
		assertEquals(id, url.getId());
		assertEquals("/path/to/file", url.getPath());
		assertNull(url.getQuery());
		assertNull(url.getFragment());

		url2 = new DIDURL(url.toString());
		assertEquals(url, url2);
	}

	@Test
	void relativeQueryTest() throws MalformedURLException {
		var url = new DIDURL("/path/to/file?foo=bar&test=true&seq=1234&flag");
		System.out.println(url);

		assertNull(url.getScheme());
		assertNull(url.getMethod());
		assertNull(url.getId());
		assertEquals("/path/to/file", url.getPath());
		assertEquals("foo=bar&test=true&seq=1234&flag", url.getQuery());
		assertNull(url.getFragment());

		var url2 = new DIDURL(url.toString());
		assertEquals(url, url2);

		var id = Id.random();

		url = new DIDURL(id, "/path/to/file?foo=bar&test=true&seq=1234&flag");
		System.out.println(url);

		assertEquals("did", url.getScheme());
		assertEquals("boson",url.getMethod());
		assertEquals(id, url.getId());
		assertEquals("/path/to/file", url.getPath());
		assertEquals("foo=bar&test=true&seq=1234&flag", url.getQuery());
		assertNull(url.getFragment());

		url2 = new DIDURL(url.toString());
		assertEquals(url, url2);
	}

	@Test
	void relativeURLTest() throws MalformedURLException {
		var url = new DIDURL("/path/to/file?foo=bar&test=true&seq=1234&flag#testFragment");
		System.out.println(url);

		assertNull(url.getScheme());
		assertNull(url.getMethod());
		assertNull(url.getId());
		assertEquals("/path/to/file", url.getPath());
		assertEquals("foo=bar&test=true&seq=1234&flag", url.getQuery());
		assertEquals("testFragment", url.getFragment());

		var url2 = new DIDURL(url.toString());
		assertEquals(url, url2);

		var id = Id.random();

		url = new DIDURL(id, "/path/to/file?foo=bar&test=true&seq=1234&flag#testFragment");
		System.out.println(url);

		assertEquals("did", url.getScheme());
		assertEquals("boson",url.getMethod());
		assertEquals(id, url.getId());
		assertEquals("/path/to/file", url.getPath());
		assertEquals("foo=bar&test=true&seq=1234&flag", url.getQuery());
		assertEquals("testFragment", url.getFragment());

		url2 = new DIDURL(url.toString());
		assertEquals(url, url2);
	}

	@Test
	void relativeFragmentTest() throws MalformedURLException {
		var url = new DIDURL("#testFragment");
		System.out.println(url);

		assertNull(url.getScheme());
		assertNull(url.getMethod());
		assertNull(url.getId());
		assertNull(url.getPath());
		assertNull(url.getQuery());
		assertEquals("testFragment", url.getFragment());

		var url2 = new DIDURL(url.toString());
		assertEquals(url, url2);

		var id = Id.random();

		url = new DIDURL(id, "#testFragment");
		System.out.println(url);

		assertEquals("did", url.getScheme());
		assertEquals("boson",url.getMethod());
		assertEquals(id, url.getId());
		assertNull(url.getPath());
		assertNull(url.getQuery());
		assertEquals("testFragment", url.getFragment());

		url2 = new DIDURL(url.toString());
		assertEquals(url, url2);
	}

	@Test
	void ingoredRelativeURLTest() throws MalformedURLException {
		var id = Id.random();
		var ref = Id.random();

		var url = new DIDURL(ref, id.toDIDString() + "/path/to/file?foo=bar&test=true&seq=1234&flag#testFragment");
		System.out.println(url);

		assertEquals("did", url.getScheme());
		assertEquals("boson",url.getMethod());
		assertEquals(id, url.getId());
		assertEquals("/path/to/file", url.getPath());
		assertEquals("foo=bar&test=true&seq=1234&flag", url.getQuery());
		assertEquals("testFragment", url.getFragment());
	}

	@Test
	void emptyURLTest() throws MalformedURLException {
		var url = new DIDURL("");
		System.out.println(url);

		assertNull(url.getScheme());
		assertNull(url.getMethod());
		assertNull(url.getId());
		assertNull(url.getPath());
		assertNull(url.getQuery());
		assertNull(url.getFragment());
	}

	@Test
	void emptyPartsURLTest() throws MalformedURLException {
		var id = Id.random();
		var url = new DIDURL(id.toDIDString() + "/?#");
		System.out.println(url);

		assertEquals("did", url.getScheme());
		assertEquals("boson", url.getMethod());
		assertEquals(id, url.getId());
		assertEquals("/", url.getPath());
		assertNull(url.getQuery());
		assertNull(url.getFragment());
	}

	@Test
	void malformedURLTest() throws MalformedURLException {
		var e = assertThrows(MalformedURLException.class, () -> new DIDURL("foo:bar"));
		assertEquals("Invalid scheme: foo", e.getMessage());

		e = assertThrows(MalformedURLException.class, () -> new DIDURL(":boson/path/to/file"));
		assertEquals("Missing DIDURL scheme", e.getMessage());

		e = assertThrows(MalformedURLException.class, () -> new DIDURL("did:foo:bar"));
		assertEquals("Unsupported method: foo", e.getMessage());

		e = assertThrows(MalformedURLException.class, () -> new DIDURL("did::foo/path/to/file"));
		assertEquals("Missing DIDURL method", e.getMessage());

		e = assertThrows(MalformedURLException.class, () -> new DIDURL("did:/path/to/file"));
		assertEquals("Missing DIDURL method", e.getMessage());

		e = assertThrows(MalformedURLException.class, () -> new DIDURL("did/path/to/file"));
		assertEquals("Missing DIDURL method", e.getMessage());

		e = assertThrows(MalformedURLException.class, () -> new DIDURL("did:boson:bar"));
		assertEquals("Invalid method specific id: bar", e.getMessage());

		e = assertThrows(MalformedURLException.class, () -> new DIDURL("did:boson/path/to/file"));
		assertEquals("Missing method specific id", e.getMessage());

		e = assertThrows(MalformedURLException.class, () -> new DIDURL("did:boson:" + Id.random() + ":abc/path/to/file"));
		assertTrue(e.getMessage().startsWith("Invalid method specific id: "));
	}
}