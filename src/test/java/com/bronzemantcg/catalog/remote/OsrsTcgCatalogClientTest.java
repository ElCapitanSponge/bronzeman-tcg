package com.bronzemantcg.catalog.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class OsrsTcgCatalogClientTest
{
	private static final String ENDPOINT = "https://example.test/catalog";

	@Test
	public void usesDedicatedApiSubdomain()
	{
		assertEquals("https://api.osrs-tcg.net/api/v1/catalog/cards/live",
			OsrsTcgCatalogClient.LIVE_CATALOG_URL);
	}

	@Test
	public void downloadsBodyAndCapturesVersionAsynchronously() throws Exception
	{
		String json = "{\"items\":[],\"npcs\":[]}";
		OkHttpClient httpClient = clientReturning(200, json, "version-7");
		OsrsTcgCatalogClient client = new OsrsTcgCatalogClient(httpClient, ENDPOINT, 1024);

		OsrsTcgCatalogClient.CatalogResponse response = fetch(client);

		assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), response.getBody());
		assertEquals("version-7", response.getVersion());
		assertEquals("\"version-7\"", response.getEtag());
	}

	@Test
	public void bypassesSharedHttpCacheAndUsesEtagValidation() throws Exception
	{
		AtomicInteger requests = new AtomicInteger();
		OkHttpClient httpClient = new OkHttpClient.Builder()
			.addInterceptor(chain ->
			{
				requests.incrementAndGet();
				assertTrue(chain.request().cacheControl().noCache());
				assertTrue(chain.request().cacheControl().noStore());
				assertEquals("\"etag-7\"", chain.request().header("If-None-Match"));
				return response(chain.request(), 304, "", null);
			})
			.build();
		OsrsTcgCatalogClient client = new OsrsTcgCatalogClient(httpClient, ENDPOINT, 1024);

		OsrsTcgCatalogClient.CatalogResponse response = fetch(client, "\"etag-7\"");

		assertEquals(1, requests.get());
		assertTrue(response.isNotModified());
		assertEquals(0, response.getBody().length);
	}

	@Test
	public void rejectsNotModifiedWithoutAValidator()
	{
		OsrsTcgCatalogClient client = new OsrsTcgCatalogClient(
			clientReturning(304, "", null), ENDPOINT, 1024);

		ExecutionException exception = assertThrows(ExecutionException.class,
			() -> fetch(client));

		assertTrue(exception.getCause().getMessage().contains(
			"304 without a cached catalogue validator"));
	}

	@Test
	public void reportsNetworkFailureWithoutASecondCacheRequest()
	{
		AtomicInteger requests = new AtomicInteger();
		OkHttpClient httpClient = new OkHttpClient.Builder()
			.addInterceptor(chain ->
			{
				requests.incrementAndGet();
				throw new IOException("offline");
			})
			.build();
		OsrsTcgCatalogClient client = new OsrsTcgCatalogClient(httpClient, ENDPOINT, 1024);

		ExecutionException exception = assertThrows(ExecutionException.class,
			() -> fetch(client));
		assertTrue(exception.getCause().getMessage().contains("offline"));
		assertEquals(1, requests.get());
	}

	@Test
	public void rejectsResponsesAboveTheConfiguredLimit()
	{
		OkHttpClient httpClient = clientReturning(200, "12345", null);
		OsrsTcgCatalogClient client = new OsrsTcgCatalogClient(httpClient, ENDPOINT, 4);

		ExecutionException exception = assertThrows(ExecutionException.class,
			() -> fetch(client));
		assertTrue(exception.getCause().getMessage().contains("exceeds 4 bytes"));
	}

	@Test
	public void cancellationSuppressesLateCallbacks() throws Exception
	{
		CompletableFuture<Void> entered = new CompletableFuture<>();
		CompletableFuture<Void> release = new CompletableFuture<>();
		OkHttpClient httpClient = new OkHttpClient.Builder()
			.addInterceptor(chain ->
			{
				entered.complete(null);
				try
				{
					release.get(2, TimeUnit.SECONDS);
				}
				catch (InterruptedException exception)
				{
					Thread.currentThread().interrupt();
					throw new IOException("interrupted", exception);
				}
				catch (ExecutionException | TimeoutException exception)
				{
					throw new IOException("test gate failed", exception);
				}
				return response(chain.request(), 200, "late", null);
			})
			.build();
		OsrsTcgCatalogClient client = new OsrsTcgCatalogClient(httpClient, ENDPOINT, 1024);
		CompletableFuture<OsrsTcgCatalogClient.CatalogResponse> result = new CompletableFuture<>();
		OsrsTcgCatalogClient.FetchHandle handle = client.fetch(listener(result));
		entered.get(2, TimeUnit.SECONDS);

		handle.cancel();
		release.complete(null);

		assertTrue(handle.isCancelled());
		assertThrows(TimeoutException.class, () -> result.get(250, TimeUnit.MILLISECONDS));
	}

	private static OsrsTcgCatalogClient.CatalogResponse fetch(OsrsTcgCatalogClient client)
		throws InterruptedException, ExecutionException, TimeoutException
	{
		return fetch(client, null);
	}

	private static OsrsTcgCatalogClient.CatalogResponse fetch(OsrsTcgCatalogClient client,
		String currentEtag)
		throws InterruptedException, ExecutionException, TimeoutException
	{
		CompletableFuture<OsrsTcgCatalogClient.CatalogResponse> future = new CompletableFuture<>();
		client.fetch(currentEtag, listener(future));
		return future.get(2, TimeUnit.SECONDS);
	}

	private static OsrsTcgCatalogClient.Listener listener(
		CompletableFuture<OsrsTcgCatalogClient.CatalogResponse> future)
	{
		return new OsrsTcgCatalogClient.Listener()
		{
			@Override
			public void onSuccess(OsrsTcgCatalogClient.CatalogResponse response)
			{
				future.complete(response);
			}

			@Override
			public void onFailure(String reason, Throwable cause)
			{
				future.completeExceptionally(new IOException(reason, cause));
			}
		};
	}

	private static OkHttpClient clientReturning(int code, String body, String version)
	{
		return new OkHttpClient.Builder()
			.addInterceptor(chain -> response(chain.request(), code, body, version))
			.build();
	}

	private static Response response(okhttp3.Request request, int code,
		String body, String version)
	{
		Response.Builder builder = new Response.Builder()
			.request(request)
			.protocol(Protocol.HTTP_1_1)
			.code(code)
			.message(code == 200 ? "OK" : "Unavailable")
			.body(ResponseBody.create(MediaType.parse("application/json"), body));
		if (version != null)
		{
			builder.header("X-Catalog-Version", version);
			builder.header("ETag", "\"" + version + "\"");
		}
		return builder.build();
	}
}
