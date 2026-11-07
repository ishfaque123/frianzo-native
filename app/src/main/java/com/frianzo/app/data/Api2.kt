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
    // v3.8 (F6): fields the backend already sends — display name + time
    // ago need createdAt; friendStatus seeds the follow pill; shareCode,
    // durationSec and isMine unblock feed polish without extra calls.
    val durationSec: Int? = null,
    val isMine: Boolean = false,
    val friendStatus: String? = null,
    val shareCode: String? = null,
    val createdAt: String? = null,
)
data class ReelFeedData(val reels: List<Reel>, val hasMore: Boolean = false, val nextOffset: Int = 0)
data class ReelsConfigData(val enabled: Boolean = true, val maxDurationSec: Int = 180, val dailyLimit: Int = 0)
data class ReelUploadData(val reel: Reel?)
// v3.5 (R3/R14/R23/R28): reel endpoints native now calls.
data class ReelByIdData(val reel: Reel?)
data class ReelViewData(val viewed: Boolean = false, val viewCount: Int = 0)
data class ReelStatusData(val postedToday: Int = 0, val remaining: Int = 0)
// v3.8 (F2): POST /api/reels/detect-category { frames: [base64 x1-3], caption? }
// returns { category, categories }.
data class DetectCategoryData(val category: String? = null, val categories: List<String>? = null)
data class ReelFavoritesData(val reels: List<Reel>? = null)
data class ReelFavoriteData(val favorited: Boolean = false)
data class ReelLikeData(val liked: Boolean = false, val likeCount: Int = 0)
data class ReelComment(val id: String, val content: String?, val author: Author?, val createdAt: String?, val reactionCount: Int = 0, val myReaction: String? = null, val replies: List<ReelComment> = emptyList())
data class ReelCommentsPagination(val hasMore: Boolean = false, val nextCursor: String? = null)
data class ReelCommentsData(val comments: List<ReelComment>, val pagination: ReelCommentsPagination? = null)

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
data class StatusCreateData(val status: StatusItem?)
data class StatusViewer(val id: String?, val username: String?, val displayName: String?, val profilePictureUrl: String?, val viewedAt: String? = null, val liked: Boolean = false)
// v2.9 FIX: GET /api/statuses/{id}/viewers returns { viewers: [...] },
// NOT a bare array — Envelope<List<StatusViewer>> failed at runtime.
data class StatusViewersData(val viewers: List<StatusViewer> = emptyList())
data class PostCreateData(val post: Post?)
/** Backend GET /api/posts/{id} returns { post }, not a bare post. (Currently unused in UI.) */
data class PostResponse(val post: Post?)
// v5.14 (#5 Save post): POST /api/posts/{id}/favorite returns
// { favorited }, GET /api/posts/me/favorites returns { posts }.
// Mirrors ReelFavoriteData / ReelFavoritesData.
data class PostFavoriteData(val favorited: Boolean = false)
data class PostFavoritesData(val posts: List<Post>? = null)

