package com.frianzo.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.annotations.SerializedName
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/** Live backend — the same AWS backend the website uses. No second backend. */
const val BASE_URL = "https://api.frianzo.online/"

/** Envelope used by every backend endpoint: { success, data } / { success, error }. */
data class Envelope<T>(val success: Boolean, val data: T?, val error: ErrorBody?)
data class ErrorBody(val code: String?, val message: String?)

data class Author(
    val id: String?,
    val username: String?,
    val displayName: String?,
    val profilePictureUrl: String?,
    val isVerified: Boolean = false,
)

data class TaggedUser(
    val id: String?,
    val username: String?,
    val displayName: String?,
    val profilePictureUrl: String?,
)

data class Post(
    val id: String,
    val content: String?,
    val imageUrl: String?,
    val createdAt: String?,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val shareCount: Int = 0,
    val author: Author?,
    val backgroundStyle: String? = null,
    val location: String? = null,
    val isPinned: Boolean = false,
    val reactionCounts: Map<String, Int>? = null,
    val myReaction: String? = null,
    val shareCode: String? = null,
    // v3.4: backend toPostDTO already returns these; the composer sends them.
    val visibility: String? = null,
    val commentAudience: String? = null,
    val taggedUsers: List<TaggedUser>? = null,
    // v2.9: a repost carries the original post (backend toPostDTO
    // returns originalPost recursively); null for original posts.
    val originalPost: Post? = null,
)

data class FeedData(
    // Nullable on purpose: Gson can inject null for an empty/missing list,
    // and `posts + null` would crash the feed on startup. Callers use ?: emptyList().
    val posts: List<Post>? = null,
    val hasMore: Boolean = false,
    val nextOffset: Int = 0,
)

data class MeData(val id: String?, val username: String?, val displayName: String?)
/** Backend /api/auth/me returns { user: {...} }, not bare fields. */
data class MeResponse(val user: MeData?)
data class NativeConfigData(val clientId: String)
data class GoogleNativeRequest(val idToken: String, val nonce: String, val deviceToken: String? = null)
data class GoogleNativeData(val user: MeData?, val isNewUser: Boolean = false, val deviceToken: String?, val token: String? = null)
data class EmailCodeRequest(val email: String)
data class EmailVerifyRequest(val email: String, val code: String, val deviceToken: String? = null)
data class EmailVerifyData(val user: MeData?, val isNewUser: Boolean = false, val deviceToken: String?)
data class SentData(val sent: Boolean = false)

interface FrianzoApi {
    @GET("api/auth/google/native-config")
    suspend fun nativeConfig(): Envelope<NativeConfigData>

    @POST("api/auth/google/native")
    suspend fun googleNative(@Body req: GoogleNativeRequest): Envelope<GoogleNativeData>

    @POST("api/auth/email/request-code")
    suspend fun requestEmailCode(@Body req: EmailCodeRequest): Envelope<SentData>

    @POST("api/auth/email/verify-code")
    suspend fun verifyEmailCode(@Body req: EmailVerifyRequest): Envelope<EmailVerifyData>

    @GET("api/auth/me")
    suspend fun me(): Envelope<MeResponse>

    /** Public feed (optionalAuth): works logged-out, and personalised when logged in.
     *  Backend page size is 5; pagination is by offset. */
    @GET("api/posts")
    suspend fun feed(@Query("offset") offset: Int = 0): Envelope<FeedData>
}

/**
 * CookieJar persisted in SharedPreferences. This is the critical piece:
 * the backend sets vynzo_auth_token as a cookie on login and only reads it
 * back from cookies — an Authorization header is ignored. Without this,
 * login would "succeed" and every later call would still be logged out.
 */
class PersistentCookieJar(context: Context) : CookieJar {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("frianzo_cookies", Context.MODE_PRIVATE)

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val edit = prefs.edit()
        for (cookie in cookies) {
            edit.putString(cookie.name, cookie.toString())
        }
        edit.apply()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        // A-B32: a cookie with no Expires/Max-Age is a session cookie and
        // OkHttp reports expiresAt == Long.MIN_VALUE for it — the old
        // `expiresAt > now` filter silently dropped every such cookie.
        // Session cookies are valid until the app closes, so keep them.
        // Unparseable stored values are still dropped (they cannot be
        // sent), but the stored raw value is left in prefs for debugging
        // instead of being wiped.
        return prefs.all.values.mapNotNull { raw ->
            try { Cookie.parse(url, raw as String) } catch (_: Exception) { null }
        }.filter { it.expiresAt == Long.MIN_VALUE || it.expiresAt > System.currentTimeMillis() }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * Defence-in-depth for Google login: the backend now sets the auth
     * cookie (primary path, deployed 2026-11-02), but if a login response
     * ever carries a token with no usable cookie, persist that token as
     * the vynzo_auth_token cookie so the next /api/auth/me call is
     * authenticated. The backend is cookie-only and ignores Authorization
     * headers, so storing the token as a cookie is the only fallback
     * that can work. Never called with a hard-coded token.
     */
    fun saveAuthToken(token: String) {
        // A-B32: the 7-day expiry is NOT arbitrary — it matches the
        // server's own auth-cookie maxAge (vynzo-backend authController
        // COOKIE_OPTIONS: maxAge 7 * 24h). The Set-Cookie expiry from
        // saveFromResponse is always preferred when the server sets a
        // cookie; this fallback only fires when a login response carries
        // a token with no usable cookie, so it mirrors the server value.
        val cookie = Cookie.Builder()
            .name("vynzo_auth_token")
            .value(token)
            .domain("api.frianzo.online")
            .path("/")
            .secure()
            .expiresAt(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000)
            .build()
        prefs.edit().putString(cookie.name, cookie.toString()).apply()
    }
}

/**
 * Backend verifyOrigin rejects mutating requests with no Origin header
 * (403 MISSING_ORIGIN). A native app sends no Origin by default, so this
 * interceptor sets the website origin on EVERY request. It must be used
 * by BOTH createApi and createApi2: Api2 previously had no Origin header
 * at all, so every Api2 POST (create post, follow, like, comment, chat
 * send, mark read) would 403 even though the Api (email/Google) POSTs
 * had already been fixed.
 */
fun originInterceptor() = Interceptor { chain ->
    chain.proceed(
        chain.request().newBuilder()
            .header("Origin", "https://frianzo.online")
            .build()
    )
}

fun createApi(context: Context, cookieJar: PersistentCookieJar): FrianzoApi {
    val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
    val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor(originInterceptor())
        .addInterceptor(logging)
        .build()
    return Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(FrianzoApi::class.java)
}



