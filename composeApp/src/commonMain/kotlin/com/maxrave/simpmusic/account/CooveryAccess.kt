package com.maxrave.simpmusic.account

import com.maxrave.data.io.fileDir
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Coovery account gate. Email and password go to Firebase Authentication.
 * Whether the account may open the app is a Firestore document the owner sets:
 * users/{uid} with planActive = true, and an optional planExpiresAt timestamp.
 * YouTube sign-in is a different account and is not touched here.
 */
internal class CooveryAccessStore {
    suspend fun restore(): CooveryAccessState {
        val session = readSession() ?: return CooveryAccessState.SignedOut
        return loadPlan(session)
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
        return when (val state = loadPlan(session)) {
            CooveryAccessState.SignedOut -> CooveryAccessResult.Failed(CooveryAccessFailure.Unknown)
            else -> CooveryAccessResult.Ready(state)
        }
    }

    private suspend fun loadPlan(session: CooverySession): CooveryAccessState {
        val fresh =
            if (session.expiresAtEpochMs > now() + TOKEN_SKEW_MS) {
                session
            } else {
                refresh(session) ?: return CooveryAccessState.SignedOut
            }
        if (fresh != session) writeSession(fresh)
        val document = readPlan(fresh.idToken, fresh.localId)
        if (document == null) {
            val retried = refresh(fresh) ?: return CooveryAccessState.SignedOut
            writeSession(retried)
            return when (val again = readPlan(retried.idToken, retried.localId)) {
                null -> CooveryAccessState.SignedOut
                else -> planState(retried.email, again)
            }
        }
        return planState(fresh.email, document)
    }

    private fun planState(
        email: String,
        plan: PlanDocument,
    ): CooveryAccessState =
        when {
            plan.unreadable -> CooveryAccessState.Inactive(email, unreadable = true)
            plan.active -> CooveryAccessState.Active(email)
            else -> CooveryAccessState.Inactive(email)
        }

    private suspend fun readPlan(
        idToken: String,
        localId: String,
    ): PlanDocument? {
        val url =
            "https://firestore.googleapis.com/v1/projects/${CooveryFirebase.PROJECT_ID}" +
                "/databases/(default)/documents/users/$localId"
        val response =
            try {
                client.request(url) {
                    method = HttpMethod.Get
                    header("Authorization", "Bearer $idToken")
                }
            } catch (_: Exception) {
                return PlanDocument(active = false, unreadable = true)
            }
        val text = response.bodyAsText()
        return when (response.status.value) {
            200 -> parsePlan(text)
            404 -> PlanDocument(active = false, unreadable = false)
            401 -> null
            else -> PlanDocument(active = false, unreadable = true)
        }
    }

    private fun parsePlan(text: String): PlanDocument {
        val fields =
            runCatching { Json.parseToJsonElement(text).jsonObject["fields"]?.jsonObject }.getOrNull()
                ?: return PlanDocument(active = false, unreadable = true)
        val active = fields["planActive"]?.jsonObject?.get("booleanValue")?.jsonPrimitive?.booleanOrNull == true
        val expires = fields["planExpiresAt"]?.jsonObject?.get("timestampValue")?.jsonPrimitive?.contentOrNull
        if (!active) return PlanDocument(active = false, unreadable = false)
        if (expires.isNullOrBlank()) return PlanDocument(active = true, unreadable = false)
        val stillValid =
            runCatching { Instant.parse(expires).toEpochMilliseconds() > now() }.getOrDefault(false)
        return PlanDocument(active = stillValid, unreadable = false)
    }

    private suspend fun refresh(session: CooverySession): CooverySession? {
        val response =
            try {
                client.post("https://securetoken.googleapis.com/v1/token?key=${CooveryFirebase.API_KEY}") {
                    header("Content-Type", "application/x-www-form-urlencoded")
                    setBody(
                        "grant_type=refresh_token&refresh_token=${session.refreshToken.encodeURLParameter()}",
                    )
                }
            } catch (_: Exception) {
                return null
            }
        if (response.status.value !in 200..299) return null
        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull() ?: return null
        val idToken = body["id_token"]?.jsonPrimitive?.contentOrNull ?: return null
        val refreshToken = body["refresh_token"]?.jsonPrimitive?.contentOrNull ?: session.refreshToken
        val expiresIn = body["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
        return session.copy(
            idToken = idToken,
            refreshToken = refreshToken,
            expiresAtEpochMs = now() + expiresIn * 1000,
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

    private data class PlanDocument(
        val active: Boolean,
        val unreadable: Boolean,
    )

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

    data class Inactive(
        val email: String,
        val unreadable: Boolean = false,
    ) : CooveryAccessState

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
