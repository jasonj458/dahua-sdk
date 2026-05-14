package main.java.com.netsdk.demo.frame.Gate;

import com.sun.jna.Pointer;

import main.java.com.netsdk.demo.module.GateModule;
import main.java.com.netsdk.demo.module.LoginModule;
import main.java.com.netsdk.lib.NetSDKLib;
import main.java.com.netsdk.lib.NetSDKLib.LLong;

/**
 * Minimal command-line open-door smoke test.
 */
public class GateOpenDoorTest {
	private static final int DEFAULT_DOOR_INDEX = 0;
	private static final String DEFAULT_DOOR_LABEL = "Office";

	private static class DisConnect implements NetSDKLib.fDisConnect {
		public void invoke(LLong loginHandle, String ip, int port, Pointer user) {
			System.out.println("Device disconnected: " + ip + ":" + port);
		}
	}

	private static class HaveReConnect implements NetSDKLib.fHaveReConnect {
		public void invoke(LLong loginHandle, String ip, int port, Pointer user) {
			System.out.println("Device reconnected: " + ip + ":" + port);
		}
	}

	public static void main(String[] args) {
		String host = readRequiredEnv("DAHUA_HOST");
		int port = readRequiredIntEnv("DAHUA_PORT");
		String username = readRequiredEnv("DAHUA_USERNAME");
		String password = readRequiredEnv("DAHUA_PASSWORD");
		int doorIndex = readIntEnvOrDefault("DOOR_INDEX", DEFAULT_DOOR_INDEX);
		String doorLabel = readEnvOrDefault("DOOR_LABEL", DEFAULT_DOOR_LABEL);

		boolean inited = false;
		try {
			inited = LoginModule.init(new DisConnect(), new HaveReConnect());
			if (!inited) {
				System.err.println("SDK init failed.");
				return;
			}

			boolean loginOk = LoginModule.login(host, port, username, password);
			if (!loginOk) {
				return;
			}

			boolean openOk = GateModule.openDoor(doorIndex, doorLabel);
			System.out.println("openDoor result (" + doorLabel + ", index " + doorIndex + "): " + openOk);
		} finally {
			LoginModule.logout();
			if (inited) {
				LoginModule.cleanup();
			}
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

	private static String readEnvOrDefault(String name, String defaultValue) {
		String value = System.getenv(name);
		if (value == null || value.trim().isEmpty()) {
			return defaultValue;
		}
		return value.trim();
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
}
