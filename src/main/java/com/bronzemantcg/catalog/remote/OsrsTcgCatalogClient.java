package com.bronzemantcg.catalog.remote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Downloads the public OSRS TCG catalogue without retaining its raw response in OkHttp's cache. */
@Singleton
public class OsrsTcgCatalogClient
{
	static final String LIVE_CATALOG_URL = "https://api.osrs-tcg.net/api/v1/catalog/cards/live";
	static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

	private final OkHttpClient httpClient;
	private final String endpoint;
	private final int maximumResponseBytes;

	@Inject
	public OsrsTcgCatalogClient(OkHttpClient httpClient)
	{
		this(httpClient, LIVE_CATALOG_URL, MAX_RESPONSE_BYTES);
	}

	OsrsTcgCatalogClient(OkHttpClient httpClient, String endpoint, int maximumResponseBytes)
	{
		if (httpClient == null)
		{
			throw new IllegalArgumentException("httpClient is required");
		}
		if (endpoint == null || endpoint.trim().isEmpty())
		{
			throw new IllegalArgumentException("endpoint is required");
		}
		if (maximumResponseBytes <= 0)
		{
			throw new IllegalArgumentException("maximumResponseBytes must be positive");
		}
		this.httpClient = httpClient;
		this.endpoint = endpoint;
		this.maximumResponseBytes = maximumResponseBytes;
	}

	public FetchHandle fetch(Listener listener)
	{
		return fetch(null, listener);
	}

	public FetchHandle fetch(String currentEtag, Listener listener)
	{
		if (listener == null)
		{
			throw new IllegalArgumentException("listener is required");
		}
		FetchHandle handle = new FetchHandle();
		enqueue(handle, currentEtag, listener);
		return handle;
	}

	private void enqueue(FetchHandle handle, String currentEtag, Listener listener)
	{
		if (handle.isCancelled())
		{
			return;
		}
		CacheControl cacheControl = new CacheControl.Builder().noCache().noStore().build();
		Request.Builder builder = new Request.Builder().url(endpoint).get()
			.cacheControl(cacheControl);
		if (currentEtag != null && !currentEtag.trim().isEmpty())
		{
			builder.header("If-None-Match", currentEtag.trim());
		}
		Call call = httpClient.newCall(builder.build());
		handle.setActiveCall(call);
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(@Nonnull Call failedCall, @Nonnull IOException exception)
			{
				if (handle.isCancelled())
				{
					return;
				}
				listener.onFailure(failureMessage(exception.getMessage()), exception);
			}

			@Override
			public void onResponse(@Nonnull Call completedCall, @Nonnull Response response)
			{
				try (Response closeableResponse = response)
				{
					if (handle.isCancelled())
					{
						return;
					}
					if (response.code() == 304)
					{
						if (currentEtag == null || currentEtag.trim().isEmpty())
						{
							listener.onFailure(
								"HTTP 304 without a cached catalogue validator", null);
							return;
						}
						listener.onSuccess(CatalogResponse.notModified(
							catalogVersion(response), response.header("ETag")));
						return;
					}
					if (!response.isSuccessful())
					{
						listener.onFailure("HTTP " + response.code(), null);
						return;
					}

					ResponseBody body = response.body();
					if (body == null)
					{
						listener.onFailure("catalogue response has no body", null);
						return;
					}
					byte[] bytes;
					try
					{
						bytes = readBounded(body);
					}
					catch (IOException exception)
					{
						listener.onFailure(failureMessage(exception.getMessage()), exception);
						return;
					}
					if (!handle.isCancelled())
					{
						listener.onSuccess(new CatalogResponse(bytes,
							catalogVersion(response), response.header("ETag")));
					}
				}
			}
		});
	}

	private byte[] readBounded(ResponseBody body) throws IOException
	{
		long contentLength = body.contentLength();
		if (contentLength > maximumResponseBytes)
		{
			throw new IOException("catalogue response exceeds " + maximumResponseBytes + " bytes");
		}
		try (InputStream input = body.byteStream();
			ByteArrayOutputStream output = new ByteArrayOutputStream(
				contentLength > 0 ? (int) contentLength : 8192))
		{
			byte[] buffer = new byte[8192];
			int total = 0;
			int read;
			while ((read = input.read(buffer)) != -1)
			{
				total += read;
				if (total > maximumResponseBytes)
				{
					throw new IOException("catalogue response exceeds "
						+ maximumResponseBytes + " bytes");
				}
				output.write(buffer, 0, read);
			}
			return output.toByteArray();
		}
	}

	private static String catalogVersion(Response response)
	{
		String version = response.header("X-Catalog-Version");
		if (version == null || version.trim().isEmpty())
		{
			version = response.header("ETag");
		}
		return version == null ? "unknown" : version.trim();
	}

	private static String failureMessage(String currentFailure)
	{
		return currentFailure == null || currentFailure.trim().isEmpty()
			? "catalogue request failed" : currentFailure.trim();
	}

	public interface Listener
	{
		void onSuccess(CatalogResponse response);

		void onFailure(String reason, Throwable cause);
	}

	public static final class FetchHandle
	{
		private final AtomicBoolean cancelled = new AtomicBoolean();
		private final AtomicReference<Call> activeCall = new AtomicReference<>();

		public void cancel()
		{
			cancelled.set(true);
			Call call = activeCall.getAndSet(null);
			if (call != null)
			{
				call.cancel();
			}
		}

		public boolean isCancelled()
		{
			return cancelled.get();
		}

		private void setActiveCall(Call call)
		{
			Call previous = activeCall.getAndSet(call);
			if (previous != null && previous != call)
			{
				previous.cancel();
			}
			if (cancelled.get() && activeCall.compareAndSet(call, null))
			{
				call.cancel();
			}
		}
	}

	public static final class CatalogResponse
	{
		private final byte[] body;
		private final String version;
		private final String etag;
		private final boolean notModified;

		CatalogResponse(byte[] body, String version)
		{
			this(body, version, null, false);
		}

		CatalogResponse(byte[] body, String version, String etag)
		{
			this(body, version, etag, false);
		}

		private CatalogResponse(byte[] body, String version, String etag,
			boolean notModified)
		{
			this.body = body.clone();
			this.version = version;
			this.etag = etag;
			this.notModified = notModified;
		}

		static CatalogResponse notModified(String version, String etag)
		{
			return new CatalogResponse(new byte[0], version, etag, true);
		}

		public byte[] getBody()
		{
			return body.clone();
		}

		public String getVersion()
		{
			return version;
		}

		public String getEtag()
		{
			return etag;
		}

		public boolean isNotModified()
		{
			return notModified;
		}
	}
}
