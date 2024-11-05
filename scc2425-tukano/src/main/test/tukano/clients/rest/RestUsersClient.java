package tukano.clients.rest;

import java.util.List;

import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import tukano.api.Result;
import tukano.api.AppUser;
import tukano.api.Users;
import tukano.api.rest.RestUsers;


public class RestUsersClient extends RestClient implements Users {

	public RestUsersClient( String serverURI ) {
		super( serverURI, RestUsers.PATH );
	}
		
	private Result<String> _createUser(AppUser user) {
		return super.toJavaResult( 
			target.request()
			.accept(MediaType.APPLICATION_JSON)
			.post(Entity.entity(user, MediaType.APPLICATION_JSON)), String.class );
	}

	private Result<AppUser> _getUser(String userId, String pwd) {
		return super.toJavaResult(
				target.path( userId )
				.queryParam(RestUsers.PWD, pwd).request()
				.accept(MediaType.APPLICATION_JSON)
				.get(), AppUser.class);
	}
	
	public Result<AppUser> _updateUser(String userId, String password, AppUser user) {
		return super.toJavaResult(
				target
				.path( userId )
				.queryParam(RestUsers.PWD, password)
				.request()
				.accept(MediaType.APPLICATION_JSON)
				.put(Entity.entity(user, MediaType.APPLICATION_JSON)), AppUser.class);
	}

	public Result<AppUser> _deleteUser(String userId, String password) {
		return super.toJavaResult(
				target
				.path( userId )
				.queryParam(RestUsers.PWD, password)
				.request()
				.accept(MediaType.APPLICATION_JSON)
				.delete(), AppUser.class);
	}

	public Result<List<AppUser>> _searchUsers(String pattern) {
		return super.toJavaResult(
				target
				.queryParam(RestUsers.QUERY, pattern)
				.request()
				.accept(MediaType.APPLICATION_JSON)
				.get(), new GenericType<List<AppUser>>() {});
	}

	@Override
	public Result<String> createUser(AppUser user) {
		return super.reTry( () -> _createUser(user));
	}

	@Override
	public Result<AppUser> getUser(String userId, String pwd) {
		return super.reTry( () -> _getUser(userId, pwd));
	}

	@Override
	public Result<AppUser> updateUser(String userId, String pwd, AppUser user) {
		return super.reTry( () -> _updateUser(userId, pwd, user));
	}

	@Override
	public Result<AppUser> deleteUser(String userId, String pwd) {
		return super.reTry( () -> _deleteUser(userId, pwd));
	}

	@Override
	public Result<List<AppUser>> searchUsers(String pattern) {
		return super.reTry( () -> _searchUsers(pattern));
	}
}
