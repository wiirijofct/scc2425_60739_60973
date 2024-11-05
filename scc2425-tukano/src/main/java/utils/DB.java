package utils;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import org.hibernate.Session;

import tukano.api.Result;

public class DB {

	public static <T> List<T> sql(String query, Class<T> clazz) {
		return Hibernate.getInstance().sql(query, clazz);
		// return CosmosDB.getInstance().sql(query, clazz);
	}
	
	public static <T> List<T> sql(Class<T> clazz, String fmt, Object ... args) {
		return Hibernate.getInstance().sql(String.format(fmt, args), clazz);
		// return CosmosDB.getInstance().sql(String.format(fmt, args), clazz);
	}
	
	public static <T> Result<T> getOne(String id, Class<T> clazz) {
		return Hibernate.getInstance().getOne(id, clazz);
		// return CosmosDB.getInstance().getOne(id, clazz);
	}
	
	public static <T> Result<T> deleteOne(T obj) {
		return Hibernate.getInstance().deleteOne(obj);
		// return CosmosDB.getInstance().deleteOne(obj);
	}
	
	public static <T> Result<T> updateOne(T obj) {
		return Hibernate.getInstance().updateOne(obj);
		// return CosmosDB.getInstance().updateOne(obj);
	}
	
	public static <T> Result<T> insertOne( T obj) {
		return Result.errorOrValue(Hibernate.getInstance().persistOne(obj), obj);
		// return Result.errorOrValue(CosmosDB.getInstance().persistOne(obj), obj);
	}

	// public static <T> Result<T> transaction( Consumer<CosmosDatabase> c) {
	// 	return CosmosDB.getInstance().execute( c::accept );
	// }

	// public static <T> Result<T> transaction( Function<CosmosDatabase, Result<T>> func) {
	// 	return CosmosDB.getInstance().execute( func );
	// }
	
	public static <T> Result<T> transaction( Consumer<Session> c) {
		return Hibernate.getInstance().execute( c::accept );
	}
	
	public static <T> Result<T> transaction( Function<Session, Result<T>> func) {
		return Hibernate.getInstance().execute( func );
	}
}
