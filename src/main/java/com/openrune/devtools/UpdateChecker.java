/*
 * Copyright (c) 2026, Mark7625
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.openrune.devtools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Checks this fork's GitHub releases for a newer build and announces it in game.
 * Deliberately notify-only: downloading a jar is an unauthenticated third-party
 * fetch and swapping it in requires external processes, so the user installs
 * updates by dropping the asset into their sideloaded-plugins folder.
 */
@Slf4j
class UpdateChecker
{
	// The fork publishes its own releases; checking upstream's would point users
	// at a different lineage than the one they are running.
	private static final String RELEASES_API =
		"https://api.github.com/repos/Devexity-fivem/OpenRune-Developer-Tools/releases/latest";
	private static final String RELEASES_PAGE =
		"https://github.com/Devexity-fivem/OpenRune-Developer-Tools/releases";

	private final Client client;
	private final ClientThread clientThread;
	private final OkHttpClient okHttpClient;

	@Getter
	private volatile String latestTag;
	@Getter
	private volatile boolean updateAvailable;

	UpdateChecker(Client client, ClientThread clientThread, OkHttpClient okHttpClient)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.okHttpClient = okHttpClient;
	}

	/** Fire-and-forget: check for a newer release on the OkHttp thread pool. */
	void checkAsync()
	{
		String current = currentVersion();
		if (current == null || current.endsWith("-local"))
		{
			log.info("Dev build ({}); skipping update check", current);
			return;
		}
		Request request = new Request.Builder()
			.url(RELEASES_API)
			.header("Accept", "application/vnd.github+json")
			.build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				// Offline or DNS blocked is routine for a dev tool, not warn-worthy.
				log.debug("Update check failed: {}", e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response; ResponseBody body = r.body())
				{
					if (r.code() == 404)
					{
						// No release published yet; that is not an error.
						return;
					}
					if (!r.isSuccessful() || body == null)
					{
						log.debug("Update check got HTTP {}", r.code());
						return;
					}
					JsonObject release = new JsonParser().parse(body.string()).getAsJsonObject();
					if (!release.has("tag_name"))
					{
						return;
					}
					String tag = release.get("tag_name").getAsString();
					latestTag = tag;
					String latest = tag.startsWith("v") ? tag.substring(1) : tag;
					if (latest.equals(current))
					{
						log.info("OpenRune-DeveloperTools {} is up to date", current);
						return;
					}
					if (!hasJarAsset(release))
					{
						log.info("Update {} found but release has no jar asset", tag);
						return;
					}
					updateAvailable = true;
					announce("OpenRune-DeveloperTools " + latest + " is available. "
						+ "Download the jar from the plugin releases page and replace "
						+ "the copy in your sideloaded-plugins folder: " + RELEASES_PAGE);
				}
				catch (Exception e)
				{
					log.debug("Update check failed parsing response", e);
				}
			}
		});
	}

	private String currentVersion()
	{
		String version = UpdateChecker.class.getPackage().getImplementationVersion();
		if (version != null)
		{
			return version;
		}
		// Fallback: read our own manifest (sideloaded jars may not expose the package version)
		try (InputStream in = UpdateChecker.class.getResourceAsStream("/META-INF/MANIFEST.MF"))
		{
			if (in != null)
			{
				java.util.jar.Manifest manifest = new java.util.jar.Manifest(in);
				return manifest.getMainAttributes().getValue("Implementation-Version");
			}
		}
		catch (IOException ignored)
		{
			// treated as dev build
		}
		return null;
	}

	private static boolean hasJarAsset(JsonObject release)
	{
		JsonArray assets = release.getAsJsonArray("assets");
		if (assets == null)
		{
			return false;
		}
		for (int i = 0; i < assets.size(); i++)
		{
			JsonObject asset = assets.get(i).getAsJsonObject();
			if (asset.has("name") && asset.get("name").getAsString().endsWith(".jar"))
			{
				return true;
			}
		}
		return false;
	}

	private void announce(String message)
	{
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return false; // retry each frame until the player is logged in
			}
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
			return true;
		});
		log.info(message);
	}
}
