package tukano.impl.storage;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;

import tukano.api.Result;
import utils.Props;

public class AzureStorage implements BlobStorage {

    private static Logger Log = Logger.getLogger(AzureStorage.class.getName());

    private static final String storageConnectionString = Props.get("BlobStoreConnection", "error?");

    BlobContainerClient containerClient = new BlobContainerClientBuilder()
		.connectionString(storageConnectionString)
		.containerName("shorts")
		.buildClient();

    @Override
    public Result<Void> write(String path, byte[] bytes) {
    
        try {
            BlobClient blobClient = containerClient.getBlobClient(path);
            BinaryData data = BinaryData.fromBytes(bytes);
            blobClient.upload(data);
            return Result.ok();
        } catch (BlobStorageException e) {
            Log.log(Level.SEVERE, "Error writing to Azure Storage: {0}", e.getMessage());
            return Result.error(Result.ErrorCode.INTERNAL_ERROR);
        } catch (Exception e) {
            Log.log(Level.SEVERE, "Error writing to Azure Storage: {0}", e.getMessage());
            return Result.error(Result.ErrorCode.INTERNAL_ERROR);
        }
      
    }

    @Override
    public Result<Void> delete(String path) {
        try {
            BlobClient blobClient = containerClient.getBlobClient(path);
            blobClient.delete();
            return Result.ok();
        } catch (BlobStorageException e) {
            Log.log(Level.SEVERE, "Error deleting from Azure Storage: {0}", e.getMessage());
            return Result.error(Result.ErrorCode.INTERNAL_ERROR);
        } catch (Exception e) {
            Log.log(Level.SEVERE, "Error deleting from Azure Storage: {0}", e.getMessage());
            return Result.error(Result.ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public Result<Void> deleteAllBlobsWithPrefix(String prefix) {
        try {
            for (BlobItem blobItem : containerClient.listBlobs(new ListBlobsOptions().setPrefix(prefix), null)) {
                BlobClient blobClient = containerClient.getBlobClient(blobItem.getName());
                blobClient.delete();
                Log.info(() -> String.format("Deleted blob: %s", blobItem.getName()));
            }
            return Result.ok();
        } catch (BlobStorageException e) {
            Log.log(Level.SEVERE, "Error deleting blobs from Azure Storage: {0}", e.getMessage());
            return Result.error(Result.ErrorCode.INTERNAL_ERROR);
        } catch (Exception e) {
            Log.log(Level.SEVERE, "Error deleting blobs from Azure Storage: {0}", e.getMessage());
            return Result.error(Result.ErrorCode.INTERNAL_ERROR);
        }
    }


    @Override
    public Result<byte[]> read(String path) {

      try {
          BlobClient blobClient = containerClient.getBlobClient(path);
          BinaryData data = blobClient.downloadContent();
          triggerRead(path);
          return Result.ok(data.toBytes());
      } catch (BlobStorageException e) {
          Log.log(Level.SEVERE, "Error reading from Azure Storage: {0}", e.getMessage());
          return Result.error(Result.ErrorCode.INTERNAL_ERROR);
      } catch (Exception e) {
          Log.log(Level.SEVERE, "Error reading from Azure Storage: {0}", e.getMessage());
          return Result.error(Result.ErrorCode.INTERNAL_ERROR);
      }

    }

    @Override
    public Result<Void> read(String path, Consumer<byte[]> sink) {

        try {
            BlobClient blobClient = containerClient.getBlobClient(path);
            BinaryData data = blobClient.downloadContent();
            sink.accept(data.toBytes());
            return Result.ok();
        } catch (BlobStorageException e) {
            Log.log(Level.SEVERE, "Error reading from Azure Storage: {0}", e.getMessage());
            return Result.error(Result.ErrorCode.INTERNAL_ERROR);
        } catch (Exception e) {
            Log.log(Level.SEVERE, "Error reading from Azure Storage: {0}", e.getMessage());
            return Result.error(Result.ErrorCode.INTERNAL_ERROR);
        }

    }

    private void triggerRead(String blobname) {
        String FUNCTION_URL = Props.get("FUNCTION_URL", "");
        String url = FUNCTION_URL + "&blobname=" + blobname;
    
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                URL urlObj = new URL(url);
                HttpURLConnection con = (HttpURLConnection) urlObj.openConnection();
                con.setRequestMethod("GET");
                con.connect();
                int responseCode = con.getResponseCode();
                System.out.println("response code: " + responseCode);
                String responseMessage = con.getResponseMessage();
                if (responseCode != 200) {
                    System.out.println("Error triggering read: " + responseMessage);
                } else {
                    System.out.println("Successfully triggered read");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        executor.shutdown();
    }
    
}
