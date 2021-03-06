/*
 * Copyright 2012 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package azkaban.webapp;

import static azkaban.ServiceProvider.SERVICE_PROVIDER;
import static java.util.Objects.requireNonNull;

import azkaban.AzkabanCommonModule;
import azkaban.Constants;
import azkaban.database.AzkabanDatabaseSetup;
import azkaban.executor.ExecutorManager;
import azkaban.jmx.JmxExecutorManager;
import azkaban.jmx.JmxJettyServer;
import azkaban.jmx.JmxTriggerManager;
import azkaban.metrics.MetricsManager;
import azkaban.metrics.MetricsUtility;
import azkaban.project.ProjectManager;
import azkaban.scheduler.ScheduleLoader;
import azkaban.scheduler.ScheduleManager;
import azkaban.scheduler.TriggerBasedScheduleLoader;
import azkaban.server.AzkabanServer;
import azkaban.server.session.SessionCache;
import azkaban.trigger.TriggerManager;
import azkaban.trigger.TriggerManagerException;
import azkaban.trigger.builtin.BasicTimeChecker;
import azkaban.trigger.builtin.CreateTriggerAction;
import azkaban.trigger.builtin.ExecuteFlowAction;
import azkaban.trigger.builtin.ExecutionChecker;
import azkaban.trigger.builtin.KillExecutionAction;
import azkaban.trigger.builtin.SlaAlertAction;
import azkaban.trigger.builtin.SlaChecker;
import azkaban.user.UserManager;
import azkaban.user.XmlUserManager;
import azkaban.utils.FileIOUtils;
import azkaban.utils.Props;
import azkaban.utils.PropsUtils;
import azkaban.utils.StdOutErrRedirect;
import azkaban.utils.Utils;
import azkaban.webapp.plugin.PluginRegistry;
import azkaban.webapp.plugin.TriggerPlugin;
import azkaban.webapp.plugin.ViewerPlugin;
import azkaban.webapp.servlet.AbstractAzkabanServlet;
import azkaban.webapp.servlet.ExecutorServlet;
import azkaban.webapp.servlet.HistoryServlet;
import azkaban.webapp.servlet.IndexRedirectServlet;
import azkaban.webapp.servlet.JMXHttpServlet;
import azkaban.webapp.servlet.ProjectManagerServlet;
import azkaban.webapp.servlet.ProjectServlet;
import azkaban.webapp.servlet.ScheduleServlet;
import azkaban.webapp.servlet.StatsServlet;
import azkaban.webapp.servlet.TriggerManagerServlet;
import com.codahale.metrics.MetricRegistry;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.linkedin.restli.server.RestliServlet;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.apache.log4j.jmx.HierarchyDynamicMBean;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.log.Log4JLogChute;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.apache.velocity.runtime.resource.loader.JarResourceLoader;
import org.joda.time.DateTimeZone;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.DefaultServlet;
import org.mortbay.jetty.servlet.ServletHolder;
import org.mortbay.thread.QueuedThreadPool;


/**
 * The Azkaban Jetty server class
 *
 * Global azkaban properties for setup. All of them are optional unless
 * otherwise marked: azkaban.name - The displayed name of this instance.
 * azkaban.label - Short descriptor of this Azkaban instance. azkaban.color -
 * Theme color azkaban.temp.dir - Temp dir used by Azkaban for various file
 * uses. web.resource.dir - The directory that contains the static web files.
 * default.timezone.id - The timezone code. I.E. America/Los Angeles
 *
 * user.manager.class - The UserManager class used for the user manager. Default
 * is XmlUserManager. project.manager.class - The ProjectManager to load
 * projects project.global.properties - The base properties inherited by all
 * projects and jobs
 *
 * jetty.maxThreads - # of threads for jetty jetty.ssl.port - The ssl port used
 * for sessionizing. jetty.keystore - Jetty keystore . jetty.keypassword - Jetty
 * keystore password jetty.truststore - Jetty truststore jetty.trustpassword -
 * Jetty truststore password
 */
public class AzkabanWebServer extends AzkabanServer {

  public static final String DEFAULT_CONF_PATH = "conf";
  private static final String AZKABAN_ACCESS_LOGGER_NAME =
      "azkaban.webapp.servlet.LoginAbstractAzkabanServlet";
  private static final Logger logger = Logger.getLogger(AzkabanWebServer.class);
  private static final int MAX_FORM_CONTENT_SIZE = 10 * 1024 * 1024;
  private static final String DEFAULT_TIMEZONE_ID = "default.timezone.id";
  private static final String VELOCITY_DEV_MODE_PARAM = "velocity.dev.mode";
  private static final String USER_MANAGER_CLASS_PARAM = "user.manager.class";
  private static final String DEFAULT_STATIC_DIR = "";
  private static AzkabanWebServer app;
  private final VelocityEngine velocityEngine;

