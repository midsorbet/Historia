/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.mxtool.compilerserver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public abstract class CompilerDaemon {

    protected void logf(String commandLine, Object... args) {
        if (verbose) {
            System.err.printf(commandLine, args);
        }
    }

    private boolean verbose = false;
    private volatile boolean running;
    private ThreadPoolExecutor threadPool;
    private ServerSocket serverSocket;

    public void run(String[] args) throws Exception {
        int jobsArg = -1;
        int i = 0;
        while (i < args.length) {
            String arg = args[i];
            if (arg.equals("-v")) {
                verbose = true;
            } else if (arg.equals("-j") && ++i < args.length) {
                try {
                    jobsArg = Integer.parseInt(args[i]);
                } catch (NumberFormatException e) {
                    usage();
                }
            } else {
                usage();
            }
            i++;
        }

        // create socket
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();

        // Need at least 2 threads since we dedicate one to the control
        // connection waiting for the shutdown message.
        int threadCount = Math.max(2, jobsArg > 0 ? jobsArg : Runtime.getRuntime().availableProcessors());
        threadPool = new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), new ThreadFactory() {
            public Thread newThread(Runnable runnable) {
                return new Thread(runnable);
            }
        });

        System.out.printf("Started server on port %d [%d threads]\n", port, threadCount);
        running = true;
        while (running) {
            try {
                threadPool.submit(new Connection(serverSocket.accept(), createCompiler()));
            } catch (SocketException e) {
                if (running) {
                    e.printStackTrace();
                } else {
                    // Socket was closed
                }
            }
        }
    }

    private static void usage() {
        System.err.println("Usage: [ -v ] [ -j NUM ]");
        System.exit(1);
    }

    abstract Compiler createCompiler();

    interface Compiler {
        int compile(String[] args) throws Exception;
    }

    String join(String delim, String[] strings) {
        if (strings.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(strings[0]);
        for (int i = 1; i < strings.length; i++) {
            sb.append(delim);
            sb.append(strings[i]);
        }
        return sb.toString();
    }

    public class Connection implements Runnable {

        private final Socket connectionSocket;
        private final Compiler compiler;

        public Connection(Socket connectionSocket, Compiler compiler) {
            this.connectionSocket = connectionSocket;
            this.compiler = compiler;
        }

        public void run() {
            try {
                BufferedReader input = new BufferedReader(new InputStreamReader(connectionSocket.getInputStream(), "UTF-8"));
                OutputStreamWriter output = new OutputStreamWriter(connectionSocket.getOutputStream(), "UTF-8");

                try {
                    String commandLine = input.readLine();
                    if (commandLine == null || commandLine.length() == 0) {
                        logf("Shutting down\n");
                        running = false;
                        while (threadPool.getActiveCount() > 1) {
                            threadPool.awaitTermination(50, TimeUnit.MILLISECONDS);
                        }
                        serverSocket.close();
                        // Just to be sure...
                        System.exit(0);
                    } else {
                        String[] args = commandLine.split("\u0000");
                        logf("Compiling %s\n", join(" ", args));

                        int result = compiler.compile(args);
                        logf("Result = %d\n", result);

                        output.write(result + "\n");
                    }
                } finally {
                    // close IO streams, then socket
                    output.close();
                    input.close();
                    connectionSocket.close();
                }
            } catch (Exception ioe) {
                ioe.printStackTrace();
            }
        }
    }
}
