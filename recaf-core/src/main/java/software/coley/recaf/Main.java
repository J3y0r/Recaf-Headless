package software.coley.recaf;

import jakarta.enterprise.inject.spi.Bean;
import org.slf4j.Logger;
import picocli.CommandLine;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.analytics.logging.RecafLoggingFilter;
import software.coley.recaf.cdi.EagerInitializationExtension;
import software.coley.recaf.cdi.InitializationEvent;
import software.coley.recaf.launch.LaunchArguments;
import software.coley.recaf.launch.LaunchCommand;
import software.coley.recaf.launch.LaunchHandler;
import software.coley.recaf.services.compile.CompilerDiagnostic;
import software.coley.recaf.services.file.RecafDirectoriesConfig;
import software.coley.recaf.services.plugin.PluginContainer;
import software.coley.recaf.services.plugin.PluginException;
import software.coley.recaf.services.plugin.PluginManager;
import software.coley.recaf.services.plugin.discovery.DirectoryPluginDiscoverer;
import software.coley.recaf.services.script.ScriptEngine;
import software.coley.recaf.services.script.ScriptResult;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.util.JdkValidation;
import software.coley.recaf.workspace.model.BasicWorkspace;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Application entry-point for Recaf's headless service runtime.
 *
 * @author Matt Coley
 */
public class Main {
	private static final Logger logger = Logging.get(Main.class);
	private static LaunchArguments launchArgs;
	private static Recaf recaf;

	/**
	 * @param args
	 * 		Application arguments.
	 */
	public static void main(String[] args) {
		System.setProperty("java.awt.headless", "true");

		// Add a shutdown hook which dumps system information to console.
		// Should provide useful information that users can copy/paste to us for diagnosing problems.
		ExitDebugLoggingHook.register();

		// Handle arguments.
		LaunchCommand launchArgValues = new LaunchCommand();
		try {
			CommandLine cmd = new CommandLine(launchArgValues);
			cmd.setStopAtPositional(true);
			cmd.setStopAtUnmatched(true);
			CommandLine.ParseResult parseResult = cmd.parseArgs(args);
			if (CommandLine.printHelpIfRequested(parseResult))
				return;
			if (launchArgValues.call())
				return;
		} catch (Exception ex) {
			CommandLine.usage(launchArgValues, System.out);
			return;
		}

		configureConsoleLogging(launchArgValues);

		// Validate we're on a JDK and not a JRE
		JdkValidation.validateJdk();

		// Invoke the bootstrapper and initialize the service runtime.
		recaf = Bootstrap.get();
		launchArgs = recaf.get(LaunchArguments.class);
		launchArgs.setCommand(launchArgValues);
		launchArgs.setRawArgs(args);

		// Set up the launch-handler bean to load inputs if specified by the launch arguments.
		Bean<?> bean = recaf.getContainer().getBeanContainer().getBeans(LaunchHandler.class).iterator().next();
		LaunchHandler.task = Main::initHandleInputs;
		EagerInitializationExtension.getApplicationScopedEagerBeans().add(bean);

		initialize();
	}

	private static void configureConsoleLogging(LaunchCommand launchArgValues) {
		if (!launchArgValues.isConsoleLoggingEnabled())
			RecafLoggingFilter.setConsoleLevel(System.Logger.Level.OFF);
	}

	/**
	 * Initialize the headless service runtime.
	 */
	private static void initialize() {
		initLogging();
		initPlugins();
		fireInitEvent();
		awaitShutdownIfNeeded();
	}

	/**
	 * Publishes the {@link InitializationEvent} so that eagerly initialized services marked to run immediately are
	 * instantiated before the service enters its steady state.
	 */
	private static void fireInitEvent() {
		recaf.getContainer().getBeanContainer().getEvent().fire(new InitializationEvent());
	}

	private static void awaitShutdownIfNeeded() {
		if (launchArgs.isRunOnce())
			return;

		try {
			new CountDownLatch(1).await();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			logger.warn("Background service interrupted, shutting down");
		}
	}