  private final Server server;
  private final UserManager userManager;
  private final ProjectManager projectManager;
  private final ExecutorManager executorManager;
  private final ScheduleManager scheduleManager;
  private final TriggerManager triggerManager;
  private final ClassLoader baseClassLoader;
  private final Props props;
  private final SessionCache sessionCache;
  private final List<ObjectName> registeredMBeans = new ArrayList<>();
  //queuedThreadPool is mainly used to monitor jetty threadpool.
  private QueuedThreadPool queuedThreadPool;
  private Map<String, TriggerPlugin> triggerPlugins;
  private MBeanServer mbeanServer;

  /**
   * Constructor usually called by tomcat AzkabanServletContext to create the
   * initial server
   */
  public AzkabanWebServer() throws Exception {
    this(null, loadConfigurationFromAzkabanHome());
  }

  @Inject
  public AzkabanWebServer(final Server server, final Props props) throws Exception {
    this.props = requireNonNull(props);
    this.server = server;

    this.velocityEngine = configureVelocityEngine(props.getBoolean(VELOCITY_DEV_MODE_PARAM, false));
    this.sessionCache = new SessionCache(props);
    this.userManager = loadUserManager(props);

    // TODO remove hack. Move injection to constructor
    this.executorManager = SERVICE_PROVIDER.getInstance(ExecutorManager.class);
    this.projectManager = SERVICE_PROVIDER.getInstance(ProjectManager.class);
    this.triggerManager = SERVICE_PROVIDER.getInstance(TriggerManager.class);

    loadBuiltinCheckersAndActions();

    // load all trigger agents here
    this.scheduleManager = loadScheduleManager(this.triggerManager);

    final String triggerPluginDir =
        props.getString("trigger.plugin.dir", "plugins/triggers");

    loadPluginCheckersAndActions(triggerPluginDir);

    this.baseClassLoader = this.getClassLoader();

    // Setup time zone
    if (props.containsKey(DEFAULT_TIMEZONE_ID)) {
      final String timezone = props.getString(DEFAULT_TIMEZONE_ID);
      System.setProperty("user.timezone", timezone);
      TimeZone.setDefault(TimeZone.getTimeZone(timezone));
      DateTimeZone.setDefault(DateTimeZone.forID(timezone));
      logger.info("Setting timezone to " + timezone);
    }

    configureMBeanServer();
  }

  public static AzkabanWebServer getInstance() {
    return app;
  }

  /**
   * Azkaban using Jetty
   */
  public static void main(final String[] args) throws Exception {
    // Redirect all std out and err messages into log4j
    StdOutErrRedirect.redirectOutAndErrToLog();

    logger.info("Starting Jetty Azkaban Web Server...");
    final Props props = AzkabanServer.loadProps(args);

    if (props == null) {
      logger.error("Azkaban Properties not loaded. Exiting..");
      System.exit(1);
    }

    /* Initialize Guice Injector */
    final Injector injector = Guice
        .createInjector(new AzkabanCommonModule(props), new AzkabanWebServerModule());
    SERVICE_PROVIDER.setInjector(injector);

    launch(injector.getInstance(AzkabanWebServer.class));
  }

  public static void launch(final AzkabanWebServer webServer) throws Exception {
    /* This creates the Web Server instance */
    app = webServer;

    // TODO refactor code into ServerProvider
    prepareAndStartServer(webServer.getServerProps(), app.server);

    Runtime.getRuntime().addShutdownHook(new Thread() {

      @Override
      public void run() {
        try {
          logTopMemoryConsumers();
        } catch (final Exception e) {
          logger.info(("Exception when logging top memory consumers"), e);
        }

        logger.info("Shutting down http server...");
        try {
          app.close();
        } catch (final Exception e) {
          logger.error("Error while shutting down http server.", e);
        }
        logger.info("kk thx bye.");
      }

      public void logTopMemoryConsumers() throws Exception, IOException {
        if (new File("/bin/bash").exists() && new File("/bin/ps").exists()
            && new File("/usr/bin/head").exists()) {
          logger.info("logging top memeory consumer");

          final java.lang.ProcessBuilder processBuilder =
              new java.lang.ProcessBuilder("/bin/bash", "-c",
                  "/bin/ps aux --sort -rss | /usr/bin/head");
          final Process p = processBuilder.start();
          p.waitFor();

          final InputStream is = p.getInputStream();
          final java.io.BufferedReader reader =
              new java.io.BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
          String line = null;
          while ((line = reader.readLine()) != null) {
            logger.info(line);
          }
          is.close();
        }
      }
    });
  }