// ---------- Chat ----------
data class ConversationUser(val id: String?, val username: String?, val displayName: String?, val profilePictureUrl: String?, val lastActiveAt: String? = null, val isOnline: Boolean = false, val showOnlineStatus: Boolean? = null, val isVerified: Boolean = false)
// B-B39: read ticks. isRead/deliveredAt/readAt are populated by the
// backend when it sends them (nullable until then); the thread also
// reads otherLastReadAt/DeliveredAt from MessagesData below.
// v5.13: per-message actions (web parity). The backend returns these
// on every message (getMessages + socket events); Gson leaves a missing
// field null/false, so the list field is nullable and read via orEmpty().
data class MessageReaction(val userId: String?, val emoji: String?)
data class ReplyRefSender(val displayName: String?, val username: String?)
data class ReplyRef(val id: String?, val content: String?, val mediaType: String? = null, val sender: ReplyRefSender? = null, val deletedAt: String? = null)
data class ConversationMessage(val id: String?, val content: String?, val senderId: String?, val createdAt: String?, val mediaUrl: String? = null, val mediaType: String? = null, val voiceDuration: Int? = null, val isDeleted: Boolean = false, val isRead: Boolean? = null, val deliveredAt: String? = null, val readAt: String? = null, val replyToId: String? = null, val replyTo: ReplyRef? = null, val reactions: List<MessageReaction>? = null, val isForwarded: Boolean = false, val pinnedAt: String? = null, val pinnedById: String? = null, val editedAt: String? = null, val expiresAt: String? = null)
data class Conversation(
    val id: String,
    val otherUser: ConversationUser?,
    val messages: List<ConversationMessage>? = null,
    /** Backend list returns lastMessage (singular), not a messages array. */
    val lastMessage: ConversationMessage? = null,
    val unreadCount: Int = 0,
    // v3.4: listConversations returns these per conversation.
    val lastMessageStatus: String? = null,
    val disappearingDuration: Int? = null,
    val updatedAt: String? = null,
)
// B-B39: GET /api/messages/conversations/{id}/messages returns
// { messages, otherLastReadAt, otherLastDeliveredAt } (messageService).
// B-B6: it does NOT mark the conversation read — there is no mark-read
// endpoint; the backend must add one (NEEDS-BACKEND).
data class MessagesData(val messages: List<ConversationMessage>, val otherLastReadAt: String? = null, val otherLastDeliveredAt: String? = null)
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
data class NotificationPref(val category: String, val enabled: Boolean = true)
data class NotificationPrefsData(val preferences: List<NotificationPref>? = null)
data class DeleteIdsRequest(val ids: List<String>)
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
    val province: String? = null,
    val country: String?,
    // v3.3: backend toPublicProfile already returns these (no backend change).
    val mutualFollowers: List<MutualFollower>? = null,
    val mutualCount: Int? = null,
    val friendStatus: String?,
    val isPrivate: Boolean = false,
    val isVerified: Boolean = false,
    val isOnline: Boolean = false,
    // Shown only when the backend actually returns them (null = hidden,
    // never a misleading 0). Backend field names vary, so accept both.
    // v3.4: toPrivateProfile returns these; Account Privacy screen reads/saves them.
    val showOnlineStatus: Boolean? = null,
    val messagePermission: String? = null,
    val tagPermission: String? = null,
    val phone: String? = null,
    val commentsDisabled: Boolean? = null,
    val followerCount: Int? = null,
    val followersCount: Int? = null,
    val followingCount: Int? = null,
    val followingsCount: Int? = null,
    // v5.16 (Edit Profile): the backend's toPrivateProfile already returns
    // these on GET /api/users/me (no backend change) — gender for the Gender
    // select, and the 30-day name-change timestamps for the lock.
    val gender: String? = null,
    val usernameChangedAt: String? = null,
    val displayNameChangedAt: String? = null,
    // v5.18.4 (A-H2): read by MyInterestsScreen — null until the backend
    // returns it. VERIFIED 2026-11-08: userService.toPrivateProfile does
    // NOT include `interests` (the DB column exists and PUT saves it, but
    // no GET returns it), so this stays null and the screen keeps Save
    // disabled rather than wiping saved interests. If the backend adds
    // the field to toPrivateProfile, the screen starts working with no
    // further app change.
    val interests: List<String>? = null,
)
// v3.3: backend getMutualFollowers returns { username, displayName }.
data class MutualFollower(val username: String?, val displayName: String? = null)
data class SearchUsersData(val users: List<PublicProfile>)
// v5.18: GET /api/upload/chat returns {url, mediaType} ("image" | "voice").
data class UploadChatData(val url: String? = null, val mediaType: String? = null)
// v5.18: GET /api/tags/:tag returns {tag, posts, reels, hasMore}.
// The tag reel DTO lacks viewCount/shareCount/category/friendStatus —
// Gson defaults on Reel cover them, no adapter change needed.
data class TagData(val tag: String = "", val posts: List<Post> = emptyList(), val reels: List<Reel> = emptyList(), val hasMore: Boolean = false)
// v5.18: GET /api/search (deployed live 2026-11-07, verified). Response {q, posts, reels, hasMore}.
data class SearchAllData(val q: String = "", val posts: List<Post> = emptyList(), val reels: List<Reel> = emptyList(), val hasMore: Boolean = false)
/** v5.18.2: backend GET /api/search is LIVE (deployed + verified 2026-11-07),
 * so the Posts/Reels search tabs are shown. */
