package com.frianzo.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.annotations.SerializedName
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
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

data class Post(
    val id: String,
    val content: String?,
    val imageUrl: String?,
    val createdAt: String?,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val shareCount: Int = 0,
    val author: Author?,
)

data class FeedData(
    val posts: List<Post>,
    val hasMore: Boolean = false,
    val nextOffset: Int = 0,
)

data class MeData(val id: String?, val username: String?, val displayName: String?)
data class NativeConfigData(val clientId: String)
data class GoogleNativeRequest(val idToken: String, val nonce: String, val deviceToken: String? = null)
data class GoogleNativeData(val user: MeData?, val isNewUser: Boolean = false, val deviceToken: String?)
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
    suspend fun me(): Envelope<MeData>

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
        return prefs.all.values.mapNotNull { raw ->
            try { Cookie.parse(url, raw as String) } catch (_: Exception) { null }
        }.filter { it.expiresAt > System.currentTimeMillis() }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

fun createApi(context: Context, cookieJar: PersistentCookieJar): FrianzoApi {
    val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
    val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor(logging)
        .build()
    return Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(FrianzoApi::class.java)
}