  private static void prepareAndStartServer(final Props azkabanSettings, final Server server)
      throws Exception {
    validateDatabaseVersion(azkabanSettings);
    configureRoutes(server, azkabanSettings);

    if (azkabanSettings.getBoolean(Constants.ConfigurationKeys.IS_METRICS_ENABLED, false)) {
      app.startWebMetrics();
    }
    try {
      server.start();
      logger.info("Server started");
    } catch (final Exception e) {
      logger.warn(e);
      Utils.croak(e.getMessage(), 1);
    }
  }

  private static void validateDatabaseVersion(final Props azkabanSettings)
      throws IOException, SQLException {
    final boolean checkDB = azkabanSettings
        .getBoolean(AzkabanDatabaseSetup.DATABASE_CHECK_VERSION, false);
    if (checkDB) {
      final AzkabanDatabaseSetup setup = new AzkabanDatabaseSetup(azkabanSettings);
      setup.loadTableInfo();
      if (setup.needsUpdating()) {
        logger.error("Database is out of date.");
        setup.printUpgradePlan();

        logger.error("Exiting with error.");
        System.exit(-1);
      }
    }
  }

  private static void configureRoutes(final Server server, final Props azkabanSettings)
      throws TriggerManagerException {
    final int maxThreads = azkabanSettings
        .getInt("jetty.maxThreads", Constants.DEFAULT_JETTY_MAX_THREAD_COUNT);

    final QueuedThreadPool httpThreadPool = new QueuedThreadPool(maxThreads);
    app.setThreadPool(httpThreadPool);
    server.setThreadPool(httpThreadPool);

    final String staticDir =
        azkabanSettings.getString("web.resource.dir", DEFAULT_STATIC_DIR);
    logger.info("Setting up web resource dir " + staticDir);
    final Context root = new Context(server, "/", Context.SESSIONS);
    root.setMaxFormContentSize(MAX_FORM_CONTENT_SIZE);

    final String defaultServletPath =
        azkabanSettings.getString("azkaban.default.servlet.path", "/index");
    root.setResourceBase(staticDir);
    final ServletHolder indexRedirect =
        new ServletHolder(new IndexRedirectServlet(defaultServletPath));
    root.addServlet(indexRedirect, "/");
    final ServletHolder index = new ServletHolder(new ProjectServlet());
    root.addServlet(index, "/index");

    final ServletHolder staticServlet = new ServletHolder(new DefaultServlet());
    root.addServlet(staticServlet, "/css/*");
    root.addServlet(staticServlet, "/js/*");
    root.addServlet(staticServlet, "/images/*");
    root.addServlet(staticServlet, "/fonts/*");
    root.addServlet(staticServlet, "/favicon.ico");

    root.addServlet(new ServletHolder(new ProjectManagerServlet()), "/manager");
    root.addServlet(new ServletHolder(new ExecutorServlet()), "/executor");
    root.addServlet(new ServletHolder(new HistoryServlet()), "/history");
    root.addServlet(new ServletHolder(new ScheduleServlet()), "/schedule");
    root.addServlet(new ServletHolder(new JMXHttpServlet()), "/jmx");
    root.addServlet(new ServletHolder(new TriggerManagerServlet()), "/triggers");
    root.addServlet(new ServletHolder(new StatsServlet()), "/stats");

    final ServletHolder restliHolder = new ServletHolder(new RestliServlet());
    restliHolder.setInitParameter("resourcePackages", "azkaban.restli");
    root.addServlet(restliHolder, "/restli/*");

    final String viewerPluginDir =
        azkabanSettings.getString("viewer.plugin.dir", "plugins/viewer");
    loadViewerPlugins(root, viewerPluginDir, app.getVelocityEngine());

    // triggerplugin
    final String triggerPluginDir =
        azkabanSettings.getString("trigger.plugin.dir", "plugins/triggers");
    final Map<String, TriggerPlugin> triggerPlugins =
        loadTriggerPlugins(root, triggerPluginDir, app);
    app.setTriggerPlugins(triggerPlugins);
    // always have basic time trigger
    // TODO: find something else to do the job
    app.getTriggerManager().start();

    root.setAttribute(Constants.AZKABAN_SERVLET_CONTEXT_KEY, app);
  }

