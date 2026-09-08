/*
 * Copyright (c) 2026, ImTheC4
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
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.imthec4.customstatlayout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * A deliberately small HTTP/1.1 server that answers exactly one request: the current
 * stat snapshot, as JSON.
 *
 * It is written against the JDK's own sockets, so the plugin carries no third-party
 * dependency and does not rely on the jdk.httpserver module being present in whichever
 * runtime launched the client. It binds to the loopback address only, so nothing outside
 * this machine can reach it, and it serves no files from disk -- the only thing it can
 * ever return is the string the plugin hands it.
 */
@Slf4j
class CustomStatLayoutServer
{
	/** Refuse to read a request head larger than this; a browser's is well under 2 KB. */
	private static final int MAX_REQUEST_HEAD = 8192;

	private final Supplier<String> json;
	private final ServerSocket socket;
	private final ExecutorService workers;
	private volatile boolean running = true;

	/**
	 * @param port loopback port to listen on
	 * @param json supplies the current snapshot; called from a worker thread, so it must
	 *             be safe to call off the client thread
	 */
	CustomStatLayoutServer(int port, Supplier<String> json) throws IOException
	{
		this.json = json;
		this.socket = new ServerSocket(port, 16, InetAddress.getLoopbackAddress());
		this.workers = Executors.newFixedThreadPool(4, r ->
		{
			Thread t = new Thread(r, "custom-stat-layout-http");
			t.setDaemon(true);
			return t;
		});

		Thread accept = new Thread(this::acceptLoop, "custom-stat-layout-accept");
		accept.setDaemon(true);
		accept.start();
	}

	int port()
	{
		return socket.getLocalPort();
	}

	void stop()
	{
		running = false;
		try
		{
			socket.close();
		}
		catch (IOException ignored)
		{
			// closing is the only way to break out of accept(); a failure here changes nothing
		}
		workers.shutdownNow();
		try
		{
			workers.awaitTermination(2, TimeUnit.SECONDS);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}

	private void acceptLoop()
	{
		while (running)
		{
			try
			{
				Socket client = socket.accept();
				workers.execute(() -> handle(client));
			}
			catch (IOException e)
			{
				if (running)
				{
					log.debug("accept failed", e);
				}
				// when running is false the socket was closed on purpose, so fall out quietly
			}
			catch (RejectedExecutionException e)
			{
				return;
			}
		}
	}

	private void handle(Socket client)
	{
		try (Socket s = client)
		{
			s.setSoTimeout(5000);
			String head = readRequestLine(s.getInputStream());
			if (head == null)
			{
				return;
			}

			String[] parts = head.split(" ");
			if (parts.length < 2)
			{
				send(s, 400, "text/plain; charset=utf-8", "Bad Request".getBytes(StandardCharsets.UTF_8));
				return;
			}

			String method = parts[0];
			String target = parts[1];
			int query = target.indexOf('?');
			if (query >= 0)
			{
				target = target.substring(0, query);
			}

			if ("OPTIONS".equals(method))
			{
				// Preflight. A page served over HTTPS may fetch http://127.0.0.1 -- loopback
				// counts as a trustworthy origin, so this is not blocked as mixed content --
				// but Private Network Access makes the browser ask permission first, and it
				// asks with an OPTIONS request. Without the answer below, an overlay served
				// from a website could never read this server.
				send(s, 204, null, new byte[0]);
				return;
			}
			if (!"GET".equals(method) && !"HEAD".equals(method))
			{
				send(s, 405, "text/plain; charset=utf-8", "Method Not Allowed".getBytes(StandardCharsets.UTF_8));
				return;
			}

			if ("/stats.json".equals(target) || "/".equals(target))
			{
				send(s, 200, "application/json; charset=utf-8", json.get().getBytes(StandardCharsets.UTF_8));
				return;
			}

			send(s, 404, "text/plain; charset=utf-8", "Not Found".getBytes(StandardCharsets.UTF_8));
		}
		catch (SocketTimeoutException e)
		{
			// a client that opened a connection and said nothing; nothing to do
		}
		catch (IOException e)
		{
			log.debug("request failed", e);
		}
	}

	private static void send(Socket s, int status, String contentType, byte[] body) throws IOException
	{
		StringBuilder h = new StringBuilder(320);
		h.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
		if (contentType != null)
		{
			h.append("Content-Type: ").append(contentType).append("\r\n");
		}
		// The overlay may be loaded from a file:// page, from another port, or from a website,
		// so every response is readable cross-origin. What is served is only the numbers the
		// player already has on screen -- no account name, no world, nothing identifying.
		h.append("Access-Control-Allow-Origin: *\r\n");
		h.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
		h.append("Access-Control-Allow-Headers: *\r\n");
		h.append("Access-Control-Max-Age: 600\r\n");
		h.append("Access-Control-Allow-Private-Network: true\r\n");
		h.append("Cache-Control: no-store\r\n");
		h.append("Content-Length: ").append(body.length).append("\r\n");
		h.append("Connection: close\r\n\r\n");

		OutputStream out = s.getOutputStream();
		out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
		out.write(body);
		out.flush();
	}

	private static String reason(int status)
	{
		switch (status)
		{
			case 200:
				return "OK";
			case 204:
				return "No Content";
			case 400:
				return "Bad Request";
			case 404:
				return "Not Found";
			case 405:
				return "Method Not Allowed";
			default:
				return "Unknown";
		}
	}

	/**
	 * Reads the request line and drains the rest of the head. Only the first line matters,
	 * but the head has to be consumed so the client does not see a broken pipe.
	 */
	private static String readRequestLine(InputStream in) throws IOException
	{
		StringBuilder line = new StringBuilder(128);
		String first = null;
		int consecutiveNewlines = 0;
		int read = 0;

		while (read < MAX_REQUEST_HEAD)
		{
			int b = in.read();
			if (b < 0)
			{
				break;
			}
			read++;
			if (b == '\r')
			{
				continue;
			}
			if (b == '\n')
			{
				if (first == null)
				{
					first = line.toString();
				}
				if (++consecutiveNewlines == 2)
				{
					break;
				}
				line.setLength(0);
				continue;
			}
			consecutiveNewlines = 0;
			line.append((char) b);
		}

		return first == null || first.isEmpty() ? null : first;
	}
}
