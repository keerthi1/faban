/* The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://www.sun.com/cddl/cddl.html or
 * install_dir/legal/LICENSE
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at install_dir/legal/LICENSE.
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * $Id: AgentBootstrap.java,v 1.3 2007/10/16 09:25:39 akara Exp $
 *
 * Copyright 2005 Sun Microsystems Inc. All Rights Reserved
 */
package com.sun.faban.harness.agent;

import com.sun.faban.common.Registry;
import com.sun.faban.common.RegistryLocator;
import com.sun.faban.common.Utilities;
import com.sun.faban.harness.common.Config;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.rmi.RMISecurityManager;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.RMISocketFactory;
import java.util.HashSet;
import java.util.Properties;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.logging.LogManager;

/**
 * Bootstrap class for the CmdAgent and FileAgent
 */
public class AgentBootstrap {

    private static int daemonPort = 9981;

    private static Logger logger =
                            Logger.getLogger(AgentBootstrap.class.getName());
    static AgentSocketFactory socketFactory;
    static String progName;
    static boolean daemon = false;
    static boolean agentsAreUp = false;
    static String host;
    static String ident;
    static String master;
    static Registry registry;
    static String javaHome;
    // Initialize it to make sure it doesn't end up a 'null'
    static String jvmOptions = " ";
    static CmdAgentImpl cmd;
    static FileAgentImpl file;
    static HashSet<String> registeredNames = new HashSet<String>();
    static Properties origLogProperties = new Properties();

    public static void main(String[] args) {
        System.setSecurityManager (new RMISecurityManager());

        progName = System.getProperty("faban.cli.command");
        String usage = "Usage: " + progName + " [port]";

        if (args.length < 2) {
            if (args.length == 1) {
                if ("-h".equals(args[0]) || "--help".equals(args) ||
                                            "-?".equals(args)) {
                    System.err.println(usage);
                    System.exit(0);
                } else {
                    daemonPort = Integer.parseInt(args[0]);
                }
            }
            startDaemon();
        } else if (args.length > 3) {
            try {
                startAgents(args);
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(-1);
            }
        } else {
            // We do not expose the start params for agent mode as that
            // is not supposed to be called by the user. The daemon mode
            // has only one optional param - port.
            System.err.println(usage);
            System.exit(-1);
        }
    }