  private static Map<String, TriggerPlugin> loadTriggerPlugins(final Context root,
      final String pluginPath, final AzkabanWebServer azkabanWebApp) {
    final File triggerPluginPath = new File(pluginPath);
    if (!triggerPluginPath.exists()) {
      return new HashMap<>();
    }

    final Map<String, TriggerPlugin> installedTriggerPlugins =
        new HashMap<>();
    final ClassLoader parentLoader = AzkabanWebServer.class.getClassLoader();
    final File[] pluginDirs = triggerPluginPath.listFiles();
    final ArrayList<String> jarPaths = new ArrayList<>();
    for (final File pluginDir : pluginDirs) {
      if (!pluginDir.exists()) {
        logger.error("Error! Trigger plugin path " + pluginDir.getPath()
            + " doesn't exist.");
        continue;
      }

      if (!pluginDir.isDirectory()) {
        logger.error("The plugin path " + pluginDir + " is not a directory.");
        continue;
      }

      // Load the conf directory
      final File propertiesDir = new File(pluginDir, "conf");
      Props pluginProps = null;
      if (propertiesDir.exists() && propertiesDir.isDirectory()) {
        final File propertiesFile = new File(propertiesDir, "plugin.properties");
        final File propertiesOverrideFile =
            new File(propertiesDir, "override.properties");

        if (propertiesFile.exists()) {
          if (propertiesOverrideFile.exists()) {
            pluginProps =
                PropsUtils.loadProps(null, propertiesFile,
                    propertiesOverrideFile);
          } else {
            pluginProps = PropsUtils.loadProps(null, propertiesFile);
          }
        } else {
          logger.error("Plugin conf file " + propertiesFile + " not found.");
          continue;
        }
      } else {
        logger.error("Plugin conf path " + propertiesDir + " not found.");
        continue;
      }

      final String pluginName = pluginProps.getString("trigger.name");
      final List<String> extLibClasspath =
          pluginProps.getStringList("trigger.external.classpaths",
              (List<String>) null);

      final String pluginClass = pluginProps.getString("trigger.class");
      if (pluginClass == null) {
        logger.error("Trigger class is not set.");
      } else {
        logger.error("Plugin class " + pluginClass);
      }

      URLClassLoader urlClassLoader = null;
      final File libDir = new File(pluginDir, "lib");
      if (libDir.exists() && libDir.isDirectory()) {
        final File[] files = libDir.listFiles();

        final ArrayList<URL> urls = new ArrayList<>();
        for (int i = 0; i < files.length; ++i) {
          try {
            final URL url = files[i].toURI().toURL();
            urls.add(url);
          } catch (final MalformedURLException e) {
            logger.error(e);
          }
        }
        if (extLibClasspath != null) {
          for (final String extLib : extLibClasspath) {
            try {
              final File file = new File(pluginDir, extLib);
              final URL url = file.toURI().toURL();
              urls.add(url);
            } catch (final MalformedURLException e) {
              logger.error(e);
            }
          }
        }

        urlClassLoader =
            new URLClassLoader(urls.toArray(new URL[urls.size()]), parentLoader);
      } else {
        logger.error("Library path " + propertiesDir + " not found.");
        continue;
      }

      Class<?> triggerClass = null;
      try {
        triggerClass = urlClassLoader.loadClass(pluginClass);
      } catch (final ClassNotFoundException e) {
        logger.error("Class " + pluginClass + " not found.");
        continue;
      }

      final String source = FileIOUtils.getSourcePathFromClass(triggerClass);
      logger.info("Source jar " + source);
      jarPaths.add("jar:file:" + source);

      Constructor<?> constructor = null;
      try {
        constructor =
            triggerClass.getConstructor(String.class, Props.class,
                Context.class, AzkabanWebServer.class);
      } catch (final NoSuchMethodException e) {
        logger.error("Constructor not found in " + pluginClass);
        continue;
      }

      Object obj = null;
      try {
        obj =
            constructor.newInstance(pluginName, pluginProps, root,
                azkabanWebApp);
      } catch (final Exception e) {
        logger.error(e);
      }

      if (!(obj instanceof TriggerPlugin)) {
        logger.error("The object is not an TriggerPlugin");
        continue;
      }

      final TriggerPlugin plugin = (TriggerPlugin) obj;
      installedTriggerPlugins.put(pluginName, plugin);
    }

    // Velocity needs the jar resource paths to be set.
    final String jarResourcePath = StringUtils.join(jarPaths, ", ");
    logger.info("Setting jar resource path " + jarResourcePath);
    final VelocityEngine ve = azkabanWebApp.getVelocityEngine();
    ve.addProperty("jar.resource.loader.path", jarResourcePath);

    return installedTriggerPlugins;
  }