	/**
	 * Configure file logging appender and compress old logs.
	 */
	private static void initLogging() {
		RecafDirectoriesConfig directories = recaf.get(RecafDirectoriesConfig.class);

		// Setup appender
		String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
		Path logFile = directories.getBaseDirectory().resolve("log-" + date + ".txt");
		directories.initCurrentLogPath(logFile);
		Logging.addFileAppender(logFile);

		// Set default error handler
		Thread.setDefaultUncaughtExceptionHandler((t, e) ->
				logger.error("Uncaught exception on thread '{}'", t.getName(), e));

		// Archive old logs
		try {
			Files.createDirectories(directories.getLogsDirectory());
			List<Path> oldLogs = Files.list(directories.getBaseDirectory())
					.filter(p -> p.getFileName().toString().matches("log-\\d+-\\d+-\\d+\\.txt"))
					.collect(Collectors.toList());

			// Do not treat the current log file as an old log file
			oldLogs.remove(logFile);

			// Handling old entries
			logger.trace("Compressing {} old log files", oldLogs.size());
			for (Path oldLog : oldLogs) {
				String originalFileName = oldLog.getFileName().toString();
				String archiveFileName = originalFileName.replace(".txt", ".zip");
				Path archivedLog = directories.getLogsDirectory().resolve(archiveFileName);

				// Compress the log into a zip
				try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(archivedLog.toFile()))) {
					zos.putNextEntry(new ZipEntry(originalFileName));
					Files.copy(oldLog, zos);
					zos.closeEntry();
				}

				// Remove the old file
				Files.delete(oldLog);
			}
		} catch (IOException ex) {
			logger.warn("Failed to compress old logs", ex);
		}
	}

	/**
	 * Load plugins.
	 */
	private static void initPlugins() {
		PluginManager pluginManager = recaf.get(PluginManager.class);

		// Load from the plugin directory
		try {
			RecafDirectoriesConfig dirConfig = recaf.get(RecafDirectoriesConfig.class);
			Path pluginDirectory = dirConfig.getPluginDirectory();
			Path extraPluginDirectory = dirConfig.getExtraPluginDirectory();
			pluginManager.loadPlugins(new DirectoryPluginDiscoverer(pluginDirectory));
			if (extraPluginDirectory != null)
				pluginManager.loadPlugins(new DirectoryPluginDiscoverer(extraPluginDirectory));
		} catch (PluginException ex) {
			logger.error("Failed to initialize plugins", ex);
		}

		// Log the discovered plugins
		Collection<PluginContainer<?>> plugins = pluginManager.getPlugins();
		if (plugins.isEmpty()) {
			logger.info("Initialization: No plugins found");
		} else {
			String split = "\n - ";
			logger.info("Initialization: {} plugins found:" + split + "{}",
					plugins.size(),
					plugins.stream().map(PluginContainer::info)
							.map(info -> info.name() + " - " + info.version())
							.collect(Collectors.joining(split)));
		}
	}

	private static void initHandleInputs() {
		// Open initial file if found.
		try {
			File input = launchArgs.getInput();
			if (input != null && input.exists()) {
				ResourceImporter importer = recaf.get(ResourceImporter.class);
				WorkspaceManager workspaceManager = recaf.get(WorkspaceManager.class);
				workspaceManager.setCurrent(new BasicWorkspace(importer.importResource(input)));
			}
		} catch (Throwable t) {
			logger.error("Error handling loading of launch workspace content.", t);
		}

		// Run startup script.
		try {
			File script = launchArgs.getScript();
			if (script != null && !script.isFile())
				script = launchArgs.getScriptInScriptsDirectory();
			if (script != null && script.isFile()) {
				ScriptResult result = recaf.get(ScriptEngine.class)
						.run(Files.readString(script.toPath()))
						.get();
				if (!result.wasSuccess()) {
					if (result.wasRuntimeError()) {
						// The script engine will have already logged the exceptions
						logger.error("Error encountered when executing script '{}'", script.getName());
					} else if (result.wasCompileFailure()) {
						// Inform the user where the script is incorrectly formatted
						logger.error("Error compiling script:\n{}", result.getCompileDiagnostics().stream()
								.map(CompilerDiagnostic::toString)
								.collect(Collectors.joining("\n")));
					}
				}
			}
		} catch (Throwable t) {
			logger.error("Error handling execution of launch script.", t);
		}
	}
}
