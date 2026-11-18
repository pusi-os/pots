package com.pusios.pots.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The whole check runs against a mocked GitHub: no test here touches the network, and every
 * failure branch (offline, 403, 404, garbage body) is driven by the status code alone.
 */
class AppUpdateManagerCheckTest {

    private fun manager(
        engine: MockEngine,
        currentVersion: String = "3.13.1",
        os: UpdateOs = UpdateOs.Android,
        arch: UpdateArch = UpdateArch.Arm64,
    ): AppUpdateManager = AppUpdateManager(
        client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        },
        currentVersionName = currentVersion,
        os = os,
        arch = arch,
    )

    private fun jsonEngine(body: String, status: HttpStatusCode = HttpStatusCode.OK) = MockEngine { _ ->
        respond(
            content = body,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    @Test
    fun aNewerReleaseIsAvailableForThisPlatform() = runTest {
        val result = manager(jsonEngine(NEWER_RELEASE_JSON)).check()

        val available = assertIs<UpdateCheck.Available>(result)
        assertEquals("3.14.0", available.release.versionName)
        assertEquals("Pots-3.14.0-android.apk", available.asset.name)
    }

    @Test
    fun theRunningVersionIsNotOfferedAgain() = runTest {
        assertEquals(UpdateCheck.UpToDate("3.13.1"), manager(jsonEngine(LATEST_RELEASE_JSON)).check())
    }

    @Test
    fun anOlderReleaseIsNotOffered() = runTest {
        assertEquals(
            UpdateCheck.UpToDate("3.13.1"),
            manager(jsonEngine(LATEST_RELEASE_JSON), currentVersion = "3.14.0").check(),
        )
    }

    @Test
    fun nineIsOlderThanThirteen() = runTest {
        // The exact case a string comparison gets backwards.
        assertIs<UpdateCheck.Available>(manager(jsonEngine(LATEST_RELEASE_JSON), currentVersion = "3.9.0").check())
    }

    @Test
    fun aRateLimitedResponseAsksTheUserToWait() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.Forbidden) }
        assertEquals(UpdateCheck.Failed(UpdateError.RateLimited), manager(engine).check())
    }

    @Test
    fun tooManyRequestsIsAlsoARateLimit() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.TooManyRequests) }
        assertEquals(UpdateCheck.Failed(UpdateError.RateLimited), manager(engine).check())
    }

    @Test
    fun aMissingReleaseIsReportedAsNotFound() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.NotFound) }
        assertEquals(UpdateCheck.Failed(UpdateError.NotFound), manager(engine).check())
    }

    @Test
    fun aBrokenServerIsReportedAsSuch() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.InternalServerError) }
        assertEquals(UpdateCheck.Failed(UpdateError.ServerError), manager(engine).check())
    }

    @Test
    fun anOfflineDeviceGetsANetworkError() = runTest {
        val engine = MockEngine { _ -> throw RuntimeException("no route to host") }
        assertEquals(UpdateCheck.Failed(UpdateError.NetworkError), manager(engine).check())
    }

    @Test
    fun anUnreadableBodyIsInvalidNotACrash() = runTest {
        val engine = MockEngine { _ -> respond(content = "<html>proxy</html>", status = HttpStatusCode.OK) }
        assertEquals(UpdateCheck.Failed(UpdateError.InvalidResponse), manager(engine).check())
    }

    @Test
    fun aReleaseWithoutABuildForThisOsSaysSo() = runTest {
        // The release carries an APK only; a Linux host must not be handed it.
        val result = manager(
            jsonEngine(NEWER_ANDROID_ONLY_JSON),
            os = UpdateOs.Linux,
            arch = UpdateArch.X64,
        ).check()

        assertEquals(UpdateCheck.Failed(UpdateError.NoAssetForPlatform), result)
    }

    @Test
    fun theCheckNeverRequestsTheAsset() = runTest {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += request.url.toString()
            respond(
                content = NEWER_RELEASE_JSON,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        manager(engine).check()

        assertEquals(listOf(POTS_RELEASES_URL), requests)
    }

    @Test
    fun anUnparseableLocalVersionRefusesToCompare() = runTest {
        assertEquals(
            UpdateCheck.Failed(UpdateError.InvalidResponse),
            manager(jsonEngine(NEWER_RELEASE_JSON), currentVersion = "nightly").check(),
        )
    }
}

/** The live 3.13.1 payload bumped by one minor version, assets and all. */
internal val NEWER_RELEASE_JSON = LATEST_RELEASE_JSON.replace("3.13.1", "3.14.0")

private val NEWER_ANDROID_ONLY_JSON = """
{"tag_name":"v3.14.0","html_url":"https://github.com/pusi-os/pots/releases/tag/v3.14.0","body":"",
 "assets":[{"name":"Pots-3.14.0-android.apk","size":10,
            "browser_download_url":"https://example/Pots-3.14.0-android.apk"}]}
"""