const val SEARCH_API_READY = true
data class FollowData(val following: Boolean = false, val isFollowing: Boolean = false, val friendStatus: String?)
/** Backend GET /api/follows/{id}/status returns { status: "none"|"following"|"follow_back"|"friends"|"self" }. */
data class FollowStatusData(val status: String?)
data class FollowListUser(val id: String?, val username: String?, val displayName: String?, val profilePictureUrl: String?, val isVerified: Boolean = false, val isFollowing: Boolean = false)
data class FollowListData(val users: List<FollowListUser>? = null, val pagination: FollowPagination? = null)
// v3.4: GET /api/follows/{id}/{direction}?page=&limit= returns this pagination block.
data class FollowPagination(val page: Int = 1, val limit: Int = 20, val total: Int = 0, val pages: Int = 1, val hasNext: Boolean = false, val hasPrevious: Boolean = false)
// v2.9: GET /api/follows/{userId}/counts returns { followers, following, friends }.
data class FollowCountsData(val followers: Int = 0, val following: Int = 0, val friends: Int = 0)
data class Achievement(val key: String?, val name: String?, val description: String?, val icon: String? = null, val earnedAt: String? = null)
data class AchievementsData(val achievements: List<Achievement>? = null)
data class Strike(val id: String?, val severity: String?, val reason: String?, val action: String?, val createdAt: String?)
data class Removal(val id: String?, val type: String?, val detail: String?, val createdAt: String?)
data class StrikesData(val strikes: List<Strike>? = null, val removals: List<Removal>? = null, val total: Int = 0)
data class DeviceItem(val id: String?, val label: String?, val location: String?, val lastActiveAt: String?, val isCurrent: Boolean = false)
data class DevicesData(val devices: List<DeviceItem>? = null)
data class BlockedUser(val id: String?, val username: String?, val displayName: String?, val profilePictureUrl: String?)
data class BlockStatusData(val blockedByMe: Boolean = false, val blockedByOther: Boolean = false)
data class BlockedEntry(val blockedAt: String? = null, val user: BlockedUser?)
data class BlockedListData(val blocked: List<BlockedEntry>? = null)
/** Backend GET /api/users/me and /api/users/{username} return { user: {...} }, not bare fields. */
data class ProfileResponse(val user: PublicProfile?)

// G1: POST /api/users/me/profile-setup — the same endpoint the web setup
// page uses (submitProfileSetup in vynzo-frontend authApi.ts). The backend
// sets profileCompleted=true, 409s USERNAME_TAKEN, and 403s when the profile
// is already complete. gender is nullable so it can be omitted when blank:
// the backend's zod enum rejects an empty string (see REPORT.md).
data class ProfileSetupRequest(
    val username: String,
    val displayName: String,
    val dateOfBirth: String,
    val bio: String? = null,
    val gender: String? = null,
    val customGender: String? = null,
    val phone: String? = null,
)


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
// v3.4: GET /api/users/me/dashboard returns weekly goals + allGoalsMet +
// newAchievement { key, name } | null (userController.getMyDashboard).
data class DashboardGoal(val current: Int = 0, val target: Int = 0)
data class DashboardGoals(val posts: DashboardGoal? = null, val reels: DashboardGoal? = null, val stories: DashboardGoal? = null)
data class DashboardAchievement(val key: String?, val name: String?)
data class DashboardData(val earnings: Double = 0.0, val monetization: Monetization? = null, val stats: DashboardStats? = null, val goals: DashboardGoals? = null, val allGoalsMet: Boolean = false, val newAchievement: DashboardAchievement? = null)
data class CloseFriendCandidate(val id: String, val username: String, val displayName: String?, val profilePictureUrl: String?, val isCloseFriend: Boolean = false)
data class CloseFriendsData(val users: List<CloseFriendCandidate>? = null)
// v3.4: GET /api/statuses/muted returns { muted: [user...] } (statusMuteService:
// rows map to the muted user, same select as chat users).
data class MutedUser(val id: String?, val username: String?, val displayName: String?, val profilePictureUrl: String?, val isVerified: Boolean = false)
data class MutedStoriesData(val muted: List<MutedUser>? = null)
// v3.4: GET /api/users/me/referral returns { count, referralLink }.
data class ReferralData(val count: Int = 0, val referralLink: String? = null)
// v3.4: GET /api/geo/reverse?lat=&lng= returns { location: "City, Country" }.
data class GeoReverseData(val location: String? = null)
data class VideoGenStatus(val enabled: Boolean = false, val hasAccess: Boolean = false, val referralCount: Int = 0, val referralsNeeded: Int = 25)
data class VideoGenGenerateRequest(val prompt: String, val aspectRatio: String, val durationSeconds: Int)
data class VideoGenGenerateData(val operationName: String?)
data class VideoGenOperation(val done: Boolean = false, val videoUrl: String?, val error: String?)
// ---------- Highlights (v2.9) ----------
// GET /api/highlights/user/{username} returns { highlights: [...] }.
// Highlight DTO (highlightService.toHighlightDTO): id, title, coverUrl,
// createdAt, itemCount, items[{ id, mediaUrl, mediaType, createdAt }].
data class HighlightItem(val id: String, val mediaUrl: String?, val mediaType: String?, val createdAt: String? = null)
data class Highlight(val id: String, val title: String?, val coverUrl: String?, val createdAt: String? = null, val itemCount: Int = 0, val items: List<HighlightItem> = emptyList())
data class HighlightsData(val highlights: List<Highlight>? = null)