  private static void loadViewerPlugins(final Context root, final String pluginPath,
      final VelocityEngine ve) {
    final File viewerPluginPath = new File(pluginPath);
    if (!viewerPluginPath.exists()) {
      return;
    }

    final ClassLoader parentLoader = AzkabanWebServer.class.getClassLoader();
    final File[] pluginDirs = viewerPluginPath.listFiles();
    final ArrayList<String> jarPaths = new ArrayList<>();
    for (final File pluginDir : pluginDirs) {
      if (!pluginDir.exists()) {
        logger.error("Error viewer plugin path " + pluginDir.getPath()
            + " doesn't exist.");
        continue;
      }

      if (!pluginDir.isDirectory()) {
        logger.error("The plugin path " + pluginDir + " is not a directory.");
        continue;
      }

      // Load the conf directory
      final File propertiesDir = new File(pluginDir, "conf");
      Props pluginProps = null;
      if (propertiesDir.exists() && propertiesDir.isDirectory()) {
        final File propertiesFile = new File(propertiesDir, "plugin.properties");
        final File propertiesOverrideFile =
            new File(propertiesDir, "override.properties");

        if (propertiesFile.exists()) {
          if (propertiesOverrideFile.exists()) {
            pluginProps =
                PropsUtils.loadProps(null, propertiesFile,
                    propertiesOverrideFile);
          } else {
            pluginProps = PropsUtils.loadProps(null, propertiesFile);
          }
        } else {
          logger.error("Plugin conf file " + propertiesFile + " not found.");
          continue;
        }
      } else {
        logger.error("Plugin conf path " + propertiesDir + " not found.");
        continue;
      }

      final String pluginName = pluginProps.getString("viewer.name");
      final String pluginWebPath = pluginProps.getString("viewer.path");
      final String pluginJobTypes = pluginProps.getString("viewer.jobtypes", null);
      final int pluginOrder = pluginProps.getInt("viewer.order", 0);
      final boolean pluginHidden = pluginProps.getBoolean("viewer.hidden", false);
      final List<String> extLibClasspath =
          pluginProps.getStringList("viewer.external.classpaths",
              (List<String>) null);

      final String pluginClass = pluginProps.getString("viewer.servlet.class");
      if (pluginClass == null) {
        logger.error("Viewer class is not set.");
      } else {
        logger.info("Plugin class " + pluginClass);
      }

      URLClassLoader urlClassLoader = null;
      final File libDir = new File(pluginDir, "lib");
      if (libDir.exists() && libDir.isDirectory()) {
        final File[] files = libDir.listFiles();

        final ArrayList<URL> urls = new ArrayList<>();
        for (int i = 0; i < files.length; ++i) {
          try {
            final URL url = files[i].toURI().toURL();
            urls.add(url);
          } catch (final MalformedURLException e) {
            logger.error(e);
          }
        }

        // Load any external libraries.
        if (extLibClasspath != null) {
          for (final String extLib : extLibClasspath) {
            final File extLibFile = new File(pluginDir, extLib);
            if (extLibFile.exists()) {
              if (extLibFile.isDirectory()) {
                // extLibFile is a directory; load all the files in the
                // directory.
                final File[] extLibFiles = extLibFile.listFiles();
                for (int i = 0; i < extLibFiles.length; ++i) {
                  try {
                    final URL url = extLibFiles[i].toURI().toURL();
                    urls.add(url);
                  } catch (final MalformedURLException e) {
                    logger.error(e);
                  }
                }
              } else { // extLibFile is a file
                try {
                  final URL url = extLibFile.toURI().toURL();
                  urls.add(url);
                } catch (final MalformedURLException e) {
                  logger.error(e);
                }
              }
            } else {
              logger.error("External library path "
                  + extLibFile.getAbsolutePath() + " not found.");
              continue;
            }
          }
        }

        urlClassLoader =
            new URLClassLoader(urls.toArray(new URL[urls.size()]), parentLoader);
      } else {
        logger
            .error("Library path " + libDir.getAbsolutePath() + " not found.");
        continue;
      }

      Class<?> viewerClass = null;
      try {
        viewerClass = urlClassLoader.loadClass(pluginClass);
      } catch (final ClassNotFoundException e) {
        logger.error("Class " + pluginClass + " not found.");
        continue;
      }

      final String source = FileIOUtils.getSourcePathFromClass(viewerClass);
      logger.info("Source jar " + source);
      jarPaths.add("jar:file:" + source);

      Constructor<?> constructor = null;
      try {
        constructor = viewerClass.getConstructor(Props.class);
      } catch (final NoSuchMethodException e) {
        logger.error("Constructor not found in " + pluginClass);
        continue;
      }

      Object obj = null;
      try {
        obj = constructor.newInstance(pluginProps);
      } catch (final Exception e) {
        logger.error(e);
        logger.error(e.getCause());
      }

      if (!(obj instanceof AbstractAzkabanServlet)) {
        logger.error("The object is not an AbstractAzkabanServlet");
        continue;
      }

      final AbstractAzkabanServlet avServlet = (AbstractAzkabanServlet) obj;
      root.addServlet(new ServletHolder(avServlet), "/" + pluginWebPath + "/*");
      PluginRegistry.getRegistry().register(
          new ViewerPlugin(pluginName, pluginWebPath, pluginOrder,
              pluginHidden, pluginJobTypes));
    }

    // Velocity needs the jar resource paths to be set.
    final String jarResourcePath = StringUtils.join(jarPaths, ", ");
    logger.info("Setting jar resource path " + jarResourcePath);
    ve.addProperty("jar.resource.loader.path", jarResourcePath);
  }

