package main.java.com.netsdk.demo.frame.Gate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;

import com.sun.jna.Pointer;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import main.java.com.netsdk.demo.module.GateModule;
import main.java.com.netsdk.demo.module.LoginModule;
import main.java.com.netsdk.lib.NetSDKLib;
import main.java.com.netsdk.lib.NetSDKLib.LLong;

/**
 * Webhook server for remote door open.
 *
 * Endpoints:
 * POST /door/open/{doorIndex}
 * POST /door/open/office
 * POST /door/open/studio
 *
 * Headers:
 * X-Door-Token: token value from DOOR_WEBHOOK_TOKEN
 * X-Door-Label: optional user-id/label written to SDK request
 *
 * Required environment variables:
 * DAHUA_HOST, DAHUA_PORT, DAHUA_USERNAME, DAHUA_PASSWORD, DOOR_WEBHOOK_TOKEN
 *
 * Optional:
 * DOOR_WEBHOOK_PORT (default: 8080)
 */
public class GateDoorWebhookServer {

	private static final Object OPEN_LOCK = new Object();
	private static String expectedToken;

	private static class DisConnect implements NetSDKLib.fDisConnect {
		public void invoke(LLong loginHandle, String ip, int port, Pointer user) {
			log("device_disconnected", "-", -1, false, ip + ":" + port);
		}
	}

	private static class HaveReConnect implements NetSDKLib.fHaveReConnect {
		public void invoke(LLong loginHandle, String ip, int port, Pointer user) {
			log("device_reconnected", "-", -1, true, ip + ":" + port);
		}
	}

	private static class DoorOpenHandler implements HttpHandler {
		public void handle(HttpExchange exchange) throws IOException {
			try {
				if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
					sendJson(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
					return;
				}

				String token = exchange.getRequestHeaders().getFirst("X-Door-Token");
				if (token == null || !token.equals(expectedToken)) {
					sendJson(exchange, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
					log("unauthorized", exchange.getRequestURI().getPath(), -1, false, "token mismatch");
					return;
				}

				String path = exchange.getRequestURI().getPath();
				int doorIndex = parseDoorIndex(path);
				if (doorIndex < 0) {
					sendJson(exchange, 400, "{\"ok\":false,\"error\":\"invalid_door_index\"}");
					log("invalid_index", path, doorIndex, false, "");
					return;
				}

				String doorLabel = exchange.getRequestHeaders().getFirst("X-Door-Label");
				if (doorLabel == null || doorLabel.trim().isEmpty()) {
					doorLabel = "webhook-door-" + doorIndex;
				}

				boolean opened;
				synchronized (OPEN_LOCK) {
					opened = GateModule.openDoor(doorIndex, doorLabel);
				}

				if (opened) {
					sendJson(exchange, 200, "{\"ok\":true}");
					log("open_ok", path, doorIndex, true, "label=" + doorLabel);
				} else {
					sendJson(exchange, 500, "{\"ok\":false,\"error\":\"open_failed\"}");
					log("open_failed", path, doorIndex, false, "label=" + doorLabel);
				}
			} finally {
				exchange.close();
			}
		}
	}

	public static void main(String[] args) throws Exception {
		String host = readRequiredEnv("DAHUA_HOST");
		int port = readRequiredIntEnv("DAHUA_PORT");
		String username = readRequiredEnv("DAHUA_USERNAME");
		String password = readRequiredEnv("DAHUA_PASSWORD");
		expectedToken = readRequiredEnv("DOOR_WEBHOOK_TOKEN");
		int webhookPort = readIntEnvOrDefault("DOOR_WEBHOOK_PORT", 8080);
		String webhookBindHost = readEnvOrDefault("DOOR_WEBHOOK_BIND_HOST", "0.0.0.0");

		boolean inited = LoginModule.init(new DisConnect(), new HaveReConnect());
		if (!inited) {
			throw new IllegalStateException("SDK init failed");
		}

		boolean loginOk = LoginModule.login(host, port, username, password);
		if (!loginOk) {
			LoginModule.cleanup();
			throw new IllegalStateException("Dahua login failed");
		}

		final HttpServer server = HttpServer.create(new InetSocketAddress(webhookBindHost, webhookPort), 0);
		server.createContext("/door/open", new DoorOpenHandler());
		server.setExecutor(null);

		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
			public void run() {
				log("shutdown", "-", -1, true, "stopping webhook server");
				server.stop(0);
				LoginModule.logout();
				LoginModule.cleanup();
			}
		}));

		server.start();
		log("server_started", "/door/open/{doorIndex|office|studio}", -1, true, "bind=" + webhookBindHost + ":" + webhookPort);
	}

	private static int parseDoorIndex(String path) {
		if (path == null) {
			return -1;
		}
		String[] parts = path.split("/");
		if (parts.length != 4) {
			return -1;
		}
		if (!"door".equals(parts[1]) || !"open".equals(parts[2])) {
			return -1;
		}
		String doorPart = parts[3].toLowerCase();
		if ("office".equals(doorPart)) {
			return 0;
		}
		if ("studio".equals(doorPart)) {
			return 1;
		}
		try {
			int index = Integer.parseInt(parts[3]);
			return index >= 0 ? index : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static String readRequiredEnv(String name) {
		String value = System.getenv(name);
		if (value == null || value.trim().isEmpty()) {
			throw new IllegalArgumentException("Missing environment variable: " + name);
		}
		return value.trim();
	}

	private static int readRequiredIntEnv(String name) {
		String value = readRequiredEnv(name);
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid integer env var " + name + ": " + value);
		}
	}

	private static int readIntEnvOrDefault(String name, int defaultValue) {
		String value = System.getenv(name);
		if (value == null || value.trim().isEmpty()) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	private static String readEnvOrDefault(String name, String defaultValue) {
		String value = System.getenv(name);
		if (value == null || value.trim().isEmpty()) {
			return defaultValue;
		}
		return value.trim();
	}

	private static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
		byte[] bytes = json.getBytes("UTF-8");
		Headers responseHeaders = exchange.getResponseHeaders();
		responseHeaders.set("Content-Type", "application/json; charset=UTF-8");
		exchange.sendResponseHeaders(statusCode, bytes.length);
		OutputStream os = exchange.getResponseBody();
		os.write(bytes);
		os.flush();
	}

	private static void log(String action, String endpoint, int doorIndex, boolean success, String detail) {
		String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
		StringBuilder sb = new StringBuilder();
		sb.append("[").append(timestamp).append("] ");
		sb.append("action=").append(action);
		sb.append(" endpoint=").append(endpoint);
		sb.append(" doorIndex=").append(doorIndex);
		sb.append(" success=").append(success);
		if (detail != null && detail.length() > 0) {
			sb.append(" detail=").append(detail);
		}
		System.out.println(sb.toString());
	}
}
