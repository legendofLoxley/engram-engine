package app.alfrd.engram.drive

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * The only HTTP client the Drive connector has, and it cannot write: every request is checked before it leaves. Allowed is a GET under
 * `/drive/v3/` and the token endpoint's POST to `/token`, each to an allowed host over https (plain http only to a loopback address, for
 * tests). A PUT, PATCH, DELETE, any other POST, any other path or any other host is refused with an [IOException]. Redirects are not followed,
 * so a response cannot send a request somewhere the check did not see.
 */
object DriveHttp {
    val PRODUCTION_HOSTS = setOf("www.googleapis.com", "oauth2.googleapis.com")

    fun production(): OkHttpClient = client(PRODUCTION_HOSTS)

    fun client(allowedHosts: Set<String>): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).callTimeout(300, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .addInterceptor(ReadOnly(allowedHosts))
        .build()

    private class ReadOnly(private val allowedHosts: Set<String>) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url
            fun refuse(why: String): Nothing = throw IOException("read-only connector: $why")
            if (url.host !in allowedHosts) refuse("host not allowed")
            if (url.scheme != "https" && !runCatching { InetAddress.getByName(url.host).isLoopbackAddress }.getOrDefault(false)) refuse("https is required")
            val path = url.encodedPath
            val allowed = (request.method == "GET" && path.startsWith("/drive/v3/")) || (request.method == "POST" && path == "/token")
            if (!allowed) refuse("only GET under /drive/v3/ and POST /token are allowed")
            return chain.proceed(request)
        }
    }
}