// ---------- Interests (v2.9) ----------
// GET /api/users/interests returns { interests: [12 fixed names] }.
// PUT /api/users/me/interests { interests: [...] } returns { interests }.
// v5.18.4 (A-H2): there is STILL no GET for the user's current interests —
// GET /api/users/me (toPrivateProfile) does not include `interests`
// (verified in vynzo-backend userService.ts 2026-11-08), so
// MyInterestsScreen only pre-selects when PublicProfile.interests is
// non-null and otherwise keeps Save DISABLED instead of wiping the
// saved list. The backend gap: toPrivateProfile must return `interests`.
data class InterestsData(val interests: List<String>? = null)

// ---------- Monetization (v5.18.4, A-H1) ----------
// Payout methods + earnings (vynzo-backend payoutRoutes.ts, mounted at
// /api/payout in app.ts; payoutService.ts):
//   GET /api/payout/methods -> { methods: [PayoutMethod], country,
//     countryName, allowed: { PK|IN|US: [{ code, label, fields }] } }
//   POST /api/payout/methods { methodType, accountName, accountNumber,
//     extra? } -> { method }
//   DELETE /api/payout/methods/{id} -> { deleted: true }
//   POST /api/payout/methods/{id}/default -> { updated: true }
//   GET /api/payout/earnings?limit= -> { earnings: [EarningItem], total }
// Leaderboard (userRoutes GET /api/users/leaderboard, referralService):
//   GET /api/users/leaderboard?category=followers|likers|commenters|
//     referrers&limit= -> { leaderboard: [LeaderboardEntry], category }
data class PayoutMethodField(val accountNumber: String? = null, val extra: String? = null)
data class PayoutMethodOption(val code: String? = null, val label: String? = null, val fields: PayoutMethodField? = null)
data class PayoutMethod(val id: String? = null, val country: String? = null, val methodType: String? = null, val methodLabel: String? = null, val accountName: String? = null, val accountNumber: String? = null, val extra: String? = null, val isDefault: Boolean = false)
data class PayoutMethodsData(val methods: List<PayoutMethod>? = null, val country: String? = null, val countryName: String? = null, val allowed: Map<String, List<PayoutMethodOption>>? = null)
data class PayoutMethodData(val method: PayoutMethod? = null)
data class EarningItem(val id: String? = null, val amount: Double = 0.0, val type: String? = null, val createdAt: String? = null)
data class EarningsData(val earnings: List<EarningItem>? = null, val total: Double = 0.0)
data class LeaderboardEntry(val rank: Int = 0, val username: String? = null, val displayName: String? = null, val avatar: String? = null, val isVerified: Boolean = false, val count: Int = 0)
data class LeaderboardData(val leaderboard: List<LeaderboardEntry>? = null, val category: String? = null)

// ---------- Verification (v2.9) ----------
// GET /api/users/me/verification-request returns
// { request: { id, status, reason, adminNote, reviewedAt, createdAt } | null,
//   eligibility: { eligible, isVerified,
//     requirements: { referrals, followers, accountAge, posts, profile,
//       cleanRecord: { current, required, met } } } }.
// Requirements (fresh userController.getVerificationEligibility): referrals
// 25, followers 100, accountAge 30 days, posts 5, profile Photo+Bio,
// cleanRecord no violations in 90 days. `current` is a number OR a string,
// so it is a JsonElement, rendered by verificationValue() in DrawerScreens.
data class VerificationRequirement(val current: com.google.gson.JsonElement? = null, val required: com.google.gson.JsonElement? = null, val met: Boolean = false)
data class VerificationEligibility(val eligible: Boolean = false, val requirements: Map<String, VerificationRequirement>? = null, val isVerified: Boolean = false)
data class VerificationRequestData(val id: String?, val status: String?, val reason: String?, val adminNote: String?, val reviewedAt: String?, val createdAt: String?)
data class VerificationData(val request: VerificationRequestData? = null, val eligibility: VerificationEligibility? = null)