  /**
   * Loads the Azkaban property file from the AZKABAN_HOME conf directory
   */
  private static Props loadConfigurationFromAzkabanHome() {
    final String azkabanHome = System.getenv("AZKABAN_HOME");

    if (azkabanHome == null) {
      logger.error("AZKABAN_HOME not set. Will try default.");
      return null;
    }

    if (!new File(azkabanHome).isDirectory()
        || !new File(azkabanHome).canRead()) {
      logger.error(azkabanHome + " is not a readable directory.");
      return null;
    }

    final File confPath = new File(azkabanHome, DEFAULT_CONF_PATH);
    if (!confPath.exists() || !confPath.isDirectory() || !confPath.canRead()) {
      logger
          .error(azkabanHome + " does not contain a readable conf directory.");
      return null;
    }

    return loadAzkabanConfigurationFromDirectory(confPath);
  }

  private void startWebMetrics() throws Exception {

    final MetricRegistry registry = MetricsManager.INSTANCE.getRegistry();

    // The number of idle threads in Jetty thread pool
    MetricsUtility
        .addGauge("JETTY-NumIdleThreads", registry, this.queuedThreadPool::getIdleThreads);

    // The number of threads in Jetty thread pool. The formula is:
    // threads = idleThreads + busyThreads
    MetricsUtility.addGauge("JETTY-NumTotalThreads", registry, this.queuedThreadPool::getThreads);

    // The number of requests queued in the Jetty thread pool.
    MetricsUtility.addGauge("JETTY-NumQueueSize", registry, this.queuedThreadPool::getQueueSize);

    MetricsUtility
        .addGauge("WEB-NumQueuedFlows", registry, this.executorManager::getQueuedFlowSize);
    /**
     * TODO: Currently {@link ExecutorManager#getRunningFlows()} includes both running and non-dispatched flows.
     * Originally we would like to do a subtraction between getRunningFlows and {@link ExecutorManager#getQueuedFlowSize()},
     * in order to have the correct runnable flows.
     * However, both getRunningFlows and getQueuedFlowSize are not synchronized, such that we can not make
     * a thread safe subtraction. We need to fix this in the future.
     */
    MetricsUtility
        .addGauge("WEB-NumRunningFlows", registry,
            () -> this.executorManager.getRunningFlows().size());

    logger.info("starting reporting Web Server Metrics");
    MetricsManager.INSTANCE.startReporting("AZ-WEB", this.props);
  }

  private UserManager loadUserManager(final Props props) {
    final Class<?> userManagerClass = props.getClass(USER_MANAGER_CLASS_PARAM, null);
    final UserManager manager;
    if (userManagerClass != null && userManagerClass.getConstructors().length > 0) {
      logger.info("Loading user manager class " + userManagerClass.getName());
      try {
        final Constructor<?> userManagerConstructor = userManagerClass.getConstructor(Props.class);
        manager = (UserManager) userManagerConstructor.newInstance(props);
      } catch (final Exception e) {
        logger.error("Could not instantiate UserManager " + userManagerClass.getName());
        throw new RuntimeException(e);
      }
    } else {
      manager = new XmlUserManager(props);
    }
    return manager;
  }

  private ScheduleManager loadScheduleManager(final TriggerManager tm)
      throws Exception {
    logger.info("Loading trigger based scheduler");
    final ScheduleLoader loader =
        new TriggerBasedScheduleLoader(tm, ScheduleManager.triggerSource);
    return new ScheduleManager(loader);
  }

  private void loadBuiltinCheckersAndActions() {
    logger.info("Loading built-in checker and action types");
    ExecuteFlowAction.setExecutorManager(this.executorManager);
    ExecuteFlowAction.setProjectManager(this.projectManager);
    ExecuteFlowAction.setTriggerManager(this.triggerManager);
    KillExecutionAction.setExecutorManager(this.executorManager);
    CreateTriggerAction.setTriggerManager(this.triggerManager);
    ExecutionChecker.setExecutorManager(this.executorManager);

    this.triggerManager.registerCheckerType(BasicTimeChecker.type, BasicTimeChecker.class);
    this.triggerManager.registerCheckerType(SlaChecker.type, SlaChecker.class);
    this.triggerManager.registerCheckerType(ExecutionChecker.type, ExecutionChecker.class);
    this.triggerManager.registerActionType(ExecuteFlowAction.type, ExecuteFlowAction.class);
    this.triggerManager.registerActionType(KillExecutionAction.type, KillExecutionAction.class);
    this.triggerManager.registerActionType(SlaAlertAction.type, SlaAlertAction.class);
    this.triggerManager.registerActionType(CreateTriggerAction.type, CreateTriggerAction.class);
  }

