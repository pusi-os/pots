// Acceptance tests for the MCP authorization half (3.21.1): challenge parsing, PKCE, the
// authorize URL, discovery URL candidates and the SSRF guard. Everything here is pure, so the
// tests pin the wire format instead of a mock's opinion of it.

package com.pusios.pots.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpAuthTest {

    // ---------------------------------------------------------------- WWW-Authenticate

    @Test
    fun parsesBearerChallengeWithResourceMetadata() {
        val challenge = parseWwwAuthenticate(
            "Bearer realm=\"mcp\", resource_metadata=\"https://sentry.example/.well-known/oauth-protected-resource/mcp\", " +
                "scope=\"org:read projects:write\"",
        )
        assertNotNull(challenge)
        assertEquals("Bearer", challenge.scheme)
        assertEquals(
            "https://sentry.example/.well-known/oauth-protected-resource/mcp",
            challenge.resourceMetadataUrl,
        )
        assertEquals("org:read projects:write", challenge.scope)
        assertNull(challenge.error)
    }

    @Test
    fun parsesQuotedCommasAndEscapes() {
        val challenge = parseWwwAuthenticate(
            "Bearer error=\"invalid_token\", error_description=\"token expired, please re-login\", scope=\"a,b\"",
        )
        assertNotNull(challenge)
        assertEquals("invalid_token", challenge.error)
        assertEquals("token expired, please re-login", challenge.params["error_description"])
        assertEquals("a,b", challenge.scope)
    }

    @Test
    fun ignoresNonBearerAndEmptyChallenges() {
        assertNull(parseWwwAuthenticate(null))
        assertNull(parseWwwAuthenticate("   "))
        assertNull(parseWwwAuthenticate("Basic realm=\"x\""))
        // A bare scheme has no parameters: nothing to discover, so the UI offers a manual token.
        assertNull(parseWwwAuthenticate("Bearer"))
    }

    // ---------------------------------------------------------------- PKCE

    @Test
    fun pkceMatchesTheRfc7636Vector() {
        // RFC 7636 appendix B.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge(verifier))
    }

    @Test
    fun newVerifierIsBase64UrlAndLongEnough() {
        val verifier = Pkce.newVerifier()
        assertEquals(43, verifier.length, "32 bytes of entropy = 43 base64url chars")
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "base64url alphabet only")
        assertFalse(verifier.contains('='))
        assertTrue(Pkce.newVerifier() != verifier, "verifiers must not repeat")
    }

    // ---------------------------------------------------------------- authorize URL

    @Test
    fun authorizeUrlCarriesPkceStateAndResource() {
        val endpoints = McpAuthEndpoints(
            issuer = "https://auth.example",
            authorizationEndpoint = "https://auth.example/authorize",
            tokenEndpoint = "https://auth.example/token",
            registrationEndpoint = null,
            scopesSupported = emptyList(),
            resource = "https://mcp.example/mcp",
        )
        val url = buildAuthorizeUrl(
            endpoints = endpoints,
            clientId = "client 1",
            redirectUri = "http://127.0.0.1:53121/callback",
            state = "st/ate",
            codeChallenge = "chal",
            scope = "org:read",
        )
        assertTrue(url.startsWith("https://auth.example/authorize?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=client%201"), "client ids are percent-encoded")
        assertTrue(url.contains("code_challenge=chal"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=st%2Fate"))
        assertTrue(url.contains("resource=https%3A%2F%2Fmcp.example%2Fmcp"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A53121%2Fcallback"))
        assertTrue(url.contains("scope=org%3Aread"))
    }

    @Test
    fun authorizeUrlAppendsToAnEndpointThatAlreadyHasAQuery() {
        val endpoints = McpAuthEndpoints(
            issuer = "https://auth.example",
            authorizationEndpoint = "https://auth.example/authorize?tenant=x",
            tokenEndpoint = "https://auth.example/token",
            registrationEndpoint = null,
            scopesSupported = emptyList(),
            resource = "https://mcp.example",
        )
        val url = buildAuthorizeUrl(endpoints, "c", "http://127.0.0.1:1/cb", "s", "ch", null)
        assertTrue(url.contains("tenant=x&response_type=code"))
        assertFalse(url.contains("scope="), "no scope is sent when the server did not ask for one")
    }

    // ---------------------------------------------------------------- discovery candidates

    @Test
    fun resourceMetadataCandidatesPreferThePathScopedDocument() {
        assertEquals(
            listOf(
                "https://mcp.example/.well-known/oauth-protected-resource/mcp",
                "https://mcp.example/.well-known/oauth-protected-resource",
            ),
            resourceMetadataCandidates("https://mcp.example/mcp"),
        )
        assertEquals(
            listOf("https://mcp.example/.well-known/oauth-protected-resource"),
            resourceMetadataCandidates("https://mcp.example/"),
        )
    }

    @Test
    fun authServerMetadataCandidatesCoverBothRfc8414AndOidc() {
        assertEquals(
            listOf(
                "https://auth.example/.well-known/oauth-authorization-server",
                "https://auth.example/.well-known/openid-configuration",
            ),
            authServerMetadataCandidates("https://auth.example"),
        )
        assertTrue(
            authServerMetadataCandidates("https://auth.example/tenant").contains(
                "https://auth.example/tenant/.well-known/openid-configuration",
            ),
        )
    }

    @Test
    fun canonicalResourceDropsFragmentAndTrailingSlash() {
        assertEquals("https://mcp.example/mcp", canonicalResource("https://mcp.example/mcp/#frag"))
        assertEquals("https://mcp.example", canonicalResource("https://mcp.example/"))
    }

    // ---------------------------------------------------------------- SSRF guard

    @Test
    fun agentMayNotAddPrivateOrLoopbackServers() {
        assertTrue(isPrivateAddress("http://127.0.0.1:8080/mcp"))
        assertTrue(isPrivateAddress("http://localhost:3000"))
        assertTrue(isPrivateAddress("http://192.168.1.10/mcp"))
        assertTrue(isPrivateAddress("http://10.0.0.5/mcp"))
        assertTrue(isPrivateAddress("http://172.16.4.2/mcp"))
        assertTrue(isPrivateAddress("http://169.254.169.254/latest/meta-data"))
        assertTrue(isPrivateAddress("http://[::1]/mcp"))
        assertTrue(isPrivateAddress("http://[::1]:8080/mcp"))
        assertTrue(isPrivateAddress("http://[fe80::1]/mcp"), "IPv6 link-local")
        assertTrue(isPrivateAddress("http://[fe80::1%25eth0]/mcp"), "link-local with a zone id")
        assertTrue(isPrivateAddress("http://[fd00::1]/mcp"), "IPv6 unique-local (fd00::/7)")
        assertTrue(isPrivateAddress("http://[fc00::abcd]/mcp"), "IPv6 unique-local (fc00::/7)")
        assertTrue(isPrivateAddress("http://[::ffff:127.0.0.1]/mcp"), "IPv4-mapped loopback")
        assertTrue(isPrivateAddress("http://[::ffff:7f00:1]/mcp"), "IPv4-mapped loopback in hex")
        assertTrue(isPrivateAddress("http://[::ffff:192.168.1.5]/mcp"), "IPv4-mapped private")
        assertTrue(isPrivateAddress("http://[0:0:0:0:0:0:0:1]/mcp"), "uncompressed loopback")
        assertTrue(isPrivateAddress("http://fe80::1/mcp"), "unbracketed link-local")
        // The JVM resolver also accepts these spellings of 127.0.0.1; a non-canonical quad is refused.
        assertTrue(isPrivateAddress("http://2130706433/mcp"), "one-number IPv4 spelling is loopback")
        assertTrue(isPrivateAddress("http://127.1/mcp"), "shortened IPv4 spelling is loopback")
        assertTrue(isPrivateAddress("http://127.0.1/mcp"), "three-part IPv4 spelling is loopback")
        assertTrue(isPrivateAddress("http://0177.0.0.1/mcp"), "leading-zero octet is not a canonical quad")
        assertFalse(isPrivateAddress("https://mcp.sentry.io/mcp"))
        assertFalse(isPrivateAddress("https://172.32.0.1/mcp"), "172.32 is public")
        assertFalse(isPrivateAddress("https://8.8.8.8/mcp"))
        assertFalse(isPrivateAddress("https://[2001:4860:4860::8888]/mcp"), "public IPv6 stays allowed")
        assertFalse(isPrivateAddress("https://[::ffff:8.8.8.8]/mcp"), "mapped public IPv4 stays allowed")
        assertFalse(isPrivateAddress("https://[::ffff:808:808]/mcp"), "mapped public IPv4 in hex stays allowed")
    }
}