// ---------- Saved accounts (v2.9) ----------
// GET /api/auth/accounts returns { accounts: [{ id, username,
// displayName, profilePictureUrl, profileCompleted }] } (the user's own
// accounts saved on this device). POST /api/auth/switch { accountId }
// sets the new auth cookie and returns { user }.
data class SavedAccount(val id: String?, val username: String?, val displayName: String?, val profilePictureUrl: String?)
data class SavedAccountsData(val accounts: List<SavedAccount>? = null)

data class IsaiMessage(val role: String, val content: String)
data class IsaiChatRequest(val messages: List<IsaiMessage>)
data class IsaiReply(val reply: String?, val remaining: Int? = null)

interface FrianzoApi2 {
    // Reels (auth required)
    // v3.2: web profile uses GET /api/reels/user/{username} (reelApi.ts
    // fetchUserReels) for the profile Reels tab + Reels count.
    @GET("api/reels/user/{username}")
    suspend fun userReels(@Path("username") username: String): Envelope<ReelFeedData>
    @GET("api/reels")
    suspend fun reelFeed(@Query("offset") offset: Int = 0, @Query("feed") feed: String = "foryou", @Query("exclude") exclude: String? = null): Envelope<ReelFeedData>
    @GET("api/reels/config")
    suspend fun reelsConfig(): Envelope<ReelsConfigData>
    @Multipart
    @POST("api/reels")
    suspend fun uploadReel(
        @Part video: MultipartBody.Part,
        @Part("caption") caption: RequestBody?,
        @Part("category") category: RequestBody?,
        @Part("durationSec") durationSec: RequestBody,
        // v3.4: only values the UI really has are sent (cover = picked image,
        // speed = 0.5|1|1.5|2, textOverlay <= 100 chars, trim seconds).
        // No filter part: server filter enum is only
        // normal,warm,cool,bw,vintage,vivid,beauty — never invent others.
        @Part cover: MultipartBody.Part? = null,
        @Part("speed") speed: RequestBody? = null,
        @Part("textOverlay") textOverlay: RequestBody? = null,
        @Part("trimStartSec") trimStartSec: RequestBody? = null,
        @Part("trimEndSec") trimEndSec: RequestBody? = null,
        // v3.5 (R15): server filter enum normal/warm/cool/bw/vintage/vivid/beauty.
        @Part("filter") filter: RequestBody? = null,
        // v3.8 (F4): backend createReelSchema already accepts all of these
        // (letterbox/flip as "true", rotate "90"|"180"|"270",
        // crop "square"|"portrait", textPosition "top"|"middle"|"bottom").
        @Part("letterbox") letterbox: RequestBody? = null,
        @Part("flip") flip: RequestBody? = null,
        @Part("rotate") rotate: RequestBody? = null,
        @Part("crop") crop: RequestBody? = null,
        @Part("textPosition") textPosition: RequestBody? = null,
    ): Envelope<ReelUploadData>
    @POST("api/reels/{id}/like")
    suspend fun toggleReelLike(@Path("id") id: String): Envelope<ReelLikeData>
    @POST("api/reels/{id}/favorite")
    suspend fun toggleReelFavorite(@Path("id") id: String): Envelope<ReelFavoriteData>
    @POST("api/reels/{id}/report")
    suspend fun reportReel(@Path("id") id: String, @Body body: Map<String, String>): Envelope<Any>
    @DELETE("api/reels/{id}")
    suspend fun deleteReel(@Path("id") id: String): Envelope<Any>
    @POST("api/reels/{id}/view")
    suspend fun recordReelView(@Path("id") id: String, @Body body: Map<String, Int>): Envelope<ReelViewData>
    // v3.5 (R3): fetch one reel directly for deep-links / after upload.
    @GET("api/reels/{id}")
    suspend fun reelById(@Path("id") id: String): Envelope<ReelByIdData>
    // v3.5 (R14): saved reels list.
    @GET("api/reels/me/favorites")
    suspend fun myFavoriteReels(): Envelope<ReelFavoritesData>
    // v3.5 (R28): how many reels the user posted today / has left.
    @GET("api/reels/me/status")
    suspend fun myReelStatus(): Envelope<ReelStatusData>
    // v3.8 (F2): AI category auto-detect from video frames.
    @POST("api/reels/detect-category")
    suspend fun detectCategory(@Body body: Map<String, Any>): Envelope<DetectCategoryData>
    // v3.5 (R13): server returns a watermarked mp4 (not an Envelope).
    @GET("api/reels/{id}/download")
    suspend fun downloadReel(@Path("id") id: String): okhttp3.ResponseBody
    @GET("api/reels/{id}/comments")
    suspend fun reelComments(@Path("id") id: String, @Query("cursor") cursor: String? = null, @Query("limit") limit: Int = 20): Envelope<ReelCommentsData>
    @POST("api/reels/{id}/comments")
    suspend fun addReelComment(@Path("id") id: String, @Body body: CommentCreateRequest): Envelope<Any>
    @POST("api/reels/comments/{commentId}/reaction")
    suspend fun reelCommentReaction(@Path("commentId") commentId: String, @Body body: Map<String, String>): Envelope<Any>
    @PUT("api/reels/comments/{commentId}")
    suspend fun editReelComment(@Path("commentId") commentId: String, @Body body: Map<String, String>): Envelope<Any>
    @POST("api/reels/comments/{commentId}/report")
    suspend fun reportReelComment(@Path("commentId") commentId: String, @Body body: Map<String, String>): Envelope<Any>
    @DELETE("api/reels/comments/{commentId}")
    suspend fun deleteReelComment(@Path("commentId") commentId: String): Envelope<Any>

