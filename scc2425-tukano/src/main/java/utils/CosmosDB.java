package utils;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.util.CosmosPagedIterable;

import tukano.api.Result;
import tukano.api.Result.ErrorCode;

public class CosmosDB {
    private static final String COSMOS_DB_ENDPOINT = Props.get("COSMOSDB_URL", "error?");
    private static final String COSMOS_DB_KEY = Props.get("COSMOSDB_KEY", "error?");
    private static final String DATABASE_NAME = Props.get("COSMOSDB_DATABASE", "error?");

    private CosmosClient cosmosClient;
    private CosmosDatabase cosmosDatabase;
    private static CosmosDB instance;

    private CosmosDB() {
          try {
            cosmosClient = new CosmosClientBuilder()
                    .endpoint(COSMOS_DB_ENDPOINT)
                    .key(COSMOS_DB_KEY)
                    //.directMode()
                    .gatewayMode()
                    .consistencyLevel(ConsistencyLevel.SESSION)
                    .connectionSharingAcrossClientsEnabled(true)
                    .contentResponseOnWriteEnabled(true)
                    .buildClient();
                    
            cosmosDatabase = cosmosClient.getDatabase(DATABASE_NAME);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    synchronized public static CosmosDB getInstance() {
        if (instance == null)
            instance = new CosmosDB();
        return instance;
    }

    public Result<Void> persistOne(Object obj) {
        try {
            CosmosContainer cosmosContainer = getContainerForClass(obj.getClass());
            cosmosContainer.createItem(obj);
            return Result.ok();
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }
    }
    
    public <T> Result<T> updateOne(T obj) {
        try {
            CosmosContainer cosmosContainer = getContainerForClass(obj.getClass());
            CosmosItemResponse<T> response = cosmosContainer.upsertItem(obj);
            return Result.ok(response.getItem());
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.NOT_FOUND);
        }
    }
    
    public <T> Result<T> deleteOne(T obj) {
        try {
            CosmosContainer cosmosContainer = getContainerForClass(obj.getClass());
            cosmosContainer.deleteItem(obj, new CosmosItemRequestOptions());
            return Result.ok();
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.NOT_FOUND);
        }
    }
    
    public <T> Result<T> getOne(String id, Class<T> clazz) {
        try {
            CosmosContainer cosmosContainer = getContainerForClass(clazz);
            CosmosItemResponse<T> response = cosmosContainer.readItem(id, new PartitionKey(id), new CosmosItemRequestOptions(), clazz);
            return Result.ok(response.getItem());
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.NOT_FOUND);
        }
    }
    
    public <T> List<T> sql(String sqlStatement, Class<T> clazz) {
        try {
            CosmosContainer cosmosContainer = getContainerForQuery(sqlStatement);
            CosmosPagedIterable<T> query = cosmosContainer.queryItems(sqlStatement, new CosmosQueryRequestOptions(), clazz);

            List<T> results = query.stream().toList();
            return results;
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    public <T> Result<T> execute(Consumer<CosmosDatabase> proc) {
        try {
            proc.accept(cosmosDatabase);
            return Result.ok();
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }
    }

    public <T> Result<T> execute(Function<CosmosDatabase, Result<T>> func) {
        try {
            return func.apply(cosmosDatabase);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }
    }

    private CosmosContainer getContainer(String name) {
        String containerName;
        switch (name.toLowerCase()) {
            case "user" -> containerName = "users";
            case "u" -> containerName = "users";
            case "short" -> containerName = "shorts";
            case "s" -> containerName = "shorts";
            case "following" -> containerName = "following";
            case "f" -> containerName = "following";
            case "likes" -> containerName = "likes";
            case "l" -> containerName = "likes";
            case "integer" -> containerName = "shorts";
            case "string" -> containerName = "shorts";
            default -> throw new IllegalArgumentException("Unknown class: " + name);
        }
        System.out.println("Container id : " + cosmosDatabase.getContainer(containerName).getId());
        return cosmosDatabase.getContainer(containerName);
    }

    private CosmosContainer getContainerForClass(Class<?> clazz) {
        return getContainer(clazz.getSimpleName());
    }

    private CosmosContainer getContainerForQuery(String query) {
        String result = "";
        String regex = "\\bFROM\\s+(\\w+)"; // Match "FROM" followed by a space and capture the next word
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(query);
        
        if (matcher.find()) {
            result = matcher.group(1); // Get the captured group after "FROM"
        }
        return getContainer(result);
    }
    
}