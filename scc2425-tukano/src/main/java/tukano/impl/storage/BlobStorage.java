package tukano.impl.storage;

import java.util.function.Consumer;

import tukano.api.Result;

public interface BlobStorage {
		
	public Result<Void> write(String path, byte[] bytes );
		
	public Result<Void> delete(String path);
	
	public Result<byte[]> read(String path);

	public Result<Void> read(String path, Consumer<byte[]> sink);

    public Result<Void> deleteAllBlobsWithPrefix(String userId);

}