    private static void startDaemon() {
        daemon = true;
        /* Note that the daemon is not designed to accept any concurrency at
         * all and hence the accept/dispatch is not threaded. This is not a
         * bug. It should only receive one and only one connection request per
         * run. Requests to start an agent while one is running will return
         * with an error. We don't care if a concurrent request has to wait.
         * Simplicity is the goal here.
         */
        try {
            ServerSocket serverSocket = new ServerSocket(daemonPort);
            byte[] buffer = new byte[8192];
            for (;;) {
                Socket socket = serverSocket.accept();
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                int length = in.read(buffer);
                if (agentsAreUp)
                    out.write("ERROR: Agents already running.".getBytes());
                if (length > 0) {
                    String argLine = new String(buffer, 0, length);
                    System.out.println("Agent(Daemon) starting agent with options: " +
                                                                    argLine);
                    String[] args = argLine.split(" ");
                    if (args.length < 4) {
                       out.write("ERROR: Inadequate params.".getBytes());
                    }
                    try {
                        startAgents(args);
                        out.write("OK".getBytes());
                    } catch (Exception e) {
                        e.printStackTrace();
                        agentsAreUp = false;
                        out.write(("ERROR: " + e.getMessage()).getBytes());
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();  // We don't use logger here 'cause we don't
            // know the harness at this time.
            // The logger may not be configured properly.
            System.exit(1);
        }

    }

    private static void startAgents(String[] args) throws Exception {
        agentsAreUp = true;

        String hostname = args[0];
        master = args[1];
        String masterLocal = args[2];
        javaHome = args[3];

        String downloadURL = null;
        String benchName = null;

        // Setup the basic jvmOptions for this environment which may not
        // be the same as passed down from the master.
        // We need to be careful to escape properties having '\\' on win32
        String escapedHome = Config.FABAN_HOME.replace("\\", "\\\\");
        String fs = File.separatorChar == '\\' ? "\\\\" : File.separator;
        jvmOptions = "-Dfaban.home=" + escapedHome +
                " -Djava.security.policy=" + escapedHome + "config" + fs +
                "faban.policy -Djava.util.logging.config.file=" + escapedHome +
                "config" + fs + "logging.properties";

        // There may be optional JVM args
        if(args.length > 4) {
            for(int i = 4; i < args.length; i++)
                if(args[i].startsWith("faban.download")) {
                    downloadURL = args[i].substring(
                            args[i].indexOf('=') + 1);
                }else if (args[i].startsWith("faban.benchmarkName")) {
                    benchName = args[i].substring(args[i].indexOf('=') + 1);
                } else if (args[i].indexOf("faban.logging.port") != -1) {
                    jvmOptions = jvmOptions + ' ' + args[i];
                    Config.LOGGING_PORT = Integer.parseInt(
                            args[i].substring(args[i].indexOf("=") + 1));
                } else if (args[i].indexOf("faban.registry.port") != -1) {
                    jvmOptions = jvmOptions + ' ' + args[i];
                    Config.RMI_PORT = Integer.parseInt(
                            args[i].substring(args[i].indexOf("=") + 1));
                } else if ("-server".equals(args[i]) ||
                        "-client".equals(args[i])) { // prepend these options
                    jvmOptions = args[i] + ' ' + jvmOptions;
                } else if (args[i].startsWith("-Dfaban.home=") ||
                        args[i].startsWith("-Djava.security.policy=") ||
                        args[i].startsWith("-Djava.util.logging.config.file=")){
                    // These are sometimes passed down from the master.
                    // Ignore these. Use our local settings instead.
                    // NOOP
                } else {
                    jvmOptions = jvmOptions + ' ' + args[i];
                }
        }

        setLogger();

        // Ensure proper JAVA_HOME, defaulting to this one.
        File java = new File(javaHome + File.separator + "bin", "java");
        if (!java.exists()) {
            String newJavaHome = Utilities.getJavaHome();
            logger.warning("JAVA_HOME " + javaHome + " does not exist. Using " +
                                                    newJavaHome + " instead.");
            javaHome = newJavaHome;
        }

        logger.finer("JVM options for child processes:" + jvmOptions);

        // We cannot set the socket factory twice. So we need to reconfigure it.
        if (socketFactory == null) {
            socketFactory = new AgentSocketFactory(master, masterLocal);
            RMISocketFactory.setSocketFactory(socketFactory);
        } else {
            socketFactory.setMaster(master, masterLocal);
        }

        // Get hold of the registry
        registry = RegistryLocator.getRegistry(master, Config.RMI_PORT);
        logger.fine("Succeeded obtaining registry.");

        // host and ident will be unique
        host = InetAddress.getLocalHost().getHostName();

        // Sometimes we get the host name with the whole domain baggage.
        // The host name is widely used in result files, tools, etc. We
        // do not want that baggage. So we make sure to crop it off.
        // i.e. brazilian.sfbay.Sun.COM should just show as brazilian.
        int dotIdx = host.indexOf('.');
        if (dotIdx > 0)
            host = host.substring(0, dotIdx);

        ident = Config.CMD_AGENT + "@" + host;

        // Make sure there is only one agent running in a machine
        CmdAgent agent = (CmdAgent)registry.getService(ident);

        if((agent != null) && (!host.equals(hostname))){
            // re-register the agents with the 'hostname'
            register(Config.CMD_AGENT + "@" + hostname, agent);
            logger.fine("Succeeded re-registering " + Config.CMD_AGENT +
                    "@" + hostname);
            FileAgent f = (FileAgent)registry.getService(Config.FILE_AGENT +
                    "@" + host);
            register(Config.FILE_AGENT + "@" + hostname, f);
            logger.fine("Succeeded re-registering " + Config.FILE_AGENT +
                    "@" + hostname);
        }
        else {
            new BenchmarkLoader().loadBenchmark(benchName, downloadURL);
            if (cmd == null)
                cmd = new CmdAgentImpl(benchName);
            else
                cmd.setBenchName(benchName);

            register(ident, cmd);

            // Register it with the 'hostname' also if host != hostname
            if(!host.equals(hostname))
                register(Config.CMD_AGENT + "@" + hostname, cmd);

            if(host.equals(master)) {
                ident = Config.CMD_AGENT;
                register(ident, cmd);
            } else if (sameHost(host, master)) {
                ident = Config.CMD_AGENT;
                register(ident, cmd);
            }

            // Create and register FileAgent
            if (file == null)
                file = new FileAgentImpl();
            register(Config.FILE_AGENT + "@" + host, file);

            // Register it with the 'hostname' also if host != hostname
            if(!host.equals(hostname))
                register(Config.FILE_AGENT + "@" + hostname, file);

            // Register a blank Config.FILE_AGENT for the master's
            // file agent.
            if (sameHost(host, master))
                register(Config.FILE_AGENT, file);
        }
    }

    private static void register(String name, Remote service)
            throws RemoteException {
        if (registeredNames.add(name)) {
            registry.register(name, service);
            logger.fine("Succeeded registering " + name);
        }
    }

    static void deregisterAgents() throws RemoteException {
        for (String name : registeredNames) {
            registry.unregister(name);
        }
        registeredNames.clear();
    }

    static void terminateAgents() {
        agentsAreUp = false;
        resetLogger();
        if (!daemon) {
            System.exit(0);
        }
    }

    private static boolean sameHost(String host1, String host2) {
        InetAddress[] host1Ip = new InetAddress[0];
        try {
            host1Ip = InetAddress.getAllByName(host1);
        } catch (UnknownHostException e) {
            logger.severe("Host " + host1 + " not found.");
            return false;
        }
        InetAddress[] host2Ip = new InetAddress[0];
        try {
            host2Ip = InetAddress.getAllByName(host2);
        } catch (UnknownHostException e) {
            logger.severe("Host " + host2 + " not found.");
            return false;
        }
        for (int i = 0; i < host1Ip.length; i++) {
            for (int j = 0; j < host2Ip.length; j++) {
                if (host1Ip[i].equals(host2Ip[j]))
                    return true;
            }
        }
        return false;
    }

    private static void setLogger() {
        try {
            // Update the logging.properties file in config dir
            Properties log = new Properties();
            FileInputStream in = new FileInputStream(Config.CONFIG_DIR +
                                                    "logging.properties");
            log.load(in);
            in.close();

            // Make a copy of the properties
            Set<Map.Entry<Object, Object>> entrySet = log.entrySet();
            for (Map.Entry entry : entrySet)
                origLogProperties.setProperty((String) entry.getKey(),
                                                (String) entry.getValue());

            // Update if it has changed.
            if(!(log.getProperty("java.util.logging.SocketHandler.host").
                    equals(master) &&
                 log.getProperty("java.util.logging.SocketHandler.port").
                    equals(String.valueOf(Config.LOGGING_PORT)))){

                logger.fine("Updating " + Config.CONFIG_DIR +
                                                        "logging.properties");
                log.setProperty("java.util.logging.SocketHandler.host", master);
                log.setProperty("java.util.logging.SocketHandler.port",
                                        String.valueOf(Config.LOGGING_PORT));
                FileOutputStream out = new FileOutputStream(
                        new File(Config.CONFIG_DIR + "logging.properties"));
                log.store(out, "Faban logging properties");
                out.close();
            }
            LogManager.getLogManager().readConfiguration(new FileInputStream(
                    Config.CONFIG_DIR + "logging.properties"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void resetLogger() {
        try {
            FileOutputStream out = new FileOutputStream(
                    new File(Config.CONFIG_DIR + "logging.properties"));
            origLogProperties.store(out, "Faban logging properties");
            out.close();
            LogManager.getLogManager().readConfiguration(new FileInputStream(
                    Config.CONFIG_DIR + "logging.properties"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
