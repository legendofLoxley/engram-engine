package app.alfrd.engram.api

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

fun Application.configureAuth() {
    val jwtSecret = System.getenv("SUPABASE_JWT_SECRET")
        ?: error("SUPABASE_JWT_SECRET environment variable is required")
    val jwtIssuer = System.getenv("SUPABASE_ISSUER")
        ?: error("SUPABASE_ISSUER environment variable is required")

    install(Authentication) {
        jwt("supabase") {
            verifier(
                JWT.require(Algorithm.HMAC256(jwtSecret))
                    .withIssuer(jwtIssuer)
                    .build()
            )
            validate { credential ->
                val email = credential.payload.getClaim("email")?.asString()
                if (email != null) {
                    JWTPrincipal(credential.payload)
                } else {
                    null  // reject tokens without email claim
                }
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing token"))
            }
        }
    }
}

fun ApplicationCall.userEmail(): String? =
    principal<JWTPrincipal>()?.payload?.getClaim("email")?.asString()
