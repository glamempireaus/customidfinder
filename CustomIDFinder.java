package com.daja;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Main
{

	private static final String WORDS_FILE = "four.txt";
	private static final String PROXIES_FILE = "proxies.txt";
	private static final String OUTPUT_FILE = "output.txt";

	private static final boolean SEARCH_GROUP = false;
	private static final int THREAD_COUNT = 12;
	private static final String PROFILE_URL = "https://steamcommunity.com/id/";
	private static final String GROUP_URL = "https://steamcommunity.com/groups/";
	private static final int STARTING_LINE = 1;

	private static String URL;
	private static File outputFile;
	private static int currentLine;

	private static final List<String> wordList = new ArrayList<>();
	private static final List<ProxyWrapper> availableProxies = new ArrayList<>();
	private static final ExecutorService executorService = new ThreadPoolExecutor(THREAD_COUNT, THREAD_COUNT, 0L,
			TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(THREAD_COUNT), new ThreadPoolExecutor.CallerRunsPolicy());

	public static enum Status
	{
		CHECK_CONNECTION, AVAILABLE, WORD_ASSIGNED, CURRENTLY_FETCHING, FINISHED_FETCHING, FINDING_PHRASE, DISABLED;
	}

	public static void main(String[] argc) throws ExecutionException
	{
		URL = SEARCH_GROUP ? GROUP_URL : PROFILE_URL;
		outputFile = new File(OUTPUT_FILE);
		currentLine = STARTING_LINE;

		// read proxies

		try
		{
			BufferedReader proxyReader = new BufferedReader(new FileReader(PROXIES_FILE));
			String line;
			while ((line = proxyReader.readLine()) != null)
			{
				String[] address = line.split(":");
				availableProxies.add(new ProxyWrapper(address[0], Integer.parseInt(address[1])));
			}
			proxyReader.close();
		} catch (IOException e)
		{
			e.printStackTrace();
			System.exit(1);
		}

		// read list of words

		int totalLines = 0;
		try
		{
			BufferedReader reader = new BufferedReader(new FileReader(WORDS_FILE));

			String line;
			while ((line = reader.readLine()) != null)
			{
				wordList.add(line);
				totalLines++;
			}
			reader.close();

		} catch (IOException e1)
		{
			e1.printStackTrace();
		}

		// check all proxy connections

		System.out.println("Attempting connection to proxies. This may take some time, please wait...");

		for (ProxyWrapper proxyWrapper : availableProxies)
		{
			executorService.execute(new ParallelTask(proxyWrapper.proxyId));
		}

		int count = 0;
		for (ProxyWrapper proxyWrapper : availableProxies)
		{
			if (proxyWrapper.status == Status.AVAILABLE)
				count++;
		}

		System.out.println("Successfully connected to: " + count + "/" + availableProxies.size() + " proxies.");
		System.out.println("Printing ids that are available:");

		// runs until finished
		while (currentLine < totalLines)
		{
			for (ProxyWrapper proxy : availableProxies)
			{
				if (proxy.status != Status.AVAILABLE && proxy.status != Status.FINISHED_FETCHING)
				{
					continue;
				}

				if (proxy.status == Status.AVAILABLE)
				{
					proxy.status = Status.WORD_ASSIGNED;
					proxy.word = wordList.get(currentLine);
					currentLine++;
				} else
				{
					proxy.status = Status.FINDING_PHRASE;
				}
				executorService.execute(new ParallelTask(proxy.proxyId));
			}
		}
	}

	public static class ParallelTask implements Runnable
	{
		private int assignedProxyId;

		public ParallelTask(int proxyIndex)
		{
			this.assignedProxyId = proxyIndex;
		}

		public void run()
		{
			ProxyWrapper proxyWrapper = availableProxies.get(assignedProxyId);

			// proxy is first connecting

			if (proxyWrapper.status == Status.CHECK_CONNECTION)
			{
				try
				{
					proxyWrapper.connection.connect();
				} catch (IOException e)
				{
					proxyWrapper.status = Status.DISABLED;
					return;
				}

				proxyWrapper.status = Status.AVAILABLE;
			}

			// proxy is fetching content

			else if (proxyWrapper.status == Status.WORD_ASSIGNED)
			{
				proxyWrapper.status = Status.CURRENTLY_FETCHING;
				proxyWrapper.content = fetchWebPage(proxyWrapper);

			}

			// check if proxy object is fetching content

			else if (proxyWrapper.status == Status.FINDING_PHRASE)
			{
				String content = null;
				try
				{
					content = proxyWrapper.content.get();
				} catch (InterruptedException | ExecutionException e)
				{
					proxyWrapper.status = Status.DISABLED;
				}

				String phrase = SEARCH_GROUP ? "No group could be retrieved for the given URL."
						: "The specified profile could not be found.";

				if (content != null && content.contains(phrase))
				{
					System.out.println(proxyWrapper.word);
					try (FileWriter fw = new FileWriter(outputFile.getAbsoluteFile(), true);
							BufferedWriter bw = new BufferedWriter(fw))
					{
						bw.write(proxyWrapper.word + "\n");
					} catch (IOException e)
					{
						e.printStackTrace();
					}
				}

				proxyWrapper.status = Status.AVAILABLE;
			}
		}
	}

	public static CompletableFuture<String> fetchWebPage(ProxyWrapper proxyWrapper)
	{
		return CompletableFuture.supplyAsync(() -> {
			try
			{
				proxyWrapper.connection = new URL(URL + proxyWrapper.word).openConnection(proxyWrapper.proxy);
				InputStream inputStream = proxyWrapper.connection.getInputStream();
				byte[] bytes = inputStream.readAllBytes();
				proxyWrapper.status = Status.FINISHED_FETCHING;
				return new String(bytes);
			} catch (IOException e)
			{
				proxyWrapper.status = Status.DISABLED;
				return null;
			}
		});
	}

	public static class ProxyWrapper
	{
		private static int total = 0;

		private final int proxyId;
		private String word;
		private final Proxy proxy;
		private CompletableFuture<String> content;
		private URLConnection connection;
		private Status status;

		public ProxyWrapper(String ip, int port)
		{
			this.proxyId = total;
			this.status = Status.CHECK_CONNECTION;
			this.proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(ip, port));
			try
			{
				this.connection = new URL(PROFILE_URL).openConnection(proxy);
				this.connection.setConnectTimeout(10000);
			} catch (IOException e)
			{
				System.err.println("Proxy " + ip + " cannot connect.");
				this.status = Status.DISABLED;
			}
			total++;
		}
	}
}