    // Posts
    // v3.4: one create call — every part is optional except content, so the
    // composer sends visibility / commentAudience / taggedUserIds (JSON
    // array) / location / backgroundStyle only when the user set them.
    @Multipart
    @POST("api/posts")
    suspend fun createPost(
        // B-B40: nullable — image-only posts omit the content part
        // entirely (backend-verified: empty content is accepted only
        // with an image; the part is optional in the multipart body).
        @Part("content") content: RequestBody? = null,
        @Part image: MultipartBody.Part?,
        @Part("backgroundStyle") backgroundStyle: RequestBody? = null,
        @Part("visibility") visibility: RequestBody? = null,
        @Part("commentAudience") commentAudience: RequestBody? = null,
        @Part("taggedUserIds") taggedUserIds: RequestBody? = null,
        @Part("location") location: RequestBody? = null,
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
    // v2.9: POST /api/posts/{id}/view { watchSec } records a post view.
    @POST("api/posts/{id}/view")
    suspend fun recordPostView(@Path("id") id: String, @Body body: Map<String, Int>): Envelope<Any>
    @POST("api/posts/{id}/pin")
    suspend fun togglePin(@Path("id") id: String): Envelope<Any>
    // v5.14 (#5 Save post): mirrors toggleReelFavorite / myFavoriteReels.
    @POST("api/posts/{id}/favorite")
    suspend fun togglePostFavorite(@Path("id") id: String): Envelope<PostFavoriteData>
    @GET("api/posts/me/favorites")
    suspend fun myFavoritePosts(): Envelope<PostFavoritesData>
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
    @Multipart
    @POST("api/statuses")
    suspend fun createStatus(
        @Part media: MultipartBody.Part?,
        @Part("textContent") textContent: RequestBody?,
        @Part("bgColor") bgColor: RequestBody?,
        @Part("visibility") visibility: RequestBody?,
    ): Envelope<StatusCreateData>
    @GET("api/statuses/muted")
    suspend fun mutedStories(): Envelope<MutedStoriesData>
    @GET("api/users/me/referral")
    suspend fun referral(): Envelope<ReferralData>
    @GET("api/geo/reverse")
    suspend fun reverseGeo(@Query("lat") lat: Double, @Query("lng") lng: Double): Envelope<GeoReverseData>
    @GET("api/statuses/{id}/viewers")
    suspend fun statusViewers(@Path("id") id: String): Envelope<StatusViewersData>
    @POST("api/statuses/mute/{userId}")
    suspend fun muteStories(@Path("userId") userId: String): Envelope<Any>
    @DELETE("api/statuses/mute/{userId}")
    suspend fun unmuteStories(@Path("userId") userId: String): Envelope<Any>
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
    suspend fun messages(@Path("id") id: String, @Query("cursor") cursor: String? = null): Envelope<MessagesData>
    // v5.18: chat media upload — POST /api/upload/chat (field "file"),
    // returns {url, mediaType}. The media itself is then sent over the
    // socket (message:send with mediaUrl/mediaType), like the web.
    @Multipart
    @POST("api/upload/chat")
    suspend fun uploadChatMedia(@Part file: MultipartBody.Part): Envelope<UploadChatData>
    @POST("api/messages/conversations/{id}/messages")
    suspend fun sendMessage(@Path("id") id: String, @Body body: SendMessageRequest): Envelope<ConversationMessage>
    @DELETE("api/messages/conversations/{id}")
    suspend fun deleteConversation(@Path("id") id: String): Envelope<Any>
    // B-B2: the body is a raw JSON RequestBody, NOT a Map — default
    // Gson drops null values, so a Map could never send the explicit
    // {"duration":null} that means Off. The backend accepts null = Off
    // (messageController: null or one of 3600/86400/604800/7776000; 0 is
    // rejected 400). Callers build the JSON string themselves.
    @POST("api/messages/conversations/{id}/disappearing")
    suspend fun setDisappearing(@Path("id") id: String, @Body body: okhttp3.RequestBody): Envelope<Any>

    // Notifications
    @GET("api/notifications")
    suspend fun notifications(): Envelope<NotificationsData>
    @GET("api/notifications/unread-count")
    suspend fun unreadCount(): Envelope<UnreadData>
    @POST("api/notifications/{id}/read")
    suspend fun markNotificationRead(@Path("id") id: String): Envelope<Any>
    @POST("api/notifications/read-all")
    suspend fun markAllNotificationsRead(): Envelope<Any>
    @GET("api/notifications/preferences")
    suspend fun notificationPrefs(): Envelope<NotificationPrefsData>
    @PUT("api/notifications/preferences")
    suspend fun updateNotificationPref(@Body body: Map<String, Any>): Envelope<Any>
    @DELETE("api/notifications/all")
    suspend fun deleteAllNotifications(): Envelope<Any>
    // B-B38 (backend-verified): the Express handler reads req.body.ids,
    // so the bodied DELETE is accepted server-side; kept as-is, and B-B20
    // now surfaces a failure instead of silently doing nothing.
    @HTTP(method = "DELETE", path = "api/notifications", hasBody = true)
    suspend fun deleteNotifications(@Body body: DeleteIdsRequest): Envelope<Any>

    // Profile / search / follow
    @GET("api/users/me")
    suspend fun myProfile(): Envelope<ProfileResponse>
    // G1: the web profile-setup save endpoint (POST /api/users/me/profile-setup).
    @POST("api/users/me/profile-setup")
    suspend fun submitProfileSetup(@Body req: ProfileSetupRequest): Envelope<ProfileResponse>
    @GET("api/users/{username}")
    suspend fun publicProfile(@Path("username") username: String): Envelope<ProfileResponse>
    @GET("api/users/search")
    suspend fun searchUsers(@Query("q") q: String): Envelope<SearchUsersData>
    // v5.18: tag page — GET /api/tags/:tag (exists, no backend change).
    @GET("api/tags/{tag}")
    suspend fun getTag(@Path("tag") tag: String, @Query("limit") limit: Int = 20, @Query("offset") offset: Int = 0): Envelope<TagData>
    // v5.18: post/reel search — GET /api/search (backend staged, gated by
    // SEARCH_API_READY; do not call while the endpoint is not deployed).
    @GET("api/search")
    suspend fun searchAll(@Query("q") q: String, @Query("type") type: String = "all", @Query("limit") limit: Int = 20, @Query("offset") offset: Int = 0): Envelope<SearchAllData>
    @GET("api/posts/user/{username}")
    suspend fun userPosts(@Path("username") username: String): Envelope<FeedData>
    @POST("api/follows/{userId}")
    suspend fun toggleFollow(@Path("userId") userId: String): Envelope<FollowData>
    @GET("api/follows/{userId}/status")
    suspend fun followStatus(@Path("userId") userId: String): Envelope<FollowStatusData>
    // v2.9: the ONLY follower-count endpoint (PublicProfile returns none).
    @GET("api/follows/{userId}/counts")
    suspend fun followCounts(@Path("userId") userId: String): Envelope<FollowCountsData>
    @GET("api/follows/{userId}/{direction}")
    suspend fun followUsers(@Path("userId") userId: String, @Path("direction") direction: String, @Query("page") page: Int = 1, @Query("limit") limit: Int = 20): Envelope<FollowListData>
    @GET("api/users/me/achievements")
    suspend fun myAchievements(): Envelope<AchievementsData>
    @GET("api/users/me/strikes")
    suspend fun myStrikes(): Envelope<StrikesData>
    @GET("api/devices")
    suspend fun devices(): Envelope<DevicesData>
    @DELETE("api/devices/{id}")
    suspend fun revokeDevice(@Path("id") id: String): Envelope<Any>
    @DELETE("api/devices/others")
    suspend fun revokeOtherDevices(): Envelope<Any>
    @GET("api/blocks")
    suspend fun blockedUsers(): Envelope<BlockedListData>
    @POST("api/blocks/{userId}")
    suspend fun blockUser(@Path("userId") userId: String): Envelope<Any>
    @DELETE("api/blocks/{userId}")
    suspend fun unblockUser(@Path("userId") userId: String): Envelope<Any>
    @GET("api/blocks/{userId}/status")
    suspend fun blockStatus(@Path("userId") userId: String): Envelope<BlockStatusData>
    @Multipart
    @POST("api/users/me/avatar")
    suspend fun uploadAvatar(@Part image: MultipartBody.Part): Envelope<Any>
    @Multipart
    @POST("api/users/me/cover")
    suspend fun uploadCover(@Part image: MultipartBody.Part): Envelope<Any>

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
    // v2.9: POST /api/users/me/not-interested { type: "creator"|"category"|
    // "interested_category", value } — "Not interested" is NOT hidePost.
    @POST("api/users/me/not-interested")
    suspend fun notInterested(@Body body: Map<String, String>): Envelope<Any>
    // v3.4: POST /api/users/{userId}/report { reason, details? } (userRoutes).
    @POST("api/users/{userId}/report")
    suspend fun reportUser(@Path("userId") userId: String, @Body body: Map<String, String>): Envelope<Any>
    // v3.4: PATCH /api/comments/settings/toggle { disabled: boolean }
    // (commentRoutes) — toggles the user's commentsDisabled flag.
    @PATCH("api/comments/settings/toggle")
    suspend fun toggleComments(@Body body: Map<String, Boolean>): Envelope<Any>
    // v2.9: highlights
    @GET("api/highlights/user/{username}")
    suspend fun highlights(@Path("username") username: String): Envelope<HighlightsData>
    @POST("api/highlights")
    suspend fun createHighlight(@Body body: Map<String, Any>): Envelope<Any>
    @DELETE("api/highlights/{id}")
    suspend fun deleteHighlight(@Path("id") id: String): Envelope<Any>
    // v2.9: interests (GET is /api/users/interests, PUT is /api/users/me/interests)
    @GET("api/users/interests")
    suspend fun interests(): Envelope<InterestsData>
    @PUT("api/users/me/interests")
    suspend fun updateMyInterests(@Body body: Map<String, List<String>>): Envelope<InterestsData>
    // v5.18.4 (A-H1): monetization — payout methods, earnings, leaderboard.
    // Every endpoint below was verified to exist in vynzo-backend main
    // (payoutRoutes.ts + userRoutes.ts) before this client was written.
    @GET("api/payout/methods")
    suspend fun payoutMethods(): Envelope<PayoutMethodsData>
    @POST("api/payout/methods")
    suspend fun addPayoutMethod(@Body body: Map<String, String>): Envelope<PayoutMethodData>
    @DELETE("api/payout/methods/{id}")
    suspend fun deletePayoutMethod(@Path("id") id: String): Envelope<Any>
    @POST("api/payout/methods/{id}/default")
    suspend fun setDefaultPayoutMethod(@Path("id") id: String): Envelope<Any>
    @GET("api/payout/earnings")
    suspend fun earnings(@Query("limit") limit: Int = 20): Envelope<EarningsData>
    @GET("api/users/leaderboard")
    suspend fun leaderboard(@Query("category") category: String, @Query("limit") limit: Int = 10): Envelope<LeaderboardData>
    // v2.9: verification
    @GET("api/users/me/verification-request")
    suspend fun verificationRequest(): Envelope<VerificationData>
    @POST("api/users/me/verification-request")
    suspend fun createVerificationRequest(@Body body: Map<String, String>): Envelope<VerificationData>
    // v2.9: saved accounts
    @GET("api/auth/accounts")
    suspend fun savedAccounts(): Envelope<SavedAccountsData>
    @POST("api/auth/switch")
    suspend fun switchAccount(@Body body: Map<String, String>): Envelope<ProfileResponse>
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
        // v5.12: explicit timeouts so a stalled request fails fast instead
        // of hanging the UI on the OkHttp 10s defaults (offline support).
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    return retrofit2.Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
        .build()
        .create(FrianzoApi2::class.java)
}


