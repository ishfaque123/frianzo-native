package com.frianzo.app.data

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*

// ---------- Reels ----------
data class Reel(
    val id: String,
    val videoUrl: String?,
    val thumbnailUrl: String?,
    val caption: String?,
    val category: String?,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val viewCount: Int = 0,
    val shareCount: Int = 0,
    val liked: Boolean = false,
    val favorited: Boolean = false,
    val author: Author?,
)
data class ReelFeedData(val reels: List<Reel>, val hasMore: Boolean = false, val nextOffset: Int = 0)
data class ReelLikeData(val liked: Boolean = false, val likeCount: Int = 0)
data class ReelComment(val id: String, val content: String?, val author: Author?, val createdAt: String?)
data class ReelCommentsData(val comments: List<ReelComment>)

// ---------- Posts: detail / reactions / comments ----------
data class ReactionData(val myReaction: String?, val reactionCounts: Map<String, Int>? = null)
data class Comment(
    val id: String,
    val content: String?,
    val author: Author?,
    val createdAt: String?,
    val replyCount: Int = 0,
)
data class CommentsData(val comments: List<Comment>)
data class CommentCreateRequest(val content: String)
data class PostCreateData(val post: Post?)

// ---------- Chat ----------
data class ConversationUser(val id: String?, val username: String?, val displayName: String?, val profilePictureUrl: String?)
data class ConversationMessage(val id: String?, val content: String?, val senderId: String?, val createdAt: String?)
data class Conversation(
    val id: String,
    val otherUser: ConversationUser?,
    val messages: List<ConversationMessage>? = null,
    val unreadCount: Int = 0,
)
data class CreateConversationRequest(val userId: String)
data class CreateConversationData(val id: String)
data class SendMessageRequest(val content: String)

// ---------- Notifications ----------
data class NotificationItem(
    val id: String,
    val type: String?,
    val read: Boolean = false,
    val detail: String?,
    val createdAt: String?,
    val postId: String?,
    val reelId: String?,
    val actor: Author?,
)
data class NotificationsData(val notifications: List<NotificationItem>)
data class UnreadData(val unreadCount: Int = 0, val count: Int = 0)

// ---------- Profile / search / follow ----------
data class PublicProfile(
    val id: String?,
    val username: String?,
    val displayName: String?,
    val bio: String?,
    val profilePictureUrl: String?,
    val coverPhotoUrl: String?,
    val website: String?,
    val city: String?,
    val country: String?,
    val friendStatus: String?,
    val isPrivate: Boolean = false,
    val isVerified: Boolean = false,
    val isOnline: Boolean = false,
)
data class SearchUsersData(val users: List<PublicProfile>)
data class FollowData(val following: Boolean = false, val isFollowing: Boolean = false, val friendStatus: String?)

interface FrianzoApi2 {
    // Reels (auth required)
    @GET("api/reels")
    suspend fun reelFeed(@Query("offset") offset: Int = 0, @Query("feed") feed: String = "foryou"): Envelope<ReelFeedData>
    @POST("api/reels/{id}/like")
    suspend fun toggleReelLike(@Path("id") id: String): Envelope<ReelLikeData>
    @POST("api/reels/{id}/view")
    suspend fun recordReelView(@Path("id") id: String, @Body body: Map<String, Int>): Envelope<Any>
    @GET("api/reels/{id}/comments")
    suspend fun reelComments(@Path("id") id: String): Envelope<ReelCommentsData>
    @POST("api/reels/{id}/comments")
    suspend fun addReelComment(@Path("id") id: String, @Body body: CommentCreateRequest): Envelope<Any>

    // Posts
    @Multipart
    @POST("api/posts")
    suspend fun createPost(
        @Part("content") content: RequestBody,
        @Part image: MultipartBody.Part?,
    ): Envelope<PostCreateData>
    @GET("api/posts/{id}")
    suspend fun postById(@Path("id") id: String): Envelope<Post>
    @POST("api/posts/{id}/reaction")
    suspend fun setReaction(@Path("id") id: String, @Body body: Map<String, String>): Envelope<ReactionData>
    @GET("api/comments/{postId}")
    suspend fun postComments(@Path("postId") postId: String): Envelope<CommentsData>
    @POST("api/comments/{postId}")
    suspend fun addPostComment(@Path("postId") postId: String, @Body body: CommentCreateRequest): Envelope<Any>

    // Chat (REST polling for step 1 of chat; live socket is a later stage)
    @GET("api/messages/conversations")
    suspend fun conversations(): Envelope<List<Conversation>>
    @POST("api/messages/conversations")
    suspend fun createConversation(@Body body: CreateConversationRequest): Envelope<CreateConversationData>
    @GET("api/messages/conversations/{id}/messages")
    suspend fun messages(@Path("id") id: String): Envelope<List<ConversationMessage>>
    @POST("api/messages/conversations/{id}/messages")
    suspend fun sendMessage(@Path("id") id: String, @Body body: SendMessageRequest): Envelope<ConversationMessage>

    // Notifications
    @GET("api/notifications")
    suspend fun notifications(): Envelope<NotificationsData>
    @GET("api/notifications/unread-count")
    suspend fun unreadCount(): Envelope<UnreadData>
    @POST("api/notifications/{id}/read")
    suspend fun markNotificationRead(@Path("id") id: String): Envelope<Any>
    @POST("api/notifications/read-all")
    suspend fun markAllNotificationsRead(): Envelope<Any>

    // Profile / search / follow
    @GET("api/users/me")
    suspend fun myProfile(): Envelope<PublicProfile>
    @GET("api/users/{username}")
    suspend fun publicProfile(@Path("username") username: String): Envelope<PublicProfile>
    @GET("api/users/search")
    suspend fun searchUsers(@Query("q") q: String): Envelope<SearchUsersData>
    @GET("api/posts/user/{username}")
    suspend fun userPosts(@Path("username") username: String): Envelope<FeedData>
    @POST("api/follows/{userId}")
    suspend fun toggleFollow(@Path("userId") userId: String): Envelope<FollowData>
    @GET("api/follows/{userId}/status")
    suspend fun followStatus(@Path("userId") userId: String): Envelope<FollowData>
}

fun createApi2(context: android.content.Context, cookieJar: PersistentCookieJar): FrianzoApi2 {
    val client = okhttp3.OkHttpClient.Builder().cookieJar(cookieJar).build()
    return retrofit2.Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
        .build()
        .create(FrianzoApi2::class.java)
}
