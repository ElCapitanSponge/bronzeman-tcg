package com.bronzemantcg.catalog.remote;

import com.bronzemantcg.ownership.ActiveCardIdentityCatalog;
import com.bronzemantcg.ownership.BundledCardIdentityCatalog;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/** Fetches, validates and capability-gates the public OSRS TCG identity catalogue. */
@Slf4j
@Singleton
public final class RemoteCatalogService
{
	private final OsrsTcgCatalogClient client;
	private final OsrsTcgCatalogParser parser;
	private final RemoteCatalogValidator validator;
	private final BundledCardIdentityCatalog bundledCatalog;
	private final ActiveCardIdentityCatalog activeCatalog;
	private final RemoteCatalogCacheStore cacheStore;
	private final AtomicLong generation = new AtomicLong();
	private final AtomicReference<OsrsTcgCatalogClient.FetchHandle> activeFetch =
		new AtomicReference<>();

	private boolean running;
	private boolean enabled;
	private boolean v1Capable;
	private boolean fetchStarted;
	private OsrsTcgCatalogSnapshot pendingSnapshot;
	private String pendingVersion;
	private String pendingEtag;
	private volatile Listener listener = Listener.NONE;

	@Inject
	public RemoteCatalogService(OsrsTcgCatalogClient client,
		OsrsTcgCatalogParser parser, RemoteCatalogValidator validator,
		BundledCardIdentityCatalog bundledCatalog,
		ActiveCardIdentityCatalog activeCatalog, RemoteCatalogCacheStore cacheStore)
	{
		this.client = client;
		this.parser = parser;
		this.validator = validator;
		this.bundledCatalog = bundledCatalog;
		this.activeCatalog = activeCatalog;
		this.cacheStore = cacheStore;
	}

	public void setListener(Listener listener)
	{
		this.listener = listener == null ? Listener.NONE : listener;
	}

	public void startUp()
	{
		generation.incrementAndGet();
		cancelActiveFetch();
		OsrsTcgCatalogSnapshot cachedSnapshot = null;
		String cachedVersion = null;
		String cachedEtag = null;
		try
		{
			RemoteCatalogCacheStore.CachedCatalog cached = cacheStore.load();
			if (cached != null)
			{
				validator.validate(cached.getSnapshot());
				cachedSnapshot = cached.getSnapshot()
					.withLegacyAliases(bundledCatalog.getEntries());
				cachedVersion = cached.getVersion();
				cachedEtag = cached.getEtag();
			}
		}
		catch (CatalogValidationException | IOException | RuntimeException ex)
		{
			log.warn("Ignoring invalid Bronzeman live-catalogue cache; "
				+ "bundled fallback remains active.", ex);
		}
		synchronized (this)
		{
			running = true;
			enabled = false;
			v1Capable = false;
			fetchStarted = false;
			pendingSnapshot = cachedSnapshot;
			pendingVersion = cachedVersion;
			pendingEtag = cachedEtag;
			activeCatalog.useBundled();
		}
	}

	/** Enables or disables every request to the public catalogue endpoint. */
	public void setEnabled(boolean enabled)
	{
		boolean cancelFetch = false;
		long changedRevision;
		long fetchGeneration = -1L;
		synchronized (this)
		{
			long before = activeCatalog.getRevision();
			boolean wasEnabled = this.enabled;
			this.enabled = running && enabled;
			if (!this.enabled)
			{
				activeCatalog.useBundled();
				if (wasEnabled)
				{
					generation.incrementAndGet();
					cancelFetch = true;
					fetchStarted = false;
				}
			}
			else
			{
				activatePendingIfReady();
				if (v1Capable && !fetchStarted)
				{
					fetchStarted = true;
					fetchGeneration = generation.get();
				}
			}
			changedRevision = changedRevision(before);
		}
		if (cancelFetch)
		{
			cancelActiveFetch();
		}
		notifyChanged(changedRevision);
		if (fetchGeneration >= 0)
		{
			startFetch(fetchGeneration);
		}
	}

	private void startFetch(long activeGeneration)
	{
		String currentEtag;
		synchronized (this)
		{
			currentEtag = pendingEtag;
		}
		OsrsTcgCatalogClient.FetchHandle handle = client.fetch(currentEtag,
			new OsrsTcgCatalogClient.Listener()
			{
				@Override
				public void onSuccess(OsrsTcgCatalogClient.CatalogResponse response)
				{
					handleSuccess(activeGeneration, response);
				}

				@Override
				public void onFailure(String reason, Throwable cause)
				{
					if (activeGeneration == generation.get())
					{
						log.debug("Remote OSRS TCG catalogue unavailable: {}", reason, cause);
					}
				}
			});
		activeFetch.set(handle);
		if (activeGeneration != generation.get())
		{
			handle.cancel();
		}
	}

