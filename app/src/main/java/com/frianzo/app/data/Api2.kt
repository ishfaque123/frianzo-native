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
data class ReactionData(val myReaction: String? = null, val reaction: String? = null, val likeCount: Int? = null, val reactionCounts: Map<String, Int>? = null)
data class Comment(
    val id: String,
    val content: String?,
    val author: Author?,
    val createdAt: String?,
    val replyCount: Int = 0,
    val mediaUrl: String? = null,
    val myReaction: String? = null,
    val reactionCount: Int = 0,
    val replies: List<Comment> = emptyList(),
)
data class CommentsData(val comments: List<Comment>)
data class CommentCreateRequest(val content: String, val parentCommentId: String? = null)
data class ShareCodeData(val code: String?)
data class StatusItem(
    val id: String,
    val mediaUrl: String? = null,
    val mediaType: String? = null,
    val thumbnailUrl: String? = null,
    val textContent: String? = null,
    val bgColor: String? = null,
    val visibility: String? = null,
    val createdAt: String? = null,
    val seen: Boolean = false,
    val liked: Boolean = false,
)
data class StatusGroup(
    val userId: String,
    val user: Author? = null,
    val items: List<StatusItem> = emptyList(),
    val hasUnseen: Boolean = false,
)
data class StatusFeedData(val feed: List<StatusGroup>? = null)
data class PostCreateData(val post: Post?)
/** Backend GET /api/posts/{id} returns { post }, not a bare post. (Currently unused in UI.) */
data class PostResponse(val post: Post?)

// ---------- Chat ----------
data class ConversationUser(val id: String?, val username: String?, val displayName: String?, val profilePictureUrl: String?)
data class ConversationMessage(val id: String?, val content: String?, val senderId: String?, val createdAt: String?)
data class Conversation(
    val id: String,
    val otherUser: ConversationUser?,
    val messages: List<ConversationMessage>? = null,
    /** Backend list returns lastMessage (singular), not a messages array. */
    val lastMessage: ConversationMessage? = null,
    val unreadCount: Int = 0,
)
data class MessagesData(val messages: List<ConversationMessage>)
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
/** Backend GET /api/follows/{id}/status returns { status: "none"|"following"|"follow_back"|"friends"|"self" }. */
data class FollowStatusData(val status: String?)
/** Backend GET /api/users/me and /api/users/{username} return { user: {...} }, not bare fields. */
data class ProfileResponse(val user: PublicProfile?)


