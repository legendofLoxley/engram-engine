package app.alfrd.engram.api

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import java.net.URI
import java.util.concurrent.TimeUnit

fun Application.configureAuth() {
    val jwtIssuer = System.getenv("SUPABASE_ISSUER")
        ?: error("SUPABASE_ISSUER environment variable is required")
    val jwksUrl = "$jwtIssuer/.well-known/jwks.json"

    val jwkProvider = JwkProviderBuilder(URI.create(jwksUrl).toURL())
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    val logger = log

    install(Authentication) {
        jwt("supabase") {
            verifier(jwkProvider, jwtIssuer) {
                withAudience("authenticated")
                acceptLeeway(10)
            }
            validate { credential ->
                val email = credential.payload.getClaim("email")?.asString()
                if (email != null) {
                    JWTPrincipal(credential.payload)
                } else {
                    logger.warn(
                        "JWT rejected: missing email claim — sub={} iss={}",
                        credential.payload.subject,
                        credential.payload.issuer
                    )
                    null
                }
            }
            challenge { _, _ ->
                val raw = call.request.authorization()?.removePrefix("Bearer ")?.trim()
                if (raw != null) {
                    try {
                        val decoded = JWT.decode(raw)
                        logger.warn(
                            "JWT rejected: alg={} kid={} iss={} aud={} sub={}",
                            decoded.algorithm,
                            decoded.keyId,
                            decoded.issuer,
                            decoded.audience,
                            decoded.subject
                        )
                    } catch (e: Exception) {
                        logger.warn("JWT rejected: malformed token — {}", e.message)
                    }
                } else {
                    logger.warn("JWT rejected: no Authorization header on {}", call.request.uri)
                }
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing token"))
            }
        }
    }
}

fun ApplicationCall.userEmail(): String? =
    principal<JWTPrincipal>()?.payload?.getClaim("email")?.asString()
