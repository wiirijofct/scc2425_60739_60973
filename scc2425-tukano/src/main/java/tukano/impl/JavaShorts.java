package tukano.impl;

import static java.lang.String.format;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import redis.clients.jedis.Jedis;
import tukano.api.Blobs;
import tukano.api.Result;
import static tukano.api.Result.ErrorCode.BAD_REQUEST;
import static tukano.api.Result.ErrorCode.FORBIDDEN;
import static tukano.api.Result.error;
import static tukano.api.Result.errorOrResult;
import static tukano.api.Result.errorOrValue;
import static tukano.api.Result.errorOrVoid;
import static tukano.api.Result.ok;
import tukano.api.Short;
import tukano.api.Shorts;
import tukano.api.User;
import tukano.impl.data.Following;
import tukano.impl.data.Likes;
import tukano.impl.rest.TukanoRestServer;
import utils.DB;
import static utils.DB.getOne;
import utils.JSON;
import utils.RedisCache;

public class JavaShorts implements Shorts {

	private static Logger Log = Logger.getLogger(JavaShorts.class.getName());

	private static Shorts instance;

	private static final int SHORT_TTL = 29; // 5 seconds
	private static final int LIKE_LIST_TTL = 15; // 3 seconds

	private static final String SHORTS_PREFIX = "shorts:";
	private static final String LIKES_PREFIX = "likers:";

	synchronized public static Shorts getInstance() {
		if (instance == null)
			instance = new JavaShorts();
		return instance;
	}

	private JavaShorts() {
	}

	@Override
	public Result<Short> createShort(String userId, String password) {
		Log.info(() -> format("createShort : userId = %s, pwd = %s\n", userId, password));

		return errorOrResult(okUser(userId, password), user -> {
			var shortId = format("%s+%s", userId, UUID.randomUUID());
			var blobUrl = format("%s/%s/%s", TukanoRestServer.serverURI, Blobs.NAME, shortId);
			var shrt = new Short(shortId, userId, blobUrl);

			return errorOrValue(DB.insertOne(shrt), s -> {
				try (Jedis jedis = RedisCache.getCachePool().getResource()) {
					var shortKey = SHORTS_PREFIX + shortId;
					var value = JSON.encode(shrt);
					jedis.set(shortKey, value);
					jedis.expire(shortKey, SHORT_TTL);
				}
				return s.copyWithLikes_And_Token(0);
			});
		});
	}

	@Override
	public Result<Short> getShort(String shortId) {
		Log.info(() -> format("getShort : shortId = %s\n", shortId));

		if (shortId == null)
			return error(BAD_REQUEST);

		Result<Short> shrtResult = null;
		int likesCount = -1;

		// Tries to get the short and its likes from the cache
		try (Jedis jedis = RedisCache.getCachePool().getResource()) {
			var shortKey = SHORTS_PREFIX + shortId;
			var shortValue = jedis.get(shortKey);
			if (shortValue != null) {
				jedis.expire(shortKey, SHORT_TTL);
				shrtResult = Result.ok(JSON.decode(shortValue, Short.class));
			}
			var key = LIKES_PREFIX + shortId;
			if (jedis.exists(key)) {
				var value = jedis.lrange(key, 0, -1);
				likesCount = value.size();
			}
		}
		//If cant get short from cache, get it from the database
		if (shrtResult == null)
			shrtResult = getOne(shortId, Short.class);

		// If the likes arent in the cache, they are fetched from the database
		if (likesCount == -1) {
			String query = format("SELECT VALUE COUNT(1) FROM l WHERE l.shortId = '%s'", shortId);
			List<Integer> likesList = DB.sql(query, Integer.class);
			likesCount = likesList.isEmpty() ? 0 : likesList.get(0);
		}
		
		// Needed to avoid the final modifier error
		int finalLikesCount = likesCount;

		return errorOrValue(shrtResult, shrt -> shrt.copyWithLikes_And_Token(finalLikesCount));
	}