// ---------- Drawer destinations (v2.7, verified against the web frontend) ----------
data class UsageData(val pings: List<String>? = null)
data class EngagementBreakdown(val likes: Int = 0, val comments: Int = 0, val shares: Int = 0)
data class DailyView(val date: String, val views: Int = 0)
data class TopPerforming(val type: String?, val id: String?, val title: String?, val mediaUrl: String?, val createdAt: String?, val views: Int = 0)
data class DashboardStats(
    val views: Int = 0,
    val engagement: Int = 0,
    val engagementBreakdown: EngagementBreakdown? = null,
    val engagementTrendPct: Double? = null,
    val newFollowers: Int = 0,
    val followersTrendPct: Double? = null,
    val content: Int = 0,
    val contentTrendPct: Double? = null,
    val dailyViews: List<DailyView> = emptyList(),
    val viewsTrendPct: Double? = null,
    val topPerforming: TopPerforming? = null,
)
data class Monetization(val status: String?, val referrals: Int = 0, val requiredReferrals: Int = 0, val progress: Int = 0, val referralLink: String = "")
data class DashboardData(val earnings: Double = 0.0, val monetization: Monetization? = null, val stats: DashboardStats? = null)
data class CloseFriendCandidate(val id: String, val username: String, val displayName: String?, val profilePictureUrl: String?, val isCloseFriend: Boolean = false)
data class CloseFriendsData(val users: List<CloseFriendCandidate>? = null)
data class VideoGenStatus(val enabled: Boolean = false, val hasAccess: Boolean = false, val referralCount: Int = 0, val referralsNeeded: Int = 25)
data class VideoGenGenerateRequest(val prompt: String, val aspectRatio: String, val durationSeconds: Int)
data class VideoGenGenerateData(val operationName: String?)
data class VideoGenOperation(val done: Boolean = false, val videoUrl: String?, val error: String?)
data class IsaiMessage(val role: String, val content: String)
data class IsaiChatRequest(val messages: List<IsaiMessage>)
data class IsaiReply(val reply: String?, val remaining: Int? = null)

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
    suspend fun postById(@Path("id") id: String): Envelope<PostResponse>
    @POST("api/posts/{id}/reaction")
    suspend fun setReaction(@Path("id") id: String, @Body body: Map<String, String>): Envelope<ReactionData>
    @GET("api/comments/{postId}")
    suspend fun postComments(@Path("postId") postId: String): Envelope<CommentsData>
    @POST("api/comments/{postId}")
    suspend fun addPostComment(@Path("postId") postId: String, @Body body: CommentCreateRequest): Envelope<Any>
    @Multipart
    @POST("api/comments/{postId}")
    suspend fun addPostCommentImage(
        @Path("postId") postId: String,
        @Part("content") content: okhttp3.RequestBody,
        @Part("parentCommentId") parentCommentId: okhttp3.RequestBody?,
        @Part image: MultipartBody.Part?,
    ): Envelope<Any>
    @POST("api/comments/{commentId}/reaction")
    suspend fun setCommentReaction(@Path("commentId") commentId: String, @Body body: Map<String, String>): Envelope<Any>
    @DELETE("api/comments/{commentId}")
    suspend fun deleteComment(@Path("commentId") commentId: String): Envelope<Any>
    @POST("api/comments/{commentId}/report")
    suspend fun reportComment(@Path("commentId") commentId: String, @Body body: Map<String, String>): Envelope<Any>
    @POST("api/posts/{id}/report")
    suspend fun reportPost(@Path("id") id: String, @Body body: Map<String, String>): Envelope<Any>
    @POST("api/posts/{id}/hide")
    suspend fun hidePost(@Path("id") id: String): Envelope<Any>
    @POST("api/posts/{id}/pin")
    suspend fun togglePin(@Path("id") id: String): Envelope<Any>
    @PATCH("api/posts/{id}")
    suspend fun updatePost(@Path("id") id: String, @Body body: Map<String, String>): Envelope<Any>
    @DELETE("api/posts/{id}")
    suspend fun deletePost(@Path("id") id: String): Envelope<Any>
    @POST("api/posts/{id}/share")
    suspend fun repostPost(@Path("id") id: String, @Body body: Map<String, String>): Envelope<Any>
    @POST("api/share")
    suspend fun createShareLink(@Body body: Map<String, String>): Envelope<ShareCodeData>
    @POST("api/share/event")
    suspend fun recordShareEvent(@Body body: Map<String, String>): Envelope<Any>
    @GET("api/statuses")
    suspend fun statusFeed(): Envelope<StatusFeedData>
    @POST("api/statuses/{id}/view")
    suspend fun viewStatus(@Path("id") id: String): Envelope<Any>
    @POST("api/statuses/{id}/like")
    suspend fun toggleStatusLike(@Path("id") id: String): Envelope<Any>
    @DELETE("api/statuses/{id}")
    suspend fun deleteStatus(@Path("id") id: String): Envelope<Any>

    // Chat (REST polling for step 1 of chat; live socket is a later stage)
    @GET("api/messages/conversations")
    suspend fun conversations(): Envelope<List<Conversation>>
    @POST("api/messages/conversations")
    suspend fun createConversation(@Body body: CreateConversationRequest): Envelope<CreateConversationData>
    @GET("api/messages/conversations/{id}/messages")
    suspend fun messages(@Path("id") id: String): Envelope<MessagesData>
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
    suspend fun myProfile(): Envelope<ProfileResponse>
    @GET("api/users/{username}")
    suspend fun publicProfile(@Path("username") username: String): Envelope<ProfileResponse>
    @GET("api/users/search")
    suspend fun searchUsers(@Query("q") q: String): Envelope<SearchUsersData>
    @GET("api/posts/user/{username}")
    suspend fun userPosts(@Path("username") username: String): Envelope<FeedData>
    @POST("api/follows/{userId}")
    suspend fun toggleFollow(@Path("userId") userId: String): Envelope<FollowData>
    @GET("api/follows/{userId}/status")
    suspend fun followStatus(@Path("userId") userId: String): Envelope<FollowStatusData>

    // Drawer destinations (v2.7)
    @GET("api/usage")
    suspend fun usage(@Query("from") from: String, @Query("to") to: String): Envelope<UsageData>
    @GET("api/users/me/dashboard")
    suspend fun dashboard(@Query("days") days: Int = 30): Envelope<DashboardData>
    @GET("api/close-friends")
    suspend fun closeFriends(): Envelope<CloseFriendsData>
    @POST("api/close-friends/{userId}")
    suspend fun addCloseFriend(@Path("userId") userId: String): Envelope<Any>
    @DELETE("api/close-friends/{userId}")
    suspend fun removeCloseFriend(@Path("userId") userId: String): Envelope<Any>
    @GET("api/video-gen/status")
    suspend fun videoGenStatus(): Envelope<VideoGenStatus>
    @POST("api/video-gen/generate")
    suspend fun generateVideo(@Body body: VideoGenGenerateRequest): Envelope<VideoGenGenerateData>
    @GET("api/video-gen/operation/{name}")
    suspend fun videoGenOperation(@Path("name") name: String): Envelope<VideoGenOperation>
    @POST("api/ai-assistant/chat")
    suspend fun isaiChat(@Body body: IsaiChatRequest): Envelope<IsaiReply>
    // Comment parity (web: PATCH /api/comments/{id})
    @PATCH("api/comments/{commentId}")
    suspend fun editComment(@Path("commentId") commentId: String, @Body body: Map<String, String>): Envelope<Any>
    // Settings (web: PATCH / DELETE /api/users/me)
    @PATCH("api/users/me")
    suspend fun updateMe(@Body body: Map<String, Any>): Envelope<Any>
    @DELETE("api/users/me")
    suspend fun deleteMe(): Envelope<Any>
}

fun createApi2(context: android.content.Context, cookieJar: PersistentCookieJar): FrianzoApi2 {
    // MUST carry the same Origin interceptor as createApi (see Api.kt):
    // without it every Api2 POST 403s with MISSING_ORIGIN.
    val client = okhttp3.OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor(originInterceptor())
        .build()
    return retrofit2.Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
        .build()
        .create(FrianzoApi2::class.java)
}


