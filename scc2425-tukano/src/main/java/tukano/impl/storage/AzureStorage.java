package tukano.impl.storage;

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

    // this is hardcoded for now, should be in a config file or env variable
    private static final String storageConnectionString = Props.get("BlobStoreConnection", "error?");

    BlobContainerClient containerClient = new BlobContainerClientBuilder()
		.connectionString(storageConnectionString)
		.containerName("blobs")
		.buildClient();

    @Override
    public Result<Void> write(String path, byte[] bytes) {
    
        // im assuming the path is the blobId here
        // idk if the path should be the container uri + blobId
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
    
}
