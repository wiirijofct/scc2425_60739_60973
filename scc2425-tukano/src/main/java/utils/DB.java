package utils;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import org.hibernate.Session;

import com.azure.cosmos.CosmosDatabase;

import tukano.api.Result;

public class DB {

	public static final String BASE = Props.get("database", "error");
	public static final String NOSQL = "nosql";

	public static <T> List<T> sql(String query, Class<T> clazz) {
		if(BASE == NOSQL)
			return CosmosDB.getInstance().sql(query, clazz);
		else
			return Hibernate.getInstance().sql(query, clazz);
	}
	
	public static <T> List<T> sql(Class<T> clazz, String fmt, Object ... args) {
		if(BASE == NOSQL)
			return CosmosDB.getInstance().sql(String.format(fmt, args), clazz);
		else
			return Hibernate.getInstance().sql(String.format(fmt, args), clazz);
	}
	
	public static <T> Result<T> getOne(String id, Class<T> clazz) {
		if(BASE == NOSQL)
			return CosmosDB.getInstance().getOne(id, clazz);
		else
			return Hibernate.getInstance().getOne(id, clazz);
	}
	
	public static <T> Result<T> deleteOne(T obj) {
		if(BASE == NOSQL)
			return CosmosDB.getInstance().deleteOne(obj);
		else
			return Hibernate.getInstance().deleteOne(obj);
	}
	
	public static <T> Result<T> updateOne(T obj) {
		if(BASE == NOSQL)
			return CosmosDB.getInstance().updateOne(obj);
		else
			return Hibernate.getInstance().updateOne(obj);
	}
	
	public static <T> Result<T> insertOne( T obj) {
		if(BASE == NOSQL)
			return Result.errorOrValue(CosmosDB.getInstance().persistOne(obj), obj);
		else
			return Result.errorOrValue(Hibernate.getInstance().persistOne(obj), obj);
	}

	public static <T> Result<T> transaction( Consumer<Object> c) {
		if(BASE == NOSQL)
			return CosmosDB.getInstance().execute( c::accept );
		else
			return Hibernate.getInstance().execute( c::accept );
	}

	@SuppressWarnings("unchecked")
	public static <T> Result<T> transaction( Function<Object, Result<T>> func) {
		if(BASE == NOSQL)
			return CosmosDB.getInstance().execute( (Consumer<CosmosDatabase>) func );
		else
			return Hibernate.getInstance().execute( (Consumer<Session>) func );
	}
	
	// public static <T> Result<T> transaction( Consumer<Session> c) {
	// 	return Hibernate.getInstance().execute( c::accept );
	// }
	
	// public static <T> Result<T> transaction( Function<Session, Result<T>> func) {
	// 	return Hibernate.getInstance().execute( func );
	// }
}
