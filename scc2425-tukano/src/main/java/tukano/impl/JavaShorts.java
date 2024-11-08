package tukano.impl;

import static java.lang.String.format;
import java.util.ArrayList;
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
import utils.CosmosDB;
import utils.DB;
import static utils.DB.getOne;
import utils.JSON;
import utils.RedisCache;

public class JavaShorts implements Shorts {

	private static Logger Log = Logger.getLogger(JavaShorts.class.getName());

	private static Shorts instance;

	private static final int SHORT_TTL = 5; // 5 seconds
	private static final int LIKE_LIST_TTL = 3; // 3 seconds

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
				return s.copyWithLikes_Views_And_Token(0,0);
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
		// If cant get short from cache, get it from the database
		if (shrtResult == null)
			shrtResult = getOne(shortId, Short.class);

		// If the likes arent in the cache, they are fetched from the database
		if (likesCount == -1) {
			String query;
			if(DB.BASE.equals(DB.NOSQL))
				query = format("SELECT VALUE COUNT(1) FROM l WHERE l.shortId = '%s'", shortId);
			else
				query = format("SELECT COUNT(*) FROM likes WHERE shortId = '%s'", shortId);
			List<Integer> likesList = DB.sql(query, Integer.class);
			likesCount = likesList.isEmpty() ? 0 : likesList.get(0);
		}

		// Needed to avoid the final modifier error
		int finalLikesCount = likesCount;