	public void setV1Capable(boolean capable)
	{
		long changedRevision;
		long fetchGeneration = -1L;
		synchronized (this)
		{
			long before = activeCatalog.getRevision();
			v1Capable = running && capable;
			if (!v1Capable)
			{
				activeCatalog.useBundled();
			}
			else if (enabled)
			{
				activatePendingIfReady();
				if (!fetchStarted)
				{
					fetchStarted = true;
					fetchGeneration = generation.get();
				}
			}
			changedRevision = changedRevision(before);
		}
		notifyChanged(changedRevision);
		if (fetchGeneration >= 0)
		{
			startFetch(fetchGeneration);
		}
	}

	public void shutDown()
	{
		generation.incrementAndGet();
		cancelActiveFetch();
		synchronized (this)
		{
			running = false;
			enabled = false;
			v1Capable = false;
			fetchStarted = false;
			pendingSnapshot = null;
			pendingVersion = null;
			pendingEtag = null;
			activeCatalog.useBundled();
		}
	}

	private void cancelActiveFetch()
	{
		OsrsTcgCatalogClient.FetchHandle handle = activeFetch.getAndSet(null);
		if (handle != null)
		{
			handle.cancel();
		}
	}

	private void handleSuccess(long activeGeneration,
		OsrsTcgCatalogClient.CatalogResponse response)
	{
		if (activeGeneration != generation.get())
		{
			return;
		}
		try
		{
			if (response.isNotModified())
			{
				long changedRevision;
				synchronized (this)
				{
					if (!running || pendingSnapshot == null
						|| activeGeneration != generation.get())
					{
						return;
					}
					long before = activeCatalog.getRevision();
					activatePendingIfReady();
					changedRevision = changedRevision(before);
				}
				notifyChanged(changedRevision);
				log.info("Validated cached OSRS TCG catalogue remains current (version={})",
					pendingVersion);
				return;
			}
			OsrsTcgCatalogSnapshot remote;
			try (InputStreamReader reader = new InputStreamReader(
				new ByteArrayInputStream(response.getBody()), StandardCharsets.UTF_8))
			{
				remote = parser.parse(reader);
			}
			validator.validate(remote);
			if (activeGeneration != generation.get())
			{
				return;
			}
			try
			{
				cacheStore.save(remote, response.getVersion(), response.getEtag(),
					Instant.now().toEpochMilli());
			}
			catch (IOException | RuntimeException ex)
			{
				log.warn("Could not update the Bronzeman live-catalogue cache; using this "
					+ "validated response for the current session.", ex);
			}
			remote = remote.withLegacyAliases(bundledCatalog.getEntries());
			long changedRevision;
			synchronized (this)
			{
				if (!running || activeGeneration != generation.get())
				{
					return;
				}
				pendingSnapshot = remote;
				pendingVersion = response.getVersion();
				pendingEtag = response.getEtag();
				long before = activeCatalog.getRevision();
				activatePendingIfReady();
				changedRevision = changedRevision(before);
			}
			notifyChanged(changedRevision);
			log.info("Validated OSRS TCG catalogue (version={}, active={})",
				response.getVersion(), activeCatalog.isRemoteActive());
		}
		catch (CatalogValidationException | IOException | RuntimeException exception)
		{
			if (activeGeneration == generation.get())
			{
				log.warn("Rejected remote OSRS TCG catalogue; bundled fallback remains active.",
					exception);
			}
		}
	}

	private void activatePendingIfReady()
	{
		if (running && enabled && v1Capable && pendingSnapshot != null)
		{
			activeCatalog.activate(pendingSnapshot, pendingSnapshot.getEntries(), pendingVersion);
		}
	}

	private long changedRevision(long before)
	{
		long after = activeCatalog.getRevision();
		return after == before ? -1L : after;
	}

	private void notifyChanged(long revision)
	{
		if (revision < 0)
		{
			return;
		}
		try
		{
			listener.onActiveCatalogChanged(revision, activeCatalog.isV1CatalogAvailable());
		}
		catch (RuntimeException ex)
		{
			log.warn("Remote catalogue listener failed", ex);
		}
	}

	public interface Listener
	{
		Listener NONE = (revision, v1CatalogAvailable) -> { };

		void onActiveCatalogChanged(long revision, boolean v1CatalogAvailable);
	}
}
