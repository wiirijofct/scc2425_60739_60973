package tukano.serverless;

import java.util.Optional;

import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.models.PartitionKey;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import tukano.api.Result;
import tukano.api.Short;
import tukano.api.rest.RestBlobs;
import tukano.impl.JavaBlobs;

public class DownloadFunction {
    private static final String BLOB_ID = "blobId";
    private static final String HTTP_TRIGGER_NAME = "req";
    private static final String HTTP_FUNCTION_NAME = "DownloadBlobFunction";
    private static final String HTTP_TRIGGER_ROUTE = RestBlobs.PATH + "/{" + BLOB_ID + "}";
    private static final String COSMOS_DB_NAME = "COSMOSDB_DATABASE";
    private static final String COSMOS_CONTAINER_NAME = "shorts";
    private static final String COSMOS_ENDPOINT = "AzureCosmosDBConnection";
    private static final String COSMOS_KEY = "COSMOSDB_KEY";

    @FunctionName(HTTP_FUNCTION_NAME)
    public HttpResponseMessage run(
            @HttpTrigger(
                    name = HTTP_TRIGGER_NAME,
                    methods = {HttpMethod.GET},
                    authLevel = AuthorizationLevel.FUNCTION,
                    route = HTTP_TRIGGER_ROUTE)
            HttpRequestMessage<Optional<String>> request,
            @BindingName(BLOB_ID) String blobId,
            final ExecutionContext context) {
        context.getLogger().info("Download blob request received for blobId: " + blobId);

        try {
            // Retrieve the token from query parameters
            String token = request.getQueryParameters().get(RestBlobs.TOKEN);

            if (token == null || token.isEmpty()) {
                return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                        .body("Token is missing.")
                        .build();
            }

            // Call the download method from JavaBlobs
            JavaBlobs blobsInstance = (JavaBlobs) JavaBlobs.getInstance();
            Result<byte[]> result = blobsInstance.download(blobId, token);

            if (!result.isOK()) {
                return request.createResponseBuilder(HttpStatus.FORBIDDEN)
                        .body("Blob not found or access forbidden.")
                        .build();
            }

            byte[] blobData = result.value();

            // Increment view count for the blob (this can be implemented separately)
            incrementViewCount(blobId);

            return request.createResponseBuilder(HttpStatus.OK)
                    .header("Content-Type", "application/octet-stream")
                    .body(blobData)
                    .build();

        } catch (Exception e) {
            context.getLogger().severe("Error occurred during download: " + e.getMessage());
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error processing request: " + e.getMessage())
                    .build();
        }
    }

    // Incrementing view count in CosmosDB
    private void incrementViewCount(String blobId) {
        try {
            // Initialize Cosmos client
            var cosmosClient = new CosmosClientBuilder()
                    .endpoint(COSMOS_ENDPOINT)
                    .key(COSMOS_KEY)
                    .buildClient();

            // Get the database and container
            CosmosDatabase database = cosmosClient.getDatabase(COSMOS_DB_NAME);
            CosmosContainer container = database.getContainer(COSMOS_CONTAINER_NAME);

            // Retrieve the document (Short object) with the given blobId
            var response = container.readItem(blobId, new PartitionKey(blobId), Short.class);
            Short shortItem = response.getItem();

            // Increment the views count
            shortItem.setTotalViews(shortItem.getTotalViews() + 1);

            // Update the document in the container
            container.upsertItem(shortItem);

            // Close the client
            cosmosClient.close();
        } catch (Exception e) {
            System.err.println("Error incrementing view count: " + e.getMessage());
        }
    }
}
