package software.coley.recaf.launch;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchCommandTest {
	@Test
	void runOnceFlagParses() {
		LaunchCommand command = new LaunchCommand();
		new CommandLine(command).parseArgs("--run-once");
		assertTrue(command.isRunOnce());
	}

	@Test
	void headlessAliasMapsToRunOnce() {
		LaunchCommand command = new LaunchCommand();
		new CommandLine(command).parseArgs("--headless");
		assertTrue(command.isRunOnce());
		assertTrue(command.isHeadless());
	}

	@Test
	void backgroundServiceIsSilentByDefault() {
		LaunchCommand command = new LaunchCommand();
		new CommandLine(command).parseArgs();
		assertFalse(command.isRunOnce());
		assertFalse(command.isConsoleLoggingEnabled());
	}

	@Test
	void consoleFlagEnablesConsoleLogging() {
		LaunchCommand command = new LaunchCommand();
		new CommandLine(command).parseArgs("--console");
		assertTrue(command.isConsoleLoggingEnabled());
	}

	@Test
	void silentOverridesConsoleLogging() {
		LaunchCommand command = new LaunchCommand();
		new CommandLine(command).parseArgs("--console", "--silent");
		assertFalse(command.isConsoleLoggingEnabled());
		assertTrue(command.isSilent());
	}
}
