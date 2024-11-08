package scc.serverless;

import java.util.Map;

import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.implementation.guava25.base.Optional;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.PartitionKey;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import scc.data.Short;

public class ViewCountFunction {
	private static final String FUNCTION_NAME = "ViewCountFunction";
	// private static final String BLOBSTORE_CONNECTION_ENV = "BlobStoreConnection";
	// private static final String COSMOSDB_CONNECTION_ENV = "CosmosDBConnection";
	private static final String COSMOSDB_DATABASE_ENV = "COSMOSDB_DATABASE";
	private static final String COSMOSDB_CONTAINER = "shorts";
	private static final String COSMOSDB_KEY_ENV = "COSMOSDB_KEY";
	private static final String COSMOSDB_URL_ENV = "COSMOSDB_URL";

	private static final CosmosAsyncClient cosmosClient = new CosmosClientBuilder()
        .endpoint(System.getenv("COSMOSDB_URL"))
        .key(System.getenv("COSMOSDB_KEY"))
        .gatewayMode()
        .consistencyLevel(ConsistencyLevel.SESSION)
        .connectionSharingAcrossClientsEnabled(true)
        .contentResponseOnWriteEnabled(true)
        .buildAsyncClient();

private static final CosmosAsyncContainer container = cosmosClient
        .getDatabase(System.getenv("COSMOSDB_DATABASE"))
        .getContainer(COSMOSDB_CONTAINER);

	
	@FunctionName(FUNCTION_NAME)
	public void IncrementViews(
			@HttpTrigger(name = "req", 
			methods = { HttpMethod.GET },
			authLevel = AuthorizationLevel.FUNCTION) HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {
		context.getLogger().info("Java HTTP trigger processed a request.");

		Map<String, String> params = request.getQueryParameters();
		String blobname = params.getOrDefault("blobname", "");
		context.getLogger().info("blobname: " + blobname);
		if (blobname == null) {
			context.getLogger().warning("blobname parameter is missing");
			return;
		}
		String ItemId = blobname.replace('/', '+');
		context.getLogger().info("ItemId: " + ItemId);
		if (ItemId == null) {
			context.getLogger().warning("ItemId parameter is missing");
			return;
		}

		try {
			CosmosItemResponse<Short> response = container.readItem(ItemId, new PartitionKey(ItemId), Short.class).block();
			if (response.getStatusCode() == 200) {
				Short shortItem = (Short) response.getItem();
				shortItem.incrementTotalViews();
				
				CosmosItemResponse<Short> updateResponse = container.replaceItem(shortItem, ItemId, new PartitionKey(ItemId), new CosmosItemRequestOptions()).block();
				context.getLogger().info("Update response: " + updateResponse.getStatusCode());
			}
			else {
				context.getLogger().warning("Item not found: " + ItemId);
			}
			
		} catch (CosmosException e) {
			context.getLogger().severe("Failed to update item: " + e.getMessage());
		}
	}
}
