package tukano.impl;

import static java.lang.String.format;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import redis.clients.jedis.Jedis;
import tukano.api.Result;
import static tukano.api.Result.ErrorCode.BAD_REQUEST;
import static tukano.api.Result.ErrorCode.FORBIDDEN;
import static tukano.api.Result.error;
import static tukano.api.Result.errorOrResult;
import static tukano.api.Result.errorOrValue;
import static tukano.api.Result.ok;
import tukano.api.AppUser;
import tukano.api.Users;
import utils.DB;
import utils.JSON;
import utils.RedisCache;


public class JavaUsers implements Users {
	
	private static Logger Log = Logger.getLogger(JavaUsers.class.getName());

	private static Users instance;

	private static final int USER_CACHE_TTL = 10; // 3 seconds
	private static final String USERS_PREFIX = "users:";
	
	synchronized public static Users getInstance() {
		if( instance == null )
			instance = new JavaUsers();
		return instance;
	}
	
	private JavaUsers() {}
	
	@Override
	public Result<String> createUser(AppUser user) {
		Log.info(() -> format("createUser : %s\n", user));

		System.out.println("#######################################################################");
		System.out.println("db: " + DB.BASE);

		if( badUserInfo( user ) )
				return error(BAD_REQUEST);

		return errorOrValue( DB.insertOne( user), usr -> {
			try (Jedis jedis = RedisCache.getCachePool().getResource()) {
				var key = USERS_PREFIX + user.getUserId();
				var value = JSON.encode(user);
				jedis.set(key, value);
				jedis.expire(key, USER_CACHE_TTL);
			}

			return user.getUserId();} );
	}

	@Override
	public Result<AppUser> getUser(String userId, String pwd) {
		Log.info( () -> format("getUser : userId = %s, pwd = %s\n", userId, pwd));

		if (userId == null)
			return error(BAD_REQUEST);

		try (Jedis jedis = RedisCache.getCachePool().getResource()) {
			var key = USERS_PREFIX + userId;
			var val =jedis.get(key);
			if (val != null) {
				var user = JSON.decode(val, AppUser.class);
				return validatedUserOrError( ok(user), pwd);
			}
		}
		return validatedUserOrError( DB.getOne( userId, AppUser.class), pwd);
	}

	@Override
	public Result<AppUser> updateUser(String userId, String pwd, AppUser other) {
		Log.info(() -> format("updateUser : userId = %s, pwd = %s, user: %s\n", userId, pwd, other));

		if (badUpdateUserInfo(userId, pwd, other))
			return error(BAD_REQUEST);
			
		try (Jedis jedis = RedisCache.getCachePool().getResource()) {
			var key = USERS_PREFIX + userId;
			var val =jedis.get(key);
			if (val != null) {
				var user = JSON.decode(val, AppUser.class);
				var userIs = validatedUserOrError( ok(user), pwd);
				if (userIs.isOK()) {
					jedis.set(key, JSON.encode(other));
				}
				return errorOrResult( userIs, usr -> DB.updateOne( user.updateFrom(other)));
			}
		}
		return errorOrResult( validatedUserOrError(DB.getOne( userId, AppUser.class), pwd), user -> DB.updateOne( user.updateFrom(other)));
	}

	@Override
	public Result<AppUser> deleteUser(String userId, String pwd) {
		Log.info(() -> format("deleteUser : userId = %s, pwd = %s\n", userId, pwd));

		if (userId == null || pwd == null )
			return error(BAD_REQUEST);

		Result<AppUser> userIsOk = null;
		try (Jedis jedis = RedisCache.getCachePool().getResource()) {
			var key = USERS_PREFIX + userId;
			var val =jedis.get(key);
			if (val != null) {
				var user = JSON.decode(val, AppUser.class);
				userIsOk = validatedUserOrError( ok(user), pwd);
				if (userIsOk.isOK()) {
					jedis.del(key);
				}
			}
		}
		if (userIsOk == null)
			userIsOk = validatedUserOrError(DB.getOne( userId, AppUser.class), pwd);

		return errorOrResult( userIsOk, user -> {

			// Delete user shorts and related info asynchronously in a separate thread
			Executors.defaultThreadFactory().newThread( () -> {
				JavaShorts.getInstance().deleteAllShorts(userId, pwd, Token.get(userId));
				JavaBlobs.getInstance().deleteAllBlobs(userId, Token.get(userId));
			}).start();
			
			return DB.deleteOne( user);
		});
	}

	@Override
	public Result<List<AppUser>> searchUsers(String pattern) {
		Log.info( () -> format("searchUsers : patterns = %s\n", pattern));

		// var query = format("SELECT * FROM User u WHERE UPPER(u.userId) LIKE '%%%s%%'", pattern.toUpperCase());
		if(DB.BASE == DB.NOSQL)
			return searchNoSqlUsers(pattern);
		else
			return searchPostrgeUsers(pattern);
	}
	
	private Result<List<AppUser>> searchPostrgeUsers(String pattern) {
		if (pattern == null || pattern.trim().isEmpty()) {
			// if no pattern is provided return all users
			String query = "SELECT * FROM app_user"; // get all users
			List<AppUser> hits = DB.sql(query, AppUser.class)
					.stream()
					.map(AppUser::copyWithoutPassword)
					.toList();
	
			return ok(hits);
		}
	
		String query = format("SELECT * FROM app_user WHERE UPPER(userId) LIKE '%%%s%%'", pattern.toUpperCase());
		Log.info(query);
		List<AppUser> hits = DB.sql(query, AppUser.class)
				.stream()
				.map(AppUser::copyWithoutPassword)
				.toList();
	
		return ok(hits);
	}

	private Result<List<AppUser>> searchNoSqlUsers(String pattern) {

	}

	
	private Result<AppUser> validatedUserOrError( Result<AppUser> res, String pwd ) {
		if( res.isOK())
			return res.value().getPwd().equals( pwd ) ? res : error(FORBIDDEN);
		else
			return res;
	}
	
	private boolean badUserInfo( AppUser user) {
		return (user.userId() == null || user.pwd() == null || user.displayName() == null || user.email() == null);
	}
	
	private boolean badUpdateUserInfo( String userId, String pwd, AppUser info) {
		return (userId == null || pwd == null || info.getUserId() != null && ! userId.equals( info.getUserId()));
	}
}
