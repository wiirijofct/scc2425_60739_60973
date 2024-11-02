package utils;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.PartitionKey;

import tukano.api.Result;
import tukano.api.Result.ErrorCode;

public class CosmosDB {
    private static final String COSMOS_DB_ENDPOINT = "";
    private static final String COSMOS_DB_KEY = "";
    private static final String DATABASE_NAME = "";

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
        if(cosmosClient != null)
            System.out.println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$CosmosDB initialized$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$");
            
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
            CosmosContainer cosmosContainer = getContainerForClass(clazz);
            Iterable<T> query = cosmosContainer.queryItems(sqlStatement, new CosmosQueryRequestOptions(), clazz);
            return (List<T>) query;
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

    private CosmosContainer getContainerForClass(Class<?> clazz) {
        String containerName;
        
        switch (clazz.getSimpleName()) {
            case "User" -> containerName = "users";
            case "Short" -> containerName = "shorts";
            default -> throw new IllegalArgumentException("Unknown class: " + clazz.getName());
        }

        return cosmosDatabase.getContainer(containerName);
    }
    
}