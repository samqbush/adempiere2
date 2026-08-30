package org.adempiere.web.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The descriptor is written to the container log and to published CI evidence,
 * so every case here is a hygiene assertion as much as a formatting one.
 */
class RedirectDescriptorTest {

	@Test
	@DisplayName("an omitted port is reported as omitted, not as a default")
	void omittedPortIsDistinguishable() {
		assertEquals(
				"scheme=http host=127.0.0.1 port=omitted userinfo=absent "
					+ "path=/error404.html query=errorURL=<redacted> "
					+ "fragment=absent",
				RedirectDescriptor.describe(
						"http://127.0.0.1/error404.html?errorURL=/index.html"));
	}

	@Test
	@DisplayName("an explicit port is preserved because the origin decision needs it")
	void explicitPortIsPreserved() {
		assertEquals(
				"scheme=http host=127.0.0.1 port=8890 userinfo=absent "
					+ "path=/webui/ query=absent fragment=absent",
				RedirectDescriptor.describe("http://127.0.0.1:8890/webui/"));
	}

	@Test
	@DisplayName("query values are redacted and their names kept")
	void queryValuesAreRedacted() {
		String described = RedirectDescriptor.describe(
				"https://example.test/a?token=s3cr3t&user=admin");
		assertFalse(described.contains("s3cr3t"));
		assertFalse(described.contains("admin"));
		assertTrue(described.contains("token=<redacted>"));
		assertTrue(described.contains("user=<redacted>"));
	}

	@Test
	@DisplayName("a session path parameter is redacted on any segment")
	void sessionPathParameterIsRedacted() {
		String described = RedirectDescriptor.describe(
				"http://127.0.0.1:8890/wstore;jsessionid=ABCDEF0123/basket.jsp");
		assertFalse(described.contains("ABCDEF0123"));
		assertTrue(described.contains("/wstore;jsessionid=<redacted>/basket.jsp"));
	}

	@Test
	@DisplayName("userinfo is removed and reported only as present")
	void userinfoIsRemoved() {
		String described = RedirectDescriptor.describe(
				"http://alice:hunter2@127.0.0.1:8890/x");
		assertFalse(described.contains("alice"));
		assertFalse(described.contains("hunter2"));
		assertTrue(described.contains("userinfo=present"));
		assertTrue(described.contains("host=127.0.0.1"));
	}

	@Test
	@DisplayName("a fragment is reported as present without disclosing it")
	void fragmentIsNotDisclosed() {
		String described = RedirectDescriptor.describe(
				"http://127.0.0.1:8890/x#access_token=abc");
		assertFalse(described.contains("abc"));
		assertFalse(described.contains("access_token"));
		assertTrue(described.contains("fragment=present"));
	}

	@Test
	@DisplayName("an IPv6 literal keeps its brackets and its port")
	void ipv6LiteralIsNormalized() {
		assertEquals(
				"scheme=http host=[::1] port=8890 userinfo=absent "
					+ "path=/ query=absent fragment=absent",
				RedirectDescriptor.describe("http://[::1]:8890/"));
	}

	@Test
	@DisplayName("host and scheme are lower-cased so origin comparisons are stable")
	void hostAndSchemeAreNormalized() {
		assertTrue(RedirectDescriptor.describe("HTTP://LocalHost:8890/A")
				.startsWith("scheme=http host=localhost port=8890"));
	}

	@Test
	@DisplayName("a relative location is described without inventing an origin")
	void relativeLocationIsDescribed() {
		assertEquals(
				"relative path=/admin/ query=absent fragment=absent",
				RedirectDescriptor.describe("/admin/"));
	}

	@Test
	@DisplayName("an unparseable value is described rather than discarded")
	void unparseableValueIsStillDescribed() {
		assertEquals(
				"relative path=not a url query=absent fragment=absent",
				RedirectDescriptor.describe("not a url"));
		assertEquals("<null>", RedirectDescriptor.describe(null));
		assertEquals("<empty>", RedirectDescriptor.describe(""));
	}

	@Test
	@DisplayName("a non-numeric port is reported as malformed, never echoed")
	void malformedPortIsReported() {
		assertTrue(RedirectDescriptor.describe("http://127.0.0.1:80x0/a")
				.contains("port=malformed"));
	}
}