  private void loadPluginCheckersAndActions(final String pluginPath) {
    logger.info("Loading plug-in checker and action types");
    final File triggerPluginPath = new File(pluginPath);
    if (!triggerPluginPath.exists()) {
      logger.error("plugin path " + pluginPath + " doesn't exist!");
      return;
    }

    final ClassLoader parentLoader = this.getClassLoader();
    final File[] pluginDirs = triggerPluginPath.listFiles();
    final ArrayList<String> jarPaths = new ArrayList<>();
    for (final File pluginDir : pluginDirs) {
      if (!pluginDir.exists()) {
        logger.error("Error! Trigger plugin path " + pluginDir.getPath()
            + " doesn't exist.");
        continue;
      }

      if (!pluginDir.isDirectory()) {
        logger.error("The plugin path " + pluginDir + " is not a directory.");
        continue;
      }

      // Load the conf directory
      final File propertiesDir = new File(pluginDir, "conf");
      Props pluginProps = null;
      if (propertiesDir.exists() && propertiesDir.isDirectory()) {
        final File propertiesFile = new File(propertiesDir, "plugin.properties");
        final File propertiesOverrideFile =
            new File(propertiesDir, "override.properties");

        if (propertiesFile.exists()) {
          if (propertiesOverrideFile.exists()) {
            pluginProps =
                PropsUtils.loadProps(null, propertiesFile,
                    propertiesOverrideFile);
          } else {
            pluginProps = PropsUtils.loadProps(null, propertiesFile);
          }
        } else {
          logger.error("Plugin conf file " + propertiesFile + " not found.");
          continue;
        }
      } else {
        logger.error("Plugin conf path " + propertiesDir + " not found.");
        continue;
      }

      final List<String> extLibClasspath =
          pluginProps.getStringList("trigger.external.classpaths",
              (List<String>) null);

      final String pluginClass = pluginProps.getString("trigger.class");
      if (pluginClass == null) {
        logger.error("Trigger class is not set.");
      } else {
        logger.error("Plugin class " + pluginClass);
      }

      URLClassLoader urlClassLoader = null;
      final File libDir = new File(pluginDir, "lib");
      if (libDir.exists() && libDir.isDirectory()) {
        final File[] files = libDir.listFiles();

        final ArrayList<URL> urls = new ArrayList<>();
        for (int i = 0; i < files.length; ++i) {
          try {
            final URL url = files[i].toURI().toURL();
            urls.add(url);
          } catch (final MalformedURLException e) {
            logger.error(e);
          }
        }
        if (extLibClasspath != null) {
          for (final String extLib : extLibClasspath) {
            try {
              final File file = new File(pluginDir, extLib);
              final URL url = file.toURI().toURL();
              urls.add(url);
            } catch (final MalformedURLException e) {
              logger.error(e);
            }
          }
        }

        urlClassLoader =
            new URLClassLoader(urls.toArray(new URL[urls.size()]), parentLoader);
      } else {
        logger.error("Library path " + propertiesDir + " not found.");
        continue;
      }

      Class<?> triggerClass = null;
      try {
        triggerClass = urlClassLoader.loadClass(pluginClass);
      } catch (final ClassNotFoundException e) {
        logger.error("Class " + pluginClass + " not found.");
        continue;
      }

      final String source = FileIOUtils.getSourcePathFromClass(triggerClass);
      logger.info("Source jar " + source);
      jarPaths.add("jar:file:" + source);

      try {
        Utils.invokeStaticMethod(urlClassLoader, pluginClass,
            "initiateCheckerTypes", pluginProps, app);
      } catch (final Exception e) {
        logger.error("Unable to initiate checker types for " + pluginClass);
        continue;
      }

      try {
        Utils.invokeStaticMethod(urlClassLoader, pluginClass,
            "initiateActionTypes", pluginProps, app);
      } catch (final Exception e) {
        logger.error("Unable to initiate action types for " + pluginClass);
        continue;
      }

    }
  }

  /**
   * Returns the web session cache.
   */
  @Override
  public SessionCache getSessionCache() {
    return this.sessionCache;
  }

  /**
   * Returns the velocity engine for pages to use.
   */
  @Override
  public VelocityEngine getVelocityEngine() {
    return this.velocityEngine;
  }

  @Override
  public UserManager getUserManager() {
    return this.userManager;
  }

  public ProjectManager getProjectManager() {
    return this.projectManager;
  }

  public ExecutorManager getExecutorManager() {
    return this.executorManager;
  }

  public ScheduleManager getScheduleManager() {
    return this.scheduleManager;
  }

  public TriggerManager getTriggerManager() {
    return this.triggerManager;
  }