	@Override
	public Result<Void> deleteShort(String shortId, String password) {
		Log.info(() -> format("deleteShort : shortId = %s, pwd = %s\n", shortId, password));

		return errorOrResult(getShort(shortId), shrt -> {

			return errorOrResult(okUser(shrt.getOwnerId(), password), user -> {
				return DB.transaction(hibernate -> {

					// hibernate.remove( shrt);

					Result<Short> res = DB.deleteOne(shrt);
					if (!res.isOK()) {
						return Result.error(res.error());
					}
					String likesQuery = format("SELECT * FROM l WHERE l.shortId = '%s'", shortId);
					List<Likes> likesList = DB.sql(likesQuery, Likes.class);
					// var query = format("DELETE Likes l WHERE l.shortId = '%s'", shortId);
					// hibernate.createNativeQuery( query, Likes.class).executeUpdate();
					for (Likes like : likesList) {
						Result<Likes> deleteLikeRes = DB.deleteOne(like);
						if (!deleteLikeRes.isOK()) {
							return Result.error(deleteLikeRes.error());
						}
					}

					try (Jedis jedis = RedisCache.getCachePool().getResource()) {
						var shortKey = SHORTS_PREFIX + shortId;
						jedis.del(shortKey);

						var likesKey = LIKES_PREFIX + shortId;
						jedis.del(likesKey);
					}

					JavaBlobs.getInstance().delete(shrt.getBlobUrl(), Token.get());
					return Result.ok();
				});
			});
		});
	}

	@Override
	public Result<List<String>> getShorts(String userId) {
		Log.info(() -> format("getShorts : userId = %s\n", userId));

		var query = format("SELECT VALUE s.shortId FROM s WHERE s.ownerId = '%s'", userId);
		return errorOrValue(okUser(userId), DB.sql(query, String.class));
	}

	@Override
	public Result<Void> follow(String userId1, String userId2, boolean isFollowing, String password) {
		Log.info(() -> format("follow : userId1 = %s, userId2 = %s, isFollowing = %s, pwd = %s\n", userId1, userId2,
				isFollowing, password));

		return errorOrResult(okUser(userId1, password), user -> {
			var f = new Following(userId1, userId2);
			return errorOrVoid(okUser(userId2), usr ->{
				var result = isFollowing ? DB.insertOne(f) : DB.deleteOne(f);
				return result;
			});
		});
	}

	@Override
	public Result<List<String>> followers(String userId, String password) {
		Log.info(() -> format("followers : userId = %s, pwd = %s\n", userId, password));

		var query = format("SELECT VALUE f.follower FROM f WHERE f.followee = '%s'", userId);
		return errorOrValue(okUser(userId, password), usr -> {
			return DB.sql(query, String.class);
		});
	}

	@Override
	public Result<Void> like(String shortId, String userId, boolean isLiked, String password) {
		Log.info(() -> format("like : shortId = %s, userId = %s, isLiked = %s, pwd = %s\n", shortId, userId, isLiked,
				password));

		return errorOrResult(getShort(shortId), shrt -> {
			var l = new Likes(userId, shortId, shrt.getOwnerId());
			return errorOrVoid(okUser(userId, password), usr -> {
				var result = isLiked ? DB.insertOne(l) : DB.deleteOne(l);
				if (result.isOK()) {
					try (Jedis jedis = RedisCache.getCachePool().getResource()) {
						var key = LIKES_PREFIX + shortId;
						if(jedis.exists(key))
							if(isLiked)
								jedis.lpush(key, userId);
							else
								jedis.lrem(key, 1, userId);
					}
				}
				return result;
			});
		});
	}