		return errorOrValue(shrtResult, shrt -> shrt.copyWithLikes_Views_And_Token(finalLikesCount, shrt.getTotalViews()));
	}

	private Result<Void> deleShortInNoSql(Short shrt) {
		return DB.noSqltransaction(hibernate -> {
			DB.deleteOne(shrt);
			String likesQuery = format("SELECT * FROM l WHERE l.shortId = '%s'", shrt.getShortId());
			List<Likes> likesList = DB.sql(likesQuery, Likes.class);

			for (Likes like : likesList) {
				Result<Likes> deleteLikeRes = DB.deleteOne(like);

				if (!deleteLikeRes.isOK())
					return Result.error(deleteLikeRes.error());
			}

			return Result.ok();
		});
	}

	private Result<Void> deleteShortInPostgres (Short shrt) {
		return DB.transaction(hibernate -> {
			DB.deleteOne(shrt);
			var query = format("DELETE FROM likes WHERE shortId = '%s'", shrt.getShortId());
			hibernate.createNativeQuery( query, Likes.class).executeUpdate();

			return Result.ok();
		});
	}
	@Override
	public Result<Void> deleteShort(String shortId, String password) {
		Log.info(() -> format("deleteShort : shortId = %s, pwd = %s\n", shortId, password));

		return errorOrResult(getShort(shortId), shrt -> {

			return errorOrResult(okUser(shrt.getOwnerId(), password), user -> {

				Result<Void> res;;
				if(DB.BASE.equals(DB.NOSQL))
					res = deleShortInNoSql(shrt);
				else
					res = deleteShortInPostgres(shrt);
				
				JavaBlobs.getInstance().delete(shrt.getBlobUrl(), Token.get());

				if (res.isOK()) {					
					try (Jedis jedis = RedisCache.getCachePool().getResource()) {
						var shortKey = SHORTS_PREFIX + shortId;
						jedis.del(shortKey);
						
						var likesKey = LIKES_PREFIX + shortId;
						jedis.del(likesKey);
					}
				}
				return res;
			});
		});
	}

	@Override
	public Result<List<String>> getShorts(String userId) {
		Log.info(() -> format("getShorts : userId = %s\n", userId));

		String query;
		if(DB.BASE.equals(DB.NOSQL))
			query = format("SELECT VALUE s.shortId FROM s WHERE s.ownerId = '%s'", userId);
		else
			query = format("SELECT shortId FROM shorts WHERE ownerId = '%s'", userId);

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

		String query;
		if(DB.BASE.equals(DB.NOSQL))
			query = format("SELECT VALUE f.follower FROM f WHERE f.followee = '%s'", userId);
		else
			query = format("SELECT follower FROM following WHERE followee = '%s'", userId);
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
						if (jedis.exists(key))
							if (isLiked)
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
					
				String query;	
				if(DB.BASE.equals(DB.NOSQL)) 
					query = format("SELECT VALUE l.userId FROM l WHERE l.shortId = '%s'", shortId);
				else
					query = format("SELECT userId FROM likes WHERE shortId = '%s'", shortId);
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

		return errorOrValue(okUser(userId, password), usr -> {

			String query;
			if(DB.BASE.equals(DB.NOSQL)) {

				var followingQuery = format("SELECT VALUE f.followee FROM f WHERE f.follower = '%s'", userId);
				var followingList = DB.sql(followingQuery, String.class);
				
				if (followingList.isEmpty()) {
					return new ArrayList<String>(0);
				}
				
				String listString = CosmosDB.formatListForSqlInClause(followingList);
				
				query = format("SELECT VALUE s.shortId FROM s WHERE s.ownerId IN %s ORDER BY s.timestamp DESC", listString);
			}
			else
				query = format("""
								SELECT shortId 
								FROM shorts WHERE ownerId
								IN (SELECT followee FROM following WHERE follower = '%s')
								ORDER BY timestamp DESC""", userId);

			return DB.sql(query, String.class);
		});
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

	private Result<Void> deleteAllNoSqlShorts(String userId) {
			return DB.transaction((cosmos) -> {
		
				// Delete shorts
				String deleteShortsQuery = format("SELECT * FROM s WHERE s.ownerId = '%s'", userId);
				List<Short> shortsList = DB.sql(deleteShortsQuery, Short.class);
		
				for (Short shortObj : shortsList) {
					Result<Short> deleteShortRes = DB.deleteOne(shortObj);
					if (deleteShortRes.isOK()) {
						try (Jedis jedis = RedisCache.getCachePool().getResource()) {
							var shortKey = SHORTS_PREFIX + shortObj.getShortId();
							jedis.del(shortKey);

							var likesKey = LIKES_PREFIX + shortObj.getShortId();
							jedis.del(likesKey);	
						}
					}
				}
		
				Log.info("Finished deleting shorts");
		
				// Delete follows
				String deleteFollowsQuery = format(
					"SELECT * FROM f WHERE f.follower = '%s' OR f.followee = '%s'", userId, userId);
				List<Following> followsList = DB.sql(deleteFollowsQuery, Following.class);
		
				for (Following follow : followsList)
					DB.deleteOne(follow);
		
				Log.info("Finished deleting follows");
				
		
				// Delete likes
				String deleteLikesQuery = format("SELECT * FROM l WHERE l.userId = '%s'", userId);
				List<Likes> likesList = DB.sql(deleteLikesQuery, Likes.class);
		
				for (Likes like : likesList) 
					DB.deleteOne(like);
				
				Log.info("Finished deleting likes");
		
				return Result.ok();
			});
	}

	private Result<Void> deleteAllPostgreShorts(String userId) {
		return DB.transaction( (hibernate) -> {
						
			//delete shorts
			var query1 = format("DELETE FROM shorts WHERE ownerId = '%s'", userId);		
			hibernate.createQuery(query1, Short.class).executeUpdate();
			
			//delete follows
			var query2 = format("DELETE FROM following WHERE follower = '%s' OR followee = '%s'", userId, userId);		
			hibernate.createQuery(query2, Following.class).executeUpdate();
			
			//delete likes
			var query3 = format("DELETE FROM likes WHERE ownerId = '%s' OR userId = '%s'", userId, userId);		
			hibernate.createQuery(query3, Likes.class).executeUpdate();
			
		});
	}

	@Override
	public Result<Void> deleteAllShorts(String userId, String password, String token) {
		Log.info(() -> format("deleteAllShorts : userId = %s, password = %s, token = %s\n", userId, password, token));
	
		if (!Token.isValid(token, userId))
			return error(FORBIDDEN);
	
		if(DB.BASE.equals(DB.NOSQL))
			return deleteAllNoSqlShorts(userId);
		else
			return deleteAllPostgreShorts(userId);
			
	}
	
}