  /**
   * Creates and configures the velocity engine.
   */
  private VelocityEngine configureVelocityEngine(final boolean devMode) {
    final VelocityEngine engine = new VelocityEngine();
    engine.setProperty("resource.loader", "classpath, jar");
    engine.setProperty("classpath.resource.loader.class",
        ClasspathResourceLoader.class.getName());
    engine.setProperty("classpath.resource.loader.cache", !devMode);
    engine.setProperty("classpath.resource.loader.modificationCheckInterval",
        5L);
    engine.setProperty("jar.resource.loader.class",
        JarResourceLoader.class.getName());
    engine.setProperty("jar.resource.loader.cache", !devMode);
    engine.setProperty("resource.manager.logwhenfound", false);
    engine.setProperty("input.encoding", "UTF-8");
    engine.setProperty("output.encoding", "UTF-8");
    engine.setProperty("directive.set.null.allowed", true);
    engine.setProperty("resource.manager.logwhenfound", false);
    engine.setProperty("velocimacro.permissions.allow.inline", true);
    engine.setProperty("velocimacro.library.autoreload", devMode);
    engine.setProperty("velocimacro.library",
        "/azkaban/webapp/servlet/velocity/macros.vm");
    engine.setProperty(
        "velocimacro.permissions.allow.inline.to.replace.global", true);
    engine.setProperty("velocimacro.arguments.strict", true);
    engine.setProperty("runtime.log.invalid.references", devMode);
    engine.setProperty("runtime.log.logsystem.class", Log4JLogChute.class);
    engine.setProperty("runtime.log.logsystem.log4j.logger",
        Logger.getLogger("org.apache.velocity.Logger"));
    engine.setProperty("parser.pool.size", 3);
    return engine;
  }

  public ClassLoader getClassLoader() {
    return this.baseClassLoader;
  }

  /**
   * Returns the global azkaban properties
   */
  @Override
  public Props getServerProps() {
    return this.props;
  }

  public Map<String, TriggerPlugin> getTriggerPlugins() {
    return this.triggerPlugins;
  }

  private void setTriggerPlugins(final Map<String, TriggerPlugin> triggerPlugins) {
    this.triggerPlugins = triggerPlugins;
  }

  private void configureMBeanServer() {
    logger.info("Registering MBeans...");
    this.mbeanServer = ManagementFactory.getPlatformMBeanServer();

    registerMbean("jetty", new JmxJettyServer(this.server));
    registerMbean("triggerManager", new JmxTriggerManager(this.triggerManager));
    if (this.executorManager instanceof ExecutorManager) {
      registerMbean("executorManager", new JmxExecutorManager(
          (ExecutorManager) this.executorManager));
    }

    // Register Log4J loggers as JMX beans so the log level can be
    // updated via JConsole or Java VisualVM
    final HierarchyDynamicMBean log4jMBean = new HierarchyDynamicMBean();
    registerMbean("log4jmxbean", log4jMBean);
    final ObjectName accessLogLoggerObjName =
        log4jMBean.addLoggerMBean(AZKABAN_ACCESS_LOGGER_NAME);

    if (accessLogLoggerObjName == null) {
      System.out
          .println(
              "************* loginLoggerObjName is null, make sure there is a logger with name "
                  + AZKABAN_ACCESS_LOGGER_NAME);
    } else {
      System.out.println("******** loginLoggerObjName: "
          + accessLogLoggerObjName.getCanonicalName());
    }
  }

  public void close() {
    try {
      for (final ObjectName name : this.registeredMBeans) {
        this.mbeanServer.unregisterMBean(name);
        logger.info("Jmx MBean " + name.getCanonicalName() + " unregistered.");
      }
    } catch (final Exception e) {
      logger.error("Failed to cleanup MBeanServer", e);
    }
    this.scheduleManager.shutdown();
    this.executorManager.shutdown();
    try {
      this.server.stop();
    } catch (final Exception e) {
      // Catch all while closing server
      logger.error(e);
    }
    this.server.destroy();
  }

  private void registerMbean(final String name, final Object mbean) {
    final Class<?> mbeanClass = mbean.getClass();
    final ObjectName mbeanName;
    try {
      mbeanName = new ObjectName(mbeanClass.getName() + ":name=" + name);
      this.mbeanServer.registerMBean(mbean, mbeanName);
      logger.info("Bean " + mbeanClass.getCanonicalName() + " registered.");
      this.registeredMBeans.add(mbeanName);
    } catch (final Exception e) {
      logger.error("Error registering mbean " + mbeanClass.getCanonicalName(),
          e);
    }
  }

  public List<ObjectName> getMbeanNames() {
    return this.registeredMBeans;
  }

  public MBeanInfo getMBeanInfo(final ObjectName name) {
    try {
      return this.mbeanServer.getMBeanInfo(name);
    } catch (final Exception e) {
      logger.error(e);
      return null;
    }
  }

  public Object getMBeanAttribute(final ObjectName name, final String attribute) {
    try {
      return this.mbeanServer.getAttribute(name, attribute);
    } catch (final Exception e) {
      logger.error(e);
      return null;
    }
  }

  private void setThreadPool(final QueuedThreadPool queuedThreadPool) {
    this.queuedThreadPool = queuedThreadPool;
  }
}