	@Override
	public Result<List<String>> likes(String shortId, String password) {
		Log.info(() -> format("likes : shortId = %s, pwd = %s\n", shortId, password));

		List<String> likeList = null;
		try (Jedis jedis = RedisCache.getCachePool().getResource()) {
			var key = LIKES_PREFIX + shortId;
			likeList = jedis.lrange(key, 0, -1);
		}

		// Needed to avoid the final modifier error
		final List<String> finalLikeList = likeList;

		return errorOrResult(getShort(shortId), shrt -> {
			return errorOrValue(okUser(shrt.getOwnerId(), password), usr -> {

				if(!finalLikeList.isEmpty())
					return finalLikeList;
				
					//var query = format("SELECT l.userId FROM Likes l WHERE l.shortId = '%s'", shortId);
				var query = format("SELECT VALUE l.userId FROM l WHERE l.shortId = '%s'", shortId);
				var likesList = DB.sql(query, String.class);
				
				try (Jedis jedis = RedisCache.getCachePool().getResource()) {
					var key = LIKES_PREFIX + shortId;
					jedis.lpush(key, likesList.toArray(new String[0]));
					jedis.expire(key, LIKE_LIST_TTL);
				}

				return likesList;
			});
		});
	}

	@Override
	public Result<List<String>> getFeed(String userId, String password) {
		Log.info(() -> format("getFeed : userId = %s, pwd = %s\n", userId, password));

		final var QUERY_FMT = """
				SELECT s.shortId, s.timestamp FROM Short s WHERE	s.ownerId = '%s'
				UNION
				SELECT s.shortId, s.timestamp FROM Short s, Following f
					WHERE
						f.followee = s.ownerId AND f.follower = '%s'
				ORDER BY s.timestamp DESC""";

		final var query = """
				SELECT s.shortId, s.timestamp
				FROM Short s
				WHERE s.ownerId = @userId OR ARRAY_CONTAINS(@followingIds, s.ownerId)
				ORDER BY s.timestamp DESC""";

		return errorOrValue(okUser(userId, password), DB.sql(format(QUERY_FMT, userId, userId), String.class));
	}

	protected Result<User> okUser(String userId, String pwd) {
		return JavaUsers.getInstance().getUser(userId, pwd);
	}

	private Result<Void> okUser(String userId) {
		var res = okUser(userId, "");
		if (res.error() == FORBIDDEN)
			return ok();
		else
			return error(res.error());
	}

	@Override
	public Result<Void> deleteAllShorts(String userId, String password, String token) {
		Log.info(() -> format("deleteAllShorts : userId = %s, password = %s, token = %s\n", userId, password, token));

		if (!Token.isValid(token, userId))
			return error(FORBIDDEN);

		return DB.transaction((cosmos) -> {

			// delete shorts
			String deleteShortsQuery = format("SELECT * FROM s WHERE s.ownerId = '%s'", userId);
			List<Short> shortsList = DB.sql(deleteShortsQuery, Short.class);
			for (Short shortObj : shortsList) {
				Result<Short> deleteShortRes = DB.deleteOne(shortObj);
				if (deleteShortRes.error() != null) {
					return Result.error(deleteShortRes.error());
				}
				try (Jedis jedis = RedisCache.getCachePool().getResource()) {
					var shortKey = SHORTS_PREFIX + shortObj.getShortId();
					jedis.del(shortKey);

					var likesKey = LIKES_PREFIX + shortObj.getShortId();
					jedis.del(likesKey);
				}
			}
			// delete follows
			String deleteFollowsQuery = format(
					"SELECT * FROM f WHERE f.follower = '%s' OR f.followee = '%s'", userId,
					userId);
			List<Following> followsList = DB.sql(deleteFollowsQuery, Following.class);
			for (Following follow : followsList) {
				Result<Following> deleteFollowRes = DB.deleteOne(follow);
				if (deleteFollowRes.error() != null) {
					return Result.error(deleteFollowRes.error());
				}
			}
			// delete likes
			String deleteLikesQuery = format("SELECT * FROM l WHERE l.userId = '%s'", userId);
			List<Likes> likesList = DB.sql(deleteLikesQuery, Likes.class);
			for (Likes like : likesList) {
				Result<Likes> deleteLikeRes = DB.deleteOne(like);
				if (deleteLikeRes.error() != null) {
					return Result.error(deleteLikeRes.error());
				}
			}
			return Result.ok();

		});
	}

}