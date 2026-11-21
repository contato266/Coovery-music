package com.maxrave.simpmusic.account

import com.maxrave.data.io.fileDir
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Coovery account gate. A valid email and password are enough to open the app.
 * Firebase Authentication is the only check. YouTube sign-in is a different
 * account and is not touched here.
 */
internal class CooveryAccessStore {
    suspend fun restore(): CooveryAccessState {
        val session = readSession() ?: return CooveryAccessState.SignedOut
        if (session.expiresAtEpochMs > now() + TOKEN_SKEW_MS) {
            return CooveryAccessState.Active(session.email)
        }
        return when (val refreshed = refresh(session)) {
            is Refresh.Ok -> {
                writeSession(refreshed.session)
                CooveryAccessState.Active(refreshed.session.email)
            }
            Refresh.Rejected -> {
                signOut()
            }
            Refresh.Offline -> CooveryAccessState.Active(session.email)
        }
    }

    suspend fun signIn(
        email: String,
        password: String,
    ): CooveryAccessResult = authenticate("accounts:signInWithPassword", email, password)

    suspend fun signUp(
        email: String,
        password: String,
    ): CooveryAccessResult = authenticate("accounts:signUp", email, password)

    suspend fun signOut(): CooveryAccessState {
        val path = sessionPath()
        if (FileSystem.SYSTEM.exists(path)) FileSystem.SYSTEM.delete(path)
        return CooveryAccessState.SignedOut
    }

    private suspend fun authenticate(
        method: String,
        email: String,
        password: String,
    ): CooveryAccessResult {
        val trimmed = email.trim()
        val response =
            postIdentity(
                method,
                buildJsonObject {
                    put("email", trimmed)
                    put("password", password)
                    put("returnSecureToken", true)
                }.toString(),
            )
        val failure = response.failure
        if (failure != null) return CooveryAccessResult.Failed(failure)
        val session =
            response.session?.copy(email = response.session.email.ifBlank { trimmed })
                ?: return CooveryAccessResult.Failed(CooveryAccessFailure.Unknown)
        writeSession(session)
        return CooveryAccessResult.Ready(CooveryAccessState.Active(session.email))
    }

    private suspend fun refresh(session: CooverySession): Refresh {
        val response =
            try {
                client.post("https://securetoken.googleapis.com/v1/token?key=${CooveryFirebase.API_KEY}") {
                    header("Content-Type", "application/x-www-form-urlencoded")
                    setBody(
                        "grant_type=refresh_token&refresh_token=${session.refreshToken.encodeURLParameter()}",
                    )
                }
            } catch (_: Exception) {
                return Refresh.Offline
            }
        if (response.status.value !in 200..299) return Refresh.Rejected
        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            ?: return Refresh.Rejected
        val idToken = body["id_token"]?.jsonPrimitive?.contentOrNull ?: return Refresh.Rejected
        val refreshToken = body["refresh_token"]?.jsonPrimitive?.contentOrNull ?: session.refreshToken
        val expiresIn = body["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
        return Refresh.Ok(
            session.copy(
                idToken = idToken,
                refreshToken = refreshToken,
                expiresAtEpochMs = now() + expiresIn * 1000,
            ),
        )
    }

    private suspend fun postIdentity(
        method: String,
        body: String,
    ): IdentityResponse {
        val response =
            try {
                client.post(
                    "https://identitytoolkit.googleapis.com/v1/$method?key=${CooveryFirebase.API_KEY}",
                ) {
                    header("Content-Type", "application/json")
                    setBody(body)
                }
            } catch (_: Exception) {
                return IdentityResponse(failure = CooveryAccessFailure.Network)
            }
        val text = response.bodyAsText()
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (response.status.value !in 200..299) {
            val message = json?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()
            return IdentityResponse(failure = failureFor(message))
        }
        if (json == null) return IdentityResponse(failure = CooveryAccessFailure.Unknown)
        val localId = json["localId"]?.jsonPrimitive?.contentOrNull
        val idToken = json["idToken"]?.jsonPrimitive?.contentOrNull
        val refreshToken = json["refreshToken"]?.jsonPrimitive?.contentOrNull
        val email = json["email"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val expiresIn = json["expiresIn"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
        if (localId == null || idToken == null || refreshToken == null) {
            return IdentityResponse(failure = CooveryAccessFailure.Unknown)
        }
        return IdentityResponse(
            session =
                CooverySession(
                    localId = localId,
                    email = email,
                    refreshToken = refreshToken,
                    idToken = idToken,
                    expiresAtEpochMs = now() + expiresIn * 1000,
                ),
        )
    }

    private fun failureFor(message: String): CooveryAccessFailure {
        val code = message.substringBefore(" :").trim()
        return when (code) {
            "EMAIL_NOT_FOUND", "INVALID_PASSWORD", "INVALID_LOGIN_CREDENTIALS" -> CooveryAccessFailure.InvalidCredentials
            "EMAIL_EXISTS" -> CooveryAccessFailure.EmailInUse
            "WEAK_PASSWORD" -> CooveryAccessFailure.WeakPassword
            "INVALID_EMAIL" -> CooveryAccessFailure.InvalidEmail
            "OPERATION_NOT_ALLOWED" -> CooveryAccessFailure.EmailLoginDisabled
            "TOO_MANY_ATTEMPTS_TRY_LATER" -> CooveryAccessFailure.TooManyAttempts
            else -> CooveryAccessFailure.Unknown
        }
    }

    private fun readSession(): CooverySession? {
        val path = sessionPath()
        if (!FileSystem.SYSTEM.exists(path)) return null
        val text = FileSystem.SYSTEM.read(path) { readUtf8() }
        return runCatching { json.decodeFromString(CooverySession.serializer(), text) }.getOrNull()
    }

    private fun writeSession(session: CooverySession) {
        val path = sessionPath()
        path.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
        FileSystem.SYSTEM.write(path) { writeUtf8(json.encodeToString(CooverySession.serializer(), session)) }
    }

    private fun sessionPath() = "${fileDir()}/coovery-access.json".toPath()

    @OptIn(ExperimentalTime::class)
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private data class IdentityResponse(
        val session: CooverySession? = null,
        val failure: CooveryAccessFailure? = null,
    )

    private sealed interface Refresh {
        data class Ok(
            val session: CooverySession,
        ) : Refresh

        data object Rejected : Refresh

        data object Offline : Refresh
    }

    private companion object {
        const val TOKEN_SKEW_MS = 60_000L
        val client =
            HttpClient(CIO) {
                expectSuccess = false
                install(HttpTimeout) {
                    requestTimeoutMillis = 20_000
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = 20_000
                }
            }
        val json = Json { ignoreUnknownKeys = true }
    }
}

internal sealed interface CooveryAccessState {
    data object Checking : CooveryAccessState

    data object SignedOut : CooveryAccessState

    data class Active(
        val email: String,
    ) : CooveryAccessState
}

internal sealed interface CooveryAccessResult {
    data class Ready(
        val state: CooveryAccessState,
    ) : CooveryAccessResult

    data class Failed(
        val reason: CooveryAccessFailure,
    ) : CooveryAccessResult
}

internal enum class CooveryAccessFailure {
    InvalidCredentials,
    EmailInUse,
    WeakPassword,
    InvalidEmail,
    EmailLoginDisabled,
    TooManyAttempts,
    Network,
    Unknown,
}

@Serializable
private data class CooverySession(
    val localId: String,
    val email: String,
    val refreshToken: String,
    val idToken: String,
    val expiresAtEpochMs: Long,
)
